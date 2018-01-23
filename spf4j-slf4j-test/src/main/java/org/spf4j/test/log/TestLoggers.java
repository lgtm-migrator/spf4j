/*
 * Copyright 2018 SPF4J.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.spf4j.test.log;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import javax.annotation.CheckReturnValue;
import javax.annotation.concurrent.GuardedBy;
import org.hamcrest.Matcher;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;

/**
 * @author Zoltan Farkas
 */
public final class TestLoggers implements ILoggerFactory {

  private static final TestLoggers INSTANCE = new TestLoggers();

  public static TestLoggers config() {
    return INSTANCE;
  }

  private final ConcurrentMap<String, Logger> loggerMap;

  private final Object sync;

  @GuardedBy("sync")
  private volatile LogConfigImpl config;

  private final Function<String, Logger> computer;

  private TestLoggers() {
    loggerMap = new ConcurrentHashMap<String, Logger>();
    config = new LogConfigImpl(Arrays.asList(new LogPrinter(Level.valueOf(
            System.getProperty("spf4j.testLog.rootPrintLevel", "INFO"))), new DefaultAsserter()),
            Collections.EMPTY_MAP);
    computer = (k) -> new TestLogger(k, TestLoggers.this::getConfig);
    sync = new Object();
  }

  public LogConfig getConfig() {
    return config;
  }

  @CheckReturnValue
  public HandlerRegistration printer(final String category, final Level level) {
    LogPrinter logPrinter = new LogPrinter(level);
    synchronized (sync) {
      config = config.add(category, logPrinter);
    }
    return () -> {
      synchronized (sync) {
        config = config.remove(category, logPrinter);
      }
    };
  }

  /**
   * Create an log expectation that can be asserted like:
   *
   * LogAssert expect = TestLoggers.expect("org.spf4j.test", Level.ERROR, Matchers.hasProperty("format",
   * Matchers.equalTo("Booo"))); LOG.error("Booo", new RuntimeException()); expect.assertSeen();
   *
   *
   * @param category the category under which we should expect theese messages.
   * @param minimumLogLevel minimum log level of expected log messages
   * @param matchers a succession of LogMessages with each matching a Matcher is expected.
   * @return
   */
  @CheckReturnValue
  public LogAssert expect(final String category, final Level minimumLogLevel,
          final Matcher<LogRecord>... matchers) {
    LogMatchingHandler handler = new LogMatchingHandler(minimumLogLevel, matchers);
    handler.setOnAssert(() -> {
      synchronized (sync) {
        config = config.remove(category, handler);
      }
    });
    synchronized (sync) {
      config = config.add(category, handler);
    }
    return handler;
  }

  public LogAssert expect(final String category, final Level minimumLogLevel,
          final int nrTimes, final Matcher<LogRecord>... matchers) {
    Matcher<LogRecord>[] newMatchers = new Matcher[matchers.length * nrTimes];
    for (int i = 0, j = 0; i < nrTimes; i++) {
      for (Matcher<LogRecord> m : matchers) {
        newMatchers[j++] = m;
      }
    }
    return expect(category, minimumLogLevel, newMatchers);
  }

  /**
   * Return an appropriate {@link SimpleLogger} instance by name.
   */
  public Logger getLogger(final String name) {
    return loggerMap.computeIfAbsent(name, computer);
  }

}
