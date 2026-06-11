package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.voxrox.mailbackend.core.config.MailClientProperties;
import org.voxrox.mailbackend.core.config.mail.ImapProperties;
import org.voxrox.mailbackend.core.config.mail.RetryProperties;
import org.voxrox.mailbackend.core.config.mail.SmtpProperties;
import org.voxrox.mailbackend.core.config.mail.SyncProperties;
import org.voxrox.mailbackend.feature.mail.dto.SyncNotification;

@DisplayName("SseNotificationService")
class SseNotificationServiceTest {

    private SseNotificationService service;

    @BeforeEach
    void setUp() {
        var syncProps = new SyncProperties(100, 200, Duration.ofMinutes(5), Duration.ofSeconds(10), 50, 30, 300, 4, 256,
                200, Duration.ofMinutes(30), Duration.ofSeconds(30));
        var props = new MailClientProperties(mock(ImapProperties.class), mock(SmtpProperties.class), syncProps,
                mock(RetryProperties.class));
        service = new SseNotificationService(props);
    }

    private SyncNotification sampleNotification() {
        return new SyncNotification("sync_completed", 1L, "INBOX", 5, Instant.now());
    }

    @Nested
    @DisplayName("register")
    class Register {

        @Test
        @DisplayName("creates an emitter and increments the active-connection count")
        void registersEmitter() {
            SseEmitter emitter = service.register();

            assertThat(emitter).isNotNull();
            assertThat(service.getActiveEmitterCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("multiple registrations -> multiple emitters")
        void multipleRegistrations() {
            service.register();
            service.register();

            assertThat(service.getActiveEmitterCount()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("broadcast")
    class Broadcast {

        @Test
        @DisplayName("no emitters — no-op, no exception")
        void noEmitters() {
            service.broadcast(sampleNotification());
            assertThat(service.getActiveEmitterCount()).isZero();
        }

        @Test
        @DisplayName("sends the event to a registered emitter")
        void sendsToRegistered() throws Exception {
            SseEmitter emitter = mock(SseEmitter.class);
            registerMockEmitter(emitter);

            service.broadcast(sampleNotification());

            verify(emitter).send(org.mockito.ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));
        }

        @Test
        @DisplayName("dead emitter (IOException) is automatically removed")
        void removesDeadEmitter() throws Exception {
            SseEmitter emitter = mock(SseEmitter.class);
            doThrow(new IOException("broken pipe")).when(emitter)
                    .send(org.mockito.ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));
            registerMockEmitter(emitter);

            service.broadcast(sampleNotification());

            assertThat(service.getActiveEmitterCount()).isZero();
        }

        @Test
        @DisplayName("IllegalStateException on send -> emitter is removed")
        void removesOnIllegalState() throws Exception {
            SseEmitter emitter = mock(SseEmitter.class);
            doThrow(new IllegalStateException("completed")).when(emitter)
                    .send(org.mockito.ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));
            registerMockEmitter(emitter);

            service.broadcast(sampleNotification());

            assertThat(service.getActiveEmitterCount()).isZero();
        }
    }

    @Nested
    @DisplayName("sendHeartbeat")
    class Heartbeat {

        @Test
        @DisplayName("no emitters — no-op")
        void noEmitters() {
            service.sendHeartbeat();
            assertThat(service.getActiveEmitterCount()).isZero();
        }

        @Test
        @DisplayName("sends a heartbeat comment to a registered emitter")
        void sendsHeartbeat() throws Exception {
            SseEmitter emitter = mock(SseEmitter.class);
            registerMockEmitter(emitter);

            service.sendHeartbeat();

            verify(emitter).send(org.mockito.ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));
        }

        @Test
        @DisplayName("dead emitter on heartbeat is removed")
        void removesDeadOnHeartbeat() throws Exception {
            SseEmitter emitter = mock(SseEmitter.class);
            doThrow(new IOException("closed")).when(emitter)
                    .send(org.mockito.ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));
            registerMockEmitter(emitter);

            service.sendHeartbeat();

            assertThat(service.getActiveEmitterCount()).isZero();
        }
    }

    @Nested
    @DisplayName("shutdown")
    class Shutdown {

        @Test
        @DisplayName("closes every active emitter and clears the registry")
        void completesAndClearsEmitters() {
            SseEmitter first = mock(SseEmitter.class);
            SseEmitter second = mock(SseEmitter.class);
            registerMockEmitter(first);
            registerMockEmitter(second);

            service.shutdown();

            verify(first).complete();
            verify(second).complete();
            assertThat(service.getActiveEmitterCount()).isZero();
        }

        @Test
        @DisplayName("shutdown tolerates an already-closed emitter")
        void toleratesAlreadyClosedEmitter() {
            SseEmitter emitter = mock(SseEmitter.class);
            doThrow(new IllegalStateException("already completed")).when(emitter).complete();
            registerMockEmitter(emitter);

            service.shutdown();

            assertThat(service.getActiveEmitterCount()).isZero();
        }
    }

    /**
     * Registers a mock emitter directly inside the service. Using the real
     * register() and then adding a mock does not work because register() returns a
     * fresh SseEmitter. Instead we exercise the real flow through register() where
     * we need a real emitter, and inject a mock via reflection where we need
     * control over send().
     */
    private void registerMockEmitter(SseEmitter emitter) {
        try {
            var field = SseNotificationService.class.getDeclaredField("emitters");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var list = (java.util.List<SseEmitter>) field.get(service);
            list.add(emitter);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
