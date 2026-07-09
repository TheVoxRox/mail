package org.voxrox.mailbackend.core.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.voxrox.mailbackend.util.AuditLog;
import org.voxrox.mailbackend.util.LogCategory;

import module java.base;

@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);

    private final InternalApiKeyProvider apiKeyProvider;

    public ApiKeyFilter(InternalApiKeyProvider apiKeyProvider) {
        this.apiKeyProvider = apiKeyProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String clientKey = request.getHeader("X-API-KEY");

        if (clientKey != null) {
            if (secureCompare(clientKey, apiKeyProvider.getKey())) {
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken("desktop-client",
                        null, Collections.emptyList());
                SecurityContextHolder.getContext().setAuthentication(auth);
            } else {
                /*
                 * The client explicitly sent an API key but it does not match — always
                 * suspicious. Public endpoints do not send the key, so this is not a false
                 * positive. Fail-fast: respond with 401 immediately so the
                 * "invalid key on a public endpoint" case does not propagate further through
                 * the filter chain (Spring Security would not flag it, the request would slip
                 * through, which is confusing in the log).
                 */
                String actor = describeActor(request);
                log.warn("{} Invalid API key for {} (from {})", LogCategory.SECURITY, request.getRequestURI(), actor);
                AuditLog.failure("api_key_auth", actor, "invalid_key path=" + request.getRequestURI());
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Describes the request's actor for the audit log. For a localhost-only app the
     * remote IP is enough — locally it will always be 127.0.0.1 / ::1, but this
     * guards against accidental mistakes (e.g. binding on 0.0.0.0 in dev).
     */
    private String describeActor(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        return ip != null ? ip : "unknown";
    }

    private boolean secureCompare(String provided, String expected) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] providedHash = md.digest(provided.getBytes(StandardCharsets.UTF_8));
            byte[] expectedHash = md.digest(expected.getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(providedHash, expectedHash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Security provider failure", e);
        }
    }
}
