# VoxRox Mail — Content Rendering Security Audit

| | |
|---|---|
| **Version** | 1.0 |
| **Date** | 2026-07-08 |
| **Applies to** | VoxRox Mail V0.1.0 |
| **Subsystem** | Untrusted email HTML rendering — Boundary 4 of [SECURITY_THREAT_MODEL.md](../SECURITY_THREAT_MODEL.md) |
| **Verdict** | **Security: PASS** (no exploitable finding). F1 (dead links) and F2 (embedded images not rendering) both **fixed**; remote-image opt-in deferred post-beta. |

Per-subsystem release audit of the path **"raw IMAP body → rendered to the
user"**. Every email is 100% attacker-controlled input, so this is the
highest-value untrusted-input boundary in the product. Method: static
data-flow trace across backend and frontend, enumeration of every consumer
and DOM injection sink, plus an empirical browser-engine check of the mail
body sandbox (see §5).

## 1. Pipeline — triple-layer defense (confirmed)

```
IMAP raw body
  → [1] backend Jsoup Safelist        (HtmlSanitizer.java)      → stored in DB (mail-content-wrapper)
  → [2] frontend DOM allow-list        (content-sanitizer.ts)
  → [3] opaque-origin sandbox iframe   (mailFrame.ts)           + hash-pinned <meta> CSP
```

1. **Backend — canonical policy.** [HtmlSanitizer.java](../backend/src/main/java/org/voxrox/mailbackend/util/HtmlSanitizer.java):
   `Safelist.relaxed()` minus `style`; remote `http(s)` `<img>` sources
   dropped (tracking-pixel defense); every safe `<a>` gets
   `rel="nofollow noopener noreferrer"` + `target="_blank"`; wraps output in
   `<div class="mail-content-wrapper">`. **Fail-closed**: any parser/sanitizer
   exception returns a "content blocked" placeholder, never raw HTML. Invoked
   from [MailContentService.getOrFetchMessageContent](../backend/src/main/java/org/voxrox/mailbackend/feature/mail/service/MailContentService.java)
   and persisted, so the stored body is already sanitized.

2. **Frontend — stricter re-sanitize.** [content-sanitizer.ts](../frontend/src/lib/mail/content-sanitizer.ts):
   independent tag/attribute allow-list; unknown tags are unwrapped (children
   kept, attributes dropped); `style` stripped entirely; `<a href>` limited to
   `http/https/mailto/tel`; `<img src>` limited to inline `data:image/*;base64`
   (remote and `cid:` dropped); `colspan/rowspan` coerced to digits. Forces
   `target="_blank"` + `rel="noopener noreferrer nofollow"` on kept links.

3. **Frame — defense in depth.** [mailFrame.ts](../frontend/src/lib/mail/mailFrame.ts)
   renders the sanitized body via `srcdoc` in an iframe with
   `sandbox="allow-scripts"` and **no** `allow-same-origin` (opaque origin: no
   parent/cookie/storage/same-origin-network access), carrying a `<meta>` CSP
   `default-src 'none'; img-src data:; script-src 'sha256-…'; style-src
   'sha256-…'; base-uri 'none'; form-action 'none'`. The only executable script
   is the hash-pinned first-party key forwarder; every mail-body script is
   blocked by hash mismatch, and inline event handlers are blocked by
   `script-src` without `'unsafe-inline'`.

## 2. Security verdict: PASS — verified checklist

- [x] **No `{@html}`** anywhere in the frontend → mail content never becomes
      live DOM in the main document.
- [x] **No dangerous DOM sinks** — `.innerHTML =`, `insertAdjacentHTML`,
      `outerHTML =`, `document.write` = 0 occurrences in `frontend/src`. The
      only path from content to live DOM is the sandboxed iframe `srcdoc`.
- [x] **Sanitizer has no unprotected consumer** — `sanitizeMailHtml` is
      imported only by `buildMailFrameSrcdoc` (+ tests), so its output always
      lands inside the CSP frame.
- [x] **mXSS (serialize → reparse) neutralized** — even if `doc.body.innerHTML`
      serialization + reparse in the iframe resurrected markup, the frame CSP
      blocks inline scripts (hash mismatch), inline event handlers (no
      `'unsafe-inline'`) and all network (`default-src 'none'`, `img-src
      data:`). The `srcdoc` is built by string concatenation, but HTML
      serialization escapes text/attribute delimiters, so the body cannot break
      out of the `<body>` context.
- [x] **Compose path is plain-text** — reply/forward/draft flatten the body via
      `mailHtmlToPlainText` / backend `MailDraftService.htmlToPlainText`
      (Jsoup `Safelist.none()`) into the plain-text composer, never HTML.
- [x] **Tracking pixels** — remote `<img>` src dropped at **both** layers +
      `img-src data:` in the frame CSP. No "load remote content" toggle, so no
      opt-in exfil path.
- [x] **Reverse tabnabbing** — `rel="noopener noreferrer nofollow"` at both layers.
- [x] **DoS** — MIME recursion capped at `MAX_DEPTH=20`
      ([MimePartExtractor.java](../backend/src/main/java/org/voxrox/mailbackend/util/MimePartExtractor.java)).
- [x] **Fail-closed** — sanitizer error returns a placeholder, not raw HTML.

The shipping code matches Boundary 4 of the threat model. No exploitable
finding. This subsystem is **security release-ready**.

## 3. Findings (all functional / fidelity — not security)

### F1 — High (functional): links in HTML emails do not open — **FIXED 2026-07-08**

The sanitizer forces `target="_blank"` on every link, but the body frame is
`sandbox="allow-scripts"` with **no** `allow-popups`, so a `_blank`
navigation is blocked by the browser engine before it reaches Tauri. There was
**no** click/navigation bridge (the forwarder relayed only `keydown`), and
[lib.rs](../frontend/src-tauri/src/lib.rs) registers no navigation handler. Net
effect: clicking a link in a message body did nothing. Empirically confirmed —
see §5.

- **Fix (shipped):** the hash-pinned in-frame forwarder now also `preventDefault`s
  a genuine (`isTrusted`) anchor click and posts the resolved `href` to the
  parent ([mailFrame.ts](../frontend/src/lib/mail/mailFrame.ts)). The parent
  ([MessageContent.svelte](../frontend/src/lib/components/message-detail/MessageContent.svelte))
  validates `event.origin === 'null'` + `event.source` + a protocol allow-list
  (`isOpenableMailLink`: `http/https/mailto/tel`, mirroring the sanitizer) before
  opening the URL in the OS browser via the already-granted `shell:allow-open`.
  The sandbox is unchanged; the CSP hash was recomputed. This works for
  keyboard/screen-reader activation too (Enter on a focused link fires a trusted
  click).
- **Regression cover:** `isMailFrameLinkMessage` / `isOpenableMailLink` unit
  tests + the trusted-click e2e
  [links.functional.e2e.ts](../frontend/src/routes/mail/links.functional.e2e.ts)
  (asserts the relay fires and the frame does not navigate).

### F2 — Medium (functional): embedded `cid:` images never render — **FIXED 2026-07-08**

[MimePartExtractor.extractText](../backend/src/main/java/org/voxrox/mailbackend/util/MimePartExtractor.java)
returned the `text/html` part verbatim and never walked `multipart/related` to
inline `cid:` references. Combined with the (intentional) dropping of remote
images, HTML mail displayed essentially no images — embedded newsletter
graphics and logos in HTML signatures showed as missing. This is below the
baseline of every mature client: Outlook / Gmail / Apple Mail / Thunderbird
**always render embedded images** (local, no network) and only block *remote*
images by default.

- **Fix (shipped):** `MimePartExtractor.collectInlineImages` walks the MIME tree
  and inlines embedded images as `data:` URIs keyed by normalized Content-ID;
  [HtmlSanitizer](../backend/src/main/java/org/voxrox/mailbackend/util/HtmlSanitizer.java)`.sanitize(html, inlineImages)`
  rewrites each `cid:` reference to its data URI (and drops any unmatched one, so
  no dead `cid:` reaches the client). Guards: **raster types only** (gif/png/
  jpeg/webp/bmp — SVG excluded, it can carry script) and **byte caps** (2 MiB per
  image, 8 MiB per message) so the inlined base64 cannot bloat the SQLite
  `content` column unbounded. The frontend already accepts `data:image/*` +
  frame CSP `img-src data:`, so no frontend change was needed.
- **Regression cover:** `MimePartExtractorTest` (raster inlined, SVG rejected,
  oversized skipped) + `HtmlSanitizerTest` (cid resolved from map / dropped when
  absent).
- **Remaining (deferred post-beta):** remote images stay blocked by default with
  **no opt-in**. The best-practice target is Outlook's model — block by default +
  an explicit per-message "load images" gesture (+ per-sender allow-list). Filed
  as a separate post-beta task; the safe default (blocked) ships for the beta.

### F3 — Low (fidelity): plain-text bodies routed through an HTML sanitizer

A pure `text/plain` body is passed to `HtmlSanitizer.sanitize`, which parses
it as HTML and wraps it in the content div. Literal `<...>` sequences (code
snippets, `a<b and c>d`) can be interpreted as tags and dropped, mangling the
displayed text. No security impact.

### Note — remove or implement the backend `cid:` allowance

`Safelist … .addProtocols("img", "src", "cid", "data")` in HtmlSanitizer keeps
`cid:` sources that nothing downstream can render (see F2). Either implement
cid inlining (F2 fix) or drop the `cid` protocol so the policy reflects reality.

## 4. Recommendations

- **Security:** close this subsystem as **PASS**; add a change-log pointer from
  the threat model (Boundary 4) to this audit.
- **Release:** F1 (dead links) and F2 (embedded images) are **fixed**; both keep
  the sandbox/CSP posture unchanged. The remote-image opt-in (Outlook model) is
  deferred post-beta — the safe default (remote blocked) ships for the beta.
- **Regression cover:** [links.functional.e2e.ts](../frontend/src/routes/mail/links.functional.e2e.ts)
  guards the link bridge; `MimePartExtractorTest` + `HtmlSanitizerTest` guard cid
  inlining; keep [sanitizer.functional.e2e.ts](../frontend/src/routes/mail/sanitizer.functional.e2e.ts)
  and [mailFrame.test.ts](../frontend/src/lib/mail/mailFrame.test.ts) as the XSS/CSP guards.

## 5. F1 verification evidence

Run in the production build served by `frontend-e2e-preview` (Chromium engine,
same family as the WebView2 runtime the app ships on).

**Root-cause probe** — an iframe with the exact shipping config
`sandbox="allow-scripts"` (no `allow-popups`), whose in-frame script attempts a
`_blank` navigation:

```
{ windowOpen: "blocked",           // window.open('_blank') returned null
  linkClick: "clicked-no-throw",   // <a target="_blank"> click silently did nothing
  opaqueOrigin: true }             // frame origin === "null"
```

**Real component** — opening message `msg-01` renders the shipping mail frame:

```
sandbox = "allow-scripts"                         // no allow-popups
csp     = "default-src 'none'; img-src data:; script-src 'sha256-…'; … form-action 'none'"
anchor  = <a href="https://example.com/safe" target="_blank" rel="noopener noreferrer nofollow">
```

The real link is `target="_blank"` inside the exact sandbox proven to block
`_blank` navigation, with no bridge to open it → **links do not open. Confirmed.**

> Note: the preview runs in Chromium, not the packaged WebView2 build. Sandbox
> popup-blocking is a web-platform invariant shared by both, but a 30-second
> manual click in the packaged app during release smoke is the final sign-off.

**Fix verified:** [links.functional.e2e.ts](../frontend/src/routes/mail/links.functional.e2e.ts)
drives a trusted click through the opaque frame and asserts the forwarder relays
the href out (`__voxroxMailFrameLink`) while the app does not navigate — the
bridge restores working links without changing the sandbox.

## 6. References

- [SECURITY_THREAT_MODEL.md](../SECURITY_THREAT_MODEL.md) — Boundary 4 (WebView ↔ SPA).
- [backend/SECURITY_RELEASE_CHECK.md](../backend/SECURITY_RELEASE_CHECK.md) — per-release security gate.
- Chokepoints: [HtmlSanitizer.java](../backend/src/main/java/org/voxrox/mailbackend/util/HtmlSanitizer.java),
  [content-sanitizer.ts](../frontend/src/lib/mail/content-sanitizer.ts),
  [mailFrame.ts](../frontend/src/lib/mail/mailFrame.ts).
