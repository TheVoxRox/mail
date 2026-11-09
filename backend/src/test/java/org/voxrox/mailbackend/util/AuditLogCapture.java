package org.voxrox.mailbackend.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Captures what {@link AuditLog} writes while a test runs, so a test can assert
 * the entry a security claim rests on. Register it on a field:
 *
 * <pre>
 * &#64;RegisterExtension
 * final AuditLogCapture audit = new AuditLogCapture();
 * </pre>
 *
 * A {@code @Nested} class sees the enclosing instance's capture.
 * <p>
 * The entries are matched on {@code AuditLog}'s own format,
 * {@code <OUTCOME> action=<action> actor=<actor> detail|reason=<text>}, with
 * the action matched whole, so {@code token_refresh} does not also match a
 * longer action that starts with it.
 */
public final class AuditLogCapture implements BeforeEachCallback, AfterEachCallback {

    private final Logger logger = (Logger) LoggerFactory.getLogger("AUDIT");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @Override
    public void beforeEach(ExtensionContext context) {
        appender.list.clear();
        appender.start();
        logger.addAppender(appender);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        logger.detachAppender(appender);
        appender.stop();
    }

    /**
     * Asserts exactly one {@code SUCCESS} entry for the action, carrying the text.
     */
    public void assertSuccess(String action, String text) {
        assertOne("SUCCESS", action, text);
    }

    /**
     * Asserts exactly one {@code FAILURE} entry for the action, carrying the text.
     */
    public void assertFailure(String action, String text) {
        assertOne("FAILURE", action, text);
    }

    /**
     * Asserts exactly one {@code CRITICAL} entry for the action, carrying the text.
     */
    public void assertCritical(String action, String text) {
        assertOne("CRITICAL", action, text);
    }

    /** Asserts that nothing was written for the action, whatever its outcome. */
    public void assertNone(String action) {
        assertThat(entries(action)).as("audit entries for action=%s", action).isEmpty();
    }

    private void assertOne(String outcome, String action, String text) {
        List<String> entries = entries(action);
        assertThat(entries).filteredOn(entry -> entry.startsWith(outcome + " ") && entry.contains(text))
                .as("exactly one %s entry for action=%s carrying '%s', among %s", outcome, action, text, entries)
                .hasSize(1);
    }

    private List<String> entries(String action) {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains(" action=" + action + " ")).toList();
    }
}
