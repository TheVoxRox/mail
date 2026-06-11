import { writable } from 'svelte/store';
import { toError } from '$lib/api/errors.js';

type BootPhase =
	| 'idle'
	| 'starting-sidecar'
	| 'waiting-for-session'
	| 'checking-readiness'
	| 'loading-client-config'
	| 'loading-accounts'
	| 'ready'
	| 'failed';

export type BootSlowLevel = 'fast' | 'slow' | 'verySlow';

type BootTimingKey =
	| 'uiStart'
	| 'sidecarSpawnRequested'
	| 'sidecarRunning'
	| 'sessionFound'
	| 'handshakeOk'
	| 'readinessOk'
	| 'clientConfigOk'
	| 'accountsLoaded'
	| 'appReady';

export type BootTimings = Partial<Record<BootTimingKey, number>>;

export interface BootState {
	phase: BootPhase;
	slowLevel: BootSlowLevel;
	startedAt: number;
	timings: BootTimings;
	error?: Error;
}

const BOOT_SLOW_MS = 8_000;
const BOOT_VERY_SLOW_MS = 25_000;
const E2E_BOOT_SLOW_MS_KEY = 'mail.e2e.bootSlowMs';
const E2E_BOOT_VERY_SLOW_MS_KEY = 'mail.e2e.bootVerySlowMs';

let slowTimer: ReturnType<typeof setTimeout> | undefined;
let verySlowTimer: ReturnType<typeof setTimeout> | undefined;

function now(): number {
	if (typeof performance !== 'undefined') {
		return performance.now();
	}
	return Date.now();
}

function initialState(): BootState {
	const startedAt = now();
	return {
		phase: 'idle',
		slowLevel: 'fast',
		startedAt,
		timings: {
			uiStart: 0
		}
	};
}

export const bootState = writable<BootState>(initialState());

export function beginBoot(): void {
	clearBootEscalation();
	const startedAt = now();
	bootState.set({
		phase: 'starting-sidecar',
		slowLevel: 'fast',
		startedAt,
		timings: {
			uiStart: 0
		}
	});
	slowTimer = setTimeout(
		() => setBootSlowLevel('slow'),
		bootEscalationMs(E2E_BOOT_SLOW_MS_KEY, BOOT_SLOW_MS)
	);
	verySlowTimer = setTimeout(
		() => setBootSlowLevel('verySlow'),
		bootEscalationMs(E2E_BOOT_VERY_SLOW_MS_KEY, BOOT_VERY_SLOW_MS)
	);
}

export function setBootPhase(phase: BootPhase): void {
	bootState.update((state) => ({ ...state, phase, error: undefined }));
}

export function markBootTiming(key: BootTimingKey): void {
	bootState.update((state) => ({
		...state,
		timings: {
			...state.timings,
			[key]: Math.round(now() - state.startedAt)
		}
	}));
}

export function failBoot(error: unknown): Error {
	clearBootEscalation();
	const normalized = toError(error);
	bootState.update((state) => ({ ...state, phase: 'failed', error: normalized }));
	return normalized;
}

export function completeBoot(): void {
	clearBootEscalation();
	markBootTiming('appReady');
	bootState.update((state) => {
		const readyState = { ...state, phase: 'ready' as const, error: undefined };
		if (import.meta.env.DEV) {
			console.info('[mail] boot timings', readyState.timings);
		}
		return readyState;
	});
}

function setBootSlowLevel(slowLevel: BootSlowLevel): void {
	bootState.update((state) => (state.phase === 'ready' ? state : { ...state, slowLevel }));
}

function clearBootEscalation(): void {
	if (slowTimer) clearTimeout(slowTimer);
	if (verySlowTimer) clearTimeout(verySlowTimer);
	slowTimer = undefined;
	verySlowTimer = undefined;
}

function bootEscalationMs(key: string, fallback: number): number {
	if (typeof localStorage === 'undefined' || localStorage.getItem('mail.e2e') !== '1') {
		return fallback;
	}
	const value = Number(localStorage.getItem(key));
	return Number.isFinite(value) && value >= 0 ? value : fallback;
}
