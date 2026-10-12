package org.voxrox.mailbackend.core.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class ClientErrorReportServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-12T10:00:00Z");

    private final AtomicReference<Instant> now = new AtomicReference<>(T0);
    private final ClientErrorReportService service = new ClientErrorReportService(new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    });
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attach() {
        appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(ClientErrorReportService.class)).addAppender(appender);
    }

    @AfterEach
    void detach() {
        ((Logger) LoggerFactory.getLogger(ClientErrorReportService.class)).detachAppender(appender);
    }

    @Test
    @DisplayName("A report is one WARN line with the route id, the script source and the stack frames joined")
    void reportIsOneLine() {
        service.report(new ClientErrorReportRequest("1b4e28ba-2fa1-11d2-883f-0016d3cca427", "2026-10-12T10:00:00Z",
                "window_error", "Cannot read properties of undefined", "TypeError",
                "TypeError: Cannot read properties of undefined\n    at load (tauri://localhost/_app/immutable/x.js:3:9)\n",
                "tauri://localhost/_app/immutable/x.js?v=1", 3, 9, "/mail/[accountId]/[folderName]",
                Map.of("boundary", "contact-list", "not a key", "dropped")));

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).isEqualTo("[CLIENT] window_error: TypeError: "
                    + "Cannot read properties of undefined, route /mail/[accountId]/[folderName], "
                    + "source tauri://localhost/_app/immutable/x.js:3:9, context boundary=contact-list, "
                    + "event 1b4e28ba-2fa1-11d2-883f-0016d3cca427, stack: TypeError: Cannot read properties of "
                    + "undefined | at load (tauri://localhost/_app/immutable/x.js:3:9)");
        });
    }

    @Test
    @DisplayName("A path with values is cut to its route, in the route and in a page source alike")
    void pathsAreCutToTheirRoute() {
        service.report(new ClientErrorReportRequest(null, null, "unhandled_rejection", "boom", null, null,
                "tauri://localhost/mail/7/Canary%20Lawyer/0123abcd?x=1", null, null, "/mail/7/Canary%20Lawyer/0123abcd",
                null));

        assertThat(appender.list).singleElement().extracting(ILoggingEvent::getFormattedMessage).asString()
                .isEqualTo("[CLIENT] unhandled_rejection: boom, route /mail, source tauri://localhost/mail")
                .doesNotContain("Canary");
    }

    @Test
    @DisplayName("An unknown kind, a missing message and an oversized field are written bounded")
    void fieldsAreBounded() {
        service.report(new ClientErrorReportRequest("not an id!", null, "custom", null, null, "x".repeat(10_000), null,
                null, null, null, null));

        String message = appender.list.getFirst().getFormattedMessage();
        assertThat(message).startsWith("[CLIENT] unknown: (no message), stack: ").doesNotContain("not an id");
        assertThat(message).hasSizeLessThan(4100);
    }

    @Test
    @DisplayName("At most twenty reports a minute are written, and the dropped ones are counted afterwards")
    void reportsAreRateLimited() {
        for (int i = 0; i < 25; i++) {
            service.report(new ClientErrorReportRequest(null, null, "window_error", "loop " + i, null, null, null, null,
                    null, null, null));
        }
        assertThat(appender.list).hasSize(20);

        now.set(T0.plus(Duration.ofSeconds(61)));
        service.report(new ClientErrorReportRequest(null, null, "window_error", "after", null, null, null, null, null,
                null, null));

        List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages.get(20)).isEqualTo(
                "[CLIENT] 5 more client error report(s) dropped in the minute from " + T0 + ": more than 20 a minute.");
        assertThat(messages.get(21)).isEqualTo("[CLIENT] window_error: after");
    }
}
