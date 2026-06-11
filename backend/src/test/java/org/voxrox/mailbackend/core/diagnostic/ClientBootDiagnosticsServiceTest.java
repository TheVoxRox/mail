package org.voxrox.mailbackend.core.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClientBootDiagnosticsServiceTest {

    @Test
    @DisplayName("Client boot diagnostics sanitizes texts, route and timing keys")
    void updateSanitizesPayload() {
        ClientBootDiagnosticsService service = new ClientBootDiagnosticsService();

        service.update(new ClientBootDiagnosticsRequest("not-an-instant", "ready\nsecret", "fast",
                Map.of("appReady", 1234L, "apiKey", 999L, "negative", -1L, "tooLarge", 999999999L), "Browser\tAgent",
                "cs", "/settings/about?apiKey=secret#token"));

        ClientBootDiagnosticsService.ClientBootDiagnosticsSnapshot snapshot = service.latest();

        assertThat(snapshot.reportedAt()).isNotBlank();
        assertThat(snapshot.phase()).isEqualTo("ready secret");
        assertThat(snapshot.slowLevel()).isEqualTo("fast");
        assertThat(snapshot.timings()).containsExactly(Map.entry("appReady", 1234L));
        assertThat(snapshot.userAgent()).isEqualTo("Browser Agent");
        assertThat(snapshot.language()).isEqualTo("cs");
        assertThat(snapshot.route()).isEqualTo("/settings/about");
    }
}
