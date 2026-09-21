import { describe, expect, it, vi } from 'vitest';

vi.mock('$lib/mail/actions.js', () => ({
	forwardMessage: vi.fn(),
	goToCompose: vi.fn(),
	goToSearch: vi.fn(),
	replyToMessage: vi.fn(),
	syncCurrentAccount: vi.fn()
}));
vi.mock('$lib/mail/printMessages.js', () => ({ printOpenMessage: vi.fn() }));
vi.mock('$lib/mail/mailbox.js', () => ({
	deleteMessages: vi.fn(),
	moveMessages: vi.fn(),
	toggleMessageFlag: vi.fn(),
	toggleMessageSeen: vi.fn()
}));

import { createMailCommands } from './mailCommands.js';
import type { PrintableSelection } from '$lib/mail/printMessages.js';

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
		...overrides
	});
}

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
