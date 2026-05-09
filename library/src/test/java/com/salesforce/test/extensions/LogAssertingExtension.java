package com.salesforce.test.extensions;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.LoggerFactory;

/**
 * JUnit5 extension that allows for asserting that a given message was or was not logged at the given level.
 *
 * @author msgroi
 */
public class LogAssertingExtension implements AfterEachCallback {

    private final LogAssertingAppender appender;

    public LogAssertingExtension() {
        appender = LogAssertingAppender.create();
    }

    public LogAssertingExtension(Level level) {
        appender = LogAssertingAppender.create(level);
    }

    public void shutdown() {
        appender.shutdown();
    }

    @Override
    public void afterEach(ExtensionContext extensionContext) {
        appender.shutdown();
    }

    public boolean logged(Level level, Predicate<String> messagePredicate) {
        return appender.logged(level, messagePredicate);
    }

    public List<String> messagesMatchingPredicate(Level level, Predicate<String> messagePredicate) {
        return appender.messagesMatchingPredicate(level, messagePredicate);
    }

    /**
     * Records log events and provides methods that a given message was or was not logged at the given level.
     *
     * @author msgroi
     */
    public static class LogAssertingAppender extends AppenderBase<ILoggingEvent> {
        public List<ILoggingEvent> list = new CopyOnWriteArrayList<>();

        private final Logger rootLogger;
        private final Optional<ch.qos.logback.classic.Level> originalLevelOpt;

        private LogAssertingAppender(Logger rootLogger, Optional<Level> levelOpt) {
            super();
            this.rootLogger = rootLogger;
            this.originalLevelOpt = levelOpt.map((Level level) -> {
                ch.qos.logback.classic.Level originalLevel = rootLogger.getLevel();
                rootLogger.setLevel(level.getInternalLevel());
                return originalLevel;
            });
            rootLogger.addAppender(this);
            start();
        }

        @Override
        protected void append(ILoggingEvent e) {
            this.list.add(e);
        }

        @Override
        public String getName() {
            return this.getClass().getSimpleName();
        }

        public String getMessages(Level level) {
            return list.stream()
                .filter(logEvent -> level.equals(logEvent.getLevel()))
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
        }

        public boolean logged(Level level, Predicate<String> messagePredicate) {
            return messageExists(level, messagePredicate);
        }

        List<String> messagesMatchingPredicate(Level level, Predicate<String> messagePredicate) {
            return messagesMatchingPredicateStream(level, messagePredicate).collect(Collectors.toList());
        }

        private boolean messageExists(Level level, Predicate<String> messagePredicate) {
            return messagesMatchingPredicateStream(level, messagePredicate).anyMatch(messagePredicate);
        }

        private Stream<String> messagesMatchingPredicateStream(Level level, Predicate<String> messagePredicate) {
            return list.stream()
                .filter(logEvent -> level.equals(logEvent.getLevel()))
                .map(ILoggingEvent::getFormattedMessage)
                .filter(messagePredicate);
        }

        public void shutdown() {
            stop();
            originalLevelOpt.ifPresent(rootLogger::setLevel);
            rootLogger.detachAppender(this);
        }

        static LogAssertingAppender create() {
            return new LogAssertingAppender(getRootLogger(), Optional.empty());
        }

        public static LogAssertingAppender create(Level level) {
            return new LogAssertingAppender(getRootLogger(), Optional.of(level));
        }

        private static Logger getRootLogger() {
            return (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        }

    }

    /**
     * Log level not tied to specific logging implementation.
     *
     * @author msgroi
     */
    public enum Level {
        TRACE(ch.qos.logback.classic.Level.TRACE),
        DEBUG(ch.qos.logback.classic.Level.DEBUG),
        INFO(ch.qos.logback.classic.Level.INFO),
        WARN(ch.qos.logback.classic.Level.WARN),
        ERROR(ch.qos.logback.classic.Level.ERROR);

        private final ch.qos.logback.classic.Level internalLevel;

        ch.qos.logback.classic.Level getInternalLevel() {
            return internalLevel;
        }

        boolean equals(ch.qos.logback.classic.Level level) {
            return internalLevel == level;
        }

        Level(ch.qos.logback.classic.Level level) {
            this.internalLevel = level;
        }
    }

}
