# VoxRox Mail — IMAP/SMTP Protocol Layer Audit

|                    |                                                                                                                                                                                                                                                                                              |
| ------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Version**        | 1.3                                                                                                                                                                                                                                                                                          |
| **Date**           | 2026-08-08                                                                                                                                                                                                                                                                                   |
| **Applies to**     | VoxRox Mail V0.1.0                                                                                                                                                                                                                                                                           |
| **Audited commit** | `cad05cb` (re-verified 2026-08-08, recorded pre-squash as `3ff0c78` after 21 commits of drift, including the B1-2 fix; 1.0–1.2 baseline: `35a06f3`)                                                                                                                                          |
| **Code paths**     | `backend/src/main/java/org/voxrox/mailbackend/feature/mail/service`, `backend/src/main/java/org/voxrox/mailbackend/util/MimePartExtractor.java`, `backend/src/main/java/org/voxrox/mailbackend/util/SubjectNormalizer.java`, `backend/src/main/java/org/voxrox/mailbackend/core/config/mail` |
| **Auditor**        | Claude (Fable 5) + owner review                                                                                                                                                                                                                                                              |
| **Subsystem**      | External mail server ↔ sidecar — Boundary 1 of [SECURITY_THREAT_MODEL.md](../SECURITY_THREAT_MODEL.md)                                                                                                                                                                                       |
| **Verdict**        | **Security: PASS** — no exploitable finding. Two Medium DoS gaps found and **fixed in code**: **B1-1** (unbounded body fetch, 2026-07-10, §4) and **B1-2** (quadratic subject normalization, 2026-08-08, §4b); one Low informational note.                                                   |

Full per-subsystem audit of the path **"raw IMAP/SMTP wire → parsed → stored /
sent"**. After the mail body (Boundary 4), this is the second-largest
attacker-controlled surface in the product: a hostile, spoofed, or MITM'd mail
server — and any sender who controls a message's MIME — feeds every byte here.
Method: static trace of the connection/TLS setup, the fetch → MIME-parse →
persist pipeline, the threading header walk, the attachment download path, and
the SMTP send path; data-flow of the two riskiest inputs (message body, `From`
header). Enumeration anchor — the mail service classes:
`rg -l "class .*(Imap|Smtp|Mail|Message|Mime|Folder|Sync|Flag)" backend/src/main/java/org/voxrox/mailbackend/feature/mail/service backend/src/main/java/org/voxrox/mailbackend/util`.
Method was static-only at 1.0; since 1.2 the fetch → parse → persist path also
has a dynamic hostile-content harness (`MailContentGreenMailIT`, see §4) —
transport/TLS and SMTP-send claims remain static-plus-unit-tests, see
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
  **spoofable** `From` **by design**, affecting _image loading only_, never
  trust or code execution. Traced end to end:
  [RemoteImageAllowlistService](../backend/src/main/java/org/voxrox/mailbackend/feature/mail/service/RemoteImageAllowlistService.java)
  normalizes (trim+lowercase) and uses parameterized repository queries; a spoofed
  `From` matching an allow-list entry can, at worst, cause that message's remote
  `https` images to auto-load — the exact convenience trade-off documented for F2.
- **Subject now participates in threading — bounded, marker-gated.** Versions
  1.0–1.2 recorded that subject clustering was deliberately skipped. That is no
  longer true: #221 added a subject fallback
  ([ThreadingService.resolveSubjectFallbackParent](../backend/src/main/java/org/voxrox/mailbackend/feature/mail/service/ThreadingService.java)),
  so an attacker-chosen header can now influence which conversation a message
  joins. Three guards keep the exposure narrow and were verified: it applies
  **only** when the message carries neither `In-Reply-To` nor `References`,
  **only** when the subject starts with an explicit reply/forward marker, and
  **only** within a ±30-day window scoped to one account. Worst case is
  cosmetic: a spoofed message with a guessed subject appears inside an existing
  thread while still showing its own `From`. No trust, capability or content
  decision keys off thread membership — the same shape as the F2 allow-list
  trade-off in §3 above. The normalization that feeds it is the subject of
  finding **B1-2** below.
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

**Severity: Medium** (per the rubric: _DoS recoverable by restart_), tempered by
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

**Dynamic verification (added 2026-07-10).** `MailContentGreenMailIT` exercises
the fix over a live in-process IMAP server through the full production client
stack (Angus partial fetch included): an 8 MiB+ body delivered to GreenMail
opens as the localized placeholder with the flag persisted and — proven by
deleting the message server-side between opens — is never re-fetched; the
order-agnostic alternative fallback, the `multipart/related` alternative
selection and both charset paths (declared ISO-8859-2, unknown-charset UTF-8
fallback) are verified over the wire as well. Two GreenMail 2.1.9 fidelity
bugs found while building the harness bound its coverage (documented in the
test's javadoc with raw-protocol evidence): `BODY[TEXT]` of a single-part
message serves an empty literal, and partial-fetch responses omit the RFC 3501
origin-octet marker — so the single-part body shape and wire-level cid
inlining stay covered at the unit level (`MimePartExtractorTest`).

## 4b. Finding B1-2 (Medium) — quadratic subject normalization — **FIXED**

**What.** `SubjectNormalizer.stripMarkers`
([SubjectNormalizer](../backend/src/main/java/org/voxrox/mailbackend/util/SubjectNormalizer.java))
stripped each leading `Re:` / `Odp:` / `Fwd:` marker by re-slicing the string,
so a chain of _n_ markers cost O(n²) in copying. Its javadoc argued a cap was
unnecessary because the loop is finite by construction — correct about
termination, silent about cost. Nothing upstream bounds the input:
`MessageFetcher` takes `message.getSubject()` verbatim, `MessageMapper` stores
it as-is, and SQLite does not enforce the `VARCHAR(500)` the schema declares.
`ThreadingService.assignThread` then normalizes **every message during sync**.

**Impact.** A hostile or compromised mail server — the Boundary 1 adversary
this audit is scoped to — sets a Subject of stacked markers and burns sidecar
CPU on the sync executor. Measured against the packaged `-Xmx384m` heap, before
the fix:

| Subject size | Time to normalize one message |
| ------------ | ----------------------------- |
| 512 KiB      | 1.7 s                         |
| 1 MiB        | 7.2 s                         |
| 2 MiB        | 30 s                          |
| 4 MiB        | 108 s                         |

Doubling the input quadruples the cost, as the shape predicts.

**Severity: Medium** (_DoS recoverable by restart_, the same rubric line as
B1-1). Its precondition is **weaker** than B1-1's in one respect that matters:
B1-1 needed the user to open the poisoned message, whereas this runs during
automatic background sync, so a single hostile message degrades the client with
no user interaction. It is bounded the other way, though — the work is CPU, not
retained heap, it is confined to the sync executor (user actions run on
`userMailExecutor`), and it ends when the pass ends.

**Fix (shipped 2026-08-08).** Two independent changes, either of which alone
would close it: `stripMarkers` advances a region offset instead of re-slicing,
making the strip linear at any length; and `normalize` truncates its input to
`MAX_NORMALIZED_LENGTH = 1000` characters before stripping, which bounds the
work outright. 1000 is twice the column's declared intent and far above any
legitimate subject, and it affects only the grouping key — the stored subject
is untouched, so nothing the user sees changes. After the fix the 4 MiB case
normalizes in under a millisecond.

**Regression tests.** `SubjectNormalizerTest.pathologicalSubjectIsBounded`
(4 MiB marker chain under a 1 s budget) and `capLeavesRealisticSubjectsUntouched`.
Both were run against the reverted fix and both fail there — the pathological
one after 101.8 s — so the budget is empirically load-bearing, not decorative.

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

- **1.3** (2026-08-08) — re-verified against `3ff0c78` after `check:audits`
  reported 21 commits of drift since `35a06f3` (threading phase 2, draft
  lifecycle, send-path review, service splits). Every §1–§5 claim was
  re-checked against the current code and all still hold: pinned
  `checkserveridentity` on all three connectors, the fail-closed OAuth2/TLS
  guards, millisecond-string timeouts, the metadata-only `FetchProfile`,
  `MAX_DEPTH = 20`, the inline-image and `MAX_BODY_BYTES` caps, the pinned
  `partialfetch`, `MAX_REFERENCES_WALK = 50`, the UIDVALIDITY reset and the
  `Files.copy` attachment stream. Two changes to the boundary were found that
  the previous revisions did not describe: the **subject fallback** (#221) now
  lets an attacker-chosen header influence thread membership — recorded in §3
  with its three guards and a cosmetic worst case — and its normalizer carried
  a quadratic strip over an unbounded attacker-controlled input, recorded and
  fixed as **B1-2** (§4b). Verdict unchanged (**PASS**). Also noted, no action:
  #145 added CR/LF rejection to `MimeMessageBuilder`, hardening the send path
  beyond what 1.0 described, and `MessageDownloader.reconcileServerOnlyUids`
  (#204) fetches server-only UIDs in `batchSize` windows bounded by the locally
  mirrored UID range, not by anything the server chooses. The `Code paths` row
  gained `SubjectNormalizer.java`: it parses an attacker-controlled header on
  the sync path, so it was always in this boundary, but the pathspec written
  earlier the same day omitted it — the B1-2 fix landed without the gate
  noticing, which is the "too narrow a scope claim" failure
  [AUDIT_GUIDE.md](AUDIT_GUIDE.md) §2 warns the row can have.
- **1.2** (2026-07-10) — dynamic hostile-content harness added
  (`MailContentGreenMailIT`): the §4 fix and the fetch→parse pipeline claims
  are now exercised over a live IMAP server through the production client
  stack, closing part of the static-only limitation recorded at 1.0. GreenMail
  fidelity limits (empty `BODY[TEXT]` for single-part messages; partial-fetch
  response without the origin-octet marker) keep the single-part shape and
  wire-level cid inlining unit-covered only.
- **1.1** (2026-07-10) — finding B1-1 **fixed**: body reads bounded at 8 MiB
  via the inline-image `readBounded` pattern, oversized bodies replaced by a
  localized placeholder behind the persisted `messages.body_oversize` flag
  (no re-fetch, no FTS pollution), `multipart/alternative` falls back to a
  fitting plain-text part. Accepted residual AR-3 removed from the threat
  model. §4 records the fix; the informational note in §5 is unchanged.
- **1.0** (2026-07-09) — initial full audit; all Boundary 1 STRIDE mitigations
  verified against `35a06f3`. Finding B1-1 (unbounded body fetch) recorded as
  an accepted residual with a recommended fix.
