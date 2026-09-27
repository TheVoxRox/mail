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

    @Test
    @DisplayName("A route id is kept whole: it names the page and carries no value")
    void routeIdIsKept() {
        assertThat(routeOf("/mail/[accountId]/[folderName]/[stableId]"))
                .isEqualTo("/mail/[accountId]/[folderName]/[stableId]");
        assertThat(routeOf("/settings/accounts/new")).isEqualTo("/settings/accounts/new");
        assertThat(routeOf("/")).isEqualTo("/");
    }

    @Test
    @DisplayName("A path with values is cut before the first one, so a folder the user named never gets in")
    void pathWithValuesIsCut() {
        assertThat(routeOf("/mail/7/Canary%20Lawyer%204417/0123abcd")).isEqualTo("/mail");
        assertThat(routeOf("/settings/accounts/12")).isEqualTo("/settings/accounts");
        assertThat(routeOf("/search/3?q=lawyer")).isEqualTo("/search");
        assertThat(routeOf("not-a-path")).isNull();
    }

    private static String routeOf(String route) {
        ClientBootDiagnosticsService service = new ClientBootDiagnosticsService();
        service.update(
                new ClientBootDiagnosticsRequest("2026-09-27T10:00:00Z", "ready", "none", Map.of(), null, null, route));
        return service.latest().route();
    }
}
