// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest';
import {
	handleGlobalKeydown,
	isEditableTarget,
	type GlobalShortcutHandlers
} from './globalShortcuts.js';

function makeHandlers(overrides: Partial<GlobalShortcutHandlers> = {}): GlobalShortcutHandlers {
	return {
		openPalette: vi.fn(),
		isPaletteOpen: () => false,
		goToPrimaryNewAction: vi.fn(),
		goToWorkspace: vi.fn(),
		// No open message by default — message-action tests opt in via overrides.
		getMessageShortcutContext: () => null,
		reply: vi.fn(),
		replyAll: vi.fn(),
		forward: vi.fn(),
		toggleFlag: vi.fn(),
		toggleSeen: vi.fn(),
		deleteMessage: vi.fn(),
		...overrides
	};
}

function makeEvent(init: KeyboardEventInit & { code?: string }): KeyboardEvent {
	// Real keydown events are cancelable; without this jsdom leaves
	// defaultPrevented false even after preventDefault().
	const ev = new KeyboardEvent('keydown', { cancelable: true, ...init });
	// jsdom's KeyboardEvent ignores code in init in some versions — set explicitly:
	if (init.code !== undefined) Object.defineProperty(ev, 'code', { value: init.code });
	return ev;
}

describe('isEditableTarget', () => {
	it('null → false', () => {
		expect(isEditableTarget(null)).toBe(false);
	});

	it('plain div → false', () => {
		expect(isEditableTarget(document.createElement('div'))).toBe(false);
	});

	it('input / textarea / select → true', () => {
		expect(isEditableTarget(document.createElement('input'))).toBe(true);
		expect(isEditableTarget(document.createElement('textarea'))).toBe(true);
		expect(isEditableTarget(document.createElement('select'))).toBe(true);
	});

	it('contenteditable element → true', () => {
		const el = document.createElement('div');
		// `el.contentEditable = 'true'` in jsdom does not update isContentEditable
		// the same way the browser does — we test via attribute + closest.
		el.setAttribute('contenteditable', 'true');
		expect(isEditableTarget(el)).toBe(true);
	});

	it('descendant of contenteditable → true', () => {
		const parent = document.createElement('div');
		parent.setAttribute('contenteditable', 'true');
		const child = document.createElement('span');
		parent.appendChild(child);
		document.body.appendChild(parent);
		expect(isEditableTarget(child)).toBe(true);
		document.body.removeChild(parent);
	});
});

describe('handleGlobalKeydown', () => {
	it('Ctrl+K opens palette and preventDefault', () => {
		const h = makeHandlers();
		const ev = makeEvent({ key: 'k', ctrlKey: true });
		const prevent = vi.spyOn(ev, 'preventDefault');
		handleGlobalKeydown(ev, h);
		expect(h.openPalette).toHaveBeenCalledOnce();
		expect(prevent).toHaveBeenCalled();
	});

	it('Cmd+K (metaKey) also opens palette', () => {
		const h = makeHandlers();
		handleGlobalKeydown(makeEvent({ key: 'K', metaKey: true }), h);
		expect(h.openPalette).toHaveBeenCalledOnce();
	});

	it('Ctrl+K is intercepted even when target is an input', () => {
		const h = makeHandlers();
		const input = document.createElement('input');
		document.body.appendChild(input);
		const ev = makeEvent({ key: 'k', ctrlKey: true });
		Object.defineProperty(ev, 'target', { value: input });
		handleGlobalKeydown(ev, h);
		expect(h.openPalette).toHaveBeenCalled();
		document.body.removeChild(input);
	});

	it('palette open: handler exits before workspace shortcuts', () => {
		const h = makeHandlers({ isPaletteOpen: () => true });
		handleGlobalKeydown(makeEvent({ key: '1', ctrlKey: true, code: 'Digit1' }), h);
		expect(h.goToWorkspace).not.toHaveBeenCalled();
	});

	it('editable target: Ctrl+1/2/3 still switches the workspace', () => {
		const h = makeHandlers();
		const input = document.createElement('textarea');
		document.body.appendChild(input);
		const ev = makeEvent({ key: '1', ctrlKey: true, code: 'Digit1' });
		Object.defineProperty(ev, 'target', { value: input });
		const prevent = vi.spyOn(ev, 'preventDefault');
		handleGlobalKeydown(ev, h);
		expect(h.goToWorkspace).toHaveBeenCalledWith('mail');
		expect(prevent).toHaveBeenCalled();
		document.body.removeChild(input);
	});

	it('editable target: Ctrl+N still triggers the primary new action', () => {
		const h = makeHandlers();
		const input = document.createElement('input');
		document.body.appendChild(input);
		const ev = makeEvent({ key: 'n', ctrlKey: true, code: 'KeyN' });
		Object.defineProperty(ev, 'target', { value: input });
		handleGlobalKeydown(ev, h);
		expect(h.goToPrimaryNewAction).toHaveBeenCalledOnce();
		document.body.removeChild(input);
	});

	it('Ctrl+N triggers primary new action', () => {
		const h = makeHandlers();
		handleGlobalKeydown(makeEvent({ key: 'n', ctrlKey: true, code: 'KeyN' }), h);
		expect(h.goToPrimaryNewAction).toHaveBeenCalledOnce();
	});

	it('Ctrl+1/2/3 switches workspace mode', () => {
		const h = makeHandlers();
		handleGlobalKeydown(makeEvent({ key: '1', ctrlKey: true, code: 'Digit1' }), h);
		handleGlobalKeydown(makeEvent({ key: '2', ctrlKey: true, code: 'Digit2' }), h);
		handleGlobalKeydown(makeEvent({ key: '3', ctrlKey: true, code: 'Digit3' }), h);
		expect(h.goToWorkspace).toHaveBeenCalledWith('mail');
		expect(h.goToWorkspace).toHaveBeenCalledWith('contacts');
		expect(h.goToWorkspace).toHaveBeenCalledWith('settings');
	});

	it('Ctrl+Shift+1 is ignored (modifier mismatch)', () => {
		const h = makeHandlers();
		handleGlobalKeydown(makeEvent({ key: '1', ctrlKey: true, shiftKey: true, code: 'Digit1' }), h);
		expect(h.goToWorkspace).not.toHaveBeenCalled();
	});

	it('plain key without modifiers does nothing', () => {
		const h = makeHandlers();
		handleGlobalKeydown(makeEvent({ key: 'a' }), h);
		expect(h.openPalette).not.toHaveBeenCalled();
		expect(h.goToPrimaryNewAction).not.toHaveBeenCalled();
		expect(h.goToWorkspace).not.toHaveBeenCalled();
	});
});

describe('handleGlobalKeydown — message actions', () => {
	function openMessageHandlers(
		seen = false,
		overrides: Partial<GlobalShortcutHandlers> = {}
	): GlobalShortcutHandlers {
		return makeHandlers({ getMessageShortcutContext: () => ({ seen }), ...overrides });
	}

	it('Ctrl+R replies and prevents the default (reload)', () => {
		const h = openMessageHandlers();
		const ev = makeEvent({ key: 'r', ctrlKey: true });
		const prevent = vi.spyOn(ev, 'preventDefault');
		handleGlobalKeydown(ev, h);
		expect(h.reply).toHaveBeenCalledOnce();
		expect(h.replyAll).not.toHaveBeenCalled();
		expect(prevent).toHaveBeenCalled();
	});

	it('Ctrl+Shift+R replies to all', () => {
		const h = openMessageHandlers();
		handleGlobalKeydown(makeEvent({ key: 'r', ctrlKey: true, shiftKey: true }), h);
		expect(h.replyAll).toHaveBeenCalledOnce();
		expect(h.reply).not.toHaveBeenCalled();
	});

	it('Ctrl+F forwards and prevents the default (find)', () => {
		const h = openMessageHandlers();
		const ev = makeEvent({ key: 'f', ctrlKey: true });
		const prevent = vi.spyOn(ev, 'preventDefault');
		handleGlobalKeydown(ev, h);
		expect(h.forward).toHaveBeenCalledOnce();
		expect(prevent).toHaveBeenCalled();
	});

	it('Ctrl+Shift+G toggles the flag', () => {
		const h = openMessageHandlers();
		handleGlobalKeydown(makeEvent({ key: 'g', ctrlKey: true, shiftKey: true }), h);
		expect(h.toggleFlag).toHaveBeenCalledOnce();
	});

	it('Ctrl+Q marks an unread message as read', () => {
		const h = openMessageHandlers(false);
		handleGlobalKeydown(makeEvent({ key: 'q', ctrlKey: true }), h);
		expect(h.toggleSeen).toHaveBeenCalledOnce();
	});

	it('Ctrl+Q is a no-op when the message is already read (but consumes the key)', () => {
		const h = openMessageHandlers(true);
		const ev = makeEvent({ key: 'q', ctrlKey: true });
		const prevent = vi.spyOn(ev, 'preventDefault');
		handleGlobalKeydown(ev, h);
		expect(h.toggleSeen).not.toHaveBeenCalled();
		expect(prevent).toHaveBeenCalled();
	});

	it('Ctrl+U marks a read message as unread and prevents the default (view-source)', () => {
		const h = openMessageHandlers(true);
		const ev = makeEvent({ key: 'u', ctrlKey: true });
		const prevent = vi.spyOn(ev, 'preventDefault');
		handleGlobalKeydown(ev, h);
		expect(h.toggleSeen).toHaveBeenCalledOnce();
		expect(prevent).toHaveBeenCalled();
	});

	it('Ctrl+U is a no-op when the message is already unread', () => {
		const h = openMessageHandlers(false);
		handleGlobalKeydown(makeEvent({ key: 'u', ctrlKey: true }), h);
		expect(h.toggleSeen).not.toHaveBeenCalled();
	});

	it('Delete deletes the open message', () => {
		const h = openMessageHandlers();
		handleGlobalKeydown(makeEvent({ key: 'Delete' }), h);
		expect(h.deleteMessage).toHaveBeenCalledOnce();
	});

	it('does nothing when no message is open', () => {
		const h = makeHandlers(); // getMessageShortcutContext → null
		handleGlobalKeydown(makeEvent({ key: 'r', ctrlKey: true }), h);
		handleGlobalKeydown(makeEvent({ key: 'Delete' }), h);
		expect(h.reply).not.toHaveBeenCalled();
		expect(h.deleteMessage).not.toHaveBeenCalled();
	});

	it('defers to a closer handler that already called preventDefault', () => {
		const h = openMessageHandlers();
		const ev = makeEvent({ key: 'Delete' });
		ev.preventDefault(); // e.g. the message-list grid handled the focused row
		handleGlobalKeydown(ev, h);
		expect(h.deleteMessage).not.toHaveBeenCalled();
	});

	it('stays out of editable targets', () => {
		const h = openMessageHandlers();
		const input = document.createElement('input');
		document.body.appendChild(input);
		const ev = makeEvent({ key: 'r', ctrlKey: true });
		Object.defineProperty(ev, 'target', { value: input });
		handleGlobalKeydown(ev, h);
		expect(h.reply).not.toHaveBeenCalled();
		document.body.removeChild(input);
	});
});
