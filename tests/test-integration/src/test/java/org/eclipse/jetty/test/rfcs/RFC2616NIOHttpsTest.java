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

package org.eclipse.jetty.test.rfcs;

import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.test.support.XmlBasedJettyServer;
import org.eclipse.jetty.test.support.rawhttp.HttpSocket;
import org.eclipse.jetty.test.support.rawhttp.HttpsSocketImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;

/**
 * Perform the RFC2616 tests against a server running with the Jetty NIO Connector and listening on HTTPS (HTTP over SSL).
 * TODO
 */
@Disabled("TODO")
public class RFC2616NIOHttpsTest extends RFC2616BaseTest
{
    @BeforeAll
    public static void setupServer() throws Exception
    {
        XmlBasedJettyServer server = new XmlBasedJettyServer();
        server.setScheme(HttpScheme.HTTPS.asString());
        server.addXmlConfiguration("RFC2616Base.xml");
        server.addXmlConfiguration("RFC2616_Redirects.xml");
        server.addXmlConfiguration("RFC2616_Filters.xml");
        server.addXmlConfiguration("NIOHttps.xml");
        setUpServer(server, RFC2616NIOHttpsTest.class);
    }

    @Override
    public HttpSocket getHttpClientSocket() throws Exception
    {
        return new HttpsSocketImpl();
    }

    @Override
    public void test8_2_ExpectInvalid() throws Exception
    {
        super.test8_2_ExpectInvalid();
    }
}
