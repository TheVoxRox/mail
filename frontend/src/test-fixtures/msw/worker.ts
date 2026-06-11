import { browser } from '$app/environment';
import { setupWorker } from 'msw/browser';
import { sessionState } from '$lib/stores/session.js';
import {
	failNextVCardExportOnce,
	handlers,
	setFolderAuthFailure,
	setReadinessDelayMs,
	setReadinessFailures,
	setVCardExportDelayMs
} from './handlers.js';
import {
	closeSyncStreams,
	pushSendCompleted,
	pushSendFailed,
	pushSyncCompleted,
	pushSyncCompletedCrLf
} from './sse-bridge.js';
import { clearAccounts, resetFixtures } from './fixtures.js';
import type { SessionPayload } from '$lib/api/session.js';

const worker = setupWorker(...handlers);
let started = false;

function e2eSession(): SessionPayload {
	const origin = window.location.origin;
	return {
		appName: 'mail',
		appVersion: '0.0.1-SNAPSHOT',
		apiVersion: '1.0.0',
		minClientVersion: '0.0.1',
		dbSchemaVersion: '1',
		port: Number(window.location.port || 4173),
		apiKey: 'e2e-test-key',
		baseUrl: `${origin}/api`
	};
}

export async function installE2EBypass(): Promise<void> {
	if (!browser) return;

	if (!started) {
		resetFixtures();
		if (window.localStorage.getItem('mail.e2e.noAccounts') === '1') {
			clearAccounts();
		}
		setReadinessDelayMs(Number(window.localStorage.getItem('mail.e2e.readinessDelayMs') ?? 0));
		setReadinessFailures(Number(window.localStorage.getItem('mail.e2e.readinessFailures') ?? 0));
		setFolderAuthFailure(window.localStorage.getItem('mail.e2e.folderAuthFailure') === '1');
		await worker.start({
			quiet: true,
			onUnhandledRequest: 'bypass',
			serviceWorker: {
				url: '/mockServiceWorker.js'
			}
		});
		started = true;
	}

	const sessionDelayMs = Math.max(
		0,
		Number(window.localStorage.getItem('mail.e2e.sessionDelayMs') ?? 0)
	);
	if (sessionDelayMs > 0) {
		await new Promise((resolve) => setTimeout(resolve, sessionDelayMs));
	}

	sessionState.set({ status: 'ready', session: e2eSession() });
	window.__MAIL_MSW__ = {
		worker,
		reset: () => {
			closeSyncStreams();
			setReadinessDelayMs(0);
			setReadinessFailures(0);
			setFolderAuthFailure(false);
			setVCardExportDelayMs(0);
			resetFixtures();
			worker.resetHandlers(...handlers);
		},
		pushSyncCompleted,
		pushSyncCompletedCrLf,
		pushSendCompleted,
		pushSendFailed,
		setReadinessDelayMs,
		setReadinessFailures,
		setFolderAuthFailure,
		setVCardExportDelayMs,
		failNextVCardExport: failNextVCardExportOnce
	};
}

export {
	failNextVCardExportOnce,
	handlers,
	pushSendCompleted,
	pushSendFailed,
	pushSyncCompleted,
	pushSyncCompletedCrLf,
	resetFixtures,
	setFolderAuthFailure,
	setReadinessDelayMs,
	setReadinessFailures,
	setVCardExportDelayMs,
	worker
};
