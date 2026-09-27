package org.voxrox.mailbackend.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * The one authentication chokepoint of the sidecar API (audit B3 §1). A request
 * without the header passes through unauthenticated and is left to the chain's
 * default-deny; the right key authenticates it; a wrong key ends the request
 * here with 401 and an audit record, without reaching the rest of the chain.
 */
class ApiKeyFilterTest {

    private static final String KEY = "k3y-of-this-sidecar-process";

    private final InternalApiKeyProvider keyProvider = mock(InternalApiKeyProvider.class);
    private final ApiKeyFilter filter = new ApiKeyFilter(keyProvider);
    private final FilterChain chain = mock(FilterChain.class);
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private ListAppender<ILoggingEvent> audit;

    @BeforeEach
    void setUp() {
        when(keyProvider.getKey()).thenReturn(KEY);
        SecurityContextHolder.clearContext();
        audit = new ListAppender<>();
        audit.start();
        ((Logger) LoggerFactory.getLogger("AUDIT")).addAppender(audit);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger("AUDIT")).detachAppender(audit);
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest request(String key) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/accounts");
        if (key != null) {
            request.addHeader("X-API-KEY", key);
        }
        return request;
    }

    @Test
    @DisplayName("The right key authenticates the request and passes it on")
    void rightKeyAuthenticates() throws Exception {
        MockHttpServletRequest request = request(KEY);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getName()).isEqualTo("desktop-client");
        assertThat(audit.list).isEmpty();
    }

    @Test
    @DisplayName("A wrong key is a 401 with an audit record, and the chain never sees the request")
    void wrongKeyIsRejectedHere() throws Exception {
        MockHttpServletRequest request = request("not-the-key");

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        verify(chain, never()).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(audit.list).singleElement().extracting(ILoggingEvent::getFormattedMessage).asString()
                .contains("FAILURE action=api_key_auth").contains("invalid_key path=/api/v1/accounts")
                .doesNotContain("not-the-key");
    }

    @Test
    @DisplayName("A key that is the right one plus a suffix, or a prefix of it, is still wrong")
    void keysOfOtherLengthsAreWrong() throws Exception {
        for (String key : new String[]{KEY + "x", KEY.substring(0, KEY.length() - 1), ""}) {
            MockHttpServletResponse rejected = new MockHttpServletResponse();

            filter.doFilter(request(key), rejected, chain);

            assertThat(rejected.getStatus()).as(key).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        }
        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("No key passes the request on unauthenticated, for the default-deny to refuse")
    void noKeyIsLeftToTheChain() throws Exception {
        MockHttpServletRequest request = request(null);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(audit.list).isEmpty();
    }
}
