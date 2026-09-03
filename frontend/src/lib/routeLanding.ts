/**
 * How a route says its own name when it lands.
 *
 * After every navigation the app moves focus to `<main>`, whose accessible name
 * is the route title — so landing there *is* the announcement, and no separate
 * one is wanted (see frontend/src/app.css for why SvelteKit's built-in route
 * announcer was silenced rather than kept as a second channel: `assertive`
 * jumps the speech queue, and it named the page the user had just left).
 *
 * That leaves the case where focus is already gone. Three places autofocus a
 * control of their own — the welcome screen's Add-account button, the composer's
 * first recipient field, and the command palette's search box — and the layout
 * deliberately does not take focus back from them. The landmark is then never
 * entered, so with the built-in announcer silenced nothing names the route at
 * all: measured on Ctrl+N in the running desktop app, the reader got the
 * composer's field and nothing else.
 *
 * So the two channels are alternatives for one job, and the rule pairs them:
 * exactly one fires per landing, never both (which is the duplication the
 * built-in announcer was silenced for) and never neither.
 *
 * Announcing is safe at the moment the landing is decided. Measured over CDP in
 * the running app, Ctrl+N writes the new title 3.8 ms *before* the composer's
 * autofocus fires, so the name read here is the route being entered — not, as
 * with the built-in announcer, the one being left.
 */

export type RouteLandingDelivery =
	/** Focus is still at its default, so move it to the landmark that is named. */
	| { channel: 'focus-main' }
	/** Focus was claimed elsewhere; the route has to name itself out loud. */
	| { channel: 'announce'; text: string }
	/** Nothing to deliver — no name yet, or this landing already said it. */
	| { channel: 'none' };

/**
 * @param focusIsDefault focus is still on `<body>`/`<html>`, i.e. nobody claimed it
 * @param routeName the route's title, empty until boot settles the first one
 * @param announcedForLanding what this same landing already announced, if anything
 */
export function routeLandingDelivery(
	focusIsDefault: boolean,
	routeName: string,
	announcedForLanding: string | null
): RouteLandingDelivery {
	if (focusIsDefault) return { channel: 'focus-main' };
	// Boot has not settled a title yet; the landmark carries a workspace
	// fallback for that, but saying "Mail" out loud would be noise.
	if (!routeName) return { channel: 'none' };
	/*
	 * One landing can be decided twice: `afterNavigate` schedules a check, and
	 * so does the shell becoming ready, and on a cold start into an autofocusing
	 * route both can find focus already claimed. Saying it twice is the exact
	 * defect the built-in announcer was silenced over, so the second one is
	 * dropped — by name rather than by a flag, so that leaving a route and
	 * coming back to it (Ctrl+N, Esc, Ctrl+N) still announces both times.
	 */
	if (routeName === announcedForLanding) return { channel: 'none' };
	return { channel: 'announce', text: routeName };
}
