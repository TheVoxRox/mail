package org.voxrox.mailbackend.core.security;

import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;
import org.voxrox.mailbackend.core.init.HandshakeService;

@Component
public class InternalApiKeyProvider {

    private final HandshakeService handshakeService;
    private final AtomicReference<String> cachedKey = new AtomicReference<>();

    public InternalApiKeyProvider(HandshakeService handshakeService) {
        this.handshakeService = handshakeService;
    }

    public String getKey() {
        return cachedKey.updateAndGet(currentKey -> {
            if (currentKey == null) {
                return handshakeService.getOrCreateApiKey();
            }
            return currentKey;
        });
    }

    public void invalidateCache() {
        cachedKey.set(null);
    }
}
