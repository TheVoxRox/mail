/**
 * Thin abstraction over native push notifications.
 *
 * Uses the standard web `window.Notification` API — it works in the Tauri
 * webview (v2 exposes it the same as a regular browser) and in `vite dev`
 * in the browser. If the API is unavailable or the user denies notification
 * permission, the function silently does nothing. Never throws.
 */

export async function ensureNotificationPermission(): Promise<boolean> {
	if (typeof window === 'undefined' || !('Notification' in window)) return false;
	const N = window.Notification;
	if (N.permission === 'granted') return true;
	if (N.permission === 'denied') return false;
	try {
		const result = await N.requestPermission();
		return result === 'granted';
	} catch {
		return false;
	}
}

export function notifyUser(title: string, body: string): void {
	if (typeof window === 'undefined' || !('Notification' in window)) return;
	const N = window.Notification;
	if (N.permission !== 'granted') return;
	try {
		new N(title, { body });
	} catch {
		// ignore — non-critical functionality
	}
}
