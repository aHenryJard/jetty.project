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

package org.eclipse.jetty.annotations;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.annotations.AnnotationParser.AbstractHandler;
import org.eclipse.jetty.annotations.AnnotationParser.ClassInfo;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * ClassInheritanceHandler
 *
 * As asm scans for classes, remember the type hierarchy.
 */
public class ClassInheritanceHandler extends AbstractHandler
{
    private static final Logger LOG = Log.getLogger(ClassInheritanceHandler.class);

    Map<String, Set<String>> _inheritanceMap;

    public ClassInheritanceHandler(Map<String, Set<String>> map)
    {
        _inheritanceMap = map;
    }

    @Override
    public void handle(ClassInfo classInfo)
    {
        try
        {
            //Don't scan Object
            if ("java.lang.Object".equals(classInfo.getClassName()))
                return;

            for (int i = 0; classInfo.getInterfaces() != null && i < classInfo.getInterfaces().length; i++)
            {
                addToInheritanceMap(classInfo.getInterfaces()[i], classInfo.getClassName());
            }
            //To save memory, we don't record classes that only extend Object, as that can be assumed
            if (!"java.lang.Object".equals(classInfo.getSuperName()))
            {
                addToInheritanceMap(classInfo.getSuperName(), classInfo.getClassName());
            }
        }
        catch (Exception e)
        {
            LOG.warn(e);
        }
    }

    private void addToInheritanceMap(String interfaceOrSuperClassName, String implementingOrExtendingClassName)
    {

        //As it is likely that the interfaceOrSuperClassName is already in the map, try getting it first
        Set<String> implementingClasses = _inheritanceMap.get(interfaceOrSuperClassName);
        //If it isn't in the map, then add it in, but test to make sure that someone else didn't get in 
        //first and add it
        if (implementingClasses == null)
        {
            implementingClasses = ConcurrentHashMap.newKeySet();
            Set<String> tmp = _inheritanceMap.putIfAbsent(interfaceOrSuperClassName, implementingClasses);
            if (tmp != null)
                implementingClasses = tmp;
        }

        implementingClasses.add(implementingOrExtendingClassName);
    }
}
