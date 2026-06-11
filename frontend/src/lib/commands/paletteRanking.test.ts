import { describe, expect, it } from 'vitest';
import {
	contextScore,
	groupOrder,
	normalizeText,
	routeMatches,
	sortByRelevance,
	textMatchScore,
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

	it('75 for title substring', () => {
		expect(textMatchScore(e, 'mail')).toBe(75);
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
