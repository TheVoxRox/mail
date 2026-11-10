import { describe, expect, it, vi } from 'vitest';

vi.mock('#lib/mail/actions.js', () => ({
	forwardMessage: vi.fn(),
	goToCompose: vi.fn(),
	goToSearch: vi.fn(),
	replyToMessage: vi.fn(),
	syncCurrentAccount: vi.fn()
}));
vi.mock('#lib/mail/printMessages.js', () => ({ printOpenMessage: vi.fn() }));
vi.mock('#lib/mail/mailbox.js', () => ({
	deleteMessages: vi.fn(),
	moveMessages: vi.fn(),
	toggleMessageFlag: vi.fn(),
	toggleMessageSeen: vi.fn()
}));

import { createMailCommands } from './mailCommands.js';
import type { PrintableSelection } from '#lib/mail/printMessages.js';

function commands(
	overrides: Partial<Parameters<typeof createMailCommands>[0]> = {}
): ReturnType<typeof createMailCommands> {
	return createMailCommands({
		activeAccountId: 1,
		folders: [],
		locale: 'cs',
		pathname: '/mail/1/INBOX',
		selectedDetail: null,
		stableId: null,
		printableSelection: null,
		remoteImageOffer: null,
		...overrides
	});
}

describe('createMailCommands — blocked remote images', () => {
	const open = { pathname: '/mail/1/INBOX/msg-02', stableId: 'msg-02' };

	it('offers to load the images only while the open message has some blocked', () => {
		expect(commands(open).some((command) => command.id === 'mail.loadRemoteImages')).toBe(false);

		const offer = { stableId: 'msg-02', load: vi.fn(), focus: vi.fn() };
		const entry = commands({ ...open, remoteImageOffer: offer }).find(
			(command) => command.id === 'mail.loadRemoteImages'
		);
		void entry?.run();
		expect(offer.load).toHaveBeenCalledOnce();
		// It moves focus to the body itself, so the palette must not restore it.
		expect(entry?.restoreFocus).toBeUndefined();
		// Ctrl+Shift+W goes to the banner; it does not load.
		expect(entry?.shortcut).toBeUndefined();
	});

	it('does not offer another message’s images for the one that is open', () => {
		const stale = { stableId: 'msg-01', load: vi.fn(), focus: vi.fn() };
		expect(
			commands({ ...open, remoteImageOffer: stale }).some(
				(command) => command.id === 'mail.loadRemoteImages'
			)
		).toBe(false);
	});
});

describe('createMailCommands — printing', () => {
	it('offers "print selection" only while rows are ticked, titled with the bar summary', () => {
		expect(commands().some((command) => command.id === 'mail.printSelection')).toBe(false);

		const selection: PrintableSelection = {
			summary: '3 selected messages',
			print: vi.fn(() => Promise.resolve())
		};
		const entry = commands({ printableSelection: selection }).find(
			(command) => command.id === 'mail.printSelection'
		);
		expect(entry?.titleValues).toEqual({ summary: '3 selected messages' });
		void entry?.run();
		expect(selection.print).toHaveBeenCalledOnce();
	});

	it('keeps "print message" and "print selection" apart when both apply', () => {
		const ids = commands({
			pathname: '/mail/1/INBOX/msg-01',
			stableId: 'msg-01',
			printableSelection: { summary: '1', print: () => Promise.resolve() }
		}).map((command) => command.id);
		expect(ids).toContain('mail.print');
		expect(ids).toContain('mail.printSelection');
	});
});

describe('createMailCommands — moving', () => {
	it('labels the move to the Archive folder with Backspace, and no other move', () => {
		const moves = commands({
			pathname: '/mail/1/INBOX/msg-01',
			stableId: 'msg-01',
			folders: [
				{
					displayName: 'Inbox',
					folderRef: 'INBOX',
					unreadCount: 0,
					role: 'INBOX',
					parentRef: null
				},
				{
					displayName: 'Archiv',
					folderRef: 'Archive',
					unreadCount: 0,
					role: 'ARCHIVE',
					parentRef: null
				},
				{
					displayName: 'Projekty',
					folderRef: 'Projects',
					unreadCount: 0,
					role: 'USER',
					parentRef: null
				}
			]
		}).filter((command) => command.id.startsWith('mail.move.'));
		expect(moves.map((command) => [command.id, command.shortcut])).toEqual([
			['mail.move.Archive', 'Backspace'],
			['mail.move.Projects', undefined]
		]);
	});
});
