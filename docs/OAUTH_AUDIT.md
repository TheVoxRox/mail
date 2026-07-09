# VoxRox Mail — OAuth Handshake Audit

| | |
|---|---|
| **Version** | 1.0 |
| **Date** | 2026-07-09 |
| **Applies to** | VoxRox Mail V0.1.0 |
| **Audited commit** | `d55b753` |
| **Auditor** | Claude (Fable 5) + owner review |
| **Subsystem** | OAuth handshake — Boundary 2 of [SECURITY_THREAT_MODEL.md](../SECURITY_THREAT_MODEL.md) |
| **Verdict** | **Security: PASS** (no exploitable finding, no code change). |

Focused verification audit of the boundary **"OAuth provider ↔ system browser ↔
sidecar"**: every mitigation claimed by the Boundary 2 STRIDE rows was traced to
its code path, plus a data-flow check of the refresh token from callback to
disk. Method: static trace only (no dynamic testing); narrower in scope than
the full B3/B4 audits — see [AUDIT_GUIDE.md](AUDIT_GUIDE.md) for the method
tiers.

## 1. Authorization flow (confirmed)

- **PKCE (S256) for both providers.** Spring Security enables PKCE only for
  public clients; Google is configured as a confidential client, so
  [SecurityConfig.pkceAuthorizationRequestResolver](../backend/src/main/java/org/voxrox/mailbackend/core/config/SecurityConfig.java)
  forces `OAuth2AuthorizationRequestCustomizers.withPkce()` on every
  authorization request (OAuth 2.1 / Security BCP posture). Microsoft is a
  public client (`client-authentication-method=none`) — no secret exists for it
  anywhere.
- **State handling.** Spring's standard `state` nonce protects the callback.
  The custom failure handler treats `authorization_request_not_found` as benign
  **only** when the callback's `state` names a login already completed
  (`OAuth2CompletedStateTracker`) — a deliberately narrow gate: every genuine
  failure (expired code, denied consent, unknown state) still fails, and a
  different account's failure is never mistaken for success.
- **Failure hygiene.** The failure handler logs the error at WARN (code +
  description, server-side only) and redirects to the static
  `auth-failed.html?reason=<code>` with the code URL-encoded — no free-form
  provider text is ever reflected into the redirect.
- **Loopback redirect.** `redirect-uri` is
  `{baseUrl}/login/oauth2/code/{registrationId}` served by the loopback
  sidecar; both provider registrations are desktop/loopback types, so the
  provider side also refuses non-loopback redirect targets.
- **Scopes are hardcoded** per provider in `application.properties` (Google:
  `https://mail.google.com/` + `openid,email,profile`; Microsoft: IMAP/SMTP
  resource scopes + `offline_access`) — no in-app scope escalation path exists.

## 2. Token lifecycle (confirmed)

- **Refresh token at rest**: encrypted by
  [CryptoService](../backend/src/main/java/org/voxrox/mailbackend/core/security/CryptoService.java)
  (AES/GCM-256, per-account PBKDF2 key, `accountId` bound as AAD) — same path
  and format as IMAP passwords; see
  [CRYPTO_STORAGE_AUDIT.md](CRYPTO_STORAGE_AUDIT.md).
- **Access tokens live only in memory**:
  [TokenCache](../backend/src/main/java/org/voxrox/mailbackend/feature/auth/service/TokenCache.java)
  is a bounded LRU (256), invalidated on account deletion and on refresh
  failure. Never persisted.
- **Refresh failure → `requires_reauth`**
  ([OAuth2TokenService](../backend/src/main/java/org/voxrox/mailbackend/feature/auth/service/OAuth2TokenService.java)):
  the account is flagged, the sync scheduler stops picking it up, and the UI
  re-runs the OAuth wizard. The benign-duplicate-callback path deliberately
  does **not** clear the flag (`OAuth2LoginService`), so a stale success cannot
  resurrect a dead account.
- **`client_secret` is Google-only** and injected via env
  (`GOOGLE_OAUTH_CLIENT_SECRET`); the shared refresh body carries no secret —
  the Microsoft path would be rejected by AAD if one were sent (fixed boot
  blocker, see backend CHANGELOG).
- **Log hygiene**: token values are never logged; refresh logging carries a
  masked email, expiry and scope only (cross-checked with the 2026-06-13 log
  hygiene audit).

## 3. Informational notes (no change required)

- **Microsoft verified publisher** is still pending — blocks enterprise (B2B)
  tenants only, not the consumer flow. Tracked in todo.
- **Placeholder client-ids** (`mail-local-google-client-id`, …) are non-secret
  defaults; real values come from env. The historic placeholder boot trap was
  fixed (JVM now exits 1 on an unresolvable OAuth property).

## 4. References

- [SECURITY_THREAT_MODEL.md](../SECURITY_THREAT_MODEL.md) — Boundary 2 STRIDE matrix.
- [CRYPTO_STORAGE_AUDIT.md](CRYPTO_STORAGE_AUDIT.md) — Boundary 5 (token-at-rest path).
- [API_SURFACE_AUDIT.md](API_SURFACE_AUDIT.md) — Boundary 3 (public OAuth endpoints allow-list).
- [backend/SECURITY_RELEASE_CHECK.md](../backend/SECURITY_RELEASE_CHECK.md) — per-release security gate.

## 5. Change log

- **1.0** (2026-07-09) — initial focused audit; all Boundary 2 STRIDE
  mitigations verified against `d55b753`.
