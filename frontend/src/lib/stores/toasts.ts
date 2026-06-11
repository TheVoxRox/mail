import { writable } from 'svelte/store';

export type ToastTone = 'info' | 'success' | 'error';

interface Toast {
	id: number;
	message: string;
	tone: ToastTone;
	/** auto-dismiss delay in ms; 0 or negative means persistent */
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
const timers = new Map<number, ReturnType<typeof setTimeout>>();

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
	if (toast.ttl > 0) {
		const handle = setTimeout(() => dismissToast(id), toast.ttl);
		timers.set(id, handle);
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
	const handle = timers.get(id);
	if (handle) {
		clearTimeout(handle);
		timers.delete(id);
	}
	toasts.update((list) => list.filter((t) => t.id !== id));
}
