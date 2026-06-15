import { beforeNavigate } from '$app/navigation';
import { page } from '$app/stores';
import { get } from 'svelte/store';

export interface LeaveGuardOptions {
	/** True while the guard should intercept navigation (e.g. dirty && !busy && !bypass). */
	shouldGuard: () => boolean;
	/** Called, after the navigation is cancelled, with the blocked in-app target. */
	onBlocked: (target: URL) => void;
	/**
	 * Treats a navigation whose target equals the current location as a no-op (no
	 * prompt). Defaults to comparing pathname + search; pass a custom comparator
	 * when some query params should not count as a real navigation.
	 */
	isSameTarget?: (next: URL, current: URL) => boolean;
}

/**
 * Installs an unsaved-changes navigation guard. Must be called during component
 * initialisation — it registers a {@link beforeNavigate} hook. A full-page leave
 * (tab close / reload) is cancelled outright so the browser shows its own
 * prompt; an in-app navigation is cancelled and handed to {@link
 * LeaveGuardOptions.onBlocked} so the caller can confirm and then navigate.
 *
 * Shared by ComposeForm and ContactForm so the guard semantics live in one place.
 */
export function installLeaveGuard(options: LeaveGuardOptions): void {
	const isSameTarget =
		options.isSameTarget ??
		((next, current) => next.pathname + next.search === current.pathname + current.search);

	beforeNavigate((navigation) => {
		if (!options.shouldGuard()) return;
		if (navigation.type === 'leave') {
			navigation.cancel();
			return;
		}
		const nextUrl = navigation.to?.url;
		if (!nextUrl) return;
		if (isSameTarget(nextUrl, get(page).url)) return;
		navigation.cancel();
		options.onBlocked(nextUrl);
	});
}
