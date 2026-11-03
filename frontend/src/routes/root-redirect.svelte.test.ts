import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushSync, mount, unmount } from 'svelte';
import { navigating, page } from '$app/state';
import { setLocale } from '#lib/i18n/index.js';
import type { FolderResponse } from '#lib/types.js';
import RootPage from './+page.svelte';

/*
 * The cold half of `/`: no folder list in the store, so the page asks for one
 * and opens the mailbox when it arrives. The user is free to leave meanwhile,
 * and the two ways of having left are told apart here because the browser
 * cannot hold the second one open: a navigation still on its way out runs
 * between a route chunk being requested and arriving, and the e2e build serves
 * chunks through the mock service worker, out of a test's reach.
 */
const { gotoMock, loadFoldersMock } = vi.hoisted(() => ({
	gotoMock: vi.fn<(href: string, options?: { replace?: boolean }) => Promise<void>>(),
	loadFoldersMock: vi.fn<(accountId: number) => Promise<FolderResponse[]>>()
}));

/*
 * `$app/state` is the plain-object stub vitest.config.ts aliases in, so a test
 * sets where the user is by writing to it.
 */
const route = page.route as { id: string | null };
const inFlight = navigating as { to: { url: URL; route: { id: string | null } } | null };

function navigationTo(routeId: string, path: string) {
	return { url: new URL(path, 'http://localhost'), route: { id: routeId } };
}

vi.mock('$app/navigation', () => ({ goto: gotoMock }));
vi.mock('#lib/stores/accounts.js', async () => {
	const { writable } = await import('svelte/store');
	return {
		accountsState: writable({ status: 'ready', accounts: [{ id: 1 }] }),
		resolvedActiveAccountId: writable(1),
		setActiveAccount: vi.fn()
	};
});
vi.mock('#lib/stores/folders.js', () => ({ loadFolders: loadFoldersMock }));

const inbox = { folderRef: 'INBOX', role: 'INBOX', displayName: 'Inbox' } as FolderResponse;

let component: ReturnType<typeof mount> | null = null;
let deliverFolders: (folders: FolderResponse[]) => void = () => {};

beforeAll(() => {
	setLocale('en');
});

beforeEach(() => {
	route.id = '/';
	inFlight.to = null;
	gotoMock.mockResolvedValue(undefined);
	loadFoldersMock.mockImplementation(
		() => new Promise<FolderResponse[]>((resolve) => (deliverFolders = resolve))
	);
});

afterEach(() => {
	if (component) void unmount(component);
	component = null;
	document.body.innerHTML = '';
	vi.clearAllMocks();
});

/** Mounts `/` and lets it ask for the folder list, which stays pending. */
async function openRootWaitingForFolders(): Promise<void> {
	const target = document.createElement('div');
	document.body.append(target);
	component = mount(RootPage, { target });
	flushSync();
	await vi.waitFor(() => expect(loadFoldersMock).toHaveBeenCalledWith(1));
}

/** Hands the page its folder list and lets everything that follows it run. */
async function folderListArrives(): Promise<void> {
	deliverFolders([inbox]);
	await new Promise((resolve) => setTimeout(resolve, 0));
}

describe('`/` without a folder list in the store', () => {
	it('opens the mailbox once the list arrives', async () => {
		await openRootWaitingForFolders();

		await folderListArrives();

		expect(gotoMock).toHaveBeenCalledWith('/mail/1/INBOX', { replace: true });
	});

	it('leaves a user who went elsewhere where they went', async () => {
		await openRootWaitingForFolders();
		route.id = '/settings/appearance';

		await folderListArrives();

		expect(gotoMock).not.toHaveBeenCalled();
	});

	it('does not cancel a navigation still on its way out of `/`', async () => {
		await openRootWaitingForFolders();
		inFlight.to = navigationTo('/settings/appearance', '/settings/appearance');

		await folderListArrives();

		expect(gotoMock).not.toHaveBeenCalled();
	});

	it('still opens the mailbox when the navigation under way is back to `/`', async () => {
		await openRootWaitingForFolders();
		inFlight.to = navigationTo('/', '/');

		await folderListArrives();

		expect(gotoMock).toHaveBeenCalledWith('/mail/1/INBOX', { replace: true });
	});
});
