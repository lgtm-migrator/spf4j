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

import com.google.common.annotations.Beta;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.logging.LogManager;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import org.hamcrest.Matcher;
import org.hamcrest.MatcherAssert;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.spf4j.log.SLF4JBridgeHandler;
import org.spf4j.base.ExecutionContext;
import org.spf4j.base.ExecutionContexts;
import org.spf4j.base.XCollectors;
import org.spf4j.log.Level;
import org.spf4j.test.log.junit4.Spf4jTestLogRunListenerSingleton;
//CHECKSTYLE:OFF
import sun.misc.Contended;
//CHECKSTYLE:ON

/**
 * @author Zoltan Farkas
 */
@SuppressFBWarnings("SIC_INNER_SHOULD_BE_STATIC_ANON")
public final class TestLoggers implements ILoggerFactory {

  public static final  boolean EXECUTED_FROM_IDE = TestUtils.isExecutedFromIDE();

  private static final TestLoggers INSTANCE = new TestLoggers();

  private final ConcurrentMap<String, Logger> loggerMap;

  private final Object sync;

  private final Function<String, Logger> computer;

  private final java.util.logging.Logger julGlobal;

  private final java.util.logging.Logger julRoot;

  private final SortedSet<String> expectingErrorsIn;

  @GuardedBy("sync")
  @Contended
  private volatile LogConfig config;

  public static TestLoggers sys() {
    return INSTANCE;
  }

  @SuppressWarnings("checkstyle:regexp")
  private TestLoggers() {
    sync = new Object();
    loggerMap = new ConcurrentHashMap<String, Logger>();
    Level rootPrintLevel = EXECUTED_FROM_IDE
            ? Level.valueOf(System.getProperty("spf4j.testLog.rootPrintLevelIDE", "DEBUG"))
            : Level.valueOf(System.getProperty("spf4j.testLog.rootPrintLevel", "INFO"));
    final Map<String, List<LogHandler>> catHandlers;
    Map<String, PrintConfig> loadConfig = null;
    if (EXECUTED_FROM_IDE) {
      loadConfig = PrintLogConfigsIO.loadConfigFromResource("spf4j-test-prtcfg-ide.properties");
    }
    if (loadConfig == null) {
      loadConfig = PrintLogConfigsIO.loadConfigFromResource("spf4j-test-prtcfg.properties");
    }
    if (loadConfig != null) {
        catHandlers = Maps.newHashMapWithExpectedSize(loadConfig.size());
        for (PrintConfig pl : loadConfig.values()) {
          if (pl.getCategory().isEmpty()) {
            rootPrintLevel = pl.getMinLevel();
          } else {
            LogHandler logPrinter;
            if (pl.isGreedy()) {
              logPrinter = new GreedyLogPrinter(new LogPrinter(pl.getMinLevel()));
            } else {
              logPrinter = new LogPrinter(pl.getMinLevel());
            }
            catHandlers.put(pl.getCategory(), Collections.singletonList(logPrinter));
          }
        }

    } else {
      catHandlers = Collections.EMPTY_MAP;
    }
    computer = (k) -> new TestLogger(k, TestLoggers.this::getConfig);
    String vals = System.getProperty("spf4j.testLog.expectingErrorsIn");
    if (vals != null) {
      expectingErrorsIn = ImmutableSortedSet.orderedBy(LogConfigImpl.REV_STR_COMPARATOR).add(vals.split(",")).build();
    } else {
      expectingErrorsIn = ImmutableSortedSet.of();
    }
    config = new LogConfigImpl(
            ImmutableList.of(new LogPrinter(rootPrintLevel), new DefaultAsserter(expectingErrorsIn)), catHandlers);
    LogManager.getLogManager().reset();
    SLF4JBridgeHandler.removeHandlersForRootLogger();
    SLF4JBridgeHandler.install();
    julGlobal = java.util.logging.Logger.getGlobal();
    julRoot = java.util.logging.Logger.getLogger("");
    resetJulConfig();
  }

  public SortedSet<String> getExpectingErrorsIn() {
    return expectingErrorsIn;
  }

  private void resetJulConfig() {
    java.util.logging.Level julLevel = config.minRootLevel().getJulLevel();
    julGlobal.setLevel(julLevel);
    julRoot.setLevel(julLevel);
  }

  public LogConfig getConfig() {
    return config;
  }

  /**
   * Print logs above a category and log level.
   *
   * @param category the log category.
   * @param level the log level.
   * @return a handler that allows this printing to stop (when calling close).
   */
  public void print(final String category, final Level level) {
    interceptInContext(category, new LogPrinter(level));
  }

  public void print(final String category, final Level level, final boolean greedy) {
    if (greedy) {
      interceptInContext(category, new GreedyLogPrinter(new LogPrinter(level)));
    } else {
      interceptInContext(category, new LogPrinter(level));
    }
  }
  /**
   * Ability to intercept log messages logged under a category
   *
   * @param category the logger category name (a.b.c)
   * @param handler the log handler to register.
   * @return a registration handle, that you can use to unregister.
   */
  @CheckReturnValue
  public HandlerRegistration intercept(final String category, final LogHandler handler) {
    HandlerRegistration reg = () -> {
      synchronized (sync) {
        config = config.remove(category, handler);
        resetJulConfig();
      }
    };
    addConfig(category, handler,  ExecutionContexts.current(), reg);
    return reg;
  }

  @CheckReturnValue
  public void interceptInContext(final String category, final LogHandler handler) {
    HandlerRegistration reg = () -> {
      synchronized (sync) {
        config = config.remove(category, handler);
        resetJulConfig();
      }
    };
    ExecutionContext current = ExecutionContexts.current();
    if (current == null) {
      throw new IllegalStateException("No execution context available for " + Thread.currentThread());
    }
    addConfig(category, handler, current, reg);
  }


  /**
   * Collect a bunch of logs.
   * @param <T> the type of object to collect into.
   * @param category the log category (a.b.c)
   * @param fromLevel from level to collect.
   * @param passThrough pass the logs to lower category handlers or not.
   * @param collector the collector to collect the logs.
   * @return collected logs.
   */
  @CheckReturnValue
  public <T> LogCollection<T> collect(final String category, final Level fromLevel,
          final boolean passThrough,
          final Collector<TestLogRecord, ?, T> collector) {
    return collect(category, fromLevel, Level.ERROR, passThrough, collector);
  }

  /**
   * Collect matching logs.
   * matched logs are asserted.
   * @return
   */
  @CheckReturnValue
  public <T> LogCollection<T> collect(final String category, final Level fromLevel,
          final boolean passThrough, final Matcher<TestLogRecord> matcher,
          final Collector<TestLogRecord, ?, T> collector) {
    return collect(category, fromLevel, passThrough, XCollectors.filtering((log) -> {
              if (matcher.matches(log)) {
                log.attach(Attachments.ASSERTED);
                return true;
              }
              return false;
            }, collector));
  }


  /**
   * expect a stream of logs filtered by a matcher, this strem will be matched against the matchedMatcher.
   * @return
   */
  @CheckReturnValue
  public LogAssert expect(final String category, final Level fromLevel,
          final boolean passThrough, final Matcher<TestLogRecord> matcher,
          final Matcher<Iterable<TestLogRecord>> matchedMatcher) {
    LogCollection<List<TestLogRecord>> lc = collect(category, fromLevel, passThrough, XCollectors.filtering((log) -> {
      if (matcher.matches(log)) {
        log.attach(Attachments.ASSERTED);
        return true;
      }
      return false;
    }, Collectors.toList()));
    return new LogAssert() {
      @Override
      public void assertObservation() {
        MatcherAssert.assertThat(lc.get(), matchedMatcher);
      }

      @Override
      public void close() {
        assertObservation();
        lc.close();
      }
    };
  }


  /**
   * Collect a bunch of logs.
   * @param <T> the type of object to collect into.
   * @param category the log category (a.b.c)
   * @param fromLevel from level to collect.
   * @param toLevel to level to collect.
   * @param passThrough pass the logs to lower category handlers or not.
   * @param collector the collector to collect the logs.
   * @return collected logs.
   */
  @CheckReturnValue
  public <T> LogCollection<T> collect(final String category, final Level fromLevel, final Level toLevel,
          final boolean passThrough,
          final Collector<TestLogRecord, ?, T> collector) {
    return collect(category, fromLevel, toLevel, passThrough, collector, (c) -> 0);
  }

  @CheckReturnValue
  public <R> LogCollection<R> collect(final String category, final Level fromLevel, final Level toLevel,
          final boolean passThrough,
          final Collector<TestLogRecord, ?, R> collector, final ToIntFunction<List<LogHandler>> whereTo) {
    LogCollectorHandler<?, R> handler =
            new LogCollectorHandler<>(fromLevel, toLevel, passThrough, collector,
    (c) -> {
       config = config.remove(category, c);
       resetJulConfig();
    });
    addConfig(category, handler, ExecutionContexts.current(),  handler);
    return handler;
  }

  private void addConfig(final String category, final LogHandler handler,
          @Nullable final ExecutionContext ctx, final HandlerRegistration reg) {
    addConfig(category, handler, ctx, reg, (c) -> 0);
  }


  private void addConfig(final String category, final LogHandler handler,
          @Nullable final ExecutionContext ctx, final HandlerRegistration reg,
          final ToIntFunction<List<LogHandler>> whereTo) {
    synchronized (sync) {
      config = config.add(category, handler, whereTo);
      resetJulConfig();
      if (ctx != null) {
        ctx.addCloseable(reg);
      }
    }
  }

  /**
   * Convenience method for functional use.
   *
   * @param category the log category.
   * @param handler a functional handler.
   * @return a registration handle to allow you to undo the registration.
   */
  @CheckReturnValue
  public HandlerRegistration interceptAllLevels(final String category, final AllLevelsLogHandler handler) {
    return intercept(category, handler);
  }

  /**
   * all logs from category and specified levels will be ignored... (unless there are more specific handlers)
   * @param category the log category.
   * @param from from log level.
   * @param to to log level.
   * @return a registration handle to allow you to undo this filtering.
   */
  @CheckReturnValue
  public HandlerRegistration ignore(final String category, final Level from, final Level to) {
    return intercept(category, new ConsumeAllLogs(from, to));
  }

  /**
   * Create an log expectation that can be asserted like:
   * <code>
   * LogAssert expect = TestLoggers.expect("org.spf4j.test", Level.ERROR, Matchers.hasProperty("format",
   * Matchers.equalTo("Booo")));
   * LOG.error("Booo", new RuntimeException());
   * expect.assertObservation();
   * </code>
   * @param category the category under which we should expect these messages.
   * @param minimumLogLevel minimum log level of expected log messages
   * @param matchers a succession of LogMessages with each matching a Matcher is expected.
   * @return an assertion handle.
   */
  @CheckReturnValue
  public LogAssert expect(final String category, final Level minimumLogLevel,
          final Matcher<TestLogRecord>... matchers) {
    return logAssert(true, minimumLogLevel, category, matchers);
  }

  @CheckReturnValue
  public LogAssert expect(final String category, final Level minimumLogLevel,
          final long timeout, final TimeUnit unit, final Matcher<TestLogRecord>... matchers) {
    return logAssert(true, minimumLogLevel, category, timeout, unit, matchers);
  }

  /**
   * the opposite of expect.
   * @param category the category under which we should expect these messages.
   * @param minimumLogLevel minimum log level of expected log messages
   * @param matchers a succession of LogMessages with each matching a Matcher is NOT expected.
   * @return an assertion handle.
   */
  @CheckReturnValue
  public LogAssert dontExpect(final String category, final Level minimumLogLevel,
          final Matcher<TestLogRecord>... matchers) {
    return logAssert(false, minimumLogLevel, category, matchers);
  }

  private LogAssert logAssert(final boolean assertSeen, final Level minimumLogLevel,
          final String category, final long timeout, final TimeUnit unit, final Matcher<TestLogRecord>... matchers) {
    LogMatchingHandler handler =
            new LogMatchingHandlerAsync(assertSeen, category, minimumLogLevel, timeout, unit, matchers) {

      private boolean isClosed = false;

      @Override
      public void close() {
        synchronized (sync) {
          if (!isClosed) {
            config = config.remove(category, this);
            isClosed = true;
            resetJulConfig();
          }
        }
      }

    };
    addConfig(category, handler,  ExecutionContexts.current(), handler);
    return handler;
  }


  private LogAssert logAssert(final boolean assertSeen, final Level minimumLogLevel,
          final String category,  final Matcher<TestLogRecord>... matchers) {
    LogMatchingHandler handler = new LogMatchingHandler(assertSeen, category, minimumLogLevel, matchers) {

      private boolean isClosed = false;

      @Override
      public void close() {
        synchronized (sync) {
          if (!isClosed) {
            config = config.remove(category, this);
            isClosed = true;
            resetJulConfig();
          }
        }
      }

    };
    addConfig(category, handler,  ExecutionContexts.current(), handler);
    return handler;
  }

  /**
   * Ability to assert is you expect a sequence of logs to be repeated.
   *
   * @param category the log category (a.b.c)
   * @param minimumLogLevel the minimum log level of expected messages.
   * @param nrTimes number of time the sequence should appear.
   * @param matchers the sequence of matchers.
   * @return the assertion handle.
   */
  public LogAssert expect(final String category, final Level minimumLogLevel,
          final int nrTimes, final Matcher<TestLogRecord>... matchers) {
    Matcher<TestLogRecord>[] newMatchers = new Matcher[matchers.length * nrTimes];
    for (int i = 0, j = 0; i < nrTimes; i++) {
      for (Matcher<TestLogRecord> m : matchers) {
        newMatchers[j++] = m;
      }
    }
    return expect(category, minimumLogLevel, newMatchers);
  }

  public LogAssert expect(final String category, final Level minimumLogLevel,
          final int nrTimes, final long timeout, final TimeUnit unit, final Matcher<TestLogRecord>... matchers) {
    Matcher<TestLogRecord>[] newMatchers = new Matcher[matchers.length * nrTimes];
    for (int i = 0, j = 0; i < nrTimes; i++) {
      for (Matcher<TestLogRecord> m : matchers) {
        newMatchers[j++] = m;
      }
    }
    return expect(category, minimumLogLevel, timeout, unit, newMatchers);
  }


  /**
   * Assert uncaught exceptions.(from threads)
   * @param timeout timeout to wait for this assertion. from the point in time of assertion invocation.
   * @param unit
   * @param matcher the exception matcher.
   * @return the assertion handle.
   */
  @Beta
  public LogAssert expectUncaughtException(final long timeout, final TimeUnit unit,
          final Matcher<UncaughtExceptionDetail> matcher) {
    ExceptionHandoverRegistry reg = Spf4jTestLogRunListenerSingleton.getInstance().getUncaughtExceptionHandler();
    UncaughtExceptionAsserter asserter = new UncaughtExceptionAsserter(timeout, unit, matcher) {

      @Override
      public void close() {
        reg.remove(this);
      }
    };
    reg.add(asserter);
    return asserter;
  }

  /**
   * Collect up to a number of log messages.
   * @param minimumLogLevel the minimum log level of the messages.
   * @param maxNrLogs the max number of messages to collect.
   * @param collectPrinted collect messages that have been printed or not.
   * @return the collection of messages.
   */
  public LogCollection<ArrayDeque<TestLogRecord>> collect(final Level minimumLogLevel, final int maxNrLogs,
          final boolean collectPrinted) {
    return collect("", minimumLogLevel, maxNrLogs, collectPrinted);
  }

  private LogCollection<ArrayDeque<TestLogRecord>> collect(final String category, final Level minimumLogLevel,
          final int maxNrLogs,
          final boolean collectPrinted) {
    if (!collectPrinted) {
      return collect(category,
            minimumLogLevel,
            Level.ERROR,
            true,
            XCollectors.filtering(
                 (l) -> !l.hasAttachment(Attachments.PRINTED),
                 XCollectors.last(maxNrLogs,
                 new TestLogRecordImpl("test", Level.INFO, "Truncated beyond {} ", maxNrLogs))),
            List::size);
    } else {
      return collect(category,
            minimumLogLevel,
            Level.ERROR,
            true,
            XCollectors.last(maxNrLogs,
                 new TestLogRecordImpl("test", Level.INFO, "Truncated beyond {} ", maxNrLogs)),
            (c)  -> 0);
    }
  }

  /**
   * Return an appropriate {@link SimpleLogger} instance by name.
   */
  public Logger getLogger(final String name) {
    return loggerMap.computeIfAbsent(name, computer);
  }

  public java.util.logging.Logger getJulGlobal() {
    return julGlobal;
  }

  public java.util.logging.Logger getJulRoot() {
    return julRoot;
  }

  @Override
  public String toString() {
    return "TestLoggers{ config=" + config + ", loggerMap=" + loggerMap + '}';
  }

}
