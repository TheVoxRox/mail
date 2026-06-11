import { derived, get, writable } from 'svelte/store';

export const selectedMessageIds = writable<string[]>([]);

export const selectedMessageIdSet = derived(selectedMessageIds, (ids) => new Set(ids));

function unique(ids: readonly string[]): string[] {
	return Array.from(new Set(ids.filter(Boolean)));
}

export function setMessageSelection(ids: readonly string[]): void {
	selectedMessageIds.set(unique(ids));
}

export function clearMessageSelection(): void {
	selectedMessageIds.set([]);
}

export function toggleMessageSelection(stableId: string, selected?: boolean): void {
	selectedMessageIds.update((ids) => {
		const current = new Set(ids);
		const nextSelected = selected ?? !current.has(stableId);
		if (nextSelected) {
			current.add(stableId);
		} else {
			current.delete(stableId);
		}
		return Array.from(current);
	});
}

export function pruneMessageSelection(visibleStableIds: readonly string[]): void {
	const visible = new Set(visibleStableIds);
	const current = get(selectedMessageIds);
	const next = current.filter((stableId) => visible.has(stableId));
	if (next.length !== current.length) {
		selectedMessageIds.set(next);
	}
}
