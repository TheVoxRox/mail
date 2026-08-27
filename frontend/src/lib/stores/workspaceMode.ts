import { resolve } from '$app/paths';
import { page } from '$app/state';

export type WorkspaceMode = 'mail' | 'contacts' | 'settings';

export function detectWorkspaceMode(pathname: string): WorkspaceMode {
	if (pathname.startsWith('/contacts')) return 'contacts';
	if (pathname.startsWith('/settings') || pathname.startsWith('/auth/finished')) {
		return 'settings';
	}
	return 'mail';
}

/**
 * Workspace of the current route. A function rather than a store: `$app/state`
 * is runes-only, so reading it inside `$derived` or markup tracks navigation,
 * while a call from an event handler is a one-shot read of the current route.
 */
export function currentWorkspaceMode(): WorkspaceMode {
	return detectWorkspaceMode(page.url.pathname);
}

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
