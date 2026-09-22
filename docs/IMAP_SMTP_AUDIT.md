# VoxRox Mail — IMAP/SMTP Protocol Layer Audit

|                    |                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| ------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Version**        | 1.12                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| **Date**           | 2026-09-22                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| **Applies to**     | VoxRox Mail V0.1.0                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| **Audited commit** | `6224cbb` (re-verified 2026-09-22, clearing six acknowledgements; 1.7–1.8 anchor `9435e56`, re-verified 2026-09-16; 1.6 anchor `02ff962`, recorded pre-squash as `f5b75ad`; 1.5 anchor `885b98a`, re-verified 2026-09-02 at the ledger cap; 1.3–1.4 anchor `cad05cb`, recorded pre-squash as `3ff0c78`; 1.0–1.2 baseline: `35a06f3`)                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| **Code paths**     | `backend/src/main/java/org/voxrox/mailbackend/feature/mail/service`, `backend/src/main/java/org/voxrox/mailbackend/util/MimePartExtractor.java`, `backend/src/main/java/org/voxrox/mailbackend/util/SubjectNormalizer.java`, `backend/src/main/java/org/voxrox/mailbackend/core/config/mail`, `backend/src/main/java/org/voxrox/mailbackend/core/config/RetryConfig.java`, `backend/src/main/resources/application.properties`, `backend/src/main/java/org/voxrox/mailbackend/feature/mail/repository/MessageRepository.java`, `backend/src/main/java/org/voxrox/mailbackend/feature/mail/entity/MessageEntity.java`, `backend/src/main/java/org/voxrox/mailbackend/feature/mail/entity/FolderSyncStateEntity.java`, `backend/src/main/java/org/voxrox/mailbackend/feature/mail/mapper/MessageMapper.java` |
| **Auditor**        | Claude (Fable 5; 1.9 re-verified by Claude Opus 5) + owner review                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| **Subsystem**      | External mail server ↔ sidecar — Boundary 1 of [SECURITY_THREAT_MODEL.md](../SECURITY_THREAT_MODEL.md)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| **Verdict**        | **Security: open findings** — three open: **B1-5** (Medium, sending an untouched draft trusts and buffers the server's copy, §4e), **B1-7** (Medium, a declared literal size is allocated before a byte arrives, found at 1.11, §4g), **B1-6** (Low, no IMAP write timeout, §4f). Fixed in code: **B1-3** (Medium, a server-stated size exhausted the heap before our code ran, 2026-09-22, §4c), **B1-4** (High, IMAP password login without TLS when SSL was off, 2026-09-22, §4d), **B1-1** (Medium, unbounded body fetch, 2026-07-10, §4) and **B1-2** (Medium, quadratic subject normalization, 2026-08-08, §4b). Informational notes in §5.                                                                                                                                                          |

Full per-subsystem audit of the path **"raw IMAP/SMTP wire → parsed → stored /
sent"**. After the mail body (Boundary 4), this is the second-largest
attacker-controlled surface in the product: a hostile, spoofed, or MITM'd mail
server — and any sender who controls a message's MIME — feeds every byte here.
Method: static trace of the connection/TLS setup, the fetch → MIME-parse →
persist pipeline, the threading header walk, the attachment download path, and
the SMTP send path; data-flow of the two riskiest inputs (message body, `From`
header). Enumeration anchor — the mail service classes:
`rg -l "class .*(Imap|Smtp|Mail|Message|Mime|Folder|Sync|Flag)" backend/src/main/java/org/voxrox/mailbackend/feature/mail/service backend/src/main/java/org/voxrox/mailbackend/util`,
plus the classes that pattern misses and the audit covers: `ThreadingService`,
`SubjectNormalizer`, `RemoteImageAllowlistService`, `MarkdownBodyRenderer`,
`AttachmentService` and `DraftPersistenceService` (listed at 1.9, after the
verification pass found the pattern alone does not reach them).
Method was static-only at 1.0; since 1.2 the fetch → parse → persist path also
has a dynamic hostile-content harness (`MailContentGreenMailIT`, see §4) —
transport/TLS and SMTP-send claims remain static-plus-unit-tests, see
[AUDIT_GUIDE.md](AUDIT_GUIDE.md). For the IMAP store that was thinner still until
1.10: its `checkserveridentity`, `partialfetch` and timeout pins had no unit
test (the probe's and the SMTP factory's did). Since the B1-4 fix its TLS
settings are unit-tested on both paths, and every GreenMail IT connects over
IMAPS with a certificate naming `127.0.0.1` (`TestTls`), so the accepting half
of the identity pin now runs on every sync IT; the rejecting half — a
certificate for another name — has no dynamic test. Three narrower claims have gained dynamic cover since 1.7, and cover
only what they exercise: `SyncConnectionFaultGreenMailIT` shows a network that
goes quiet failing the sync pass rather than hanging it (asserted within 60 s,
with the read timeout set to 3 s; over IMAPS since 1.10), `OAuthTokenExpiryGreenMailIT`
a rejected access token refreshed once and a revoked refresh token turned into
a sign-in request (§1, retry scoping), and `MailSyncQresyncDovecotIT` a
deletion and a flag change learnt from the QRESYNC SELECT against Dovecot 2.4.5
(§2). The last needs Docker and is skipped without it; CI always has Docker.
Since 1.11 `HostileImapResponseIT` syncs from an IMAP server written for the
test, which answers a folder open with sizes no real server sends (§4c).
**Verification at 1.9** was done twice: by the author of the version, and
then, as [AUDIT_GUIDE.md](AUDIT_GUIDE.md) §5 requires, by a separate agent
without the author's context, which re-checked every claim against the tree
and disassembled the Angus 2.0.5 jar where a claim rested on library
behaviour. The second pass found the four open findings and most of the
corrections listed in the 1.9 change-log entry.

## 1. Transport & authentication (confirmed, except B1-4 and B1-6)

- **Hostname verification on every TLS connection.** `mail.<proto>.ssl.checkserveridentity=true`
  is set explicitly on the IMAP store
  ([ImapConnectionManager](../backend/src/main/java/org/voxrox/mailbackend/feature/mail/service/ImapConnectionManager.java)),
  the SMTP transport
  ([SmtpTransportFactory](../backend/src/main/java/org/voxrox/mailbackend/feature/mail/service/SmtpTransportFactory.java))
  and the credential probe
  ([MailConnectionProbe](../backend/src/main/java/org/voxrox/mailbackend/feature/mail/service/MailConnectionProbe.java)) —
  not left to the Angus Mail default, so a spoofed-server (B1-S) TLS handshake
  is rejected on identity mismatch. The pin only means something when there is
  a handshake, and until 1.10 an IMAP account whose SSL setting was off got
  the plain `imap` protocol with no STARTTLS at all — finding **B1-4** (§4d),
  fixed: every IMAP connection is now TLS, implicit or required STARTTLS, set
  for the pool and the probe alike in `ImapTransportSecurity`, so the pin
  applies to every one. 1.0–1.8 headed this bullet "always on".
- **OAuth2 token never travels in cleartext — fail-closed on BOTH protocols.**
  The XOAUTH2 SASL payload carries the bearer token, so a non-TLS socket would
  leak it. IMAP fails fast with a **CRITICAL** audit event
  (`imap_oauth2_plaintext_blocked`) if an OAuth2 account is configured without
  SSL (`ImapConnectionManager` §createNewConnectedStore); SMTP enforces the
  equivalent via `requireSslForOAuth2` (implicit SSL **or** mandatory STARTTLS)
  before every open. This closes B1-I (STARTTLS-strip) for the token.
- **STARTTLS is required, not opportunistic — on both protocols since 1.10.** For IMAP,
  `ImapTransportSecurity` sets `mail.imap.starttls.enable` and
  `.required` whenever implicit SSL is off (B1-4). For SMTP, `mail.smtp.starttls.required=true`
  is set on every session that is not implicit SSL — the two are alternatives,
  and `requireSslForOAuth2` accepts either — so a stripped/absent upgrade fails
  instead of silently sending cleartext. (1.0–1.4 wrote "always set", which
  reads as unconditional; the property is set in the `else` branch of the
  implicit-SSL test in `SmtpTransportFactory.createSession`, and always has
  been. Wording tightened at 1.5, no behaviour involved.)
- **Timeouts are real and effective.** IMAP connect 30 s / read 60 s, SMTP
  connect 30 s — passed as `String.valueOf(duration.toMillis())` (a raw
  `Duration` is silently ignored by JavaMail's `PropUtil`; the code handles
  this correctly). Closes B1-D (slow-server hang), the regression that
  Phase 6.15 fixed — for reads; IMAP sets no write timeout, finding **B1-6**
  (§4f). Where the numbers live matters and is worth stating: the
  IMAP pair and the SMTP connect timeout come from `application.properties`
  (`mail.client.imap.connection-timeout` / `.read-timeout`,
  `mail.client.smtp.connection-timeout`), **not** from the `@DefaultValue`
  on the binding record, which for SMTP is a shorter 15 s. Nothing is
  unbounded either way — the record default is the fallback if the key ever
  disappears — but a reader checking the claim against `SmtpProperties`
  alone would find a different number than the app runs with. The SMTP
  **read** timeout (`mail.smtp.timeout`) is set too, and it is the one value
  that does come from the record: `application.properties` has no
  `mail.client.smtp.read-timeout` key, so it runs at the `@DefaultValue` of
  10 s, which also sets `mail.smtp.writetimeout`. Earlier versions named only
  the SMTP connect timeout (added at 1.9).
- **Two connections per account, one connection setup.** Rewritten at 1.6:
  since the interactive-lane split, `ImapConnectionManager` keys its pool and
  its locks by `(accountId, Lane)`, so an account holds two Stores —
  `INTERACTIVE` for work a user waits on, `BACKGROUND` for sync cycles and the
  server-side half of already-committed local writes. **Both are built by the
  same `createNewConnectedStore`**, which is untouched by the split, so every
  claim above applies per connection rather than per account: the pinned
  `checkserveridentity`, the fail-closed `imap_oauth2_plaintext_blocked` guard,
  the millisecond-string timeouts and the pinned `partialfetch` are all on the
  one code path both lanes call. (1.6–1.8 said "up to two TLS sockets"; a
  Store is not one socket — Angus opens further protocol connections on it
  lazily, for example when a second folder is opened on the same Store — and,
  since the B1-4 fix, every one of them is TLS.) Verified in the tree rather than inferred —
  the split changed the keys of two maps and added a lane parameter; it moved
  no property, no credential and no protocol command. What the second socket
  does change is a resource question, not a trust one: an account now opens two
  sessions where providers cap the total, which the code treats as expected
  (see the degradation note below) rather than as an error.
- **Connection contention is bounded, not queued indefinitely.** New since 1.4,
  narrowed at 1.6: `ImapConnectionManager.executeWithLockOrSkip` takes the
  lane's store lock with a timeout and returns empty instead of waiting, and
  `ImapFolderService` uses it for folder-role lookups under
  `mail.client.imap.role-lookup-timeout` (1 s). Until 1.5 that timeout was what
  kept a read from blocking behind a whole sync folder cycle; with separate
  lanes the sync is no longer in front of it, and what the timeout now bounds
  is contention with another interactive request. The direction is unchanged
  and still fail-closed for this boundary: it removes an unbounded wait on a
  server-paced operation and adds no IMAP command. An unresolvable role is
  still indistinguishable from a busy one at the call site, which is a
  correctness consideration, not a security one — no trust decision keys off a
  folder role.
- **A refused second connection degrades, it does not retry blindly.** When an
  interactive connect fails, the action re-runs on the background lane — after
  the interactive lock is released, never while it is held — and the lane is
  then skipped for `mail.client.imap.interactive-lane-retry-after` (5 m). Two
  properties of that path matter here. It cannot turn a rejected connection
  into a connection storm against the provider, which an unbacked-off retry on
  a session cap would: one attempt per account per cooldown window, counted by
  `mail.imap.lane.fallback`, and the cooldown is consulted before the lane's
  lock is taken so a degraded account cannot queue on it either. And it
  deliberately does **not** trigger on `AuthenticationFailedException` —
  credentials belong to the account, so the other lane would fail identically,
  and routing an auth failure around the existing single-shot token refresh
  would weaken §1's retry scoping. The **reconnect** inside that refresh goes
  through the same degradation, which is not a weakening of it: a second auth
  failure still classifies as persistent, but the reconnect can independently
  hit the session cap in the moment after the old connection was closed, and
  treating that as an error would have left the one case degradation exists for
  uncovered. Degraded behaviour is the pre-1.6 behaviour: one connection, one
  queue.
- **Retry policy is scoped.** Connect is wrapped in a `RetryTemplate`
  ([RetryConfig](../backend/src/main/java/org/voxrox/mailbackend/core/config/RetryConfig.java))
  whose policy names what it retries: `SocketTimeoutException`,
  `ConnectException`, `SSLException` and `IOException`, matched through the
  cause chain, for `mail.client.retry.max-attempts` (3) attempts in all with jittered
  exponential backoff. `AuthenticationFailedException` is listed as
  **not** retryable, so it short-circuits to the token-refresh path (no
  pointless backoff on a bad token). Stated this precisely at 1.9 because
  1.0–1.8 wrote "retries only transient network errors", and `SSLException`
  is wider than that: a certificate or hostname rejection is retried too.
  That costs time, not safety — every attempt runs the same handshake with
  the same `checkserveridentity`, so each one fails the same way and the
  connect still fails closed. `RetryConfig` joined `Code paths` at 1.9 for
  the same reason: this claim rests on it, and the freshness check could not
  see it change. There is a **second retry layer** above this one, which 1.0–1.8
  did not mention: `MailSyncService.runFolderCycle` re-runs a whole folder
  cycle after an SSL or I/O error (`TransientMailErrors`), up to the same
  `max-attempts`. It fails closed the same way — each run repeats the same
  handshake — and adds cycles, not trust.

## 2. Fetch → parse → persist pipeline (confirmed, except B1-3)

- **List sync fetches metadata only.**
  [MessageFetcher.fetchBatch](../backend/src/main/java/org/voxrox/mailbackend/feature/mail/service/MessageFetcher.java)
  uses a `FetchProfile` of ENVELOPE + UID + FLAGS + CONTENT_INFO + the three
  threading headers — **not** the body. Bodies are fetched lazily on message
  open (§4), so a sync over a large mailbox never buffers bodies.
- **Malformed structure fails soft, per message.** A bad `BODYSTRUCTURE`
  (observed from Seznam) is caught — including `RuntimeException` — and the
  message is persisted as an **envelope-only stub** (no body, no attachments);
  opening it retries the body fetch through the content endpoint, which reports
  its own failure. One bad message cannot break the list page. The
  `RuntimeException` catch is the MIME walk's; the per-message catch around it
  takes only `MessagingException` and `IOException` (skip and continue), and a
  failed batch `folder.fetch` drops the whole batch (precision added at 1.9).
- **MIME parsing is depth-bounded.** Every recursive walk
  ([MimePartExtractor](../backend/src/main/java/org/voxrox/mailbackend/util/MimePartExtractor.java):
  body, inline images, attachment metadata, has-attachments) is capped at
  `MAX_DEPTH = 20`; a `Content-Type: multipart/*` whose content is not actually
  a `Multipart` degrades to empty instead of throwing a `ClassCastException`.
  One MIME walk outside the extractor is **not** under that bound:
  `AttachmentService.findPartByPath`, whose `message/rfc822` unwrap consumes no
  path segment (§5).
- **Inline images are strictly bounded.** Only `cid:`-referenced, raster
  subtypes are read, each via `readBounded` (reads `cap+1` bytes to detect and
  skip an oversize part without buffering it whole), with a 2 MiB per-image and
  8 MiB per-message cap — so a hostile `multipart/related` cannot bloat the
  SQLite `content` column or the heap through inline images.
- **A QRESYNC SELECT lets the server name rows to delete, within one folder**
  (v1.7). Opening with `ResyncData` makes the server report what changed since
  the recorded modseq, and
  [FlagSyncService.applyResyncEvents](../backend/src/main/java/org/voxrox/mailbackend/feature/mail/service/FlagSyncService.java)
  turns a `MessageVanishedEvent` into deletions of the local cache rows. The
  UIDs are server-supplied, so the scope is what matters: the delete is
  `deleteAllByAccountIdAndFolderNameAndUidIn`, bound to the account whose
  connection this is and the folder the SELECT was for, in batches of the
  configured sync size. A hostile server can therefore make the client forget
  its cached copies of that folder — which is the level of control it already
  has over a mailbox it serves, and no worse than deleting the messages itself.
  Nothing outside that (account, folder) can be reached, and no body, header or
  credential is read from the event: it carries UIDs and flags. What this bullet
  did not weigh until 1.9 is the cost of materializing those UIDs: Angus
  expands the server's range before our code sees the event, and a single
  short range exhausts the heap — finding **B1-3** (§4c), fixed at 1.11 by a
  check on every response ahead of Angus, with the delete now streaming the
  UIDs into batches, clamped to the folder's local UID range.
- **A folder without a QRESYNC baseline opens with CONDSTORE, and the server
  picks the modseq it is asked from next time** (carried up at 1.9 from the
  ledger). `ImapFolderExecutor.executeReadOnlyResynced` opens QRESYNC when
  there is a stored baseline, a known UIDVALIDITY and a local UID range and
  the server advertises QRESYNC, else `IMAPFolder.open(mode, ResyncData.CONDSTORE)`
  where the server advertises both CONDSTORE and ENABLE, else plainly — each
  failure degrading to the next. On the wire that is `ENABLE CONDSTORE` plus an
  `EXAMINE` with the CONDSTORE parameter (`ENABLE` only if that connection has
  not enabled it yet); the answer adds `HIGHESTMODSEQ` and
  a `MODSEQ` item on later FETCH responses, both parsed by Angus.
  `FlagSyncService` stores the server's `HIGHESTMODSEQ` as the next baseline
  and neither stores nor sends a non-positive one. A hostile server therefore
  chooses the number it will be asked to resynchronize from — less than the
  bullet above already grants it, since it could simply report vanished UIDs.
  The (account, folder) scope of the delete is the same for every open mode.
  Its batching is not: only the `VANISHED` path deletes in batches of the sync
  size; the UID-enumeration deletes pass their whole list in one statement.
  (The first draft of 1.9 claimed both were the same; the verification pass
  caught it.)
- **Attachment metadata is safe.** Filenames are RFC 2047-decoded for display;
  content-type is reduced to the media type before the first `;`; a negative
  `getSize()` is clamped to 0.

- **The parse those claims sit on is now exercised, not assumed.** Every claim
  above describes what happens _after_ jakarta.mail has turned
  attacker-controlled bytes into a part tree, and until 2026-08-31 no test fed
  the parser malformed MIME (`MailContentGreenMailIT` already parsed raw bytes,
  well-formed ones): every case in `MimePartExtractorTest` hands the
  extractor a tree the test built, and even its "malformed multipart" case is a
  Mockito mock returning a String — the shape a bad parse leaves behind, not
  the parse. `MimePartExtractorHostileMimeTest` now runs a corpus of raw
  messages (truncated parts, missing boundaries, a boundary token inside the
  content, bogus charsets and encodings, `message/rfc822` nesting, nesting past
  `MAX_DEPTH`) through the two attachment walks, `hasAttachments` and
  `extractAttachmentMetadata`; the body and inline-image entry points get
  separate single cases (1.4–1.8 said the corpus runs "through the four entry
  points"). It asserts **invariants**, not
  recorded output: that the two attachment walks agree, and that the depth
  bound both binds and lets shallower trees through. Which of these inputs the
  library throws on is deliberately not pinned — some do today — and throwing
  is inside the contract, since the caller catches it: the sync
  keeps the envelope-only stub (proven by `MalformedBodyStructureSyncIT`, which
  injects a `MessagingException` from a mock rather than a real parse) and
  the content endpoint answers with a typed mail error.
- **Measured while writing that test:** `MAX_DEPTH` bounds _work_, not stack. A
  copy of the extractor with the depth guard removed walks 100, 1000 and 3000
  levels of nesting and still returns the body, so a `StackOverflowError`
  escaping into the sync is not the failure mode the bound prevents. An earlier
  version of the test asserted no `Error` escapes and was deleted once that
  measurement showed it could not fail.

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
  Added at 1.9: the label is built **without quoting** the personal part, and
  `MessageEntity.getFromEmailOnly` later recovers the address as the text
  between the label's first `<` and first `>`. A personal name that itself
  contains `<…>` therefore decides the address the allow-list is keyed on,
  the reply is prefilled to (`MailDraftService`) and the content response
  reports as `senderEmail` — not the real sender. "The one security-load path"
  is too narrow as well: the same address feeds the reply target and the
  autocomplete history (`CorrespondentService`). See §5 for the severity.
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
  trade-off in §3 above. The three guards describe the forward lookup only.
  The reverse path, `findAbsorbableSubjectOrphanThreadIds`, lets a newly
  arrived message without threading headers absorb existing parentless reply
  threads with the same normalized subject inside the same ±30-day window, and
  the arriving message itself needs **no** reply marker; the candidates must
  each consist only of headerless marker replies. Same worst case — a message
  grouped with others it does not belong to — so nothing about trust moves
  (recorded at 1.9; 1.0–1.8 stated the "only" guards for both directions).
  The normalization that feeds it is the subject of finding **B1-2** below.
- **References walk is bounded.** The JWZ-light threading algorithm caps the
  `References` chain walk at `MAX_REFERENCES_WALK = 50`
  ([ThreadingService](../backend/src/main/java/org/voxrox/mailbackend/feature/mail/service/ThreadingService.java)),
  an explicit defense against a malicious/oversized `References` header (the
  published algorithm has no bound).
- **UIDVALIDITY is cross-checked.** A change in the server's UIDVALIDITY is
  detected ([FlagSyncService](../backend/src/main/java/org/voxrox/mailbackend/feature/mail/service/FlagSyncService.java))
  and triggers a state reset (`resetForUidValidityChange`), so a server that
  renumbers its mailbox (or an active-MITM UID desync, B1-T) cannot silently
  map local rows onto different server messages **during sync**. The check runs
  at the start of each sync cycle; the user-initiated UID operations between
  cycles — body open, attachment download, move, delete, flag, draft send,
  lazy page fetch — use the stored UID without comparing UIDVALIDITY, so a
  renumbering between two cycles can make one of them act on another message
  until the next cycle resets the state (narrowed at 1.9; see §5).

## 3b. Outbound composition (confirmed, new at 1.5)

The send path existed at 1.0 but was only described where it touches transport
(§1) and header injection. It grew a second content type since 1.4, which is
worth stating explicitly so the next reader does not have to decide whether it
belongs to this boundary.

- **The HTML alternative is rendered from local input, never from the wire.**
  [MimeMessageBuilder](../backend/src/main/java/org/voxrox/mailbackend/feature/mail/service/MimeMessageBuilder.java)
  now attaches a `text/html` part beside the plain text when the composed body
  is Markdown, rendered by
  [MarkdownBodyRenderer](../backend/src/main/java/org/voxrox/mailbackend/feature/mail/service/MarkdownBodyRenderer.java).
  This boundary is about bytes a hostile server supplies; these bytes are
  mostly the user's own keystrokes — but for a reply or forward the prefilled
  body carries the quoted original, the sender label and the subject, which
  came off the wire (`MailDraftService`), so wire text does reach the renderer
  (narrowed at 1.9; 1.5–1.8 said "never from the wire"). That is why its
  configuration is load-bearing rather than belt-and-braces: `escapeHtml(true)` and
  `sanitizeUrls(true)` are on, `HtmlBlock` is absent from the enabled block
  set, and rendering is skipped above `MAX_RENDERED_CHARS` (512 Ki characters) — below the
  8 MiB body cap of §4, so the send path gains no unbounded CPU sink.
- **The enabled block set is explicitly ordered.** `ENABLED_BLOCKS` is a
  `LinkedHashSet`, not `Set.of`. CommonMark registers block parsers in
  iteration order and `Set.of` salts that order per JVM, so the same body
  rendered differently between runs. Determinism is a correctness property
  rather than a security one, but a renderer whose output depends on the JVM
  instance is not a thing an audit can make claims about at all.
- **Header-bound fields still reject CR/LF.** `requireSingleLine` guards the
  subject, `In-Reply-To`, `References` and the attachment content type — the
  #145 hardening noted at 1.3, re-verified here unchanged.
- **A draft's body stays a single `text/plain` part** (`BodyFormat.PLAIN` in
  `MimeMessageBuilder`), so the Drafts APPEND never carries the rendered
  alternative and its shape is still the one §2's persistence claims were
  written against. The message around it is always a `multipart/mixed`,
  attachments or not — `build()` wraps every body — and the Sent-folder
  APPEND of a Markdown message does carry the rendered alternative. 1.5–1.8
  wrote "drafts remain single-part", and the first draft of 1.9 overcorrected
  to "the IMAP APPEND payload never carries the rendered alternative"; both
  are fixed here.
- **Sending an untouched draft does not go through this builder.** It sends
  the server's copy of the draft as fetched, so none of the guards above apply
  to it — finding **B1-5** (§4e).

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
pulled during sync). An OOM crashes the sidecar or — as the packaged JVM ran
until 2026-09-22, when `-XX:+ExitOnOutOfMemoryError` shipped (§4c residual) —
may leave it running degraded;
when the process does exit, the frontend's sidecar supervisor relaunches it
(1.0–1.8 credited the parent-process watchdog, which only makes the backend
exit when its parent dies), and the poisoned body is never persisted (the OOM happens before
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
cap depends on — Angus IMAP serving the part in partial fetches (16 KiB, the
Angus default `fetchsize`, which the code does not pin) — is now
pinned explicitly (`mail.<proto>.partialfetch=true` in
`ImapConnectionManager`), matching how `checkserveridentity` is pinned rather
than trusted to the library default. Charset decoding happens after the cap; an
unknown or malformed charset degrades to a UTF-8 decode with replacement
characters instead of failing the message. A part over the cap yields
`ExtractedBody.OVERSIZE`; `multipart/alternative` selection is order-agnostic —
an oversized rich part falls back to the last plain-text sibling if it fits, whether
or not the sender emitted RFC 2046 plain-first order (a nested
`multipart/related` alternative, the Apple Mail HTML+inline-images layout, now
also renders instead of being skipped). On an oversized body,
`MailContentService` persists the new `messages.body_oversize` flag —
best-effort, and `content` stays NULL so the FTS index never sees placeholder
text — and serves a localized "message too large" placeholder
(`mail.message.bodyTooLarge`) through the standard plain-text wrapper.
Subsequent opens short-circuit on the flag, so the oversized body costs at most
one bounded fetch — once the flag has been persisted; a failed persist costs
one bounded fetch per open until it succeeds. Reply/forward drafts quote an oversized original as an empty
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
bugs found while building the harness bound its coverage (the pom has pinned
2.1.13 since; the two were not re-checked on it) (documented in the
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
retained heap, and it ends when the pass ends. (Until 1.9 this also said it
was confined to the sync executor. It is not: `assignThread` runs wherever a
message is persisted, which includes a lazy page fetch on the HTTP request
thread — `MailFacade` → `MailSyncService.fetchServerCountAndEnsurePageLocally`
→ `MessageDownloader.downloadSequenceRange` — and the threading backfill. With
the fix in place the cost is sub-millisecond wherever it runs, so the
correction changes where the pre-fix cost would have landed, not the verdict.)

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

## 4c. Finding B1-3 (Medium) — a server-stated size exhausts the heap before our code runs — **FIXED**

**What.** Angus allocates from sizes the server states, while it is still
parsing the response and before any of our code sees it. 1.9 found this on the
QRESYNC open and titled it for that path; the fix, reading the same bytecode
again at 1.11, found the QRESYNC open is one route of three, and all three
exist on every folder open the backend makes:

- `* n EXISTS` sizes the folder's message cache (`MessageCache`, one
  reference per message) in every `IMAPFolder.open`.
- `* VANISHED (EARLIER) set` is expanded into one `long` per UID
  (`UIDSet.toArray(set, uidnext)`, UIDNEXT also the server's). `MailboxInfo`
  collects VANISHED from _any_ SELECT or EXAMINE reply, so the plain and the
  CONDSTORE open expand it too, whether or not QRESYNC was asked for.
- `* VANISHED set` (untagged) is expanded the same way by
  `IMAPFolder.handleResponse`, plus a message object per UID, whenever a
  folder is open, without checking that QRESYNC was ever enabled.

One short line of either kind asks for more than the packaged 384 MB heap
holds. `FlagSyncService.applyResyncEvents` then boxed every vanished UID into
a `List<Long>` before batching — the one part of this that was our code.
Dropping QRESYNC, one of the options 1.9 listed, would have closed none of
the three: a hostile server sends VANISHED whether it was asked for or not.

**Severity: Medium** (_DoS recoverable by restart_): automatic sync, no user
interaction, needs a hostile or compromised server; TLS keeps a network
attacker out. An allocation that large fails before it takes memory, so the
process stays up, but the `OutOfMemoryError` escapes the sync's
`catch (Exception)`: nothing is recorded, and the pass skips its remaining
folders.

**Fix (shipped 2026-09-22).** Every IMAP store the backend opens is now a
`BoundedImapStore`, registered on the session by the connection pool and by
the credential probe before their `getStore`; its connections are a
`BoundedImapProtocol`, whose `readResponse` — the one point between the wire
and Angus's parse — holds each response to a bound before returning it:

- EXISTS at most 2,000,000 (priced below);
- no VANISHED at all on a connection that has not enabled QRESYNC: the
  EARLIER form answers a QRESYNC SELECT or a `UID FETCH (VANISHED)`, and the
  other replaces EXPUNGE once QRESYNC is enabled (RFC 7162 §3.2.10), so such
  a client has asked for neither;
- VANISHED (EARLIER) at most 1,000,000 UIDs, untagged VANISHED at most 100,000
  (it also costs a message object per UID), counted with Angus's own parser;
  a descending range, which Angus would size wrongly, and a line Angus's reader
  cannot parse are refused as well.

A count under the EXISTS bound is allocated, not refused, so the bound is
priced per connection, with compressed pointers:

- 4 bytes a message for the cache the SELECT sizes.
- Up to 12 for a later EXISTS on the open folder. `IMAPFolder.handleResponse`
  allocates a `Message[]` for the new messages whether or not anyone listens,
  and grows the cache array. Once an EXPUNGE has been seen, it grows the
  sequence-number array as well.

An account holds three such connections at once: a move's source and
destination on the `BACKGROUND` Store, and a body fetch on the `INTERACTIVE`
one. So one server can take at most 72 MB, under a fifth of the heap. That is
also less than the sync's own UID listing takes for an honest CONDSTORE folder
of that size. The first version of the fix allowed 10,000,000, priced as
40 MB for the SELECT alone, which let one server take 240 MB.

A refusal is an `IOException`, which Angus turns into a synthetic BYE: the
command fails with a `ConnectionException`, the connection closes, and the
sync records the failure as for any dropped connection. A `ProtocolException`
would not do — `Protocol.command` skips a response that fails that way. So
that a server keeping to the known-UID range cannot be refused, the sync asks
for QRESYNC only when the folder's local UID range is narrower than the
VANISHED (EARLIER) bound (`MailSyncService.buildResyncRequest`); a wider
folder takes the CONDSTORE path. `FlagSyncService.deleteVanished` no longer
materializes the server's UIDs. It streams them into batches of the sync size
and deletes each batch as it fills. UIDs outside the folder's local range are
dropped first, using only its two ends from the index, because they cannot
be local rows. So memory stays at one batch, whatever the server names. The
first version of the fix intersected the set with every local UID instead,
which bounded memory by the local mirror, but read the whole folder on every
cycle that reported any deletion.

**Regression tests.** `HostileImapResponseIT` syncs from an IMAP server
written for the test (`HostileImapServer`, over TLS), which answers the open
with `* 2147483583 EXISTS` or with `* VANISHED (EARLIER) 1:2147483647` under a
lifted UIDNEXT. Run against the unfixed code, both end in
`OutOfMemoryError: Requested array size exceeds VM limit` thrown out of the
sync (from `MessageCache.ensureCapacity` and from `UIDSet.toArray`, both via
the plain `IMAPFolder.open(int)`), and the error took the failsafe fork down
with it; with the fix, each pass records a sync error and the next ordinary
answer syncs again. `BoundedImapProtocolTest` pins each bound at its edge on
responses parsed by Angus; `ImapConnectionManagerTest` and
`MailConnectionProbeTest` that both paths register the bounded store before
asking for one; `FlagSyncServiceTest` and `MailSyncServiceTest` the
local-range clamp and the QRESYNC range limit. The new tests in those last
four classes were run against the unfixed code and fail there. The exception
is `FlagSyncServiceTest.localUidsAreNotRead`, which guards against the first
version of the fix: it fails against that version, which read every local UID.

**Residual.** A folder with more than 2,000,000 messages cannot be opened. A
live VANISHED above 100,000 UIDs from an honest server — another client
expunging that many while a sync has the folder selected — costs one retried
cycle. The size a literal declares is a separate route, not covered here:
B1-7 (§4g). `-XX:+ExitOnOutOfMemoryError` for the packaged JVM was held back
until this fix (decided 2026-09-22), since until then a hostile server could
have turned it into a crash loop; it shipped once this landed, so a heap that
is exhausted anyway now ends the process — which the client restarts — instead
of leaving a JVM whose sync thread was killed mid-lane. A packaging step
verifies the flag reached the launcher's `.cfg`, and the client names exit 3
as an out-of-memory stop rather than a failed start.

**Status: fixed.**

## 4d. Finding B1-4 (High) — IMAP password login without TLS when SSL is off — **FIXED**

**What.** An account's IMAP settings carry a free `useSsl` flag
(`MailServerSettings.useSsl`, the SSL checkbox in manual setup). With it off,
`ImapConnectionManager.createNewConnectedStore` and `MailConnectionProbe.testImap`
select the plain `imap` protocol and set neither `mail.imap.starttls.enable`
nor `mail.imap.starttls.required`, so a password account sends `LOGIN` in
cleartext and every message crosses the network unencrypted. The OAuth2 guard
of §1 blocks the token case; nothing blocks the password case. SMTP differs:
without implicit SSL it requires STARTTLS. The threat model's boundary summary
("IMAPS/SMTPS over TLS; … PASSWORD-on-TLS") and its I row ("SSL/TLS
enforcement") did not hold for IMAP.

**Severity: High** — _credential exposure under specific preconditions_. The
precondition is a user choice in manual setup (the provider presets ship with
SSL on), after which a passive observer on the path reads the password; no
warning is shown. Not Critical, because it needs that choice.

**Recommendation.** Mirror SMTP: for a non-SSL IMAP connection set
`mail.imap.starttls.enable=true` and `mail.imap.starttls.required=true`, so an
unchecked SSL box means STARTTLS rather than cleartext and fails closed when
the server does not offer it. Whether a genuinely cleartext IMAP option should
exist at all (a local bridge on `127.0.0.1`, for instance) is a product
decision; if it stays, it belongs behind a loopback-only check and a visible
warning.

**Fix (shipped 2026-09-22).** An unchecked SSL box now means STARTTLS, and
STARTTLS is required: `ImapTransportSecurity.configure` sets
`mail.imap.starttls.enable` and `mail.imap.starttls.required` whenever implicit
SSL is off, alongside the `ssl.enable` and `checkserveridentity` settings it now
owns, and both the connection pool and the credential probe build their
sessions through it, so the two cannot drift apart. A server that does not
offer STARTTLS — or a network that strips it — fails the connect before any
credential is sent. The OAuth2 guard of §1 stays as it was. The account
form's note under the server settings, which said STARTTLS was not supported,
now says what the box does.

**Regression tests.** `ImapConnectionManagerTest.passwordWithoutSslRequiresStartTls`
and `MailConnectionProbeTest.passwordWithoutSslRequiresStartTls` pin the
properties on each path; `ImapStartTlsRequiredGreenMailIT` points the probe at
a GreenMail IMAP server, which implements no STARTTLS, and requires the connect
to fail. All three were run against the unfixed code and fail there — the IT
because the probe logged in over cleartext without complaint. The GreenMail
ITs, which all connected over plaintext IMAP, now use IMAPS with the shared
test certificate, and `MailSyncQresyncDovecotIT` keeps its SSL setting off
against a Dovecot that requires TLS, so it exercises the STARTTLS path against
a real server (CI only, it needs Docker).

**Residual.** An account that relied on cleartext IMAP stops connecting, which
is the intent; no loopback exception was made (decided 2026-09-22).

**Status: fixed.**

## 4e. Finding B1-5 (Medium) — sending an untouched draft trusts and buffers the server's copy — **OPEN**

**What.** A draft the user did not edit is sent as the server holds it:
`ImapAppendService.fetchAndDetachMime` writes the server's message into a
`ByteArrayOutputStream` with no bound and parses it again (the bytes are held
several times over), and `SmtpMessageService.sendDraftAsync` sends that
`MimeMessage` to its own `getAllRecipients()`. The recipients, headers and
body that go out are therefore the server's, not what the client stored or
showed: a hostile or compromised IMAP server can add a Bcc, change the text,
or serve a body large enough to exhaust the heap. The §3b CR/LF guards do not
apply, because this path never goes through `MimeMessageBuilder`.

**Severity: Medium.** It is the integrity of mail the user sends, but only
against the Boundary 1 adversary in control of the server (TLS keeps a network
attacker out, B1-4 aside), which can already read the draft and, where it also
runs the user's SMTP, send in the user's name without this path. What the path
adds is getting the user's own client to do it on the user's own action, plus
an unbounded buffer.

**Recommendation.** Send what the client knows: rebuild the message from the
locally stored draft, or at least compare the fetched copy's recipients with
the stored ones and refuse on a mismatch, and bound the fetch the way B1-1
bounds bodies. The as-is path exists to keep a draft composed in another
client intact, so which of these is a product decision at fix time.

**Status: open**, tracked in `todo.md`.

## 4f. Finding B1-6 (Low) — no IMAP write timeout — **OPEN**

**What.** The IMAP stores set `mail.<proto>.timeout` (read) and
`.connectiontimeout` but no `.writetimeout` (`ImapConnectionManager`,
`MailConnectionProbe`); SMTP sets all three. A server that stops reading — a
full TCP window during an APPEND, a stalled command — blocks the writing
thread indefinitely, and with it that lane's lock for the account.

**Severity: Low**, the same threat row as the slow-server hang (B1-D):
recoverable by restart, confined to one account's lane, and a hostile server
can stall more cheaply by not answering, which the read timeout covers.

**Recommendation.** Set `mail.<proto>.writetimeout` from a new
`mail.client.imap.write-timeout`. Angus implements it with a scheduler per
connection, a cost to weigh against the lane count.

**Status: open**, tracked in `todo.md`.

## 4g. Finding B1-7 (Medium) — a declared literal size is allocated before a byte arrives — **OPEN**

**What.** An IMAP literal announces its length (`{n}` at the end of a
line), and Angus's `ResponseInputStream.readResponse` grows its buffer to
`n` before reading the literal's bytes. `n` is the server's. Measured
against Angus 2.0.5 on a 384 MB heap (at 1.11, through
`IMAPProtocol.readResponse` over a stream): a declared `{2000000000}` fails
at once with `OutOfMemoryError`, and a declared `{300000000}` followed by
three bytes takes about 300 MB of heap before the stream ends. On a socket the
server simply stops sending, and the buffer stays allocated until the read
timeout — long enough for the rest of the process to run out. Any response can
carry a literal, the ones the sync fetches included, so it needs no user
action. The allocation happens before the response exists as an object, so
`BoundedImapProtocol` (§4c) cannot see it; and B1-1's 8 MiB body cap (§4)
bounds the bytes our code reads, not the buffer a partial fetch's literal
declares.

**Severity: Medium** (_DoS recoverable by restart_), the same precondition as
B1-3: a hostile or compromised server, automatic sync.

**Recommendation.** Refuse a declared size above a bound before Angus reads
it, which means sitting under `ResponseInputStream` — a stream wrapper on the
connection's socket, or `ByteArray.grow` in a buffer the protocol hands
Angus. Which, and the bound (a literal legitimately carries a whole message
when partial fetch is off), is a design decision at fix time.

**Status: open**, tracked in `todo.md`.

## 5. Informational notes (no change required)

- **Thread renumbering reads whole entities.**
  `ThreadingService.renumberThreadPositions` loads a thread's members through
  `findByAccountIdAndThreadId` as managed entities, and `MessageEntity.content`
  is a plain `@Lob` — eager by default — so the bodies come with them. It runs
  on every orphan merge and on a late arrival that sorts before an existing
  member, both of which a hostile server can provoke by drip-feeding a thread
  in descending date order. Two things bound it, and both were measured against
  the code rather than assumed. Each body's **read** is capped at 8 MiB by the
  B1-1 fix — the stored `content` can be several times that, because the
  plain-text escape and linkifying expand the text and inline images add their
  base64 (corrected at 1.9; the heap cost of that expansion, and of the Jsoup
  DOM for an 8 MiB HTML body, has not been measured). And — the larger
  effect — `content` is **null for a message nobody has
  opened**: the sync path persists through `MessageMapper.toEntity`, which
  has no body to set — the `FetchedMessage` it maps carries none — and
  `MessageContentPersister` is the only production code that writes `content`,
  on first open (re-checked at 1.9 by grepping `backend/src/main` for callers
  of `setContent(` and for JPQL or SQL writing the column; the comment 1.5
  quoted from the mapper is gone). The worst case is therefore
  a long thread the user has already read that the server keeps extending, not
  an arbitrary sync. Recorded rather than fixed: a body-free projection for the
  renumber would remove the heap term from both call sites and is worth its own
  change. Carried up from the freshness ledger at 1.5 — it was found during an
  acknowledgement, and acknowledgements are deleted at re-verification, so a
  note that only lived there would have been lost with them.

- **Attachment download is disk-bounded, not memory-bounded.**
  [AttachmentService.downloadToTempFile](../backend/src/main/java/org/voxrox/mailbackend/feature/mail/service/AttachmentService.java)
  streams the part to a private temp file via `Files.copy` (constant heap), with
  an empty-download integrity check and a stale-temp sweep on boot. A hostile
  server serving a huge attachment could fill the disk, but the copy never
  buffers the attachment in memory, the file lands in the app's own
  `<data-dir>\tmp` (1.0–1.8 said "the user's own temp dir"), and it is
  unlinked on stream close. Lower impact than B1-1; the same
  read-cap-and-reject approach would close it if ever desired.

- **Attachment part lookup follows `message/rfc822` without a bound** (added
  at 1.9). `AttachmentService.findPartByPath` walks the stored part path, but
  unwraps an embedded message without consuming a path segment, so a server
  that serves a chain of nested `message/rfc822` at download time drives an
  unbounded recursion — the one MIME walk outside `MAX_DEPTH`. Low: it needs a
  user-initiated download and ends in a `StackOverflowError` on that request.

- **The `From` label decides the address used downstream** (added at 1.9, see
  §3). A personal name containing `<…>` becomes the allow-list key, the reply
  target and the reported `senderEmail`. Low: it moves the F2 image-loading
  trade-off onto an address the sender chose, and a prefilled reply is shown
  to the user before it is sent. Quoting the personal part in `formatAddress`,
  or storing the parsed address separately, would close it.

- **UIDVALIDITY is checked per sync cycle, not per operation** (added at 1.9,
  see §3). Low: acting on another message needs the server to renumber the
  mailbox between two cycles — which it can do at will, but only to its own
  mailbox's messages — and the next cycle resets the state. The draft-send
  path's hard delete of the sent draft is the case with the most effect.

## 6. References

- [SECURITY_THREAT_MODEL.md](../SECURITY_THREAT_MODEL.md) — Boundary 1 STRIDE matrix.
- [CONTENT_RENDERING_AUDIT.md](CONTENT_RENDERING_AUDIT.md) — Boundary 4, what the parsed body feeds into.
- [AUDIT_GUIDE.md](AUDIT_GUIDE.md) — audit method + boundary map.
- [backend/SECURITY_RELEASE_CHECK.md](../backend/SECURITY_RELEASE_CHECK.md) — per-release security gate.

## 7. Change log

- **1.12** (2026-09-22) — **the packaged JVM exits on the first
  OutOfMemoryError** (#562). Not a finding of its own: it is the mitigation
  §4c's residual said was waiting for the B1-3 fix, which removed the crash
  loop a hostile server could otherwise have driven. What it changes is what a
  heap exhaustion costs — the process ends and the client's supervisor
  restarts it, instead of a JVM that keeps serving with whichever thread hit
  the error killed, which for a sync thread means the lane's lock is never
  released and that account never syncs again. A packaging step now checks
  that every JVM option reached the published launcher `.cfg`; an option
  jpackage fails to write is otherwise invisible, since the sidecar starts and
  serves mail without it. §4 and §4c follow. No finding changes state: B1-5,
  B1-6 and B1-7 stay open.
- **1.11** (2026-09-22) — **B1-3 fixed, its scope corrected; B1-7 found**
  (#561). Reading the Angus bytecode again for the fix showed the QRESYNC open
  is one of three routes by which a server-stated size is allocated before our
  code runs — EXISTS, and VANISHED in either form, on every folder open — so
  §4c is retitled and the option of dropping QRESYNC, which would have closed
  none of them, was not taken. The fix is a check on every IMAP response
  ahead of Angus (`BoundedImapStore`, `BoundedImapProtocol`), a QRESYNC range
  no wider than the VANISHED bound, and a delete that streams the named UIDs
  in batches clamped to the local range. Review of the PR brought EXISTS down
  from 10,000,000 to 2,000,000, priced over three connections rather than one
  SELECT. It also replaced an intersection with every local UID, which read
  the whole folder on each cycle that reported a deletion.
  `HostileImapResponseIT` shows the unfixed code ending the sync in an
  `OutOfMemoryError`. The same reading found B1-7 (Medium, §4g): a literal's
  declared size is allocated before its bytes arrive, measured at about
  300 MB for a `{300000000}` followed by three bytes. §2's QRESYNC bullet and
  the method statement point at the fix. Drift under `Code paths`
  acknowledged in the ledger, since only §2, §4c and §4g were re-read against
  this change. B1-5, B1-6 and B1-7 stay open.
- **1.10** (2026-09-22) — **B1-4 fixed** (#560): an IMAP account whose SSL
  setting is off gets required STARTTLS instead of a cleartext login, through
  one `ImapTransportSecurity` shared by the pool and the probe (§4d). §1 now
  states STARTTLS-required for both protocols, and the method statement the
  new dynamic cover: the GreenMail ITs run over IMAPS with a certificate for
  `127.0.0.1`, and a new IT proves a server without STARTTLS is refused.
  Drift under `Code paths` acknowledged in the ledger rather than re-anchored,
  since only §1 and §4d were re-read against this change. B1-3, B1-5 and B1-6
  stay open.
- **1.9** (2026-09-22) — **re-verified against `6224cbb`, clearing all six
  acknowledgements; four new findings, all open, so the verdict changes from
  PASS to open findings.** The ledger was at 6 of 8 and the check asked for
  this ahead of the cap. Since `9435e56` thirteen files moved under `Code
paths` over six PRs: the CONDSTORE open step (#509), a log line dropping a
  Message-ID (#516), the UID-enumeration interval as a setting (#519), a
  javadoc (#527), the message detail no longer fetching the body (#555) and
  the sync's own `FetchedMessage` record (#557). The version was verified
  twice. The author re-read every claim against the tree and found six
  inaccuracies; a separate agent, given only this document and the anchor as
  [AUDIT_GUIDE.md](AUDIT_GUIDE.md) §5 requires, then re-checked 116 claims,
  disassembled Angus 2.0.5 where a claim rested on library behaviour, and
  found what the author had not. **Findings:** B1-4 (High, §4d) — an IMAP
  password account with SSL off logs in over cleartext, with no STARTTLS;
  B1-3 (Medium, §4c) — Angus expands a server-supplied `VANISHED` range into
  one array before our code sees it; B1-5 (Medium, §4e) — sending an untouched
  draft sends the server's copy, unbounded and unchecked; B1-6 (Low, §4f) — no
  IMAP write timeout. All four were confirmed in the code by the author before
  being written down. **Corrections**, none of which moves a finding: the retry
  policy also retries certificate and hostname rejections, and a second retry
  layer re-runs whole folder cycles (§1; `RetryConfig` joins `Code paths`);
  the SMTP read timeout runs at its 10 s record default, which also sets the
  write timeout (§1); an account holds two Stores, not "up to two TLS sockets"
  (§1); the QRESYNC open has more preconditions than a baseline, and only the
  `VANISHED` delete batches (§2); the hostile-MIME corpus runs through two of
  the four entry points (§2); the `From` label is re-parsed without quoting,
  and that address feeds more than the allow-list (§3, §5); the subject
  fallback has a reverse path whose guards differ (§3); UIDVALIDITY is checked
  per sync cycle, not per operation (§3, §5); a reply renders quoted wire text
  through the Markdown renderer (§3b); every message is `multipart/mixed` and
  the Sent copy carries the rendered alternative (§3b); an OOM need not end the
  JVM and the frontend supervisor, not the watchdog, restarts it (§4); the
  subject normalization also runs on the request thread (§4b); the stored body
  can be several times the 8 MiB read cap (§5); the attachment part lookup is
  the one MIME walk outside `MAX_DEPTH` (§2, §5); counts nothing recomputes
  are gone; the method statement names the dynamic cover and its limits.
  **Two of those corrections undo sentences the author wrote into the first
  draft of this very version**: that the delete's batching is the same for
  every open mode, and that the IMAP APPEND payload never carries the rendered
  alternative. `Code paths` also gains `application.properties`,
  `MessageRepository`, `MessageEntity`, `FolderSyncStateEntity` and
  `MessageMapper`, which claims here rest on and the freshness check could not
  see; three of them changed between `9435e56` and `6224cbb` unobserved. The
  CONDSTORE note from the first acknowledgement is carried into §2, since the
  ledger entry is deleted with this change. In the threat model the Boundary 1
  introduction named the 1.0 anchor and "one" Medium gap, the boundary summary
  claimed PASSWORD-on-TLS, and several rows overstated; all are corrected with
  this version.
- **1.8** (2026-09-22) — a documentation revision over acknowledged drift, so
  the anchor stays `9435e56`; verdict stays PASS. The message detail stopped
  fetching the body (#555), and two sentences in §2 described that fetch. The
  stub bullet said the detail endpoint retries a malformed message's body; the
  content endpoint does that now, and it is the one that reports a failure.
  Both that bullet and the hostile-MIME bullet also said the stub records
  `contentError`, which was **not true when written**: `MessageFetcher` put the
  message on its DTO, `MessageMapper.toEntity` never read it, and
  `MalformedBodyStructureSyncIT` asserts the stub's shape (no body, no
  attachments), not an error. The field is gone from the DTO with this change,
  so both sentences now say what the IT proves. Nothing else in §2 moves: the
  fetch profile is untouched, and the catch around the MIME walk still catches
  the same three types and keeps the envelope; it only stops copying the
  exception message onto a field nobody read.
- **1.7** (2026-09-16) — **re-verified against `9435e56`, clearing all seven
  acknowledgements; verdict stays PASS.** The ledger was at 7 of 8 and the gate
  was asking for this before the cap forced it onto an unrelated PR. One path
  moved — the `feature/mail/service` package, 646 lines over seven PRs — and
  the whole of it is sync correctness and concurrency, not protocol handling.
  QRESYNC/CONDSTORE plumbing accounts for most of it: `ImapFolderExecutor` can
  open a folder with `ResyncData` and hands the resulting events to the action,
  `MailSyncService` routes to the QRESYNC path when the open produced events and
  to the CONDSTORE or batched path otherwise, `FlagSyncService` applies vanished
  UIDs and flag changes, and `UidEnumerationSchedule` rate-limits the full-UID
  fallback to once an hour per folder. §2 gains a bullet for the one new thing
  worth writing down: the server now names rows to delete, and the delete is
  scoped to that account and that folder. `MessageDownloader` gained a stableId
  tie-break so a trash folder holding two copies of one Message-ID cannot abort
  its batch; `ImapConnectionManager` publishes a reauth event instead of purging
  connections under the lane lock (CONCURRENCY.md rule 2), and its pinned
  properties are unchanged — `ssl.checkserveridentity`, `partialfetch` and
  `ssl.enable` were re-read, since §1 and the §4 fix rest on them.
  `ImapCapabilities` lost a hand-kept capability table that measurement had
  shown wrong in three of four rows; nothing reads it, and what a server
  advertises is probed per connection. Outside the package, `MessageFetcher`,
  `MailContentService` and `MimePartExtractor` — the code §2, §4 and §4b are
  about — are byte-identical to `02ff962`, so the fetch profile is still
  metadata-only, the body cap is still `MAX_BODY_BYTES`, and both findings stay
  fixed. §§1, 3, 3b, 4, 4b and 5 stand as written.
- **1.6** (2026-09-06) — revised for the interactive-lane split (`02ff962`),
  **because a claim stopped being true, not because the ledger filled up**. §1
  said the folder-role lookup "degrades to folder scope rather than blocking
  behind a sync that holds the connection for a whole folder cycle". With two
  connections per account the sync is not in front of that lookup any more, so
  the sentence described a mechanism the code no longer has — the kind of stale
  claim an acknowledgement would have carried forward instead of catching.
  Scope of this revision, stated so nobody reads more into it: §1 was re-checked
  claim by claim against the current tree (pinned `checkserveridentity` on all
  three connectors, both fail-closed OAuth2/TLS guards, the millisecond-string
  timeouts, the pinned `partialfetch`, the scoped retry policy) and rewritten
  where the lane split touches it; §2–§5 rest on the full 1.5 pass of
  2026-09-02 plus the four ledger acknowledgements between, and were re-read
  against this diff only to confirm it does not reach them — it adds a lane
  parameter and splits `fetchServerCountAndEnsurePageLocally`, and changes no
  parser, no cap and no header walk. Three records came out of it. (1) Both
  lanes are built by the **same** `createNewConnectedStore`, so §1's transport
  and credential claims now hold per connection rather than per account; the
  split moved map keys, not properties. (2) The degradation path is bounded by
  design: a refused second connection costs one attempt per account per
  five-minute cooldown, so a provider session cap cannot be turned into a
  connection storm, and an auth failure is excluded from it so the single-shot
  token refresh stays the only auth retry. (3) The `role-lookup-timeout` claim
  survives with a narrower meaning — it bounds contention inside one lane now,
  not a read waiting on a sync. Verdict unchanged (**PASS**), no new finding.
- **1.5** (2026-09-02) — re-verified against `885b98a`, **at the ledger cap
  rather than because something broke**: the acknowledgement run had reached
  six of eight and the sixth said in as many words that the next drift under
  this path should be weighed as a re-verification. Twenty-five files had moved
  under `Code paths` since `cad05cb` (+756/−146). Every §1–§5 claim re-checked
  against the current code and all still hold: pinned `checkserveridentity` on
  all three connectors, both fail-closed OAuth2/TLS guards, millisecond-string
  timeouts, the metadata-only `FetchProfile`, the soft-fail on a malformed
  `BODYSTRUCTURE`, `MAX_DEPTH = 20` across all four extractor entry points, the
  2/8 MiB inline-image caps, `MAX_BODY_BYTES` with the pinned `partialfetch`,
  the linear `stripMarkers` with its 1000-char truncation, the subject
  fallback's three guards, `MAX_REFERENCES_WALK = 50`, the UIDVALIDITY reset
  and the `Files.copy` attachment stream. Verdict unchanged (**PASS**), no new
  finding. What the pass produced instead is four records the acknowledgements
  had not put anywhere durable. (1) A **new §3b** for outbound composition: the
  Markdown-to-HTML alternative added since 1.4 renders local input, never
  server bytes, behind `escapeHtml`/`sanitizeUrls`, no `HtmlBlock`, and a
  512 kB render cap. (2) A **new §1 bullet** for `executeWithLockOrSkip` and
  the 1 s role-lookup timeout, which replaced an unbounded wait on a
  server-paced lock. (3) A **second informational note in §5** for the thread
  renumber reading `@Lob` bodies — lifted out of the ledger deliberately,
  because re-verification deletes acknowledgements and a note that only lived
  there would have gone with them. (4) Two **wording corrections in §1**: the
  STARTTLS property is set on every non-implicit-SSL session rather than
  "always", and the timeout numbers come from `application.properties`, not
  from the binding record's `@DefaultValue`, which for SMTP is a shorter 15 s.
  Also corrected outside this document: the verdict index in
  [SECURITY_RELEASE_CHECK.md](../backend/SECURITY_RELEASE_CHECK.md) pinned B1
  to `806528e`, which **does not resolve** — a pre-squash branch SHA, the exact
  failure [AUDIT_GUIDE.md](AUDIT_GUIDE.md) §2 describes, recorded there while
  this document had already written the post-squash `cad05cb` beside it.

- **1.4** (2026-08-31) — no claim changed and the anchor stays `cad05cb`;
  what changed is that §2 stopped resting on an untested parse. The claims
  there describe the pipeline after jakarta.mail has built the part tree from
  attacker-controlled bytes, and no test had ever asked it to build one — the
  seventeen existing cases all assemble the tree themselves. New
  `MimePartExtractorHostileMimeTest` closes that with sixteen raw messages
  against invariants rather than recorded output. Written alongside the same
  treatment for Boundary 4 (`CONTENT_RENDERING_AUDIT` v1.8), after the jsoup
  bump in #349 showed that a dependency can move an audited behaviour without
  `check:audits` noticing. Two new bullets in §2 carry the method and the
  measurement that killed a weaker version of the test.

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
