# VoxRox Mail — OAuth Handshake Audit

|                    |                                                                                                                                                                                                                                                                                                                                                                                         |
| ------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Version**        | 1.3                                                                                                                                                                                                                                                                                                                                                                                     |
| **Date**           | 2026-10-04                                                                                                                                                                                                                                                                                                                                                                              |
| **Applies to**     | VoxRox Mail V0.1.0                                                                                                                                                                                                                                                                                                                                                                      |
| **Audited commit** | `64633fe` (every claim re-verified 2026-10-04; 1.1 and 1.2: `cad05cb`, recorded pre-squash as `5799e8b`; 1.0 baseline: `d55b753`)                                                                                                                                                                                                                                                       |
| **Code paths**     | `backend/src/main/java/org/voxrox/mailbackend/feature/auth`, `backend/src/main/java/org/voxrox/mailbackend/core/config/SecurityConfig.java`, `backend/src/main/java/org/voxrox/mailbackend/core/config/OAuth2CompletedStateTracker.java`, `backend/src/main/java/org/voxrox/mailbackend/feature/account/service/ExternalProviderLoginService.java`, `backend/src/main/resources/static` |
| **Auditor**        | Claude (Fable 5) + owner review; 1.3 re-verified by Claude Opus 5.5                                                                                                                                                                                                                                                                                                                     |
| **Subsystem**      | OAuth handshake — Boundary 2 of [SECURITY_THREAT_MODEL.md](../SECURITY_THREAT_MODEL.md)                                                                                                                                                                                                                                                                                                 |
| **Verdict**        | **Security: open finding** — **B2-1** (High, open): the session an OAuth sign-in leaves in the system browser passes the sidecar API's default-deny without the `X-API-KEY` (§3). Every other claim re-verified; four corrected (§1, §2), five informational notes added (§4).                                                                                                          |

Focused verification audit of the boundary **"OAuth provider ↔ system browser ↔
sidecar"**: every mitigation claimed by the Boundary 2 STRIDE rows was traced to
its code path, plus a data-flow check of the refresh token from callback to
disk. Narrower in scope than the full B3/B4 audits — see
[AUDIT_GUIDE.md](AUDIT_GUIDE.md) for the method tiers.

**Method (1.3).** Static trace of every claim below against `64633fe`: each
file under `Code paths` read in full (`git ls-files` on the pathspec lists
them), and the whole delta since the previous anchor
(`git diff cad05cb 64633fe -- <Code paths>`) read as one change rather than as
the eight acknowledgements it had been recorded in. The Spring Security
behaviour §1 relies on was read from the 7.1.1 jar the build resolves
(`DefaultOAuth2AuthorizationRequestResolver`, `CommonOAuth2Provider`). Dynamic
cover is limited to the claims these tests exercise: `SecurityConfigTest` (the
failure redirect and its encoding, the completed-state gate, PKCE on the
confidential Google client), `OAuth2CompletedStateTrackerTest`,
`OAuth2LoginServiceTest` (the four guards and the scope-less token),
`ExternalProviderLoginServiceTest`, `OAuth2CallbackControllerTest`, and the
Google and Microsoft token-service tests (a permanent refresh rejection marks
`requires_reauth`). B2-1 was confirmed by a throwaway probe, not committed: a
`@SpringBootTest` driving the real filter chain through
`/oauth2/authorization/google`, `/login/oauth2/code/google` and
`/api/v1/auth/oauth2/success` against WireMock token and user-info endpoints
(`openid` dropped from the requested scopes, so no id_token has to be signed),
then calling the API with the session that flow left (§3). Not done: a sign-in
against a real provider; a real browser — which cookie a browser sends from
which origin is reasoned from the browsers' documented defaults in §3, not
measured; and the provider consoles (redirect-URI registrations, consent-screen
status), which no file in the repository can show.

`Code paths` knowingly leaves out what the sections cite but which changes for
other reasons: `backend/src/main/resources/application.properties` (the
registrations and scopes of §1; it is in B3's `Code paths`, so a change there
trips that gate), `AccountCredentialService` and `CryptoService` (the at-rest
path, Boundary 5), `ApiKeyFilter` (Boundary 3) and the client's
`frontend/src/lib/api/googleAuth.ts`.

## 1. Authorization flow (confirmed, two claims corrected)

- **PKCE (S256) for both providers.** Spring Security 7.1.1 adds PKCE by
  default only to a registration with `client-authentication-method=none` or
  the `requireProofKey` client setting. Microsoft is the first; Google, a
  confidential client (`client_secret_basic`, Spring's default for the
  `google` registration), is neither.
  [SecurityConfig.pkceAuthorizationRequestResolver](../backend/src/main/java/org/voxrox/mailbackend/core/config/SecurityConfig.java)
  installs `OAuth2AuthorizationRequestCustomizers.withPkce()` as the resolver's
  customizer, which runs on every authorization request, so the Google flow
  carries `code_challenge_method=S256` too (OAuth 2.1 / Security BCP posture).
  Microsoft is a public client — no secret exists for it anywhere: not in
  `application.properties`, and `backend/scripts/package-sidecar-windows.ps1`
  maps only its client id into the build.
- **State handling.** Spring's standard `state` nonce protects the callback.
  The custom failure handler treats `authorization_request_not_found` as benign
  **only** when the callback's `state` names a login already completed
  (`OAuth2CompletedStateTracker`, a bounded set filled by the success handler) —
  a deliberately narrow gate: every genuine failure (expired code, denied
  consent, unknown state) still fails, and a different account's failure is
  never mistaken for success.
- **Failure hygiene (corrected).** The failure handler logs at WARN, server-side
  only, the error code, the error description, the exception's type and its
  message, and redirects to the static `auth-failed.html?reason=<code>` with the
  code URL-encoded. The code is the `OAuth2Error` code — Spring's own, or the
  `error` parameter a provider put on the callback; the description never
  reaches the redirect. The page writes the reason with `textContent`, so even
  an unexpected value is shown as text and never parsed as markup. 1.2 named
  the code and description only and said nothing of how the page renders it.
  A rejection **after** the filter does not pass through this handler: the four
  login guards and the callback controller's two checks in `/success` reach
  `GlobalExceptionHandler`, which answers the browser with a problem document
  carrying the error code and the text of a message key of their own
  (`error.mail.oauth2NoEmail`, `oauth2NoUserId`, `oauth2NoRefreshToken`,
  `oauth2ScopeNotGranted`, `validation.oauth2.loginNotCompleted`), never the
  English sentence the code writes for its log (#607, #608). `/start` accepts
  a provider only if it matches `^[a-z0-9_-]+$` within 50 characters and names
  a registration, so the value it echoes in `validation.oauth2.unknownProvider`
  is confined to that alphabet, and the redirect it issues goes to Spring's own
  `/oauth2/authorization/{provider}`.
- **Loopback redirect (corrected).** `redirect-uri` is
  `{baseUrl}/login/oauth2/code/{registrationId}` — spelled out for Microsoft,
  Spring's default for the `google` registration — and `{baseUrl}` comes from
  the request that starts the flow: the client opens `/api/v1/auth/oauth2/start`
  on `localhost` (`googleAuth.ts` forces the host name), and the sidecar listens
  on `127.0.0.1` only (`server.address`). That the providers also refuse a
  non-loopback target is a property of the two app registrations, which no
  file in the repository shows; 1.2 stated it as confirmed. The repository's
  own comments disagree about which Azure platform carries the Microsoft
  redirect URI (§4).
- **Scopes are hardcoded** per provider in `application.properties` (Google:
  `https://mail.google.com/` + `openid,email,profile`; Microsoft:
  `openid,email,profile,offline_access` + the IMAP/SMTP resource scopes), and
  the Microsoft refresh repeats exactly that set from a constant
  (`MicrosoftTokenService.REFRESH_SCOPE`), so a refresh cannot widen the grant
  either. No in-app scope escalation path exists.
- **The granted scopes are verified, not assumed.** A provider may return a
  valid token that carries fewer scopes than were requested: Google asks for
  the restricted Gmail scope on a consent screen separate from the sign-in, and
  approving only the sign-in yields a token with the OIDC scopes alone.
  [OAuth2LoginService](../backend/src/main/java/org/voxrox/mailbackend/feature/auth/service/OAuth2LoginService.java)
  compares the token's scopes, case-insensitively, against the mail scopes the
  provider's `OAuth2ClaimsExtractor` declares as required (an abstract method,
  so a new provider cannot opt out by omission) and rejects the login before
  any account is persisted (`MAIL_OAUTH2_SCOPE_NOT_GRANTED`). The check is
  one-directional — it can only refuse a grant that is too narrow, never widen
  one — so it does not weaken the escalation claim above. A token response
  carrying no scope information at all is deliberately allowed through, leaving
  the IMAP connection as the judge; the requested set stays the one in
  `application.properties`.

## 2. Token lifecycle (confirmed, two claims corrected)

- **Refresh token at rest**: encrypted by
  [CryptoService](../backend/src/main/java/org/voxrox/mailbackend/core/security/CryptoService.java)
  (AES/GCM-256, per-account PBKDF2 key, `accountId` bound as AAD) — same path
  and format as IMAP passwords (`AccountCredentialService.saveCredentials`);
  see [CRYPTO_STORAGE_AUDIT.md](CRYPTO_STORAGE_AUDIT.md). Before that it is in
  memory in clear, in the authorized-client store Spring Boot configures
  (`InMemoryOAuth2AuthorizedClientService`), from the callback until `/success`
  removes it after a successful login — and longer after a rejected one (§4).
- **Access tokens live only in memory**:
  [TokenCache](../backend/src/main/java/org/voxrox/mailbackend/feature/auth/service/TokenCache.java)
  is a bounded LRU (256), never persisted. **Corrected:** 1.2 said it is
  invalidated on account deletion and on refresh failure. It is invalidated on
  a permanent refresh rejection (400 or 401) for a persisted account, when IMAP
  or SMTP refuses XOAUTH2 (the auth-retry path), after a successful re-login
  (after commit), and on deletion through `revokeToken` — always for Microsoft,
  but for Google only when the revoke call succeeds. A failed Google revoke
  leaves the entry until LRU eviction or the end of the process; nothing can
  reach it, since account ids are never reused (`AUTOINCREMENT`), and the token
  itself expires within the hour. A transient refresh failure (5xx, network)
  leaves the stale entry, which `isFresh` never returns.
- **Refresh failure → `requires_reauth`**
  ([OAuth2TokenService](../backend/src/main/java/org/voxrox/mailbackend/feature/auth/service/OAuth2TokenService.java)):
  a permanent rejection flags the account, records the last error and publishes
  `AccountRequiresReauthEvent`, which closes its pooled IMAP connections; the
  sync scheduler stops picking it up (`findByActiveTrueAndRequiresReauthFalse`),
  and the accounts page offers a sign-in-again button that restarts the
  provider's flow (1.2 said "the UI re-runs the OAuth wizard"). Three places set
  the flag (`OAuth2TokenService`, `ImapConnectionManager`,
  `markRequiresReauthIfExists`) and exactly one clears it:
  `processExternalProviderLogin`, reached only after all four guards pass and
  only with a refresh token in hand. The benign-duplicate-callback path
  redirects to `auth-finished.html` and never reaches the account, so a stale
  success cannot resurrect a dead account. A login that returns without a
  refresh token or without the required mail scopes flags an existing account
  rather than clearing it — the failing guards run before persistence, so the
  incomplete grant can neither create an account nor revive one.
- **`client_secret` is Google-only** and injected via env
  (`GOOGLE_OAUTH_CLIENT_SECRET`, a secret of the signed-release workflow that
  `package-sidecar-windows.ps1` passes to the build, refusing a placeholder
  unless told otherwise); per Google's installed-app guidance it ships in the
  build and is not treated as a secret. The shared refresh body carries no
  secret — the Microsoft path would be rejected by AAD if one were sent (fixed
  boot blocker, see backend CHANGELOG).
- **Log hygiene**: token values are never logged. Refresh logging carries a
  masked email, the expiry and the granted scope; a rejected refresh or revoke
  logs the provider's error response (status and body), which carries no token.
  Audit entries go to the separate `audit.log`, not `mail.log`: `oauth2_login`,
  `token_refresh`, `token_revoke`, `account_requires_reauth`,
  `account_reauth_cleared` and `account_create`, the last of which carries the
  provider's stable user id (`sub` or `oid`) beside the masked email — an
  identifier, not a credential. Cross-checked with the 2026-06-13 log hygiene
  audit.

## 3. Findings

### B2-1 — The sign-in's browser session stands in for the API key (High, open)

**What.** The OAuth flow runs in the system browser and needs a session for the
state, the PKCE verifier and the authorized client
(`SessionCreationPolicy.IF_REQUIRED`). When the callback succeeds, Spring keeps
the resulting `OAuth2AuthenticationToken` in that session — which is how
`/api/v1/auth/oauth2/success`, a separate request, receives it. Nothing ends
the session afterwards: `/success` removes the authorized client and
redirects, and its rejections leave the session as it was. The chain ends with
`anyRequest().authenticated()`, which any authenticated token satisfies;
`ApiKeyFilter` only adds one when the header is present. So while the session
lives — the servlet container's default 30 minutes idle, renewed by every
request that uses it — a request to any `/api/**` endpoint that carries the browser's session
cookie for `localhost` is authorized without the `X-API-KEY`. CORS answers
every `http://localhost:*` origin with credentials allowed and CSRF protection
is off, so script on any `http://localhost:<port>` page in that browser — the
same site as the sidecar, so a `SameSite=Lax` default does not hold the cookie
back — can find the port by trying it, then read and change everything the API
exposes. That includes `PUT /api/v1/accounts/{id}`, which takes a new IMAP and
SMTP host without the account's secret (`AccountService.updateAccount`), so the
next sync would present the stored password, or a fresh OAuth access token, to
a server of the caller's choosing.

**Verified** by the probe of the method statement. After the three requests of
a sign-in (provider faked), `GET /api/v1/accounts` with the session, no key and
`Origin: http://localhost:5173` answered 200 with the account, together with
`Access-Control-Allow-Origin: http://localhost:5173` and
`Access-Control-Allow-Credentials: true`; the same request without the session
answered 302; a `DELETE` of an account id that does not exist answered 404,
that is, it passed authorization. Present at every anchor: the session policy,
the default-deny, the CORS list and the disabled CSRF protection are all in the
initial import and in `d55b753`. The B3 audit's §1 and §7 reasoned the API
against a key only and called the CORS list unexploitable for that reason; both
are corrected in [API_SURFACE_AUDIT.md](API_SURFACE_AUDIT.md) 1.12.

**Severity: High** — on the threat model's rubric, credential exposure under
specific preconditions and persistent integrity loss. The preconditions: an
OAuth sign-in in the default browser during this run of the sidecar, and
script executing on a `http://localhost:*` origin in the same browser profile
while the session lives — another local web app with an injection flaw, a
local dev server serving third-party code, a page a local process opens. A
remote site does not get far: Chromium-based browsers keep a cookie without a
`SameSite` attribute off its cross-site fetches, in any browser CORS refuses
to show it a response or let it send JSON, and it does not know the port. The
same-user local process of the threat model's §1 gains nothing it could not
already take from `session.json`. What breaks is the reason the key exists:
keeping requests a browser originates out of the API.

**Recommendation.** Make an OAuth sign-in unable to satisfy the API's rule:
have `ApiKeyFilter` grant an authority of its own and require it outside
`PUBLIC_ENDPOINTS` (`hasAuthority(...)` instead of `authenticated()`), so the
session's `OAuth2AuthenticationToken` authorizes nothing but the OAuth
endpoints. Ending the session when the flow ends — on success and on every
rejection in `/success` — is worth doing too, but alone it leaves the window
between the callback and `/success`, and any path that forgets to end it.
Narrowing CORS's `http://localhost:[*]` and marking the session cookie
`SameSite=Strict` shrink the vector without closing it. The probe is the shape
of the regression test: the same three requests, after which the API call must
be refused.

**Status.** Open. Recorded in this re-verification, not fixed by it.

## 4. Informational notes (no change required)

- **Microsoft verified publisher** is deferred (decided 2026-06-20, no legal
  entity yet) — it blocks foreign organizational (B2B) tenants only, not the
  consumer flow. The Google project stays in Testing for V0.1.0, the closed
  beta, so its consent screen is unverified as well
  ([RELEASE_CHECKLIST.md](../backend/RELEASE_CHECKLIST.md)). Both tracked in
  todo.
- **Placeholder client-ids** (`mail-local-google-client-id`, …) are non-secret
  defaults; real values come from env, and the release packaging refuses a
  missing or placeholder value. The historic placeholder boot trap was fixed
  (a failed start now ends the JVM with exit code 1).
- **The authorized client outlives a rejected sign-in** (new in 1.3). The
  in-memory store holds the access and refresh token in clear, and
  `OAuth2CallbackController.success` removes them only after `processLogin`
  returns, so a sign-in that a guard or the persistence rejects leaves them
  until the process ends or the same user signs in again. Memory only — no
  log, response or file sees them, and an adversary who can read the JVM's
  memory is out of the threat model's scope (§1) — so a note, not a finding. A `finally` around
  `processLogin` would make the comment beside the removal true on every path.
- **A refresh-token reason on the scope guard** (new in 1.3).
  `markRequiresReauthIfExists` writes `reason=missing_refresh_token` to
  `audit.log` for both of its callers, the granted-scope guard included, and
  its javadoc names only the refresh-token case. The `oauth2_login` failure
  entry just before it names the real reason, so the trail is complete, but the
  second line misleads.
- **The e-mail fallback crosses providers** (new in 1.3).
  `findOrCreateExternalAccount` matches on `(oauth2_provider, external_id)` and
  then on the e-mail, whichever provider the matched row belongs to, and
  neither extractor reads an `email_verified` claim; from the multi-tenant
  Microsoft endpoint, `email` (or its fallback `preferred_username`) is not
  guaranteed to be verified. A sign-in to an account whose address equals
  another account's therefore re-binds that row to the new provider and
  identity and replaces its stored token. The user has to complete that
  sign-in themselves, the row keeps its server settings, so the mail server
  refuses the mismatched token, and signing in with the right account restores
  it: one account's availability, no access gained — a note.
- **A javadoc names the wrong client authentication** (new in 1.3).
  `SecurityConfig.pkceAuthorizationRequestResolver` describes Google as
  `client_secret_post`; the `google` registration uses Spring's default,
  `client_secret_basic`, which `application.properties` does not override. No
  effect on PKCE.
- **Provider-console state is outside a static trace** (new in 1.3). Redirect
  URI registrations and consent-screen status live in the Google Cloud and
  Entra consoles. The repository's comments disagree about Microsoft:
  `application.properties` says the loopback redirect is registered under
  "Mobile and desktop applications", `googleAuth.ts` says the Web platform.
  Either registers only a `localhost` URI, which is what §1 needs, but which
  one holds was not checked.

## 5. References

- [SECURITY_THREAT_MODEL.md](../SECURITY_THREAT_MODEL.md) — Boundary 2 STRIDE matrix.
- [CRYPTO_STORAGE_AUDIT.md](CRYPTO_STORAGE_AUDIT.md) — Boundary 5 (token-at-rest path).
- [API_SURFACE_AUDIT.md](API_SURFACE_AUDIT.md) — Boundary 3 (public OAuth endpoints allow-list; the default-deny B2-1 passes).
- [backend/SECURITY_RELEASE_CHECK.md](../backend/SECURITY_RELEASE_CHECK.md) — per-release security gate.

## 6. Change log

- **1.3** (2026-10-04) — **re-verified against `64633fe`**, clearing the eight
  acknowledgements recorded since 2026-08-08 (the scope guard of #273, comment
  and javadoc moves, the `requires_reauth` event of #439, the cache count of
  #518, and the cause codes and message keys of #599, #607 and #608) (#621).
  Every claim was re-derived from the code, not from those notes. **The verdict
  changes to an open finding**, B2-1 (High): the session a sign-in leaves in
  the system browser satisfies `anyRequest().authenticated()` without the API
  key — present at every anchor, confirmed by a throwaway probe through the real
  filter chain (§3). Corrected: PKCE is Spring's default for Microsoft only,
  and `SecurityConfig` forces it for Google (§1; the threat model's S row said
  "Spring Security default"); failure hygiene names everything the WARN line
  logs and how the page renders the reason; the provider-side half of the
  loopback claim was never checkable from the repository; the token cache is
  not cleared when Google's revoke fails, nor on a transient refresh failure
  (§2). Added: the method statement, the rejection path after the filter, the
  Microsoft refresh scope, the `audit.log` destination, the plaintext copy in
  Spring's store, and five informational notes. The threat model's B2 S, T and R
  rows, a new B2 row for B2-1 and B3's S row follow, as do §1 and §7 of
  [API_SURFACE_AUDIT.md](API_SURFACE_AUDIT.md) (1.12).
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
