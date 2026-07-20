/**
 * Application-wide keyboard shortcuts — extracted from the root
 * `+layout.svelte` so the routing logic can be unit-tested without a DOM
 * bootstrap.
 *
 * The layout supplies callbacks (open the palette, navigate); this module
 * just wires them to specific key combinations.
 *
 * When a shortcut changes here, update its label in `shortcutLabels.ts`
 * (rendered by Settings › Shortcuts and the command palette) and the
 * hand-maintained scope rows in routes/settings/shortcuts/+page.svelte.
 */
import type { WorkspaceMode } from '$lib/stores/workspaceMode.js';

/** Context for the message-action shortcuts; null when no message is open. */
export interface MessageShortcutContext {
	/** Whether the open message is currently marked as read. */
	seen: boolean;
}

export interface GlobalShortcutHandlers {
	openPalette: () => void;
	isPaletteOpen: () => boolean;
	goToShortcuts: () => Promise<void> | void;
	goToPrimaryNewAction: () => Promise<void> | void;
	goToWorkspace: (mode: WorkspaceMode) => Promise<void> | void;
	/** Returns the open-message context, or null when no message is open. */
	getMessageShortcutContext: () => MessageShortcutContext | null;
	reply: () => void;
	replyAll: () => void;
	forward: () => void;
	toggleFlag: () => void;
	toggleSeen: () => void;
	deleteMessage: () => void;
}

/** Returns true if the target is input-like and should receive the key instead of the handler. */
export function isEditableTarget(target: EventTarget | null): boolean {
	if (!(target instanceof HTMLElement)) return false;
	return (
		target.isContentEditable ||
		target.closest('[contenteditable="true"]') !== null ||
		target instanceof HTMLInputElement ||
		target instanceof HTMLTextAreaElement ||
		target instanceof HTMLSelectElement
	);
}

/**
 * Global keydown handler. Hierarchy:
 *  1) Ctrl/Cmd+K opens the palette (even in inputs).
 *  2) Palette open → handler hands the key to the palette component.
 *  3) Cursor in an editable element → handler stays out.
 *  4) `?` (no modifiers) → Settings › Shortcuts.
 *  5) Outlook-style actions on the open message (reply, forward, flag, …).
 *  6) Ctrl+N → new message / new contact depending on the workspace mode.
 *  7) Ctrl+1/2/3 → switch the workspace mode.
 */
export function handleGlobalKeydown(event: KeyboardEvent, handlers: GlobalShortcutHandlers): void {
	if (
		(event.ctrlKey || event.metaKey) &&
		!event.altKey &&
		!event.shiftKey &&
		event.key.toLowerCase() === 'k'
	) {
		event.preventDefault();
		handlers.openPalette();
		return;
	}

	if (handlers.isPaletteOpen()) return;
	if (isEditableTarget(event.target)) return;

	const isShortcutHelpKey = event.key === '?' || (event.shiftKey && event.code === 'Slash');
	if (!event.ctrlKey && !event.metaKey && !event.altKey && isShortcutHelpKey) {
		event.preventDefault();
		void handlers.goToShortcuts();
		return;
	}

	/*
	 * Outlook-style actions on the open message. They run only when a message
	 * is open (getMessageShortcutContext returns non-null) and the keystroke
	 * wasn't already handled by a closer handler — the message-list grid owns
	 * Delete/Enter/arrows on a focused row and calls preventDefault, so we bail
	 * on event.defaultPrevented to avoid acting twice. Several of these (Ctrl+R,
	 * Ctrl+Shift+R, Ctrl+F, Ctrl+U) shadow native webview behaviour (reload,
	 * find, view-source); preventDefault keeps the webview from reacting.
	 */
	if (!event.defaultPrevented) {
		const messageCtx = handlers.getMessageShortcutContext();
		if (messageCtx && handleMessageShortcut(event, messageCtx, handlers)) return;
	}

	if (!event.ctrlKey || event.altKey || event.metaKey || event.shiftKey) return;

	if (event.code === 'KeyN') {
		event.preventDefault();
		void handlers.goToPrimaryNewAction();
		return;
	}

	switch (event.code) {
		case 'Digit1':
		case 'Numpad1':
			event.preventDefault();
			void handlers.goToWorkspace('mail');
			break;
		case 'Digit2':
		case 'Numpad2':
			event.preventDefault();
			void handlers.goToWorkspace('contacts');
			break;
		case 'Digit3':
		case 'Numpad3':
			event.preventDefault();
			void handlers.goToWorkspace('settings');
			break;
	}
}

/**
 * Outlook-compatible shortcuts for the open message. Returns true when the
 * keystroke was consumed. Mark-as-read/unread are split across Ctrl+Q and
 * Ctrl+U (matching Outlook) and become no-ops when the message is already in
 * the requested state, but still consume the key so the webview never reacts.
 */
function handleMessageShortcut(
	event: KeyboardEvent,
	ctx: MessageShortcutContext,
	handlers: GlobalShortcutHandlers
): boolean {
	// Delete (no modifiers) → delete the open message.
	if (
		event.key === 'Delete' &&
		!event.ctrlKey &&
		!event.metaKey &&
		!event.altKey &&
		!event.shiftKey
	) {
		event.preventDefault();
		handlers.deleteMessage();
		return true;
	}

	const ctrl = event.ctrlKey || event.metaKey;
	if (!ctrl || event.altKey) return false;
	const key = event.key.toLowerCase();

	// Ctrl+R reply, Ctrl+Shift+R reply all (both shadow the webview reload).
	if (key === 'r') {
		event.preventDefault();
		if (event.shiftKey) handlers.replyAll();
		else handlers.reply();
		return true;
	}

	if (event.shiftKey) {
		// Ctrl+Shift+G → toggle the follow-up flag.
		if (key === 'g') {
			event.preventDefault();
			handlers.toggleFlag();
			return true;
		}
		return false;
	}

	switch (key) {
		case 'f': // Forward (shadows the webview find bar).
			event.preventDefault();
			handlers.forward();
			return true;
		case 'q': // Mark as read — no-op if already read.
			event.preventDefault();
			if (!ctx.seen) handlers.toggleSeen();
			return true;
		case 'u': // Mark as unread (shadows view-source) — no-op if already unread.
			event.preventDefault();
			if (ctx.seen) handlers.toggleSeen();
			return true;
		default:
			return false;
	}
}
