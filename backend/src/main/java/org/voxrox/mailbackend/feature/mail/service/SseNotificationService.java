package org.voxrox.mailbackend.feature.mail.service;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.voxrox.mailbackend.core.config.MailClientProperties;
import org.voxrox.mailbackend.feature.mail.dto.SseEvent;
import org.voxrox.mailbackend.util.LogCategory;

import module java.base;

@Service
public class SseNotificationService {

    private static final Logger log = LoggerFactory.getLogger(SseNotificationService.class);

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final long sseTimeoutMs;

    public SseNotificationService(MailClientProperties props) {
        this.sseTimeoutMs = props.sync().sseTimeout().toMillis();
    }

    public SseEmitter register() {
        var emitter = new SseEmitter(sseTimeoutMs);
        emitters.add(emitter);

        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));

        log.debug("{} SSE client connected, active connections: {}", LogCategory.SYNC, emitters.size());
        return emitter;
    }

    public void broadcast(SseEvent notification) {
        if (emitters.isEmpty()) {
            return;
        }

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(notification.type()).data(notification));
            } catch (IOException | IllegalStateException e) {
                emitters.remove(emitter);
                log.debug("{} SSE emitter removed (send failed): {}", LogCategory.SYNC, e.getMessage());
            }
        }
    }

    @Scheduled(fixedRateString = "${mail.client.sync.sse-heartbeat-interval:PT30S}")
    public void sendHeartbeat() {
        if (emitters.isEmpty()) {
            return;
        }

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException | IllegalStateException e) {
                emitters.remove(emitter);
                log.debug("{} SSE emitter removed during heartbeat: {}", LogCategory.SYNC, e.getMessage());
            }
        }
    }

    int getActiveEmitterCount() {
        return emitters.size();
    }

    @PreDestroy
    public void shutdown() {
        if (emitters.isEmpty()) {
            return;
        }

        log.info("{} Closing SSE connections ({} active)...", LogCategory.SYNC, emitters.size());
        for (SseEmitter emitter : emitters) {
            try {
                emitter.complete();
            } catch (IllegalStateException e) {
                log.debug("{} SSE emitter was already closed at shutdown: {}", LogCategory.SYNC, e.getMessage());
            }
        }
        emitters.clear();
    }
}
