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

export interface GlobalShortcutHandlers {
	openPalette: () => void;
	isPaletteOpen: () => boolean;
	goToPrimaryNewAction: () => Promise<void> | void;
	goToWorkspace: (mode: WorkspaceMode) => Promise<void> | void;
	/** Whether a message is open, which is what the message actions below need. */
	isMessageOpen: () => boolean;
	reply: () => void;
	replyAll: () => void;
	forward: () => void;
	toggleFlag: () => void;
	/**
	 * Marks the open message read or unread. Not a toggle: Ctrl+Q and Ctrl+U each
	 * name a direction, and deriving it from the message's current state made the
	 * press depend on a flag that arrives late (see the switch below).
	 */
	setSeen: (seen: boolean) => void;
	deleteMessage: () => void;
	/** Prints the open message, or says why it cannot yet. */
	printOpenMessage: () => void;
	/** Ctrl+P with no message open and nothing ticked: say so instead of printing the screen. */
	announceNothingToPrint: () => void;
	/** Whether focus is inside the open message: its header, toolbar or body. */
	isFocusInOpenMessage: () => boolean;
	/** Whether the mail list in view has ticked rows to print. */
	hasPrintableSelection: () => boolean;
	printSelection: () => void;
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
 *  0) A closer handler already claimed the key (defaultPrevented) → stay out.
 *  1) Ctrl/Cmd+K opens the palette (even in inputs).
 *  2) Palette open → handler hands the key to the palette component.
 *  3) Ctrl+1/2/3 (workspace), Ctrl+N (new item) and Ctrl+P (print the open
 *     message or the ticked rows) — also in inputs.
 *  4) Cursor in an editable element → handler stays out.
 *  5) Outlook-style actions on the open message (reply, forward, flag, …).
 */
export function handleGlobalKeydown(event: KeyboardEvent, handlers: GlobalShortcutHandlers): void {
	/*
	 * This runs on the window, so every component on the path has seen the key
	 * first. One that calls preventDefault has claimed it — the message-list grid
	 * does for Delete on a focused row — and acting on it here as well would do
	 * the thing twice. That holds for the window-level commands too: "they work
	 * wherever the cursor is" means no editable field swallows them, not that a
	 * component cannot bind one of them on purpose (Outlook's compose binds
	 * Ctrl+K to insert a link). Nothing in the app claims Ctrl+K, Ctrl+P, Ctrl+N
	 * or Ctrl+1-3 today, and bits-ui menus skip modified keys in their typeahead.
	 */
	if (event.defaultPrevented) return;

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

	/*
	 * Workspace switching and "new item" are window-level commands in Outlook:
	 * they work wherever the cursor is, and neither combination types or edits
	 * text. So — like Ctrl+K above — they run before the editable-target bail;
	 * otherwise a cursor parked in the search box, a settings field or a compose
	 * field silently swallowed the keystroke and the app stayed put. Leaving a
	 * half-written message this way is still guarded by the compose leave guard.
	 */
	if (handleWorkspaceShortcut(event, handlers)) return;

	/*
	 * Print acts on messages: the open one, or the rows ticked in the list. What
	 * people print from a mail client is mail, and printing whatever screen was
	 * showing put folder lists and settings pages on paper. When both exist,
	 * focus decides — in the open message it prints that message, anywhere else
	 * the ticked rows, which are what a reader in the list is working with. An
	 * open message whose body is still loading, or failed to load, is not
	 * printed as its placeholder; printOpenMessage says why instead. With
	 * neither the key prints nothing and says so — a silent no-op would leave a
	 * screen-reader user unsure the key landed — and still prevents the default,
	 * so the webview does not print the screen in the app's place. The webview's
	 * context menu has no Print entry either (webview_defaults.rs), so the mouse
	 * cannot print more than the keyboard.
	 *
	 * It sits here rather than among the open-message shortcuts below because
	 * it is ahead of the editable bail: printing is not typing, and a cursor
	 * left in the search box while a message is open should not swallow it.
	 */
	if (
		(event.ctrlKey || event.metaKey) &&
		!event.altKey &&
		!event.shiftKey &&
		event.key.toLowerCase() === 'p'
	) {
		event.preventDefault();
		const messageOpen = handlers.isMessageOpen();
		if (messageOpen && handlers.isFocusInOpenMessage()) handlers.printOpenMessage();
		else if (handlers.hasPrintableSelection()) handlers.printSelection();
		else if (messageOpen) handlers.printOpenMessage();
		else handlers.announceNothingToPrint();
		return;
	}

	if (isEditableTarget(event.target)) return;

	/*
	 * Outlook-style actions on the open message. They run only when a message
	 * is open (isMessageOpen). Several of these
	 * (Ctrl+R, Ctrl+Shift+R, Ctrl+F, Ctrl+U) shadow native webview behaviour
	 * (reload, find, view-source); preventDefault keeps the webview from reacting.
	 */
	if (handlers.isMessageOpen()) handleMessageShortcut(event, handlers);
}

/**
 * Ctrl+N and Ctrl+1/2/3. Returns true when the keystroke was consumed. `code`
 * (not `key`) keeps these working on layouts whose top-row digits type letters
 * instead — see the Czech layout case in boot.functional.e2e.ts.
 */
function handleWorkspaceShortcut(event: KeyboardEvent, handlers: GlobalShortcutHandlers): boolean {
	if (!event.ctrlKey || event.altKey || event.metaKey || event.shiftKey) return false;

	if (event.code === 'KeyN') {
		event.preventDefault();
		void handlers.goToPrimaryNewAction();
		return true;
	}

	switch (event.code) {
		case 'Digit1':
		case 'Numpad1':
			event.preventDefault();
			void handlers.goToWorkspace('mail');
			return true;
		case 'Digit2':
		case 'Numpad2':
			event.preventDefault();
			void handlers.goToWorkspace('contacts');
			return true;
		case 'Digit3':
		case 'Numpad3':
			event.preventDefault();
			void handlers.goToWorkspace('settings');
			return true;
		default:
			return false;
	}
}

/**
 * Outlook-compatible shortcuts for the open message. Returns true when the
 * keystroke was consumed. Mark-as-read/unread are split across Ctrl+Q and
 * Ctrl+U (matching Outlook); each asks for its own direction and consumes the
 * key, so the webview never reacts.
 */
function handleMessageShortcut(event: KeyboardEvent, handlers: GlobalShortcutHandlers): boolean {
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
		/*
		 * Both ask for a state rather than for a change, and neither checks
		 * whether the message is in it already. The check used to be here, reading
		 * the open message's `seen` flag — and opening a message marks it read
		 * through a request that patches that flag only once it returns, so Ctrl+U
		 * pressed in that window read "already unread", dropped the press without
		 * a sound, and the message then settled as read. Asking for the state the
		 * key names cannot go stale; a message already in it costs one idempotent
		 * request and gains the announcement that the press did something.
		 */
		case 'q': // Mark as read.
			event.preventDefault();
			handlers.setSeen(true);
			return true;
		case 'u': // Mark as unread (shadows view-source).
			event.preventDefault();
			handlers.setSeen(false);
			return true;
		default:
			return false;
	}
}
