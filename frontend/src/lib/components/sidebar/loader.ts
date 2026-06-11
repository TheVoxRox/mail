/*
 * Lazy sidebar loader. Each workspace owns its own sidebar component; only one
 * is ever rendered at a time. Splitting them into per-workspace chunks shrinks
 * the initial JS bundle, and the cache keeps switching between modes cheap
 * after the first load (the dynamic import resolves synchronously on cache
 * hit).
 *
 * Module-level cache (not inside any Svelte component) so it survives HMR /
 * remounts and is shared across any caller. Plain Map is intentional — the
 * cache is not consumed reactively (the active mode comes from
 * `workspaceMode` store; the consumer wraps `loadSidebar` in `$derived`).
 */
import type { Component } from 'svelte';
import type { WorkspaceMode } from '$lib/stores/workspaceMode.js';

type SidebarModule = Promise<{ default: Component }>;

const cache = new Map<WorkspaceMode, SidebarModule>();

export function loadSidebar(mode: WorkspaceMode): SidebarModule {
	let p = cache.get(mode);
	if (!p) {
		p =
			mode === 'mail'
				? (import('./MailSidebar.svelte') as SidebarModule)
				: mode === 'contacts'
					? (import('./ContactsSidebar.svelte') as SidebarModule)
					: (import('./SettingsSidebar.svelte') as SidebarModule);
		/*
		 * Evict a rejected import so a transient load failure (corrupt/missing
		 * chunk on a desktop bundle) does not poison the cache permanently —
		 * without this, one failure would keep returning the same rejected
		 * promise for the rest of the session, leaving that workspace's sidebar
		 * broken until app restart. The identity guard avoids evicting a newer
		 * in-flight attempt if one raced in.
		 */
		const attempt = p;
		attempt.catch(() => {
			if (cache.get(mode) === attempt) cache.delete(mode);
		});
		cache.set(mode, p);
	}
	return p;
}
