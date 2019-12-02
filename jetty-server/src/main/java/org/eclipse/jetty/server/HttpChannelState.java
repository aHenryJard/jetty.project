//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.server;

import java.io.IOException;
import java.nio.channels.WritePendingException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.servlet.AsyncListener;
import javax.servlet.ServletContext;
import javax.servlet.ServletResponse;
import javax.servlet.UnavailableException;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandler.Context;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Scheduler;

import static javax.servlet.RequestDispatcher.ERROR_EXCEPTION;
import static javax.servlet.RequestDispatcher.ERROR_EXCEPTION_TYPE;
import static javax.servlet.RequestDispatcher.ERROR_MESSAGE;
import static javax.servlet.RequestDispatcher.ERROR_REQUEST_URI;
import static javax.servlet.RequestDispatcher.ERROR_SERVLET_NAME;
import static javax.servlet.RequestDispatcher.ERROR_STATUS_CODE;

/**
 * Implementation of AsyncContext interface that holds the state of request-response cycle.
 */
public class HttpChannelState
{
    private static final Logger LOG = Log.getLogger(HttpChannelState.class);

    private static final long DEFAULT_TIMEOUT = Long.getLong("org.eclipse.jetty.server.HttpChannelState.DEFAULT_TIMEOUT", 30000L);

    /*
     * The state of the HttpChannel,used to control the overall lifecycle.
     * <pre>
     *     IDLE <-----> HANDLING ----> WAITING
     *       |                 ^       /
     *       |                  \     /
     *       v                   \   v
     *    UPGRADED               WOKEN
     * </pre>
     */
    public enum DispatchState  // TODO rename back to State for jetty-9
    {
        IDLE,        // Idle request
        HANDLING,    // Request dispatched to filter/servlet or Async IO callback
        WAITING,     // Suspended and waiting
        WOKEN,       // Dispatch to handle from ASYNC_WAIT
        UPGRADED     // Request upgraded the connection
    }

    /*
     * The state of the request processing lifecycle.
     * <pre>
     *       BLOCKING <----> COMPLETING ---> COMPLETED
     *       ^  |  ^            ^
     *      /   |   \           |
     *     |    |    DISPATCH   |
     *     |    |    ^  ^       |
     *     |    v   /   |       |
     *     |  ASYNC -------> COMPLETE
     *     |    |       |       ^
     *     |    v       |       |
     *     |  EXPIRE    |       |
     *      \   |      /        |
     *       \  v     /         |
     *       EXPIRING ----------+
     * </pre>
     */
    private enum RequestState
    {
        BLOCKING,    // Blocking request dispatched
        ASYNC,       // AsyncContext.startAsync() has been called
        DISPATCH,    // AsyncContext.dispatch() has been called
        EXPIRE,      // AsyncContext timeout has happened
        EXPIRING,    // AsyncListeners are being called
        COMPLETE,    // AsyncContext.complete() has been called
        COMPLETING,  // Request is being closed (maybe asynchronously)
        COMPLETED    // Response is completed
    }

    /*
     * The input readiness state, which works together with {@link HttpInput.State}
     */
    private enum InputState
    {
        IDLE,        // No isReady; No data
        REGISTER,    // isReady()==false handling; No data
        REGISTERED,  // isReady()==false !handling; No data
        POSSIBLE,    // isReady()==false async read callback called (http/1 only)
        PRODUCING,   // isReady()==false READ_PRODUCE action is being handled (http/1 only)
        READY        // isReady() was false, onContentAdded has been called
    }

    /*
     * The response state
     */
    private enum ResponseState
    {
        OPEN,
        COMMITTED,
        COMPLETED,
        ABORTED,
    }

    /*
    ACTION             OPEN       ASYNC      READY      PENDING       UNREADY       CLOSING     CLOSED
    --------------------------------------------------------------------------------------------------
    setWriteListener() READY->owp ise        ise        ise           ise           ise         ise
    write()            OPEN       ise        PENDING    wpe           wpe           eof         eof
    flush()            OPEN       ise        PENDING    wpe           wpe           eof         eof
    close()            CLOSING    CLOSING    CLOSING    CLOSED        CLOSED        CLOSING     CLOSED
    isReady()          OPEN:true  READY:true READY:true UNREADY:false UNREADY:false CLOSED:true CLOSED:true
    write completed    -          -          -          ASYNC         READY->owp    CLOSED      -
    */
    enum OutputState
    {
        OPEN,     // Open in blocking mode
        ASYNC,    // Open in async mode
        READY,    // isReady() has returned true
        PENDING,  // write operating in progress
        UNREADY,  // write operating in progress, isReady has returned false
        ERROR,    // An error has occured
        CLOSING,  // Asynchronous close in progress
        CLOSED    // Closed
    }

    /**
     * The actions to take as the channel moves from state to state.
     */
    public enum Action
    {
        DISPATCH,         // handle a normal request dispatch
        ASYNC_DISPATCH,   // handle an async request dispatch
        SEND_ERROR,       // Generate an error page or error dispatch
        ASYNC_ERROR,      // handle an async error
        ASYNC_TIMEOUT,    // call asyncContext onTimeout
        WRITE_CALLBACK,   // handle an IO write callback
        READ_REGISTER,    // Register for fill interest
        READ_PRODUCE,     // Check is a read is possible by parsing/filling
        READ_CALLBACK,    // handle an IO read callback
        COMPLETE,         // Complete the response by closing output
        TERMINATED,       // No further actions
        WAIT,             // Wait for further events
    }

    private final HttpChannel _channel;
    private List<AsyncListener> _asyncListeners;
    private DispatchState _state = DispatchState.IDLE;
    private RequestState _requestState = RequestState.BLOCKING;
    private ResponseState _responseState = ResponseState.OPEN;
    private InputState _inputState = InputState.IDLE;
    private OutputState _outputState = OutputState.OPEN;
    private boolean _initial = true;
    private boolean _sendError;
    private boolean _asyncWritePossible;
    private long _timeoutMs = DEFAULT_TIMEOUT;
    private AsyncContextEvent _event;

    protected HttpChannelState(HttpChannel channel)
    {
        _channel = channel;
    }

    public DispatchState getState()
    {
        synchronized (this)
        {
            return _state;
        }
    }

    public void addListener(AsyncListener listener)
    {
        synchronized (this)
        {
            if (_asyncListeners == null)
                _asyncListeners = new ArrayList<>();
            _asyncListeners.add(listener);
        }
    }

    public boolean hasListener(AsyncListener listener)
    {
        synchronized (this)
        {
            if (_asyncListeners == null)
                return false;
            for (AsyncListener l : _asyncListeners)
            {
                if (l == listener)
                    return true;

                if (l instanceof AsyncContextState.WrappedAsyncListener && ((AsyncContextState.WrappedAsyncListener)l).getListener() == listener)
                    return true;
            }

            return false;
        }
    }

    public boolean isSendError()
    {
        synchronized (this)
        {
            return _sendError;
        }
    }

    public void setTimeout(long ms)
    {
        synchronized (this)
        {
            _timeoutMs = ms;
        }
    }

    public long getTimeout()
    {
        synchronized (this)
        {
            return _timeoutMs;
        }
    }

    public AsyncContextEvent getAsyncContextEvent()
    {
        synchronized (this)
        {
            return _event;
        }
    }

    @Override
    public String toString()
    {
        synchronized (this)
        {
            return toStringLocked();
        }
    }

    private String toStringLocked()
    {
        return String.format("%s@%x{%s}",
            getClass().getSimpleName(),
            hashCode(),
            getStatusStringLocked());
    }

    private String getStatusStringLocked()
    {
        return String.format("s=%s req/in=%s/%s res/out=%s/%s awp=%b se=%b i=%b al=%d",
            _state,
            _requestState,
            _inputState,
            _responseState,
            _outputState,
            _asyncWritePossible,
            _sendError,
            _initial,
            _asyncListeners == null ? 0 : _asyncListeners.size());
    }

    public String getStatusString()
    {
        synchronized (this)
        {
            return getStatusStringLocked();
        }
    }

    public boolean commitResponse()
    {
        synchronized (this)
        {
            switch (_responseState)
            {
                case OPEN:
                    _responseState = ResponseState.COMMITTED;
                    return true;

                default:
                    return false;
            }
        }
    }

    public boolean partialResponse()
    {
        synchronized (this)
        {
            switch (_responseState)
            {
                case COMMITTED:
                    _responseState = ResponseState.OPEN;
                    return true;

                default:
                    return false;
            }
        }
    }

    public void onWriteSuccess(boolean responseComplete)
    {
        boolean wake = false;
        boolean responseCompleted = false;

        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onWriteComplete {} {}", toStringLocked(), responseComplete);
            switch (_outputState)
            {
                case PENDING:
                    _outputState = responseComplete ? OutputState.CLOSED : OutputState.ASYNC;
                    break;

                case UNREADY:
                    _outputState = responseComplete ? OutputState.CLOSED : OutputState.READY;
                    _asyncWritePossible = true;
                    if (_state == DispatchState.WAITING)
                    {
                        _state = DispatchState.WOKEN;
                        wake = true;
                    }
                    break;

                case CLOSING:
                    _outputState = OutputState.CLOSED;
                    break;

                default:
            }

            switch (_responseState)
            {
                case OPEN:
                case COMMITTED:
                    if (responseComplete)
                    {
                        _responseState = ResponseState.COMPLETED;
                        responseCompleted = true;
                    }
                    break;

                default:
                    break;
            }
        }

        if (responseCompleted)
            _channel.onResponseComplete();
        if (wake)
            _channel.execute(_channel);
    }

    public void onWriteError(Throwable th)
    {
        boolean wake = false;

        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onWriteFailed {}", toStringLocked(), th);
            switch (_outputState)
            {
                case PENDING:
                case UNREADY:
                    _outputState = OutputState.ERROR;
                    _channel.getRequest().setAttribute(HttpOutput.ERROR, th);
                    if (_state == DispatchState.WAITING)
                    {
                        _state = DispatchState.WOKEN;
                        wake = true;
                    }
                    break;

                case CLOSING:
                    _outputState = OutputState.CLOSED;
                    break;

                default:
            }
        }

        if (wake)
            _channel.execute(_channel);
    }

    public IOException getWriteError()
    {
        synchronized (this)
        {
            if (_outputState == OutputState.ERROR)
            {
                Throwable th = (Throwable) _channel.getRequest().getAttribute(HttpOutput.ERROR);
                if (th instanceof IOException)
                    return (IOException)th;
                else if (th != null)
                    return new IOException(th);
            }
        }
        return null;
    }

    public boolean isResponseCommitted()
    {
        synchronized (this)
        {
            switch (_responseState)
            {
                case OPEN:
                    return false;
                default:
                    return true;
            }
        }
    }

    public boolean isResponseCompleted()
    {
        synchronized (this)
        {
            return _responseState == ResponseState.COMPLETED;
        }
    }

    public boolean abortResponse()
    {
        synchronized (this)
        {
            switch (_responseState)
            {
                case ABORTED:
                    return false;

                case OPEN:
                    _channel.getResponse().setStatus(500);
                    _responseState = ResponseState.ABORTED;
                    return true;

                default:
                    _responseState = ResponseState.ABORTED;
                    return true;
            }
        }
    }

    /**
     * @return Next handling of the request should proceed
     */
    public Action handling()
    {
        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("handling {}", toStringLocked());

            switch (_state)
            {
                case IDLE:
                    if (_requestState != RequestState.BLOCKING)
                        throw new IllegalStateException(getStatusStringLocked());
                    _initial = true;
                    _state = DispatchState.HANDLING;
                    return Action.DISPATCH;

                case WOKEN:
                    if (_event != null && _event.getThrowable() != null && !_sendError)
                    {
                        _state = DispatchState.HANDLING;
                        return Action.ASYNC_ERROR;
                    }

                    Action action = nextAction(true);
                    if (LOG.isDebugEnabled())
                        LOG.debug("nextAction(true) {} {}", action, toStringLocked());
                    return action;

                default:
                    throw new IllegalStateException(getStatusStringLocked());
            }
        }
    }

    /**
     * Signal that the HttpConnection has finished handling the request.
     * For blocking connectors, this call may block if the request has
     * been suspended (startAsync called).
     *
     * @return next actions
     * be handled again (eg because of a resume that happened before unhandle was called)
     */
    protected Action unhandle()
    {
        boolean readInterested = false;

        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("unhandle {}", toStringLocked());

            if (_state != DispatchState.HANDLING)
                throw new IllegalStateException(this.getStatusStringLocked());

            _initial = false;

            Action action = nextAction(false);
            if (LOG.isDebugEnabled())
                LOG.debug("nextAction(false) {} {}", action, toStringLocked());
            return action;
        }
    }

    private Action nextAction(boolean handling)
    {
        // Assume we can keep going, but exceptions are below
        _state = DispatchState.HANDLING;

        if (_sendError)
        {
            switch (_requestState)
            {
                case BLOCKING:
                case ASYNC:
                case COMPLETE:
                case DISPATCH:
                case COMPLETING:
                    _requestState = RequestState.BLOCKING;
                    _sendError = false;
                    return Action.SEND_ERROR;

                default:
                    break;
            }
        }

        switch (_requestState)
        {
            case BLOCKING:
                if (handling)
                    throw new IllegalStateException(getStatusStringLocked());
                _requestState = RequestState.COMPLETING;
                return Action.COMPLETE;

            case ASYNC:
                switch (_inputState)
                {
                    case POSSIBLE:
                        _inputState = InputState.PRODUCING;
                        return Action.READ_PRODUCE;
                    case READY:
                        _inputState = InputState.IDLE;
                        return Action.READ_CALLBACK;
                    case REGISTER:
                    case PRODUCING:
                        _inputState = InputState.REGISTERED;
                        return Action.READ_REGISTER;
                    case IDLE:
                    case REGISTERED:
                        break;
                    default:
                        throw new IllegalStateException(getStatusStringLocked());
                }

                if (_asyncWritePossible)
                {
                    _asyncWritePossible = false;
                    return Action.WRITE_CALLBACK;
                }

                Scheduler scheduler = _channel.getScheduler();
                if (scheduler != null && _timeoutMs > 0 && !_event.hasTimeoutTask())
                    _event.setTimeoutTask(scheduler.schedule(_event, _timeoutMs, TimeUnit.MILLISECONDS));
                _state = DispatchState.WAITING;
                return Action.WAIT;

            case DISPATCH:
                _requestState = RequestState.BLOCKING;
                return Action.ASYNC_DISPATCH;

            case EXPIRE:
                _requestState = RequestState.EXPIRING;
                return Action.ASYNC_TIMEOUT;

            case EXPIRING:
                if (handling)
                    throw new IllegalStateException(getStatusStringLocked());
                sendError(HttpStatus.INTERNAL_SERVER_ERROR_500, "AsyncContext timeout");
                // handle sendError immediately
                _requestState = RequestState.BLOCKING;
                _sendError = false;
                return Action.SEND_ERROR;

            case COMPLETE:
                _requestState = RequestState.COMPLETING;
                return Action.COMPLETE;

            case COMPLETING:
                _state = DispatchState.WAITING;
                return Action.WAIT;

            case COMPLETED:
                _state = DispatchState.IDLE;
                return Action.TERMINATED;

            default:
                throw new IllegalStateException(getStatusStringLocked());
        }
    }

    public void startAsync(AsyncContextEvent event)
    {
        final List<AsyncListener> lastAsyncListeners;

        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("startAsync {}", toStringLocked());
            if (_state != DispatchState.HANDLING || _requestState != RequestState.BLOCKING)
                throw new IllegalStateException(this.getStatusStringLocked());

            _requestState = RequestState.ASYNC;
            _event = event;
            lastAsyncListeners = _asyncListeners;
            _asyncListeners = null;
        }

        if (lastAsyncListeners != null)
        {
            Runnable callback = new Runnable()
            {
                @Override
                public void run()
                {
                    for (AsyncListener listener : lastAsyncListeners)
                    {
                        try
                        {
                            listener.onStartAsync(event);
                        }
                        catch (Throwable e)
                        {
                            // TODO Async Dispatch Error
                            LOG.warn(e);
                        }
                    }
                }

                @Override
                public String toString()
                {
                    return "startAsync";
                }
            };

            runInContext(event, callback);
        }
    }

    public void dispatch(ServletContext context, String path)
    {
        boolean dispatch = false;
        AsyncContextEvent event;
        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("dispatch {} -> {}", toStringLocked(), path);

            switch (_requestState)
            {
                case ASYNC:
                case EXPIRING:
                    break;
                default:
                    throw new IllegalStateException(this.getStatusStringLocked());
            }

            if (context != null)
                _event.setDispatchContext(context);
            if (path != null)
                _event.setDispatchPath(path);

            if (_requestState == RequestState.ASYNC && _state == DispatchState.WAITING)
            {
                _state = DispatchState.WOKEN;
                dispatch = true;
            }
            _requestState = RequestState.DISPATCH;
            event = _event;
        }

        cancelTimeout(event);
        if (dispatch)
            scheduleDispatch();
    }

    protected void timeout()
    {
        boolean dispatch = false;
        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Timeout {}", toStringLocked());

            if (_requestState != RequestState.ASYNC)
                return;
            _requestState = RequestState.EXPIRE;

            if (_state == DispatchState.WAITING)
            {
                _state = DispatchState.WOKEN;
                dispatch = true;
            }
        }

        if (dispatch)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Dispatch after async timeout {}", this);
            scheduleDispatch();
        }
    }

    protected void onTimeout()
    {
        final List<AsyncListener> listeners;
        AsyncContextEvent event;
        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onTimeout {}", toStringLocked());
            if (_requestState != RequestState.EXPIRING || _state != DispatchState.HANDLING)
                throw new IllegalStateException(toStringLocked());
            event = _event;
            listeners = _asyncListeners;
        }

        if (listeners != null)
        {
            Runnable task = new Runnable()
            {
                @Override
                public void run()
                {
                    for (AsyncListener listener : listeners)
                    {
                        try
                        {
                            listener.onTimeout(event);
                        }
                        catch (Throwable x)
                        {
                            LOG.warn(x + " while invoking onTimeout listener " + listener);
                            LOG.debug(x);
                        }
                    }
                }

                @Override
                public String toString()
                {
                    return "onTimeout";
                }
            };

            runInContext(event, task);
        }
    }

    public void complete()
    {
        boolean handle = false;
        AsyncContextEvent event;
        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("complete {}", toStringLocked());

            event = _event;
            switch (_requestState)
            {
                case EXPIRING:
                case ASYNC:
                    _requestState = _sendError ? RequestState.BLOCKING : RequestState.COMPLETE;
                    break;

                case COMPLETE:
                    return;
                default:
                    throw new IllegalStateException(this.getStatusStringLocked());
            }
            if (_state == DispatchState.WAITING)
            {
                handle = true;
                _state = DispatchState.WOKEN;
            }
        }

        cancelTimeout(event);
        if (handle)
            runInContext(event, _channel);
    }

    public void asyncError(Throwable failure)
    {
        // This method is called when an failure occurs asynchronously to
        // normal handling.  If the request is async, we arrange for the
        // exception to be thrown from the normal handling loop and then
        // actually handled by #thrownException

        AsyncContextEvent event = null;
        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("asyncError " + toStringLocked(), failure);

            if (_state == DispatchState.WAITING && _requestState == RequestState.ASYNC)
            {
                _state = DispatchState.WOKEN;
                _event.addThrowable(failure);
                event = _event;
            }
            else
            {
                LOG.warn(failure.toString());
                LOG.debug(failure);
            }
        }

        if (event != null)
        {
            cancelTimeout(event);
            runInContext(event, _channel);
        }
    }

    protected void onError(Throwable th)
    {
        final AsyncContextEvent asyncEvent;
        final List<AsyncListener> asyncListeners;
        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("thrownException " + getStatusStringLocked(), th);

            // This can only be called from within the handle loop
            if (_state != DispatchState.HANDLING)
                throw new IllegalStateException(getStatusStringLocked());

            // If sendError has already been called, we can only handle one failure at a time!
            if (_sendError)
            {
                LOG.warn("unhandled due to prior sendError", th);
                return;
            }

            // Check async state to determine type of handling
            switch (_requestState)
            {
                case BLOCKING:
                    // handle the exception with a sendError
                    sendError(th);
                    return;

                case DISPATCH: // Dispatch has already been called but we ignore and handle exception below
                case COMPLETE: // Complete has already been called but we ignore and handle exception below
                case ASYNC:
                    if (_asyncListeners == null || _asyncListeners.isEmpty())
                    {
                        sendError(th);
                        return;
                    }
                    asyncEvent = _event;
                    asyncEvent.addThrowable(th);
                    asyncListeners = _asyncListeners;
                    break;

                default:
                    LOG.warn("unhandled in state " + _requestState, new IllegalStateException(th));
                    return;
            }
        }

        // If we are async and have async listeners
        // call onError
        runInContext(asyncEvent, () ->
        {
            for (AsyncListener listener : asyncListeners)
            {
                try
                {
                    listener.onError(asyncEvent);
                }
                catch (Throwable x)
                {
                    LOG.warn(x + " while invoking onError listener " + listener);
                    LOG.debug(x);
                }
            }
        });

        // check the actions of the listeners
        synchronized (this)
        {
            // If we are still async and nobody has called sendError
            if (_requestState == RequestState.ASYNC && !_sendError)
                // Then the listeners did not invoke API methods
                // and the container must provide a default error dispatch.
                sendError(th);
            else
                LOG.warn("unhandled in state " + _requestState, new IllegalStateException(th));
        }
    }

    private void sendError(Throwable th)
    {
        // No sync as this is always called with lock held

        // Determine the actual details of the exception
        final Request request = _channel.getRequest();
        final int code;
        final String message;
        Throwable cause = _channel.unwrap(th, BadMessageException.class, UnavailableException.class);
        if (cause == null)
        {
            code = HttpStatus.INTERNAL_SERVER_ERROR_500;
            message = th.toString();
        }
        else if (cause instanceof BadMessageException)
        {
            BadMessageException bme = (BadMessageException)cause;
            code = bme.getCode();
            message = bme.getReason();
        }
        else if (cause instanceof UnavailableException)
        {
            message = cause.toString();
            if (((UnavailableException)cause).isPermanent())
                code = HttpStatus.NOT_FOUND_404;
            else
                code = HttpStatus.SERVICE_UNAVAILABLE_503;
        }
        else
        {
            code = HttpStatus.INTERNAL_SERVER_ERROR_500;
            message = null;
        }

        sendError(code, message);

        // No ISE, so good to modify request/state
        request.setAttribute(ERROR_EXCEPTION, th);
        request.setAttribute(ERROR_EXCEPTION_TYPE, th.getClass());
        // Ensure any async lifecycle is ended!
        _requestState = RequestState.BLOCKING;
    }

    public void sendError(int code, String message)
    {
        // This method is called by Response.sendError to organise for an error page to be generated when it is possible:
        //  + The response is reset and temporarily closed.
        //  + The details of the error are saved as request attributes
        //  + The _sendError boolean is set to true so that an ERROR_DISPATCH action will be generated:
        //       - after unhandle for sync
        //       - after both unhandle and complete for async

        final Request request = _channel.getRequest();
        final Response response = _channel.getResponse();
        if (message == null)
            message = HttpStatus.getMessage(code);

        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("sendError {}", toStringLocked());

            switch (_state)
            {
                case HANDLING:
                case WOKEN:
                case WAITING:
                    break;
                default:
                    throw new IllegalStateException(getStatusStringLocked());
            }
            if (_responseState != ResponseState.OPEN)
                throw new IllegalStateException("Response is " + _responseState);

            switch (_outputState)
            {
                case OPEN:
                case READY:
                case ASYNC:
                    _outputState = OutputState.CLOSED;
                    break;

                default:
                    throw new IllegalStateException(_outputState.toString());
            }

            response.setStatus(code);
            response.closedBySendError();


            request.setAttribute(ErrorHandler.ERROR_CONTEXT, request.getErrorContext());
            request.setAttribute(ERROR_REQUEST_URI, request.getRequestURI());
            request.setAttribute(ERROR_SERVLET_NAME, request.getServletName());
            request.setAttribute(ERROR_STATUS_CODE, code);
            request.setAttribute(ERROR_MESSAGE, message);

            _sendError = true;
            if (_event != null)
            {
                Throwable cause = (Throwable)request.getAttribute(ERROR_EXCEPTION);
                if (cause != null)
                    _event.addThrowable(cause);
            }
        }
    }

    protected void completing()
    {
        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("completing {}", toStringLocked());

            switch (_requestState)
            {
                case COMPLETED:
                    throw new IllegalStateException(getStatusStringLocked());
                default:
                    _requestState = RequestState.COMPLETING;
            }
        }
    }

    protected void completed()
    {
        final List<AsyncListener> aListeners;
        final AsyncContextEvent event;
        boolean handle = false;

        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("completed {}", toStringLocked());

            if (_requestState != RequestState.COMPLETING)
                throw new IllegalStateException(this.getStatusStringLocked());

            if (_event == null)
            {
                _requestState = RequestState.COMPLETED;
                aListeners = null;
                event = null;
                if (_state == DispatchState.WAITING)
                {
                    _state = DispatchState.WOKEN;
                    handle = true;
                }
            }
            else
            {
                aListeners = _asyncListeners;
                event = _event;
            }
        }

        // release any aggregate buffer from a closing flush
        _channel.getResponse().getHttpOutput().closed();

        if (event != null)
        {
            cancelTimeout(event);
            if (aListeners != null)
            {
                runInContext(event, () ->
                {
                    for (AsyncListener listener : aListeners)
                    {
                        try
                        {
                            listener.onComplete(event);
                        }
                        catch (Throwable e)
                        {
                            LOG.warn(e + " while invoking onComplete listener " + listener);
                            LOG.debug(e);
                        }
                    }
                });
            }
            event.completed();

            synchronized (this)
            {
                _requestState = RequestState.COMPLETED;
                if (_state == DispatchState.WAITING)
                {
                    _state = DispatchState.WOKEN;
                    handle = true;
                }
            }
        }

        if (handle)
            _channel.handle();
    }

    protected void recycle()
    {
        cancelTimeout();
        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("recycle {}", toStringLocked());

            switch (_state)
            {
                case HANDLING:
                    throw new IllegalStateException(getStatusStringLocked());
                case UPGRADED:
                    return;
                default:
                    break;
            }
            _asyncListeners = null;
            _state = DispatchState.IDLE;
            _requestState = RequestState.BLOCKING;
            _responseState = ResponseState.OPEN;
            _initial = true;
            _inputState = InputState.IDLE;
            _asyncWritePossible = false;
            _timeoutMs = DEFAULT_TIMEOUT;
            _event = null;
        }
    }

    public void upgrade()
    {
        cancelTimeout();
        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("upgrade {}", toStringLocked());

            switch (_state)
            {
                case IDLE:
                    break;
                default:
                    throw new IllegalStateException(getStatusStringLocked());
            }
            _asyncListeners = null;
            _state = DispatchState.UPGRADED;
            _requestState = RequestState.BLOCKING;
            _initial = true;
            _inputState = InputState.IDLE;
            _asyncWritePossible = false;
            _timeoutMs = DEFAULT_TIMEOUT;
            _event = null;
        }
    }

    protected void scheduleDispatch()
    {
        _channel.execute(_channel);
    }

    protected void cancelTimeout()
    {
        final AsyncContextEvent event;
        synchronized (this)
        {
            event = _event;
        }
        cancelTimeout(event);
    }

    protected void cancelTimeout(AsyncContextEvent event)
    {
        if (event != null)
            event.cancelTimeoutTask();
    }

    public boolean isIdle()
    {
        synchronized (this)
        {
            return _state == DispatchState.IDLE;
        }
    }

    public boolean isExpired()
    {
        synchronized (this)
        {
            // TODO review
            return _requestState == RequestState.EXPIRE || _requestState == RequestState.EXPIRING;
        }
    }

    public boolean isInitial()
    {
        synchronized (this)
        {
            return _initial;
        }
    }

    public boolean isSuspended()
    {
        synchronized (this)
        {
            return _state == DispatchState.WAITING || _state == DispatchState.HANDLING && _requestState == RequestState.ASYNC;
        }
    }

    boolean isCompleted()
    {
        synchronized (this)
        {
            return _requestState == RequestState.COMPLETED;
        }
    }

    public boolean isAsyncStarted()
    {
        synchronized (this)
        {
            if (_state == DispatchState.HANDLING)
                return _requestState != RequestState.BLOCKING;
            return _requestState == RequestState.ASYNC || _requestState == RequestState.EXPIRING;
        }
    }

    public boolean isAsync()
    {
        synchronized (this)
        {
            return !_initial || _requestState != RequestState.BLOCKING;
        }
    }

    public Request getBaseRequest()
    {
        return _channel.getRequest();
    }

    public HttpChannel getHttpChannel()
    {
        return _channel;
    }

    public ContextHandler getContextHandler()
    {
        final AsyncContextEvent event;
        synchronized (this)
        {
            event = _event;
        }
        return getContextHandler(event);
    }

    ContextHandler getContextHandler(AsyncContextEvent event)
    {
        if (event != null)
        {
            Context context = ((Context)event.getServletContext());
            if (context != null)
                return context.getContextHandler();
        }
        return null;
    }

    public ServletResponse getServletResponse()
    {
        final AsyncContextEvent event;
        synchronized (this)
        {
            event = _event;
        }
        return getServletResponse(event);
    }

    public ServletResponse getServletResponse(AsyncContextEvent event)
    {
        if (event != null && event.getSuppliedResponse() != null)
            return event.getSuppliedResponse();
        return _channel.getResponse();
    }

    void runInContext(AsyncContextEvent event, Runnable runnable)
    {
        ContextHandler contextHandler = getContextHandler(event);
        if (contextHandler == null)
            runnable.run();
        else
            contextHandler.handle(_channel.getRequest(), runnable);
    }

    public Object getAttribute(String name)
    {
        return _channel.getRequest().getAttribute(name);
    }

    public void removeAttribute(String name)
    {
        _channel.getRequest().removeAttribute(name);
    }

    public void setAttribute(String name, Object attribute)
    {
        _channel.getRequest().setAttribute(name, attribute);
    }

    /**
     * Called to signal async read isReady() has returned false.
     * This indicates that there is no content available to be consumed
     * and that once the channel enters the ASYNC_WAIT state it will
     * register for read interest by calling {@link HttpChannel#onAsyncWaitForContent()}
     * either from this method or from a subsequent call to {@link #unhandle()}.
     */
    public void onReadUnready()
    {
        boolean interested = false;
        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onReadUnready {}", toStringLocked());

            switch (_inputState)
            {
                case IDLE:
                case READY:
                    if (_state == DispatchState.WAITING)
                    {
                        interested = true;
                        _inputState = InputState.REGISTERED;
                    }
                    else
                    {
                        _inputState = InputState.REGISTER;
                    }
                    break;

                case REGISTER:
                case REGISTERED:
                case POSSIBLE:
                case PRODUCING:
                    break;
            }
        }

        if (interested)
            _channel.onAsyncWaitForContent();
    }

    /**
     * Called to signal that content is now available to read.
     * If the channel is in ASYNC_WAIT state and unready (ie isReady() has
     * returned false), then the state is changed to ASYNC_WOKEN and true
     * is returned.
     *
     * @return True IFF the channel was unready and in ASYNC_WAIT state
     */
    public boolean onContentAdded()
    {
        boolean woken = false;
        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onContentAdded {}", toStringLocked());

            switch (_inputState)
            {
                case IDLE:
                case READY:
                    break;

                case PRODUCING:
                    _inputState = InputState.READY;
                    break;

                case REGISTER:
                case REGISTERED:
                    _inputState = InputState.READY;
                    if (_state == DispatchState.WAITING)
                    {
                        woken = true;
                        _state = DispatchState.WOKEN;
                    }
                    break;

                case POSSIBLE:
                    throw new IllegalStateException(toStringLocked());
            }
        }
        return woken;
    }

    /**
     * Called to signal that the channel is ready for a callback.
     * This is similar to calling {@link #onReadUnready()} followed by
     * {@link #onContentAdded()}, except that as content is already
     * available, read interest is never set.
     *
     * @return true if woken
     */
    public boolean onReadReady()
    {
        boolean woken = false;
        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onReadReady {}", toStringLocked());

            switch (_inputState)
            {
                case IDLE:
                    _inputState = InputState.READY;
                    if (_state == DispatchState.WAITING)
                    {
                        woken = true;
                        _state = DispatchState.WOKEN;
                    }
                    break;

                default:
                    throw new IllegalStateException(toStringLocked());
            }
        }
        return woken;
    }

    /**
     * Called to indicate that more content may be available,
     * but that a handling thread may need to produce (fill/parse)
     * it.  Typically called by the async read success callback.
     *
     * @return <code>true</code> if more content may be available
     */
    public boolean onReadPossible()
    {
        boolean woken = false;
        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onReadPossible {}", toStringLocked());

            switch (_inputState)
            {
                case REGISTERED:
                    _inputState = InputState.POSSIBLE;
                    if (_state == DispatchState.WAITING)
                    {
                        woken = true;
                        _state = DispatchState.WOKEN;
                    }
                    break;

                default:
                    throw new IllegalStateException(toStringLocked());
            }
        }
        return woken;
    }

    /**
     * Called to signal that a read has read -1.
     * Will wake if the read was called while in ASYNC_WAIT state
     *
     * @return <code>true</code> if woken
     */
    public boolean onReadEof()
    {
        boolean woken = false;
        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onEof {}", toStringLocked());

            // Force read ready so onAllDataRead can be called
            _inputState = InputState.READY;
            if (_state == DispatchState.WAITING)
            {
                woken = true;
                _state = DispatchState.WOKEN;
            }
        }
        return woken;
    }

    void reopenOutput()
    {
        synchronized (this)
        {
            _outputState = OutputState.OPEN;
        }
    }

    OutputState closeOutput()
    {
        OutputState state;
        synchronized (this)
        {
            switch (_outputState)
            {
                case CLOSING:
                    return OutputState.CLOSED;

                case CLOSED:
                    _outputState = OutputState.CLOSED;
                    break;

                case UNREADY:
                case PENDING:
                    // TODO
                    throw new IllegalStateException();

                default:
                    _outputState = OutputState.CLOSING;
                    break;
            }

            state = _outputState;
        }
        return state;
    }

    void closedOutput()
    {
        synchronized (this)
        {
            _outputState = OutputState.CLOSED;
        }
    }

    boolean isOutputClosed()
    {
        synchronized (this)
        {
            switch (_outputState)
            {
                case CLOSING:
                case CLOSED:
                    return true;
                default:
                    return false;
            }
        }
    }

    boolean isOutputAsync()
    {
        synchronized (this)
        {
            switch (_outputState)
            {
                case ASYNC:
                case READY:
                case PENDING:
                case UNREADY:
                    return true;
                default:
                    return false;
            }
        }
    }

    boolean setWriteListener()
    {
        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onSetWriteListener {}", toStringLocked());

            if (_requestState == RequestState.BLOCKING)
                throw new IllegalStateException("!ASYNC");

            if (_outputState != OutputState.OPEN)
                throw new IllegalStateException();

            _outputState = OutputState.READY;

            _asyncWritePossible = true;
            if (_state == DispatchState.WAITING)
            {
                _state = DispatchState.WOKEN;
                return true;
            }
        }
        return false;
    }

    OutputState prepareWrite(boolean flush)
    {
        synchronized (this)
        {
            switch (_outputState)
            {
                case ASYNC:
                    throw new IllegalStateException("isReady() not called");

                case READY:
                    return _outputState = flush ? OutputState.PENDING : OutputState.ASYNC;

                case UNREADY:
                    throw new WritePendingException();

                case ERROR:
                case OPEN:
                case PENDING:
                case CLOSING:
                case CLOSED:
                    return _outputState;

                default:
                    throw new IllegalStateException(_outputState.toString());
            }
        }
    }

    OutputState prepareWriteContent()
    {
        synchronized (this)
        {
            if (_responseState != ResponseState.OPEN)
                throw new IllegalStateException("cannot sendContent(), output already committed");

            switch (_outputState)
            {
                case OPEN:
                    _outputState = OutputState.PENDING;
                    break;

                case ERROR:
                    break;

                case CLOSING:
                case CLOSED:
                    _channel.getRequest().setAttribute(HttpOutput.ERROR, new EofException("Closed"));
                    _outputState = OutputState.ERROR;
                    break;

                default:
                    throw new IllegalStateException(_outputState.toString());
            }
            return _outputState;
        }
    }

    boolean isOutputReady()
    {
        synchronized (this)
        {
            switch (_outputState)
            {
                case OPEN:
                case READY:
                case ERROR:
                case CLOSING:
                case CLOSED:
                    return true;

                case ASYNC:
                    _outputState = OutputState.READY;
                    return true;

                case PENDING:
                    _outputState = OutputState.UNREADY;
                    return false;

                case UNREADY:
                    return false;

                default:
                    throw new IllegalStateException();
            }
        }
    }
}
