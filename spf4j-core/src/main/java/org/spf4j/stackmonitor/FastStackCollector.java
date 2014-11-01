/*
 * Copyright (c) 2001, Zoltan Farkas All Rights Reserved.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.spf4j.stackmonitor;

import java.lang.reflect.InvocationTargetException;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * This is a high performance sampling collector.
 * The goal is for the sampling overhead to be minimal.
 * This is better than the SimpleStackCollector in 2 ways:
 * 1) No HashMap is created during sampling. Resulting in less garbage generated by sampling.
 * 2) Stack trace for the sampling Thread is not created at all, saving some time and creating less garbage.
 * 
 * in java 1.7 the reflective invocations can probably be further optimized using:
 * http://docs.oracle.com/javase/7/docs/api/java/lang/invoke/MethodHandle.html
 * http://stackoverflow.com/questions/14146570/
 * calling-a-getter-in-java-though-reflection-whats-the-fastest-way-to-repeatedly
 * @author zoly
 */
public final class FastStackCollector extends AbstractStackCollector {
    
    private static final java.lang.reflect.Method GET_THREADS;
    private static final java.lang.reflect.Method DUMP_THREADS;
    
    private static final String[] IGNORED_THREADS = {
            "Finalizer",
            "Signal Dispatcher",
            "Reference Handler",
            "Attach Listener"
    };
    
    private final Set<Thread> ignoredThreads;
    
    public FastStackCollector(final boolean collectForMain, final String ... xtraIgnoredThreads) {
        Set<String> ignoredThreadNames = new HashSet<>(Arrays.asList(IGNORED_THREADS));
        if (!collectForMain) {
            ignoredThreadNames.add("main");
        }
        ignoredThreadNames.addAll(Arrays.asList(xtraIgnoredThreads));
        ignoredThreads = new HashSet<>(ignoredThreadNames.size());
        try {
            Thread[] threads = (Thread[]) GET_THREADS.invoke(null);
            for (Thread th : threads) {
                if (ignoredThreadNames.contains(th.getName())) {
                    ignoredThreads.add(th);
                }
            }
        } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException ex) {
            throw new RuntimeException(ex);
        }
         
    }
    

    static {
        try {
            GET_THREADS = Thread.class.getDeclaredMethod("getThreads");
            DUMP_THREADS = Thread.class.getDeclaredMethod("dumpThreads", Thread[].class);
        } catch (SecurityException | NoSuchMethodException ex) {
            throw new RuntimeException(ex);
        }
        AccessController.doPrivileged(new PrivilegedAction() {
            @Override
            public Object run() {
                GET_THREADS.setAccessible(true);
                DUMP_THREADS.setAccessible(true);
                return null; // nothing to return
            }
        });

    }

    @Override
    @edu.umd.cs.findbugs.annotations.SuppressWarnings("EXS_EXCEPTION_SOFTENING_NO_CHECKED")
    public void sample(final Thread ignore) {
        try {
            Thread[] threads = (Thread[]) GET_THREADS.invoke(null);
            final int nrThreads = threads.length;
            for (int i = 0; i < nrThreads; i++) {
                Thread th = threads[i];
                if (ignore == th) { // not interested in the sampler's stack trace
                    threads[i] = null;
                } else if (ignoredThreads.contains(th)) {
                    threads[i] = null;
                }
            }
            StackTraceElement[][] stackDump = (StackTraceElement[][]) DUMP_THREADS.invoke(null, (Object) threads);
            for (StackTraceElement[] stackTrace : stackDump) {
                if (stackTrace != null && stackTrace.length > 0) {
                        addSample(stackTrace);
                }
            }
        } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException ex) {
            throw new RuntimeException(ex);
        }
    }

}
