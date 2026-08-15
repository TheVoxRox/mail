# VoxRox Mail — OAuth Handshake Audit

|                    |                                                                                                                                                                                                                                                                                                                                                                                         |
| ------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Version**        | 1.2                                                                                                                                                                                                                                                                                                                                                                                     |
| **Date**           | 2026-08-15                                                                                                                                                                                                                                                                                                                                                                              |
| **Applies to**     | VoxRox Mail V0.1.0                                                                                                                                                                                                                                                                                                                                                                      |
| **Audited commit** | `cad05cb` (re-verified 2026-08-08, recorded pre-squash as `5799e8b`; 1.0 baseline: `d55b753`)                                                                                                                                                                                                                                                                                           |
| **Code paths**     | `backend/src/main/java/org/voxrox/mailbackend/feature/auth`, `backend/src/main/java/org/voxrox/mailbackend/core/config/SecurityConfig.java`, `backend/src/main/java/org/voxrox/mailbackend/core/config/OAuth2CompletedStateTracker.java`, `backend/src/main/java/org/voxrox/mailbackend/feature/account/service/ExternalProviderLoginService.java`, `backend/src/main/resources/static` |
| **Auditor**        | Claude (Fable 5) + owner review                                                                                                                                                                                                                                                                                                                                                         |
| **Subsystem**      | OAuth handshake — Boundary 2 of [SECURITY_THREAT_MODEL.md](../SECURITY_THREAT_MODEL.md)                                                                                                                                                                                                                                                                                                 |
| **Verdict**        | **Security: PASS** (no exploitable finding; the code change re-verified in 1.2 came from a reliability fix, not from an audit finding).                                                                                                                                                                                                                                                 |

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
- **The granted scopes are verified, not assumed.** A provider may return a
  valid token that carries fewer scopes than were requested: Google asks for
  the restricted Gmail scope on a consent screen separate from the sign-in, and
  approving only the sign-in yields a token with the OIDC scopes alone.
  [OAuth2LoginService](../backend/src/main/java/org/voxrox/mailbackend/feature/auth/service/OAuth2LoginService.java)
  compares the token's scopes against the mail scopes the provider's
  `OAuth2ClaimsExtractor` declares as required and rejects the login before any
  account is persisted (`MAIL_OAUTH2_SCOPE_NOT_GRANTED`). The check is
  one-directional — it can only refuse a grant that is too narrow, never widen
  one — so it does not weaken the escalation claim above. A token response
  carrying no scope information at all is deliberately allowed through, leaving
  the IMAP connection as the judge; the requested set stays the one in
  `application.properties`.

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
  resurrect a dead account. A login that returns without a refresh token or
  without the required mail scopes flags an existing account the same way
  rather than clearing it — the failing guards run before persistence, so the
  incomplete grant can neither create an account nor revive one.
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

- **1.2** (2026-08-15) — re-verified after the login flow gained a fourth
  fail-fast guard: `OAuth2LoginService` rejects a login whose access token does
  not carry the mail scopes its provider declares as required
  (`MAIL_OAUTH2_SCOPE_NOT_GRANTED`). Prompted by a real failure rather than a
  review — two Google logins in a row completed carrying `openid email profile`
  alone, so an account was created whose every IMAP connection was then
  refused, and the user-facing advice ("sign in again") could not fix it
  because the narrowed grant is stored at the provider. §1 gained the
  granted-scope claim, §2 the note that the guard flags an existing account
  instead of clearing it. The check is one-directional — it can refuse a grant
  that is too narrow but never widen one — so the **E** mitigation of
  Boundary 2 is strengthened, not moved, and the requested scope set is still
  exactly the one hardcoded in `application.properties`. Verdict unchanged
  (**PASS**).
- **1.1** (2026-08-08) — re-verified against `5799e8b` after `check:audits`
  reported one commit of drift. That commit (#179) is a pure delegation move:
  `OAuth2LoginService` now calls `ExternalProviderLoginService` instead of
  `AccountService` for `processExternalProviderLogin` and
  `markRequiresReauthIfExists`, same names and arguments, no behaviour change.
  Every §1–§2 claim re-checked and still true, including the one the move
  could plausibly have broken: the `requires_reauth` flag is cleared **only**
  on a successful re-login that carries a new refresh token, and the benign
  duplicate callback (`authorization_request_not_found` with a completed
  state) still only redirects — it never reaches the account, so a stale
  success cannot resurrect a dead account. Verdict unchanged (**PASS**).
  `Code paths` gained `ExternalProviderLoginService.java` and
  `OAuth2CompletedStateTracker.java`: the first is where #179 moved the token
  and account persistence this audit reasons about, the second implements the
  narrow state gate §1 describes, and neither was covered by the original
  pathspec — so a future change to either would not have tripped the gate.
- **1.0** (2026-07-09) — initial focused audit; all Boundary 2 STRIDE
  mitigations verified against `d55b753`.
