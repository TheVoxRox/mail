package org.voxrox.mailbackend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.ServerSocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MailBackendApplicationTest {

    @Test
    @DisplayName("default port is a random port managed by Spring")
    void defaultPortUsesSpringRandomPort() {
        assertThat(MailBackendApplication.resolveConfiguredPort(new String[0])).isZero();
    }

    @Test
    @DisplayName("resolveConfiguredPort prefers system property over CLI argument")
    void resolveConfiguredPortPrefersSystemProperty() {
        System.setProperty("server.port", "61234");
        try {
            int port = MailBackendApplication.resolveConfiguredPort(new String[]{"--server.port=60123"});

            assertThat(port).isEqualTo(61234);
        } finally {
            System.clearProperty("server.port");
        }
    }

    @Test
    @DisplayName("isPortAvailable returns false for an occupied loopback port")
    void isPortAvailableReturnsFalseForBoundPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            assertThat(MailBackendApplication.isPortAvailable(socket.getLocalPort())).isFalse();
        }
    }

    @Test
    @DisplayName("server.port=0 is left to Spring as a random port")
    void zeroPortIsAvailableBecauseSpringUsesRandomPort() {
        assertThat(MailBackendApplication.isPortAvailable(0)).isTrue();
    }

    @Test
    @DisplayName("invalid server.port fails with a readable configuration error")
    void invalidPortFailsWithConfigurationError() {
        System.setProperty("server.port", "abc");
        try {
            assertThatThrownBy(() -> MailBackendApplication.resolveConfiguredPort(new String[0]))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("server.port must be an integer");
        } finally {
            System.clearProperty("server.port");
        }
    }

    @Test
    @DisplayName("server.port out of range fails with a readable configuration error")
    void outOfRangePortFailsWithConfigurationError() {
        assertThatThrownBy(() -> MailBackendApplication.resolveConfiguredPort(new String[]{"--server.port=70000"}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("0-65535");
    }
}
