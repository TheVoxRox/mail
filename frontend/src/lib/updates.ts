import { browser } from '$app/environment';
import { isTauri } from '@tauri-apps/api/core';
import type { DownloadEvent, Update as TauriUpdate } from '@tauri-apps/plugin-updater';
import { get, writable } from 'svelte/store';
import { RELEASES_URL } from '$lib/version.js';
import { toErrorMessage } from '$lib/api/errors.js';

const DISMISSED_UPDATE_VERSION_KEY = 'mail.update.dismissedVersion';
const AUTO_UPDATE_CHECK_ENABLED = import.meta.env.VITE_ENABLE_AUTO_UPDATE_CHECK === '1';

interface AvailableUpdate {
	version: string;
	currentVersion: string;
	date?: string;
	body?: string;
	rawJson: Record<string, unknown>;
	update: TauriUpdate;
}

type UpdateDownloadHandler = (event: DownloadEvent) => void;

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

	const { check } = await import('@tauri-apps/plugin-updater');
	const update = await check();
	if (!update) return null;

	return {
		version: update.version,
		currentVersion: update.currentVersion,
		date: update.date,
		body: update.body,
		rawJson: update.rawJson,
		update
	};
}

async function installUpdate(
	available: AvailableUpdate,
	onDownloadEvent?: UpdateDownloadHandler
): Promise<void> {
	await available.update.downloadAndInstall(onDownloadEvent);
}

export async function checkForUpdateAndPrompt(): Promise<void> {
	if (!shouldCheckForUpdatesOnStartup()) return;

	try {
		const update = await checkForUpdate();
		if (!update || wasDismissed(update.version)) return;
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
		if (!update) return { status: 'none' };
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
		await installUpdate(update);
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
	const update = {
		version,
		currentVersion: '0.1.0',
		rawJson: {},
		async downloadAndInstall() {
			if (options.failInstall) {
				throw new Error('Mock update install failed');
			}
		}
	} as TauriUpdate;

	updatePromptState.set({
		status: 'available',
		update: {
			version: update.version,
			currentVersion: update.currentVersion,
			date: update.date,
			body: update.body,
			rawJson: update.rawJson,
			update
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
