//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.javax.common.messages;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.javax.common.CompletableFutureCallback;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class ReaderMessageSinkTest extends AbstractMessageSinkTest
{
    @Test
    public void testReader_1_Frame() throws InterruptedException, ExecutionException, TimeoutException
    {
        CompletableFuture<StringWriter> copyFuture = new CompletableFuture<>();
        ReaderCopy copy = new ReaderCopy(copyFuture);
        MethodHandle copyHandle = getAcceptHandle(copy, Reader.class);
        ReaderMessageSink sink = new ReaderMessageSink(session, copyHandle);

        CompletableFutureCallback finCallback = new CompletableFutureCallback();
        sink.accept(new Frame(OpCode.TEXT).setPayload("Hello World"), finCallback);

        finCallback.get(1, TimeUnit.SECONDS); // wait for callback
        StringWriter writer = copyFuture.get(1, TimeUnit.SECONDS);
        assertThat("FinCallback.done", finCallback.isDone(), is(true));
        assertThat("Writer.contents", writer.getBuffer().toString(), is("Hello World"));
    }

    @Test
    public void testReader_3_Frames() throws InterruptedException, ExecutionException, TimeoutException
    {
        CompletableFuture<StringWriter> copyFuture = new CompletableFuture<>();
        ReaderCopy copy = new ReaderCopy(copyFuture);
        MethodHandle copyHandle = getAcceptHandle(copy, Reader.class);
        ReaderMessageSink sink = new ReaderMessageSink(session, copyHandle);

        CompletableFutureCallback callback1 = new CompletableFutureCallback();
        CompletableFutureCallback callback2 = new CompletableFutureCallback();
        CompletableFutureCallback finCallback = new CompletableFutureCallback();

        sink.accept(new Frame(OpCode.TEXT).setPayload("Hello").setFin(false), callback1);
        sink.accept(new Frame(OpCode.CONTINUATION).setPayload(", ").setFin(false), callback2);
        sink.accept(new Frame(OpCode.CONTINUATION).setPayload("World").setFin(true), finCallback);

        finCallback.get(1, TimeUnit.SECONDS); // wait for fin callback
        StringWriter writer = copyFuture.get(1, TimeUnit.SECONDS);
        assertThat("Callback1.done", callback1.isDone(), is(true));
        assertThat("Callback2.done", callback2.isDone(), is(true));
        assertThat("finCallback.done", finCallback.isDone(), is(true));
        assertThat("Writer contents", writer.getBuffer().toString(), is("Hello, World"));
    }

    public static class ReaderCopy implements Consumer<Reader>
    {
        private CompletableFuture<StringWriter> copyFuture;

        public ReaderCopy(CompletableFuture<StringWriter> copyFuture)
        {
            this.copyFuture = copyFuture;
        }

        @Override
        public void accept(Reader reader)
        {
            try
            {
                StringWriter writer = new StringWriter();
                IO.copy(reader, writer);
                copyFuture.complete(writer);
            }
            catch (IOException e)
            {
                copyFuture.completeExceptionally(e);
            }
        }
    }
}
