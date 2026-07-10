# VoxRox Mail — IMAP/SMTP Protocol Layer Audit

| | |
|---|---|
| **Version** | 1.0 |
| **Date** | 2026-07-09 |
| **Applies to** | VoxRox Mail V0.1.0 |
| **Audited commit** | `35a06f3` |
| **Auditor** | Claude (Fable 5) + owner review |
| **Subsystem** | External mail server ↔ sidecar — Boundary 1 of [SECURITY_THREAT_MODEL.md](../SECURITY_THREAT_MODEL.md) |
| **Verdict** | **Security: PASS** — no exploitable finding. One Medium DoS gap (**B1-1**, unbounded body fetch) documented as an accepted residual at audit time and **fixed in code on 2026-07-10** (§4); one Low informational note. |

Full per-subsystem audit of the path **"raw IMAP/SMTP wire → parsed → stored /
sent"**. After the mail body (Boundary 4), this is the second-largest
attacker-controlled surface in the product: a hostile, spoofed, or MITM'd mail
server — and any sender who controls a message's MIME — feeds every byte here.
Method: static trace of the connection/TLS setup, the fetch → MIME-parse →
persist pipeline, the threading header walk, the attachment download path, and
the SMTP send path; data-flow of the two riskiest inputs (message body, `From`
header). Enumeration anchor — the mail service classes:
`rg -l "class .*(Imap|Smtp|Mail|Message|Mime|Folder|Sync|Flag)" backend/src/main/java/org/voxrox/mailbackend/feature/mail/service backend/src/main/java/org/voxrox/mailbackend/util`.
Method is static-only (no live hostile-server harness) — see
[AUDIT_GUIDE.md](AUDIT_GUIDE.md).

## 1. Transport & authentication (confirmed)

- **Hostname verification always on.** `mail.<proto>.ssl.checkserveridentity=true`
  is set explicitly on the IMAP store
  ([ImapConnectionManager](../backend/src/main/java/org/voxrox/mailbackend/feature/mail/service/ImapConnectionManager.java)),
  the SMTP transport
  ([SmtpTransportFactory](../backend/src/main/java/org/voxrox/mailbackend/feature/mail/service/SmtpTransportFactory.java))
  and the credential probe
  ([MailConnectionProbe](../backend/src/main/java/org/voxrox/mailbackend/feature/mail/service/MailConnectionProbe.java)) —
  not left to the Angus Mail default, so a spoofed-server (B1-S) TLS handshake
  is rejected on identity mismatch.
- **OAuth2 token never travels in cleartext — fail-closed on BOTH protocols.**
  The XOAUTH2 SASL payload carries the bearer token, so a non-TLS socket would
  leak it. IMAP fails fast with a **CRITICAL** audit event
  (`imap_oauth2_plaintext_blocked`) if an OAuth2 account is configured without
  SSL (`ImapConnectionManager` §createNewConnectedStore); SMTP enforces the
  equivalent via `requireSslForOAuth2` (implicit SSL **or** mandatory STARTTLS)
  before every open. This closes B1-I (STARTTLS-strip) for the token.
- **STARTTLS is required, not opportunistic.** `mail.smtp.starttls.required=true`
  is always set, so a stripped/absent upgrade fails instead of silently
  sending cleartext.
- **Timeouts are real and effective.** IMAP connect 30 s / read 60 s, SMTP
  connect 30 s — passed as `String.valueOf(duration.toMillis())` (a raw
  `Duration` is silently ignored by JavaMail's `PropUtil`; the code handles
  this correctly). Closes B1-D (slow-server hang), the regression that
  Phase 6.15 fixed.
- **Retry policy is scoped.** Connect is wrapped in a `RetryTemplate` that
  retries only transient network errors; `AuthenticationFailedException`
  short-circuits to the token-refresh path (no pointless backoff on a bad
  token).

## 2. Fetch → parse → persist pipeline (confirmed, except §4)

- **List sync fetches metadata only.**
  [MessageFetcher.fetchBatch](../backend/src/main/java/org/voxrox/mailbackend/feature/mail/service/MessageFetcher.java)
  uses a `FetchProfile` of ENVELOPE + UID + FLAGS + CONTENT_INFO + the three
  threading headers — **not** the body. Bodies are fetched lazily on message
  open (§4), so a sync over a large mailbox never buffers bodies.
- **Malformed structure fails soft, per message.** A bad `BODYSTRUCTURE`
  (observed from Seznam) is caught — including `RuntimeException` — and the
  message is persisted as an **envelope-only stub** (`contentError` recorded);
  the detail endpoint retries later. One bad message cannot break the list
  page.
- **MIME parsing is depth-bounded.** Every recursive walk
  ([MimePartExtractor](../backend/src/main/java/org/voxrox/mailbackend/util/MimePartExtractor.java):
  body, inline images, attachment metadata, has-attachments) is capped at
  `MAX_DEPTH = 20`; a `Content-Type: multipart/*` whose content is not actually
  a `Multipart` degrades to empty instead of throwing a `ClassCastException`.
- **Inline images are strictly bounded.** Only `cid:`-referenced, raster
  subtypes are read, each via `readBounded` (reads `cap+1` bytes to detect and
  skip an oversize part without buffering it whole), with a 2 MiB per-image and
  8 MiB per-message cap — so a hostile `multipart/related` cannot bloat the
  SQLite `content` column or the heap through inline images.
- **Attachment metadata is safe.** Filenames are RFC 2047-decoded for display;
  content-type is reduced to the media type before the first `;`; a negative
  `getSize()` is clamped to 0.

## 3. Header handling & threading (confirmed)

- **`From` is display-formatted, never trusted.** `formatAddress` decodes the
  personal part and builds a `"Personal <email>"` label. Headers flow into the
  DB and FTS5 index as **data**; they never reach the mail-body iframe (that is
  Boundary 4, which renders in an opaque-origin sandbox). The one security-load
  path from `From` is the remote-image allow-list — and it is keyed on the
  **spoofable** `From` **by design**, affecting *image loading only*, never
  trust or code execution. Traced end to end:
  [RemoteImageAllowlistService](../backend/src/main/java/org/voxrox/mailbackend/feature/mail/service/RemoteImageAllowlistService.java)
  normalizes (trim+lowercase) and uses parameterized repository queries; a spoofed
  `From` matching an allow-list entry can, at worst, cause that message's remote
  `https` images to auto-load — the exact convenience trade-off documented for F2.
- **References walk is bounded.** The JWZ-light threading algorithm caps the
  `References` chain walk at `MAX_REFERENCES_WALK = 50`
  ([ThreadingService](../backend/src/main/java/org/voxrox/mailbackend/feature/mail/service/ThreadingService.java)),
  an explicit defense against a malicious/oversized `References` header (the
  published algorithm has no bound).
- **UIDVALIDITY is cross-checked.** A change in the server's UIDVALIDITY is
  detected ([FlagSyncService](../backend/src/main/java/org/voxrox/mailbackend/feature/mail/service/FlagSyncService.java))
  and triggers a state reset (`resetForUidValidityChange`), so a server that
  renumbers its mailbox (or an active-MITM UID desync, B1-T) cannot silently
  map local rows onto different server messages.

## 4. Finding B1-1 (Medium) — unbounded message-body fetch — **FIXED**

**What.** [MailContentService.getOrFetchMessageContent](../backend/src/main/java/org/voxrox/mailbackend/feature/mail/service/MailContentService.java)
→ `MimePartExtractor.extractBody` reads the selected `text/plain` / `text/html`
part with `part.getContent().toString()` — **no size bound**. The full body is
buffered into a `String` on the heap, then handed to Jsoup (another full-size
parse), then stored in the SQLite `content` column (unbounded `TEXT`). Inline
images are bounded (§2) but the body itself is not. A hostile or compromised
mail server (a Boundary 1 adversary) can set any message to carry a
multi-hundred-MB body; when the user opens that message, the sidecar buffers it
twice and can exhaust the heap (packaged `-Xmx384m`).

**Severity: Medium** (per the rubric: *DoS recoverable by restart*), tempered by
strong preconditions: it requires (a) a hostile/compromised/MITM'd mail server —
already a semi-trusted party with larger levers over your own mailbox, and (b)
the user to open the specific oversized message (bodies are lazy-fetched, not
pulled during sync). An OOM crashes the sidecar; the parent-process watchdog
relaunches it, and the poisoned body is never persisted (the OOM happens before
`updateLocalCache`), so it is not a permanent denial — reopening simply fails
again until the message is deleted server-side. No data exposure, no integrity
loss, no code execution.

**Recommendation (not shipped in V0.1.0).** Bound the body read the same way
inline images are bounded: read the body part through `getInputStream()` with a
cap (e.g. 5–10 MiB decoded), and when a part exceeds it, store a
"message too large to display — download the original" placeholder instead of
the full body (the mature-client behaviour — Gmail's "message truncated"). A
raw byte cap is a low-risk change; the truncate-and-offer-original UX is the
larger follow-up. Deliberately **not** implemented autonomously because a naïve
truncation mangles legitimate large newsletters and needs a product decision on
the fallback UX.

**Residual (accepted for V0.1.0 — since closed by the fix below).** The gap is
a self-inflicted local DoS by a server the user has chosen to connect to,
recoverable by restart, requiring user interaction, and leaving no persistent
damage. Accepted for the initial release with the fix tracked as the upgrade
path.

**Fix (shipped 2026-07-10).** `MimePartExtractor` now reads every selected
`text/plain` / `text/html` body part through the same bounded stream as inline
images (`readBounded` over `getInputStream()`), capped at
`MAX_BODY_BYTES = 8 MiB` of transfer-decoded bytes. The streaming guarantee the
cap depends on — Angus IMAP serving the part in 16 KiB partial fetches — is now
pinned explicitly (`mail.<proto>.partialfetch=true` in
`ImapConnectionManager`), matching how `checkserveridentity` is pinned rather
than trusted to the library default. Charset decoding happens after the cap; an
unknown or malformed charset degrades to a UTF-8 decode with replacement
characters instead of failing the message. A part over the cap yields
`ExtractedBody.OVERSIZE`; `multipart/alternative` selection is order-agnostic —
an oversized rich part falls back to any plain-text sibling that fits, whether
or not the sender emitted RFC 2046 plain-first order (a nested
`multipart/related` alternative, the Apple Mail HTML+inline-images layout, now
also renders instead of being skipped). On an oversized body,
`MailContentService` persists the new `messages.body_oversize` flag —
best-effort, and `content` stays NULL so the FTS index never sees placeholder
text — and serves a localized "message too large" placeholder
(`mail.message.bodyTooLarge`) through the standard plain-text wrapper.
Subsequent opens short-circuit on the flag, so the oversized body costs at most
one bounded fetch. Reply/forward drafts quote an oversized original as an empty
body (`getOrFetchQuotableContent`), never as the placeholder. Covered by
`MimePartExtractorTest` (cap, charset decode, order-agnostic alternative
fallback, related-alternative rendering) and `MailContentServiceTest` (flag
persistence incl. persist-failure resilience, placeholder, quotable-empty, IMAP
short-circuit). The truncate-and-offer-original UX remains a possible future
enhancement; the placeholder points the user at their mail provider for the
full message.

## 5. Informational note (no change required)

- **Attachment download is disk-bounded, not memory-bounded.**
  [AttachmentService.downloadToTempFile](../backend/src/main/java/org/voxrox/mailbackend/feature/mail/service/AttachmentService.java)
  streams the part to a private temp file via `Files.copy` (constant heap), with
  an empty-download integrity check and a stale-temp sweep on boot. A hostile
  server serving a huge attachment could fill the disk, but the copy never
  buffers the attachment in memory, the file lands in the user's own temp dir,
  and it is unlinked on stream close. Lower impact than B1-1; the same
  read-cap-and-reject approach would close it if ever desired.

## 6. References

- [SECURITY_THREAT_MODEL.md](../SECURITY_THREAT_MODEL.md) — Boundary 1 STRIDE matrix.
- [CONTENT_RENDERING_AUDIT.md](CONTENT_RENDERING_AUDIT.md) — Boundary 4, what the parsed body feeds into.
- [AUDIT_GUIDE.md](AUDIT_GUIDE.md) — audit method + boundary map.
- [backend/SECURITY_RELEASE_CHECK.md](../backend/SECURITY_RELEASE_CHECK.md) — per-release security gate.

## 7. Change log

- **1.1** (2026-07-10) — finding B1-1 **fixed**: body reads bounded at 8 MiB
  via the inline-image `readBounded` pattern, oversized bodies replaced by a
  localized placeholder behind the persisted `messages.body_oversize` flag
  (no re-fetch, no FTS pollution), `multipart/alternative` falls back to a
  fitting plain-text part. Accepted residual AR-3 removed from the threat
  model. §4 records the fix; the informational note in §5 is unchanged.
- **1.0** (2026-07-09) — initial full audit; all Boundary 1 STRIDE mitigations
  verified against `35a06f3`. Finding B1-1 (unbounded body fetch) recorded as
  an accepted residual with a recommended fix.
