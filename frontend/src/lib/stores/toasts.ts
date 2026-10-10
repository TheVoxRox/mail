import { get, writable } from 'svelte/store';
import { toastDismissal } from './uiLayout.js';

export type ToastTone = 'info' | 'success' | 'error';

interface Toast {
	id: number;
	message: string;
	tone: ToastTone;
	/** auto-dismiss delay in ms; 0 or negative means persistent, as does the `manual` toastDismissal preference */
	ttl: number;
}

export const toasts = writable<Toast[]>([]);

/*
 * Screen-reader announcements are decoupled from the visual toasts. A live
 * region only gets announced by NVDA/JAWS when it already exists in the DOM
 * before its content changes — a freshly inserted node that itself carries
 * `aria-live`/`role="status"` is unreliable. ToastRegion renders two
 * persistent (always-mounted) live containers and we push transient text
 * children into them here; the child is removed shortly after so repeated
 * identical messages still re-announce (each is a distinct node).
 */
interface LiveAnnouncement {
	id: number;
	message: string;
}

export const politeAnnouncements = writable<LiveAnnouncement[]>([]);
export const assertiveAnnouncements = writable<LiveAnnouncement[]>([]);

const ANNOUNCEMENT_CLEAR_MS = 1500;

let nextId = 1;

/**
 * The auto-dismiss clock of each toast that has one. `handle` is null while
 * the clocks are paused, and `remaining` is what is left of the toast's time
 * from `startedAt` on.
 */
interface Countdown {
	handle: ReturnType<typeof setTimeout> | null;
	remaining: number;
	startedAt: number;
}
const countdowns = new Map<number, Countdown>();
let paused = false;

function run(id: number, countdown: Countdown): void {
	countdown.startedAt = Date.now();
	countdown.handle = setTimeout(() => dismissToast(id), countdown.remaining);
}

/**
 * Stops every toast's clock, for as long as the pointer or focus is on the
 * toasts (ToastRegion): a message being read or about to be closed should not
 * close under the reader. WCAG 2.2.1.
 */
export function pauseToastCountdowns(): void {
	if (paused) return;
	paused = true;
	for (const countdown of countdowns.values()) {
		if (countdown.handle === null) continue;
		clearTimeout(countdown.handle);
		countdown.handle = null;
		countdown.remaining -= Date.now() - countdown.startedAt;
	}
}

/** Restarts the clocks `pauseToastCountdowns` stopped, each with the time it had left. */
export function resumeToastCountdowns(): void {
	if (!paused) return;
	paused = false;
	for (const [id, countdown] of countdowns) run(id, countdown);
}

// A user who turns automatic closing off means the toasts already on screen too.
toastDismissal.subscribe((value) => {
	if (value !== 'manual') return;
	for (const countdown of countdowns.values()) {
		if (countdown.handle !== null) clearTimeout(countdown.handle);
	}
	countdowns.clear();
});

export function pushToast(
	message: string,
	options: { tone?: ToastTone; ttl?: number } = {}
): number {
	const id = nextId++;
	const toast: Toast = {
		id,
		message,
		tone: options.tone ?? 'info',
		ttl: options.ttl ?? 5000
	};
	toasts.update((list) => [...list, toast]);
	announce(toast.message, toast.tone === 'error');
	if (toast.ttl > 0 && get(toastDismissal) === 'auto') {
		const countdown: Countdown = { handle: null, remaining: toast.ttl, startedAt: Date.now() };
		countdowns.set(id, countdown);
		if (!paused) run(id, countdown);
	}
	return id;
}

function announce(message: string, assertive: boolean): void {
	const id = nextId++;
	const store = assertive ? assertiveAnnouncements : politeAnnouncements;
	store.update((list) => [...list, { id, message }]);
	setTimeout(() => store.update((list) => list.filter((a) => a.id !== id)), ANNOUNCEMENT_CLEAR_MS);
}

/**
 * Pushes a screen-reader-only message into the persistent polite live region
 * without showing a visual toast. Use for status that is conveyed visually by
 * other means (e.g. the pagination footer updating) but still needs to be
 * announced to assistive tech.
 */
export function announcePolite(message: string): void {
	announce(message, false);
}

export function dismissToast(id: number): void {
	const countdown = countdowns.get(id);
	if (countdown) {
		if (countdown.handle !== null) clearTimeout(countdown.handle);
		countdowns.delete(id);
	}
	toasts.update((list) => list.filter((t) => t.id !== id));
}
