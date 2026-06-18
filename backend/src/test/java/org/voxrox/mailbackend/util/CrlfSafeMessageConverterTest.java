package org.voxrox.mailbackend.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.joran.spi.JoranException;

/**
 * Log-injection (CWE-117) guard for {@link CrlfSafeMessageConverter}.
 *
 * <p>
 * The unit tests pin the sanitisation contract; the integration test proves
 * that rebinding the built-in {@code %m} word via {@code <conversionRule>} is
 * honoured by the Logback version we ship, which is the real risk of the
 * approach (a future Logback could refuse to override default pattern words).
 */
class CrlfSafeMessageConverterTest {

    @Nested
    @DisplayName("sanitize()")
    class Sanitize {

        @Test
        @DisplayName("null -> empty string")
        void nullReturnsEmpty() {
            assertThat(CrlfSafeMessageConverter.sanitize(null)).isEmpty();
        }

        @Test
        @DisplayName("plain message without line breaks is returned unchanged")
        void plainMessageUnchanged() {
            String input = "user j***k@seznam.cz fetched folder INBOX";
            assertThat(CrlfSafeMessageConverter.sanitize(input)).isSameAs(input);
        }

        @Test
        @DisplayName("LF is replaced with underscore")
        void lineFeedReplaced() {
            assertThat(CrlfSafeMessageConverter.sanitize("a\nb")).isEqualTo("a_b");
        }

        @Test
        @DisplayName("CR is replaced with underscore")
        void carriageReturnReplaced() {
            assertThat(CrlfSafeMessageConverter.sanitize("a\rb")).isEqualTo("a_b");
        }

        @Test
        @DisplayName("a forged log line via CRLF is neutralised")
        void forgedLineNeutralised() {
            String forged = "Selected folder INBOX\r\n2026-06-18 ERROR fake admin login succeeded";
            String sanitized = CrlfSafeMessageConverter.sanitize(forged);
            assertThat(sanitized).doesNotContain("\r").doesNotContain("\n");
            assertThat(sanitized).isEqualTo("Selected folder INBOX__2026-06-18 ERROR fake admin login succeeded");
        }

        @Test
        @DisplayName("multiple and trailing breaks are all replaced")
        void multipleBreaksReplaced() {
            assertThat(CrlfSafeMessageConverter.sanitize("\na\n\nb\r")).isEqualTo("_a__b_");
        }
    }

    @Nested
    @DisplayName("Logback wiring")
    class Wiring {

        /**
         * Registers the conversion rule through the real Joran XML path (same as
         * logback-spring.xml) and confirms a {@code %m} pattern routes the message —
         * including a value injected via a {@code {}} placeholder — through the
         * converter, with no surviving line breaks.
         */
        @Test
        @DisplayName("%m is rebound so a CRLF argument cannot forge a line")
        void conversionRuleRebindsMessageWord() throws JoranException {
            LoggerContext context = new LoggerContext();
            String config = """
                    <configuration>
                      <conversionRule conversionWord="m"
                          class="org.voxrox.mailbackend.util.CrlfSafeMessageConverter"/>
                    </configuration>
                    """;
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            configurator.doConfigure(new ByteArrayInputStream(config.getBytes(StandardCharsets.UTF_8)));

            PatternLayout layout = new PatternLayout();
            layout.setContext(context);
            layout.setPattern("%m");
            layout.start();

            LoggingEvent event = new LoggingEvent();
            event.setLevel(Level.INFO);
            event.setMessage("folder={}");
            event.setArgumentArray(new Object[]{"INBOX\r\nFORGED ADMIN LINE"});

            String rendered = layout.doLayout(event);

            assertThat(rendered).isEqualTo("folder=INBOX__FORGED ADMIN LINE");
            assertThat(rendered).doesNotContain("\r").doesNotContain("\n");

            layout.stop();
            context.stop();
        }
    }
}
