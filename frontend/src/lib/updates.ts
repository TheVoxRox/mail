import { browser } from '$app/environment';
import { invoke, isTauri } from '@tauri-apps/api/core';
import { get, writable } from 'svelte/store';
import { RELEASES_URL } from '$lib/version.js';
import { toErrorMessage } from '$lib/api/errors.js';
import { updateChannel } from '$lib/stores/updateChannel.js';

const DISMISSED_UPDATE_VERSION_KEY = 'mail.update.dismissedVersion';
const AUTO_UPDATE_CHECK_ENABLED = import.meta.env.VITE_ENABLE_AUTO_UPDATE_CHECK === '1';

/** Shape returned by the Tauri `check_for_update` command (lib.rs). */
interface UpdateMetadata {
	version: string;
	currentVersion: string;
	date?: string;
	body?: string;
}

interface AvailableUpdate extends UpdateMetadata {
	/**
	 * Installs the update found by the check that produced this object. The
	 * Tauri shell holds the pending update in managed state and refuses to
	 * install any other version than the one named here, so the install can
	 * never silently target a build the prompt did not show; a later check
	 * that finds nothing hides the prompt (see hideStalePrompt).
	 */
	install: () => Promise<void>;
}

type UpdatePromptState =
	| { status: 'hidden' }
	| { status: 'available'; update: AvailableUpdate }
	| { status: 'installing'; update: AvailableUpdate };

type UpdateFailureState =
	{ status: 'hidden' } | { status: 'failed'; message: string; releasesUrl: string | null };

type ManualUpdateCheckResult =
	| { status: 'unsupported' }
	| { status: 'none' }
	| { status: 'available'; update: AvailableUpdate }
	| { status: 'failed'; message: string };

export const updatePromptState = writable<UpdatePromptState>({ status: 'hidden' });
export const updateFailureState = writable<UpdateFailureState>({ status: 'hidden' });

function supportsNativeUpdater(): boolean {
	return browser && isTauri() && import.meta.env.VITE_E2E_MOCK !== '1';
}

function shouldCheckForUpdatesOnStartup(): boolean {
	return supportsNativeUpdater() && import.meta.env.PROD && AUTO_UPDATE_CHECK_ENABLED;
}

async function checkForUpdate(): Promise<AvailableUpdate | null> {
	if (!supportsNativeUpdater()) return null;

	const metadata = await invoke<UpdateMetadata | null>('check_for_update', {
		channel: get(updateChannel)
	});
	if (!metadata) return null;

	return {
		...metadata,
		install: () => invoke('install_pending_update', { expectedVersion: metadata.version })
	};
}

/**
 * Every check replaces the shell's pending-update slot, so after a check that
 * found nothing an open "update available" prompt would offer an update the
 * shell can no longer install. An in-flight install is left alone — it works
 * on its own handle.
 */
function hideStalePrompt(): void {
	if (get(updatePromptState).status === 'available') {
		updatePromptState.set({ status: 'hidden' });
	}
}

export async function checkForUpdateAndPrompt(): Promise<void> {
	if (!shouldCheckForUpdatesOnStartup()) return;

	try {
		const update = await checkForUpdate();
		if (!update) {
			hideStalePrompt();
			return;
		}
		if (wasDismissed(update.version)) return;
		updatePromptState.set({ status: 'available', update });
	} catch (err) {
		// Background startup checks fail silently: a transient network error or a
		// not-yet-published release must not raise an alarming dialog on every
		// launch (it is announced to screen-reader users on each cold start). The
		// prominent failure UI is reserved for the user-initiated
		// checkForUpdateManually().
		console.warn('[mail] startup update check failed', err);
	}
}

export async function checkForUpdateManually(): Promise<ManualUpdateCheckResult> {
	if (!supportsNativeUpdater()) return { status: 'unsupported' };

	try {
		const update = await checkForUpdate();
		if (!update) {
			hideStalePrompt();
			return { status: 'none' };
		}
		updatePromptState.set({ status: 'available', update });
		return { status: 'available', update };
	} catch (err) {
		showUpdateFailure(err);
		return {
			status: 'failed',
			message: toErrorMessage(err)
		};
	}
}

export async function installPromptedUpdate(): Promise<void> {
	const state = get(updatePromptState);
	if (state.status !== 'available' && state.status !== 'installing') return;

	const { update } = state;
	updatePromptState.set({ status: 'installing', update });
	try {
		await update.install();
		updatePromptState.set({ status: 'hidden' });
	} catch (err) {
		updatePromptState.set({ status: 'available', update });
		showUpdateFailure(err);
	}
}

export function postponePromptedUpdate(): void {
	const state = get(updatePromptState);
	if (state.status === 'available' || state.status === 'installing') {
		dismissVersion(state.update.version);
	}
	updatePromptState.set({ status: 'hidden' });
}

export function dismissUpdateFailure(): void {
	updateFailureState.set({ status: 'hidden' });
}

export function showMockUpdateForTests(
	version = '9.9.9',
	options: { failInstall?: boolean } = {}
): void {
	updatePromptState.set({
		status: 'available',
		update: {
			version,
			currentVersion: '0.1.0',
			async install() {
				if (options.failInstall) {
					throw new Error('Mock update install failed');
				}
			}
		}
	});
}

export function showMockUpdateFailureForTests(message = 'Mock update check failed'): void {
	showUpdateFailure(new Error(message));
}

export function resetUpdateStateForTests(): void {
	updatePromptState.set({ status: 'hidden' });
	updateFailureState.set({ status: 'hidden' });
}

function dismissVersion(version: string): void {
	if (!browser) return;
	try {
		window.localStorage.setItem(DISMISSED_UPDATE_VERSION_KEY, version);
	} catch {
		// localStorage can be unavailable in private modes; dismissal is best-effort.
	}
}

function wasDismissed(version: string): boolean {
	if (!browser) return false;
	try {
		return window.localStorage.getItem(DISMISSED_UPDATE_VERSION_KEY) === version;
	} catch {
		return false;
	}
}

function showUpdateFailure(error: unknown): void {
	const message = toErrorMessage(error);
	updateFailureState.set({
		status: 'failed',
		message,
		releasesUrl: RELEASES_URL
	});
}
