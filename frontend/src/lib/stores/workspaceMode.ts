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

/**
 * Landing route of a workspace. Takes no account: the address book is
 * application-wide, and mail and settings open on their own entry route and
 * read the active account from the store.
 */
export function workspaceHref(mode: WorkspaceMode): string {
	switch (mode) {
		case 'contacts':
			return resolve('/contacts');
		case 'settings':
			return resolve('/settings/appearance');
		case 'mail':
		default:
			return resolve('/');
	}
}
