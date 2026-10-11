import { describe, expect, it } from 'vitest';
import {
	contextScore,
	groupOrder,
	matchesAllTokens,
	normalizeText,
	routeMatches,
	sortByRelevance,
	textMatchScore,
	tokenizeQuery,
	type VisibleCommand
} from './paletteRanking.js';
import type { Command, CommandGroup } from './shared.js';

function makeCommand(opts: {
	id: string;
	group: CommandGroup;
	priority?: number;
	contexts?: Array<'mail' | 'contacts' | 'settings'>;
	routePrefixes?: string[];
}): Command {
	return {
		id: opts.id,
		titleKey: `t.${opts.id}`,
		groupKey: opts.group,
		contexts: opts.contexts,
		routePrefixes: opts.routePrefixes,
		priority: opts.priority,
		available: () => true,
		run: () => {}
	};
}

function entry(opts: {
	id: string;
	title: string;
	group: CommandGroup;
	baseIndex?: number;
	priority?: number;
	contexts?: Array<'mail' | 'contacts' | 'settings'>;
	routePrefixes?: string[];
	keywords?: string;
}): VisibleCommand {
	return {
		command: makeCommand({
			id: opts.id,
			group: opts.group,
			priority: opts.priority,
			contexts: opts.contexts,
			routePrefixes: opts.routePrefixes
		}),
		title: opts.title,
		groupLabel: opts.group,
		searchText: normalizeText(`${opts.title} ${opts.keywords ?? ''}`),
		baseIndex: opts.baseIndex ?? 0
	};
}

describe('normalizeText', () => {
	it('lowercases', () => {
		expect(normalizeText('FOO')).toBe('foo');
	});

	it('strips diacritics (NFD + combining marks)', () => {
		expect(normalizeText('Příště Žluťoučký kůň')).toBe('priste zlutoucky kun');
		expect(normalizeText('Café')).toBe('cafe');
	});
});

describe('tokenizeQuery', () => {
	it('splits on whitespace and drops empty tokens', () => {
		expect(tokenizeQuery('odpovedet vsem')).toEqual(['odpovedet', 'vsem']);
		expect(tokenizeQuery('  a   b ')).toEqual(['a', 'b']);
		expect(tokenizeQuery('')).toEqual([]);
	});
});

describe('matchesAllTokens', () => {
	const e = entry({ id: 'a', title: 'Odpovědět všem', group: 'mail', keywords: 'reply all' });

	it('matches regardless of word order', () => {
		expect(matchesAllTokens(e, tokenizeQuery('odpovedet vsem'))).toBe(true);
		expect(matchesAllTokens(e, tokenizeQuery('vsem odpovedet'))).toBe(true);
	});

	it('mixes title and keyword tokens', () => {
		expect(matchesAllTokens(e, tokenizeQuery('all odpovedet'))).toBe(true);
	});

	it('rejects when any token is missing', () => {
		expect(matchesAllTokens(e, tokenizeQuery('odpovedet nikomu'))).toBe(false);
	});

	it('empty token list matches everything', () => {
		expect(matchesAllTokens(e, [])).toBe(true);
	});
});

describe('routeMatches', () => {
	it('matches exact path', () => {
		expect(routeMatches('/mail', '/mail')).toBe(true);
	});

	it('matches with trailing-segment boundary', () => {
		expect(routeMatches('/mail/inbox', '/mail')).toBe(true);
		expect(routeMatches('/mailbox', '/mail')).toBe(false);
	});

	it('respects trailing slash as a "starts with" wildcard', () => {
		expect(routeMatches('/mail/inbox/123', '/mail/')).toBe(true);
		expect(routeMatches('/mail', '/mail/')).toBe(false);
	});
});

describe('contextScore', () => {
	it('returns 0 when no contexts/prefixes/priority match', () => {
		expect(contextScore(makeCommand({ id: 'x', group: 'view' }), '/mail/inbox')).toBe(0);
	});

	it('30 for context match (mail mode)', () => {
		const cmd = makeCommand({ id: 'x', group: 'view', contexts: ['mail'] });
		expect(contextScore(cmd, '/mail/inbox')).toBe(30);
	});

	it('40 for route prefix match', () => {
		const cmd = makeCommand({ id: 'x', group: 'view', routePrefixes: ['/settings/'] });
		expect(contextScore(cmd, '/settings/appearance')).toBe(40);
	});

	it('adds command priority on top', () => {
		const cmd = makeCommand({ id: 'x', group: 'view', priority: 10 });
		expect(contextScore(cmd, '/mail/inbox')).toBe(10);
	});

	it('combines context + route + priority', () => {
		const cmd = makeCommand({
			id: 'x',
			group: 'view',
			priority: 5,
			contexts: ['settings'],
			routePrefixes: ['/settings/']
		});
		expect(contextScore(cmd, '/settings/appearance')).toBe(75);
	});
});

describe('textMatchScore', () => {
	const e = entry({ id: 'a', title: 'Send Mail', group: 'mail', keywords: 'compose write' });

	it('returns 0 for empty query', () => {
		expect(textMatchScore(e, '')).toBe(0);
	});

	it('120 for exact title match', () => {
		expect(textMatchScore(e, 'send mail')).toBe(120);
	});

	it('100 for title prefix', () => {
		expect(textMatchScore(e, 'send')).toBe(100);
	});

	it('90 for searchText prefix when title does not prefix-match', () => {
		// searchText = "compose write send mail" → query 'compose' is prefix of searchText but not title.
		const ePrefixOnly: VisibleCommand = {
			...e,
			searchText: normalizeText('compose write send mail')
		};
		expect(textMatchScore(ePrefixOnly, 'compose')).toBe(90);
	});

	it('85 for a title word start past the first word', () => {
		expect(textMatchScore(e, 'mail')).toBe(85);
		expect(textMatchScore(e, 'ma')).toBe(85);
	});

	it('85 when a later occurrence starts a word though the first sits inside one', () => {
		const repeated = entry({ id: 'b', title: 'Podokno odeslane', group: 'view' });
		expect(textMatchScore(repeated, 'od')).toBe(85);
	});

	it('85 for a word start after punctuation', () => {
		const punctuated = entry({ id: 'c', title: 'Folder [Gmail]/Sent', group: 'nav' });
		expect(textMatchScore(punctuated, 'gmail')).toBe(85);
		expect(textMatchScore(punctuated, 'sent')).toBe(85);
	});

	it('75 for a title substring inside a word', () => {
		expect(textMatchScore(e, 'end')).toBe(75);
		expect(textMatchScore(e, 'ail')).toBe(75);
	});

	it('50 for non-title, non-prefix keyword substring', () => {
		// 'rite' (substring of "write") is in keywords but not title prefix nor searchText prefix.
		expect(textMatchScore(e, 'rite')).toBe(50);
	});
});

describe('groupOrder', () => {
	it('mail < nav < account < view < other', () => {
		expect(groupOrder('mail')).toBeLessThan(groupOrder('nav'));
		expect(groupOrder('nav')).toBeLessThan(groupOrder('account'));
		expect(groupOrder('account')).toBeLessThan(groupOrder('view'));
		expect(groupOrder('view')).toBeLessThan(groupOrder('other'));
	});
});

describe('sortByRelevance', () => {
	it('returns empty for empty input', () => {
		expect(sortByRelevance([], '', '/')).toEqual([]);
	});

	it('without query: orders by GROUP_ORDER then baseIndex (ties)', () => {
		const a = entry({ id: 'a', title: 'A', group: 'view', baseIndex: 0 });
		const b = entry({ id: 'b', title: 'B', group: 'mail', baseIndex: 1 });
		const c = entry({ id: 'c', title: 'C', group: 'nav', baseIndex: 2 });
		const sorted = sortByRelevance([a, b, c], '', '/');
		expect(sorted.map((e) => e.command.id)).toEqual(['b', 'c', 'a']);
	});

	it('groups stick together (entries of same group rendered consecutively)', () => {
		const m1 = entry({ id: 'm1', title: 'M1', group: 'mail', baseIndex: 0 });
		const v1 = entry({ id: 'v1', title: 'V1', group: 'view', baseIndex: 1 });
		const m2 = entry({ id: 'm2', title: 'M2', group: 'mail', baseIndex: 2 });
		const sorted = sortByRelevance([m1, v1, m2], '', '/');
		expect(sorted.map((e) => e.command.id)).toEqual(['m1', 'm2', 'v1']);
	});

	it('text match boosts the matching command above others', () => {
		const e1 = entry({ id: 'a', title: 'Send Mail', group: 'mail', baseIndex: 0 });
		const e2 = entry({ id: 'b', title: 'Foo Bar', group: 'mail', baseIndex: 1 });
		const sorted = sortByRelevance([e1, e2], 'send', '/');
		expect(sorted[0].command.id).toBe('a');
	});

	it('a word start in the title outranks a higher-priority command matching inside a word', () => {
		// The palette's own numbers on /mail/1/INBOX for the query "od": the
		// reading pane commands carry priority 14, the folder commands 10, and
		// both are mail-context commands under a /mail/ route prefix.
		const mailCommand = { contexts: ['mail' as const], routePrefixes: ['/mail/'] };
		const pane = entry({
			id: 'view.reading.right',
			title: 'Podokno cteni vpravo',
			group: 'view',
			priority: 14,
			baseIndex: 0,
			...mailCommand
		});
		const sent = entry({
			id: 'folder.SENT',
			title: 'Prejit do slozky Odeslane',
			group: 'nav',
			priority: 10,
			baseIndex: 1,
			...mailCommand
		});
		const sorted = sortByRelevance([pane, sent], 'od', '/mail/1/INBOX');
		expect(sorted.map((e) => e.command.id)).toEqual(['folder.SENT', 'view.reading.right']);
	});

	it('context match changes ranking when no query', () => {
		const settingsCmd = entry({
			id: 's',
			title: 'Toggle Theme',
			group: 'view',
			contexts: ['settings'],
			routePrefixes: ['/settings/appearance'],
			baseIndex: 0
		});
		const mailCmd = entry({ id: 'm', title: 'New Mail', group: 'mail', baseIndex: 1 });

		// On /mail/inbox: mail group wins by GROUP_ORDER
		const onMail = sortByRelevance([settingsCmd, mailCmd], '', '/mail/inbox');
		expect(onMail.map((e) => e.command.id)).toEqual(['m', 's']);

		// On /settings/appearance: settings context boosts it above mail group
		const onSettings = sortByRelevance([settingsCmd, mailCmd], '', '/settings/appearance');
		expect(onSettings.map((e) => e.command.id)).toEqual(['s', 'm']);
	});

	it('stable tie-break by baseIndex inside group', () => {
		const a = entry({ id: 'a', title: 'X', group: 'mail', baseIndex: 5 });
		const b = entry({ id: 'b', title: 'X', group: 'mail', baseIndex: 2 });
		const sorted = sortByRelevance([a, b], '', '/');
		expect(sorted.map((e) => e.command.id)).toEqual(['b', 'a']);
	});

	it('does not throw on duplicate baseIndex (defensive)', () => {
		const a = entry({ id: 'a', title: 'A', group: 'mail', baseIndex: 0 });
		const b = entry({ id: 'b', title: 'B', group: 'mail', baseIndex: 0 });
		expect(() => sortByRelevance([a, b], '', '/')).not.toThrow();
	});
});
