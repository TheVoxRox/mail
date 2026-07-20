/**
 * Pure ranking logic for the Command Palette — extracted from
 * `CommandPalette.svelte` so the scoring algorithm can be unit-tested.
 */
import { detectWorkspaceMode } from '$lib/stores/workspaceMode.js';
import type { Command } from '$lib/stores/commands.js';
import type { CommandGroup } from './shared.js';

export interface VisibleCommand {
	command: Command;
	title: string;
	groupLabel: string;
	searchText: string;
	baseIndex: number;
}

export const GROUP_ORDER: CommandGroup[] = ['mail', 'nav', 'account', 'view', 'other'];

const COMBINING_DIACRITICS = /[̀-ͯ]/g;

export function normalizeText(value: string): string {
	return value.normalize('NFD').replace(COMBINING_DIACRITICS, '').toLowerCase();
}

export function tokenizeQuery(normalizedQuery: string): string[] {
	return normalizedQuery.split(/\s+/).filter((token) => token.length > 0);
}

/**
 * Word-order-independent match: every query token must appear somewhere in
 * the entry's search text. In-order phrases still rank higher because
 * `textMatchScore` scores the query as a whole string.
 */
export function matchesAllTokens(entry: VisibleCommand, tokens: string[]): boolean {
	return tokens.every((token) => entry.searchText.includes(token));
}

export function routeMatches(pathname: string, prefix: string): boolean {
	if (prefix.endsWith('/')) return pathname.startsWith(prefix);
	return pathname === prefix || pathname.startsWith(`${prefix}/`);
}

export function contextScore(command: Command, pathname: string): number {
	const mode = detectWorkspaceMode(pathname);
	const contextBoost = command.contexts?.includes(mode) ? 30 : 0;
	const routeBoost = command.routePrefixes?.some((prefix) => routeMatches(pathname, prefix))
		? 40
		: 0;
	return contextBoost + routeBoost + (command.priority ?? 0);
}

export function textMatchScore(entry: VisibleCommand, normalizedQuery: string): number {
	if (!normalizedQuery) return 0;
	const normalizedTitle = normalizeText(entry.title);
	if (normalizedTitle === normalizedQuery) return 120;
	if (normalizedTitle.startsWith(normalizedQuery)) return 100;
	if (entry.searchText.startsWith(normalizedQuery)) return 90;
	if (normalizedTitle.includes(normalizedQuery)) return 75;
	return 50;
}

export function groupOrder(groupKey: CommandGroup): number {
	const index = GROUP_ORDER.indexOf(groupKey);
	return index === -1 ? GROUP_ORDER.length : index;
}

/**
 * Sorts entries by relevance:
 *   1) groups by the highest score within the group (and tiebreak via GROUP_ORDER),
 *   2) entries within a group by score (tiebreak: baseIndex).
 *
 * `normalizedQuery=''` means "no query" — context weight rises to 1.0.
 */
export function sortByRelevance(
	entries: VisibleCommand[],
	normalizedQuery: string,
	pathname: string
): VisibleCommand[] {
	const queryWeight = normalizedQuery ? 0.35 : 1;
	const scored = entries.map((entry) => ({
		entry,
		score:
			textMatchScore(entry, normalizedQuery) + contextScore(entry.command, pathname) * queryWeight
	}));

	const groups: Array<{
		groupKey: CommandGroup;
		entries: Array<{ entry: VisibleCommand; score: number }>;
		score: number;
	}> = [];

	for (const item of scored) {
		const group = groups.find((g) => g.groupKey === item.entry.command.groupKey);
		if (group) {
			group.entries.push(item);
			if (item.score > group.score) group.score = item.score;
		} else {
			groups.push({ groupKey: item.entry.command.groupKey, entries: [item], score: item.score });
		}
	}

	return groups
		.sort((a, b) => {
			if (a.score !== b.score) return b.score - a.score;
			return groupOrder(a.groupKey) - groupOrder(b.groupKey);
		})
		.flatMap((group) =>
			group.entries
				.sort((a, b) => {
					if (a.score !== b.score) return b.score - a.score;
					return a.entry.baseIndex - b.entry.baseIndex;
				})
				.map((item) => item.entry)
		);
}
