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

package org.eclipse.jetty.util.thread;

import org.eclipse.jetty.util.ProcessorUtils;
import org.eclipse.jetty.util.thread.ThreadPool.SizedThreadPool;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

public abstract class AbstractThreadPoolTest
{
    private static int originalCoreCount;

    @BeforeAll
    public static void saveState()
    {
        originalCoreCount = ProcessorUtils.availableProcessors();
    }

    @AfterAll
    public static void restoreState()
    {
        ProcessorUtils.setAvailableProcessors(originalCoreCount);
    }

    protected abstract SizedThreadPool newPool(int max);

    @Test
    public void testBudget_constructMaxThenLease()
    {
        SizedThreadPool pool = newPool(4);

        pool.getThreadPoolBudget().leaseTo(this, 2);

        try
        {
            pool.getThreadPoolBudget().leaseTo(this, 3);
            fail();
        }
        catch (IllegalStateException e)
        {
            assertThat(e.getMessage(), Matchers.containsString("Insufficient configured threads"));
        }

        pool.getThreadPoolBudget().leaseTo(this, 1);
    }

    @Test
    public void testBudget_LeaseThenSetMax()
    {
        SizedThreadPool pool = newPool(4);

        pool.getThreadPoolBudget().leaseTo(this, 2);

        pool.setMaxThreads(3);

        try
        {
            pool.setMaxThreads(1);
            fail();
        }
        catch (IllegalStateException e)
        {
            assertThat(e.getMessage(), Matchers.containsString("Insufficient configured threads"));
        }

        assertThat(pool.getMaxThreads(), Matchers.is(3));
    }
}
