/**
 * Builds the `srcdoc` for the message-body iframe and bridges keyboard
 * shortcuts back out of it.
 *
 * The body renders in a sandboxed iframe so hostile mail HTML cannot script
 * the app (Boundary 4 of the threat model). A pure `sandbox=""` frame, however,
 * is a keyboard black hole: a `keydown` inside a nested browsing context never
 * reaches the parent's global shortcut handler, so `?`, Delete, Ctrl+R, … are
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
 * parent, which validates the protocol and opens it in the OS browser via
 * `shell:allow-open` — restoring working links without granting the frame any
 * navigation or popup capability.
 *
 * The CSP hash is over the exact bytes of MAIL_FRAME_SCRIPT; mailFrame.test.ts
 * recomputes it so any edit to the script that forgets to update the hash (which
 * would silently break every shortcut) fails the build.
 */

import { sanitizeMailHtml } from './content-sanitizer.js';

/**
 * The only script allowed to run inside the mail-body frame. Kept on one line
 * so its bytes — and therefore its CSP hash — are stable and easy to reproduce.
 * Forwards real keystrokes and body-link clicks to the parent; it can do nothing
 * else (opaque origin, `default-src 'none'`).
 */
export const MAIL_FRAME_SCRIPT =
	'window.addEventListener("keydown",function(e){if(!e.isTrusted)return;window.parent.postMessage({__voxroxMailFrameKey:true,key:e.key,code:e.code,ctrlKey:e.ctrlKey,metaKey:e.metaKey,altKey:e.altKey,shiftKey:e.shiftKey},"*");});window.addEventListener("click",function(e){if(!e.isTrusted)return;var a=e.target.closest?e.target.closest("a[href]"):null;if(!a)return;e.preventDefault();window.parent.postMessage({__voxroxMailFrameLink:true,href:a.href},"*");});';

/** Base64 SHA-256 of MAIL_FRAME_SCRIPT — asserted in mailFrame.test.ts. */
export const MAIL_FRAME_SCRIPT_SHA256 = 'P0dzBplLeG9MS+sbj54Edo5FqVN4odqRclMXa+orxyM=';

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
 * (the sanitizer already restricts them to `data:`), the only executable
 * script is the hash-pinned forwarder above, and the only stylesheet is the
 * hash-pinned base style — anything the sanitizer ever missed still cannot
 * style or script the frame.
 */
export const MAIL_FRAME_CSP =
	`default-src 'none'; img-src data:; script-src 'sha256-${MAIL_FRAME_SCRIPT_SHA256}'; ` +
	`style-src 'sha256-${MAIL_FRAME_STYLE_SHA256}'; base-uri 'none'; form-action 'none'`;

/** Wraps sanitized mail HTML into the full sandboxed document served via srcdoc. */
export function buildMailFrameSrcdoc(rawHtml: string): string {
	const body = sanitizeMailHtml(rawHtml);
	return (
		'<!doctype html><html><head><meta charset="utf-8">' +
		`<meta http-equiv="Content-Security-Policy" content="${MAIL_FRAME_CSP}">` +
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

/** True when a forwarded body-link href is safe to open in the OS browser. */
export function isOpenableMailLink(href: string): boolean {
	try {
		return OPENABLE_LINK_PROTOCOLS.has(new URL(href).protocol);
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
