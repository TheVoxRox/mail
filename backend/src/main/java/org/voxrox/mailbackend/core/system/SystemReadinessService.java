package org.voxrox.mailbackend.core.system;

import org.springframework.stereotype.Service;
import org.voxrox.mailbackend.core.init.HandshakeResponse;
import org.voxrox.mailbackend.core.init.HandshakeService;

@Service
public class SystemReadinessService {

    private final HandshakeService handshakeService;

    public SystemReadinessService(HandshakeService handshakeService) {
        this.handshakeService = handshakeService;
    }

    public SystemReadinessResponse getReadiness() {
        HandshakeResponse handshake = handshakeService.getHandshake();
        return new SystemReadinessResponse(true, SystemReadinessPhase.READY, handshake.appName(),
                handshake.appVersion(), handshake.apiVersion(), handshake.minClientVersion(),
                handshake.dbSchemaVersion(), null);
    }
}
