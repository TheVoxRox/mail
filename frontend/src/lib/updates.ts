import { browser } from '$app/environment';
import { invoke, isTauri } from '@tauri-apps/api/core';
import { listen } from '@tauri-apps/api/event';
import { get, writable } from 'svelte/store';
import { RELEASES_URL } from '$lib/version.js';
import { toErrorMessage } from '$lib/api/errors.js';
import { stopBackendSidecar, usesBackendSidecar } from '$lib/backend/sidecar.js';
import { updateChannel } from '$lib/stores/updateChannel.js';

const DISMISSED_UPDATE_VERSION_KEY = 'mail.update.dismissedVersion';
const AUTO_UPDATE_CHECK_ENABLED = import.meta.env.VITE_ENABLE_AUTO_UPDATE_CHECK === '1';
/** Emitted by `download_pending_update` (lib.rs), throttled to whole percents. */
const UPDATE_PROGRESS_EVENT = 'update://download-progress';

/** Shape returned by the Tauri `check_for_update` command (lib.rs). */
interface UpdateMetadata {
	version: string;
	currentVersion: string;
	date?: string;
	body?: string;
}

interface AvailableUpdate extends UpdateMetadata {
	/**
	 * Downloads and verifies the package, leaving it in the shell's managed
	 * state. Separate from {@link install} so the backend can be shut down in
	 * between — see installPromptedUpdate.
	 */
	download: () => Promise<void>;
	/**
	 * Installs the update found by the check that produced this object. The
	 * Tauri shell holds the pending update in managed state and refuses to
	 * install any other version than the one named here, so the install can
	 * never silently target a build the prompt did not show; a later check
	 * that finds nothing hides the prompt (see hideStalePrompt).
	 */
	install: () => Promise<void>;
}

/**
 * Which of the three steps in {@link installPromptedUpdate} is running. The
 * dialog names them because they are not interchangeable to a waiting user:
 * the download is long and cancellable-by-failure, stopping the backend is
 * brief, and the install is the point of no return — it ends the app.
 */
export type UpdateInstallPhase = 'downloading' | 'stoppingBackend' | 'installing';

export interface UpdateProgress {
	downloaded: number;
	/** `null` when the server sent no `Content-Length` — no percentage exists. */
	total: number | null;
}

type UpdatePromptState =
	| { status: 'hidden' }
	| { status: 'available'; update: AvailableUpdate }
	| {
			status: 'installing';
			update: AvailableUpdate;
			phase: UpdateInstallPhase;
			/** Only ever set during `downloading`; `null` until the first event. */
			progress: UpdateProgress | null;
	  };

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
		download: () => invoke('download_pending_update', { expectedVersion: metadata.version }),
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

/**
 * Downloads the update, frees the install directory, then hands over to the
 * installer — in that order.
 *
 * The order is the point. The NSIS installer overwrites the sidecar launcher
 * and its bundled JRE inside the install directory, and Windows refuses to
 * overwrite a file a live process holds open. Left alone, nothing stops the
 * backend before then: the updater plugin spawns the installer and ends the
 * app with `std::process::exit(0)`, which skips the `beforeunload` hook that
 * normally kills the sidecar, so the only thing left is the backend's
 * parent-death watchdog — and that only starts once the app process is
 * already gone, racing an installer that is by then running. Stopping the
 * sidecar here replaces that race with a sequence.
 *
 * Nothing re-entrant: the prompt's buttons are disabled while installing, and
 * the guard below refuses a second run rather than starting a second download.
 */
export async function installPromptedUpdate(): Promise<void> {
	const state = get(updatePromptState);
	if (state.status !== 'available') return;

	const { update } = state;
	updatePromptState.set({ status: 'installing', update, phase: 'downloading', progress: null });
	let backendStopped = false;
	// Deliberately not awaited here. Awaiting would put a turn between the
	// prompt going into 'installing' and the download starting, and a check
	// landing in that turn clears the shell's pending slot — the download would
	// then fail on an update that was there when the user clicked. The handle is
	// awaited in the `finally` instead, where nothing is racing it; the cost is
	// that a progress event arriving before the listener attaches is missed,
	// which moves a bar slightly late and nothing else.
	const progressListener = listenForDownloadProgress();
	try {
		await update.download();

		if (usesBackendSidecar()) {
			setInstallPhase('stoppingBackend');
			await stopBackendSidecar();
			backendStopped = true;
		}

		setInstallPhase('installing');
		await update.install();
		updatePromptState.set({ status: 'hidden' });
	} catch (err) {
		if (backendStopped) await restartBackendAfterFailedInstall();
		// The prompt closes rather than reverting to 'available'. Leaving it open
		// stacked the failure dialog on top of it — two modals, one over the
		// other, and a screen reader walking back out of the top one into a
		// prompt still offering the update that just failed. The failure dialog
		// is the single surface for the failure; the update itself is not lost,
		// since the version was never dismissed and About → check for updates
		// finds it again.
		updatePromptState.set({ status: 'hidden' });
		showUpdateFailure(err);
	} finally {
		(await progressListener)();
	}
}

function setInstallPhase(phase: UpdateInstallPhase): void {
	updatePromptState.update((state) =>
		state.status === 'installing' ? { ...state, phase, progress: null } : state
	);
}

/**
 * Subscribes to the shell's download-progress events for the length of one
 * install, and returns the unsubscribe.
 *
 * Progress is advisory throughout: a shell that cannot deliver it (an older
 * build, a webview without the event permission) leaves the dialog on its
 * indeterminate bar and the install proceeds unchanged. That is why the failure
 * path here warns instead of throwing — refusing to update because a progress
 * bar could not be wired up would trade the important thing for the cosmetic
 * one.
 */
async function listenForDownloadProgress(): Promise<() => void> {
	if (!supportsNativeUpdater()) return () => {};

	try {
		return await listen<UpdateProgress>(UPDATE_PROGRESS_EVENT, ({ payload }) => {
			updatePromptState.update((state) =>
				state.status === 'installing' && state.phase === 'downloading'
					? { ...state, progress: payload }
					: state
			);
		});
	} catch (err) {
		console.warn('[mail] update progress listener failed', err);
		return () => {};
	}
}

/**
 * Puts the backend back after an install that did not take. Without this the
 * app survives the failure with no backend behind it, which reads to the user
 * as a second, unrelated breakage on top of the one the dialog reports.
 *
 * A plain respawn is not enough: the new sidecar comes up on a fresh port with
 * a fresh handshake key, so the boot has to be redone. The import is dynamic
 * because bootstrap imports this module — the same cycle sidecarRecovery.ts
 * breaks the same way.
 */
async function restartBackendAfterFailedInstall(): Promise<void> {
	try {
		const { bootstrap } = await import('$lib/bootstrap.js');
		await bootstrap({ restartSidecar: true });
	} catch (err) {
		// The boot / session stores surface this on their own, and the update
		// failure dialog is already on its way up; a throw here would replace
		// that dialog's message with this one.
		console.warn('[mail] backend restart after a failed update install failed', err);
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

/**
 * @param options.holdDownloadAt Leaves the mock parked in the download phase at
 * this progress, so a test can inspect the dialog while it is downloading. The
 * real progress events come from the shell, which the mocked build has no IPC
 * to, so the value is written into the same store slot the listener writes to.
 * The download promise never settles — that is the hold — and the page is
 * discarded at the end of the test.
 */
export function showMockUpdateForTests(
	version = '9.9.9',
	options: { failInstall?: boolean; holdDownloadAt?: UpdateProgress } = {}
): void {
	updatePromptState.set({
		status: 'available',
		update: {
			version,
			currentVersion: '0.1.0',
			async download() {
				const progress = options.holdDownloadAt;
				if (!progress) return;
				updatePromptState.update((state) =>
					state.status === 'installing' && state.phase === 'downloading'
						? { ...state, progress }
						: state
				);
				await new Promise<void>(() => {});
			},
			async install() {
				if (options.failInstall) {
					throw new Error('Mock update install failed');
				}
			}
		}
	});
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
