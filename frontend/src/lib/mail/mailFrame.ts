/**
 * Builds the `srcdoc` for the message-body iframe and bridges keyboard
 * shortcuts back out of it.
 *
 * The body renders in a sandboxed iframe so hostile mail HTML cannot script
 * the app (Boundary 4 of the threat model). A pure `sandbox=""` frame, however,
 * is a keyboard black hole: a `keydown` inside a nested browsing context never
 * reaches the parent's global shortcut handler, so Delete, Ctrl+R, … are
 * silently swallowed while the user reads a message.
 *
 * Fix without weakening isolation: the frame gets `sandbox="allow-scripts"`
 * (scripts on, but NO `allow-same-origin` → the document stays in an opaque
 * origin with no access to the parent, cookies, storage, or same-origin
 * network) plus a `<meta>` CSP that allows exactly ONE script — this
 * first-party key forwarder, pinned by hash. Every other script (i.e. anything
 * the sanitizer ever missed in the mail body) is blocked by the hash mismatch,
 * so the body still cannot run its own code. The forwarder relays only genuine
 * (`isTrusted`) user keystrokes via `postMessage`; the parent re-dispatches them
 * as synthetic keydowns so the existing `handleGlobalKeydown` reacts.
 *
 * The same forwarder also relays body-link clicks: an opaque-origin
 * `sandbox="allow-scripts"` frame has no `allow-popups`, so a `target="_blank"`
 * link is blocked by the engine and clicking it does nothing. The forwarder
 * `preventDefault`s a genuine anchor click and posts the resolved `href` to the
 * parent, which validates it — the protocol, and that it leads out of the app
 * rather than back into it (see `isOpenableMailLink`) — and opens it in the OS
 * browser via `shell:allow-open`, restoring working links without granting the
 * frame any navigation or popup capability.
 *
 * The CSP hash is over the exact bytes of MAIL_FRAME_SCRIPT; mailFrame.test.ts
 * recomputes it so any edit to the script that forgets to update the hash (which
 * would silently break every shortcut) fails the build.
 */

import { REMOTE_IMAGE_ATTR, sanitizeMailHtml } from './content-sanitizer.js';

/**
 * The only script allowed to run inside the mail-body frame. Kept on one line
 * so its bytes — and therefore its CSP hash — are stable and easy to reproduce.
 * Forwards real keystrokes and body-link clicks to the parent; it can do nothing
 * else (opaque origin, `default-src 'none'`).
 *
 * It also has to cancel the browser default for the combinations the app claims,
 * and that is not something the parent can do for it. Relaying a keystroke means
 * the parent reacts to a *synthetic* replay, and `preventDefault` on a synthetic
 * event cancels nothing — the genuine event stayed in the frame, unprevented, so
 * the webview ran its own accelerator as well. With the reader's focus in the
 * body that made Ctrl+Shift+G both flag the message and open the webview's find
 * bar, which is what the reader actually heard; Ctrl+R, Ctrl+F and Ctrl+U shadow
 * reload, find and view-source the same way. `handleMessageShortcut` says it
 * consumes these keys "so the webview never reacts" — this is where that promise
 * is kept for the frame.
 *
 * The claimed set mirrors `handleGlobalKeydown`, and mirrors how it matches:
 * by `key` for the letters and by `code` for Ctrl+N and the workspace digits,
 * which must survive layouts whose top row types letters. Editing keys are
 * deliberately absent: Ctrl+C and Ctrl+A have to keep working on the message
 * text.
 *
 * Shift splits the set, because it splits it in the parent too. With Shift the
 * app claims only Ctrl+Shift+R (reply all) and Ctrl+Shift+G (flag); without it,
 * Ctrl+K, Ctrl+R, Ctrl+F, Ctrl+Q, Ctrl+U and the `code`-matched Ctrl+N and
 * Ctrl+1-3 — every one of which `handleGlobalKeydown` matches under
 * `!event.shiftKey`. One letter list for both cases therefore cancelled
 * defaults the app never claims: while the reading cursor was in the body,
 * Ctrl+G (find next) and Ctrl+Shift+F/Q/U/K/N/1-3 did nothing at all — the
 * frame swallowed them and the parent ignored the relay.
 *
 * Meta folds into Ctrl for the letters and not for the codes, again mirroring
 * the parent: `handleMessageShortcut` reads `ctrlKey || metaKey`, while
 * `handleWorkspaceShortcut` bails on `metaKey`.
 */
export const MAIL_FRAME_SCRIPT =
	'window.addEventListener("keydown",function(e){if(!e.isTrusted)return;if(!e.altKey&&(e.ctrlKey||e.metaKey)&&(e.shiftKey?/^[rg]$/i.test(e.key):/^[krfqu]$/i.test(e.key)||e.ctrlKey&&!e.metaKey&&/^(KeyN|Digit[123]|Numpad[123])$/.test(e.code)))e.preventDefault();window.parent.postMessage({__voxroxMailFrameKey:true,key:e.key,code:e.code,ctrlKey:e.ctrlKey,metaKey:e.metaKey,altKey:e.altKey,shiftKey:e.shiftKey},"*");});window.addEventListener("click",function(e){if(!e.isTrusted)return;var a=e.target.closest?e.target.closest("a[href]"):null;if(!a)return;e.preventDefault();window.parent.postMessage({__voxroxMailFrameLink:true,href:a.href},"*");});';

/** Base64 SHA-256 of MAIL_FRAME_SCRIPT — asserted in mailFrame.test.ts. */
export const MAIL_FRAME_SCRIPT_SHA256 = 'edMSNLAP5VI76mC/e7yROrEhT2jCiiQXbPLr59xLyA0=';

/**
 * Base stylesheet for the mail body. The sanitizer strips every style element
 * and attribute from the mail, so this is the ONLY styling in the frame — and
 * it deliberately stays light in both app themes: mail HTML is authored against
 * a white background, so rendering it on the app's dark background makes
 * default-black text unreadable. Colors are the hex equivalents of the app's
 * light-theme tokens (foreground, primary, border, muted-foreground). Kept on
 * one line so its bytes — and therefore its CSP hash — stay stable.
 */
export const MAIL_FRAME_STYLE =
	':root{color-scheme:light}body{margin:12px;background:#ffffff;color:#0b1219;font-family:system-ui,sans-serif;font-size:14px;line-height:1.6;overflow-wrap:break-word}a{color:#00566b}img{max-width:100%;height:auto}table{max-width:100%;border-collapse:collapse}blockquote{margin:8px 0 8px 4px;padding-left:12px;border-left:3px solid #dae0e8;color:#4b5763}pre{white-space:pre-wrap}';

/** Base64 SHA-256 of MAIL_FRAME_STYLE — asserted in mailFrame.test.ts. */
export const MAIL_FRAME_STYLE_SHA256 = 'UXLUJbZ21yq1eqQCljjFZwc0mejRk10+TVL8FCWZ+C0=';

/**
 * CSP enforced inside the frame: nothing loads by default, inline images stay
 * (the sanitizer already restricts them to `data:`), the only executable script
 * is the hash-pinned forwarder above, and the only stylesheet is the hash-pinned
 * base style — anything the sanitizer ever missed still cannot style or script
 * the frame.
 *
 * `img-src` is the ONLY direction that relaxes, and only when the user has
 * explicitly opted into loading remote images for the message (audit F2): it
 * then also allows `https:` so preserved `data-voxrox-remote-src` images can
 * load. Everything else — script/style/`default-src`, the opaque-origin sandbox
 * — stays locked; `http` (cleartext) images are never allowed.
 */
export function mailFrameCsp(loadRemoteImages = false): string {
	const imgSrc = loadRemoteImages ? 'img-src data: https:' : 'img-src data:';
	return (
		`default-src 'none'; ${imgSrc}; script-src 'sha256-${MAIL_FRAME_SCRIPT_SHA256}'; ` +
		`style-src 'sha256-${MAIL_FRAME_STYLE_SHA256}'; base-uri 'none'; form-action 'none'`
	);
}

/**
 * Promotes each preserved remote image (`data-voxrox-remote-src`) to a real
 * `src` on the already-sanitized body. Only called when the user has opted in;
 * re-parsing sanitized (script-free) HTML is safe.
 */
function promoteRemoteImages(sanitizedHtml: string): string {
	if (typeof DOMParser === 'undefined') return sanitizedHtml;
	const doc = new DOMParser().parseFromString(sanitizedHtml, 'text/html');
	doc.querySelectorAll(`img[${REMOTE_IMAGE_ATTR}]`).forEach((img) => {
		const url = img.getAttribute(REMOTE_IMAGE_ATTR);
		if (url) {
			img.setAttribute('src', url);
			img.removeAttribute(REMOTE_IMAGE_ATTR);
		}
	});
	return doc.body.innerHTML;
}

/**
 * Number of blocked remote images in the message (counted on the sanitized body
 * so it matches exactly what the frame would show). Drives the "N blocked
 * images" opt-in banner.
 */
export function countRemoteImages(rawHtml: string): number {
	if (typeof DOMParser === 'undefined') return 0;
	const doc = new DOMParser().parseFromString(sanitizeMailHtml(rawHtml), 'text/html');
	return doc.querySelectorAll(`img[${REMOTE_IMAGE_ATTR}]`).length;
}

/**
 * Wraps sanitized mail HTML into the full sandboxed document served via srcdoc.
 * `loadRemoteImages` promotes preserved remote images to real `src`s and relaxes
 * the frame CSP `img-src` to allow `https:`; a `no-referrer` meta keeps any such
 * load from leaking a referrer.
 */
export function buildMailFrameSrcdoc(
	rawHtml: string,
	opts: { loadRemoteImages?: boolean } = {}
): string {
	const loadRemoteImages = opts.loadRemoteImages ?? false;
	let body = sanitizeMailHtml(rawHtml);
	if (loadRemoteImages) {
		body = promoteRemoteImages(body);
	}
	return (
		'<!doctype html><html><head><meta charset="utf-8">' +
		'<meta name="referrer" content="no-referrer">' +
		`<meta http-equiv="Content-Security-Policy" content="${mailFrameCsp(loadRemoteImages)}">` +
		`<style>${MAIL_FRAME_STYLE}</style>` +
		`<script>${MAIL_FRAME_SCRIPT}</script>` +
		`</head><body>${body}</body></html>`
	);
}

/** Shape of the message the frame forwarder posts to the parent. */
export interface MailFrameKeyMessage {
	__voxroxMailFrameKey: true;
	key: string;
	code: string;
	ctrlKey: boolean;
	metaKey: boolean;
	altKey: boolean;
	shiftKey: boolean;
}

/** Narrowing guard for an untrusted `MessageEvent.data`. */
export function isMailFrameKeyMessage(data: unknown): data is MailFrameKeyMessage {
	if (typeof data !== 'object' || data === null) return false;
	const d = data as Record<string, unknown>;
	return (
		d.__voxroxMailFrameKey === true &&
		typeof d.key === 'string' &&
		typeof d.code === 'string' &&
		typeof d.ctrlKey === 'boolean' &&
		typeof d.metaKey === 'boolean' &&
		typeof d.altKey === 'boolean' &&
		typeof d.shiftKey === 'boolean'
	);
}

/** Shape of the message the frame forwarder posts when a body link is clicked. */
export interface MailFrameLinkMessage {
	__voxroxMailFrameLink: true;
	href: string;
}

/** Narrowing guard for an untrusted `MessageEvent.data`. */
export function isMailFrameLinkMessage(data: unknown): data is MailFrameLinkMessage {
	if (typeof data !== 'object' || data === null) return false;
	const d = data as Record<string, unknown>;
	return d.__voxroxMailFrameLink === true && typeof d.href === 'string';
}

/**
 * Protocols the parent will hand to the OS opener. Mirrors the sanitizer's
 * `allowedUriProtocols` (content-sanitizer.ts) so a forwarded href can only ever
 * reach `shell:allow-open` for the same safe schemes the body was allowed to
 * carry — never `file:`, `javascript:`, `about:srcdoc#…` fragments, or a custom
 * scheme, even if a compromised frame posted an arbitrary href.
 */
const OPENABLE_LINK_PROTOCOLS = new Set(['http:', 'https:', 'mailto:', 'tel:']);

/**
 * True when a forwarded body-link href is safe to open in the OS browser.
 *
 * The protocol allow-list is not enough on its own, because the href arrives
 * already resolved: the forwarder posts `a.href`, and a `srcdoc` document does
 * NOT resolve a relative href against `about:srcdoc` — it inherits the base URL
 * of the container document (HTML: fallback base URL). Verified in Chromium:
 * inside a `sandbox="allow-scripts"` srcdoc frame whose parent is
 * `https://host/mail/1/INBOX/msg-01`, `href="#"` arrives as
 * `https://host/mail/1/INBOX/msg-01#` and `href="/foo"` as `https://host/foo`.
 * Both carry an allowed protocol, so without the origin test below a bare
 * `href="#"` — what the decorative half of marketing mail is built from — would
 * hand the app's own URL to the OS browser, and a relative href would let a
 * message pick any path on the app origin to open there.
 *
 * Refusing the app's own origin is the whole fix, and it is also the honest
 * verdict: an in-message anchor has no target either, since the sanitizer keeps
 * no `id` on any element. `origin` is the opaque `"null"` for `mailto:` and
 * `tel:`, which therefore never collide with the app's.
 *
 * `appOrigin` is a parameter for the same reason `sanitizeMailHtml` takes one —
 * the check has to be testable without a window, and the two must agree on
 * which origin is "ours".
 */
export function isOpenableMailLink(href: string, appOrigin?: string): boolean {
	const ownOrigin =
		appOrigin ?? (typeof window === 'undefined' ? 'http://localhost' : window.location.origin);
	try {
		const url = new URL(href);
		return OPENABLE_LINK_PROTOCOLS.has(url.protocol) && url.origin !== ownOrigin;
	} catch {
		return false;
	}
}

/**
 * Rebuilds a synthetic `keydown` from a forwarded message. Dispatched on
 * `window` so the app's global handler treats it exactly like a keystroke that
 * happened in the app chrome. (Synthetic events have `isTrusted === false`, so
 * they can never loop back through the frame forwarder.)
 */
export function mailFrameKeyToEvent(message: MailFrameKeyMessage): KeyboardEvent {
	return new KeyboardEvent('keydown', {
		key: message.key,
		code: message.code,
		ctrlKey: message.ctrlKey,
		metaKey: message.metaKey,
		altKey: message.altKey,
		shiftKey: message.shiftKey,
		bubbles: true,
		cancelable: true
	});
}
