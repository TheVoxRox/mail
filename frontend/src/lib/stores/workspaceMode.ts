import { resolve } from '$app/paths';
import { page } from '$app/stores';
import { derived, type Readable } from 'svelte/store';

export type WorkspaceMode = 'mail' | 'contacts' | 'settings';

export function detectWorkspaceMode(pathname: string): WorkspaceMode {
	if (pathname.startsWith('/contacts')) return 'contacts';
	if (pathname.startsWith('/settings') || pathname.startsWith('/auth/finished')) {
		return 'settings';
	}
	return 'mail';
}

export const workspaceMode: Readable<WorkspaceMode> = derived(page, ($page) =>
	detectWorkspaceMode($page.url.pathname)
);

export function workspaceHref(mode: WorkspaceMode, activeAccountId: number | null): string {
	switch (mode) {
		case 'contacts':
			return activeAccountId
				? resolve('/contacts/[accountId]', { accountId: String(activeAccountId) })
				: resolve('/contacts');
		case 'settings':
			return resolve('/settings/appearance');
		case 'mail':
		default:
			return resolve('/');
	}
}
