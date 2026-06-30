/**
 * Frontend startup sequence: handshake via session.json → account list →
 * automatic SSE notifications start. Called from the root `+layout.svelte`.
 */

import { get } from 'svelte/store';
import { initSession, invalidateSession, sessionState } from '$lib/stores/session.js';
import { accountsState, loadAccounts } from '$lib/stores/accounts.js';
import { loadClientConfig } from '$lib/stores/clientConfig.js';
import {
	ensureBackendSidecar,
	restartBackendSidecar,
	usesBackendSidecar
} from '$lib/backend/sidecar.js';
import { registerDefaultCommands } from '$lib/stores/commands.js';
import { startNotifications } from '$lib/stores/notifications.js';
import { checkForUpdateAndPrompt } from '$lib/updates.js';
import { waitForBackendReadiness } from '$lib/api/readiness.js';
import { reportClientBootDiagnostics } from '$lib/api/clientBootDiagnostics.js';
import { toError } from '$lib/api/errors.js';
import { announcePolite } from '$lib/stores/toasts.js';
import { _ } from '$lib/i18n/index.js';
import {
	beginBoot,
	bootState,
	completeBoot,
	failBoot,
	markBootTiming,
	setBootPhase
} from '$lib/stores/boot.js';

let started = false;
let starting: Promise<void> | null = null;
let bootAbortController: AbortController | null = null;
let bootGeneration = 0;

/*
 * Hard ceiling for the entire bootstrap. Individual phases have their own
 * polling (loadSession ~30 s with fs watch wake, readiness ~1 s = 5 × 200 ms
 * fail-fast) — on a full sidecar failure the loadSession step dominates the
 * wait. 60 s is longer than the verySlow escalation (25 s, see boot.ts), so
 * Retry/Restart buttons have time to surface and the user has time to click,
 * but not long enough for a stuck app to hang forever.
 */
const BOOT_TIMEOUT_MS = 60_000;
const E2E_BOOT_TIMEOUT_MS_KEY = 'mail.e2e.bootTimeoutMs';

export class BootTimeoutError extends Error {
	constructor(timeoutMs: number) {
		super(`Application did not start within ${Math.round(timeoutMs / 1000)} s.`);
		this.name = 'BootTimeoutError';
	}
}

function resolveBootTimeoutMs(): number {
	if (typeof localStorage === 'undefined' || localStorage.getItem('mail.e2e') !== '1') {
		return BOOT_TIMEOUT_MS;
	}
	const value = Number(localStorage.getItem(E2E_BOOT_TIMEOUT_MS_KEY));
	return Number.isFinite(value) && value > 0 ? value : BOOT_TIMEOUT_MS;
}

interface BootstrapOptions {
	force?: boolean;
	restartSidecar?: boolean;
}

export async function bootstrap(options: BootstrapOptions = {}): Promise<void> {
	const forceNewAttempt = options.force === true || options.restartSidecar === true;
	if (started && !forceNewAttempt) return;
	if (starting && !forceNewAttempt) return starting;

	if (forceNewAttempt) {
		bootAbortController?.abort();
		started = false;
		starting = null;
	}

	const generation = ++bootGeneration;
	const abortController = new AbortController();
	bootAbortController = abortController;

	const timeoutMs = resolveBootTimeoutMs();
	const timeoutError = new BootTimeoutError(timeoutMs);
	const timeoutId = setTimeout(() => {
		/*
		 * abort(reason) propaguje BootTimeoutError do vsech polling smycek, ktere
		 * cti signal.reason. Konkretni catch v bootstrap pak prevede signal.aborted
		 * na failBoot() s touto chybou, takze UI ukaze citelnou hlasku.
		 */
		abortController.abort(timeoutError);
	}, timeoutMs);

	const attempt = runBootstrap({
		generation,
		restartSidecar: options.restartSidecar === true,
		signal: abortController.signal,
		timeoutError
	})
		.then(() => {
			if (generation === bootGeneration) {
				started = true;
			}
		})
		.finally(() => {
			clearTimeout(timeoutId);
			if (starting === attempt) {
				starting = null;
			}
			if (bootAbortController === abortController) {
				bootAbortController = null;
			}
		});

	starting = attempt;
	return attempt;
}

async function runBootstrap({
	generation,
	restartSidecar,
	signal,
	timeoutError
}: {
	generation: number;
	restartSidecar: boolean;
	signal: AbortSignal;
	timeoutError: BootTimeoutError;
}): Promise<void> {
	beginBoot();
	/*
	 * Screen-reader-first: push one reliable polite announcement at boot start.
	 * BootLoadingView's status is a role="status" region, which only announces
	 * content CHANGES — its initial text is silent — so a SR user could get no
	 * spoken feedback until the first phase transition. This guarantees an
	 * immediate "loading" announcement; the role="status" region then announces
	 * each subsequent phase change itself. i18n is initialised eagerly at module
	 * import, so get(_) resolves synchronously here.
	 */
	announcePolite(get(_)('app.loadingApp'));
	try {
		const shouldRestartSidecar = restartSidecar && usesBackendSidecar();
		registerDefaultCommands();

		setBootPhase('starting-sidecar');
		markBootTiming('sidecarSpawnRequested');
		if (shouldRestartSidecar) {
			invalidateSession();
			await restartBackendSidecar();
		} else {
			await ensureBackendSidecar();
		}
		markBootTiming('sidecarRunning');

		setBootPhase('waiting-for-session');
		if (import.meta.env.VITE_E2E_MOCK === '1') {
			const { installE2EBypass } = await import('../test-fixtures/msw/worker.js');
			await installE2EBypass();
		}
		const currentSession = get(sessionState);
		const session =
			currentSession.status === 'ready' && !shouldRestartSidecar
				? currentSession.session
				: await initSession({ signal });
		markBootTiming('sessionFound');
		markBootTiming('handshakeOk');

		setBootPhase('checking-readiness');
		await waitForBackendReadiness(session, { signal });
		markBootTiming('readinessOk');

		/*
		 * loadClientConfig and loadAccounts are independent (different
		 * endpoint, different store), so we fetch them in parallel. The UI
		 * phase flips to 'loading-accounts' the moment the second fetch
		 * starts so the user sees progress; in reality both run at the same
		 * time and total wait shrinks by ~1 roundtrip.
		 */
		setBootPhase('loading-client-config');
		const accountsAlreadyReady = get(accountsState).status === 'ready';
		const clientConfigPromise = loadClientConfig().then(() => {
			markBootTiming('clientConfigOk');
		});
		const accountsPromise = accountsAlreadyReady
			? Promise.resolve()
			: (async () => {
					setBootPhase('loading-accounts');
					await loadAccounts();
				})();
		await Promise.all([clientConfigPromise, accountsPromise]);
		markBootTiming('accountsLoaded');

		startNotifications();
		void checkForUpdateAndPrompt();
		if (generation !== bootGeneration) return;
		completeBoot();
		void reportClientBootDiagnostics(get(bootState));
	} catch (err) {
		if (generation !== bootGeneration) {
			throw toError(err);
		}
		/*
		 * If the abort was caused by the bootstrap timeout (60s), polling
		 * loops surface it as their domain error (SessionLoadError /
		 * Backend*Error) with an "aborted" message. For the UI the original
		 * BootTimeoutError is more informative, so we prefer it when
		 * signal.reason === timeoutError.
		 */
		if (signal.aborted && signal.reason === timeoutError) {
			throw failBoot(timeoutError);
		}
		throw failBoot(err);
	}
}
