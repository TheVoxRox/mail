package org.voxrox.mailbackend.core.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.voxrox.mailbackend.core.init.HandshakeResponse;
import org.voxrox.mailbackend.core.init.HandshakeService;

class SystemReadinessServiceTest {

    @Test
    @DisplayName("Readiness is independent of sync health and returns ready once the API has started")
    void readinessIsIndependentFromMailSyncHealth() {
        HandshakeService handshakeService = org.mockito.Mockito.mock(HandshakeService.class);
        when(handshakeService.getHandshake())
                .thenReturn(new HandshakeResponse("mail", "9.8.7-test", "1.0.0", "0.0.1", "1"));

        SystemReadinessResponse response = new SystemReadinessService(handshakeService).getReadiness();

        assertThat(response.ready()).isTrue();
        assertThat(response.phase()).isEqualTo(SystemReadinessPhase.READY);
        assertThat(response.appName()).isEqualTo("mail");
        assertThat(response.appVersion()).isEqualTo("9.8.7-test");
        assertThat(response.apiVersion()).isEqualTo("1.0.0");
        assertThat(response.minClientVersion()).isEqualTo("0.0.1");
        assertThat(response.dbSchemaVersion()).isEqualTo("1");
        assertThat(response.reason()).isNull();
    }
}
