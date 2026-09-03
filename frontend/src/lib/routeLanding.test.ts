import { describe, expect, it } from 'vitest';
import { routeLandingDelivery } from './routeLanding.js';

/*
 * Route names here are placeholders, not the app's own titles: the rule never
 * looks inside the string, and real titles carry diacritics that would have to
 * be whitelisted out of the translation gate — which is for fixtures, not for
 * what a test asserts.
 */
const INBOX = 'Mail - Inbox';
const COMPOSE = 'Mail - New message';
const WELCOME = 'Mail - Welcome';

describe('routeLandingDelivery', () => {
	it('moves focus to the landmark when nobody claimed it', () => {
		expect(routeLandingDelivery(true, INBOX, null)).toEqual({ channel: 'focus-main' });
	});

	/*
	 * The pairing this rule exists for: the landmark is named after the route,
	 * so entering it already says the name. Announcing as well is the
	 * duplication that got SvelteKit's built-in announcer silenced.
	 */
	it('does not also announce when it moved focus to the landmark', () => {
		expect(routeLandingDelivery(true, COMPOSE, null).channel).not.toBe('announce');
	});

	it('announces the route when an autofocus took the focus first', () => {
		expect(routeLandingDelivery(false, COMPOSE, null)).toEqual({
			channel: 'announce',
			text: COMPOSE
		});
	});

	/*
	 * Boot has not settled a title yet. The landmark falls back to the workspace
	 * name for its label, but saying that out loud names the environment rather
	 * than the place — the failure that moved the landmark label off the
	 * workspace name in the first place.
	 */
	it('stays quiet when the route has no name yet', () => {
		expect(routeLandingDelivery(false, '', null)).toEqual({ channel: 'none' });
	});

	/*
	 * `afterNavigate` and the shell becoming ready both schedule a check, and on
	 * a cold start into an autofocusing route both can find focus claimed.
	 */
	it('says it once per landing even when the landing is decided twice', () => {
		expect(routeLandingDelivery(false, WELCOME, WELCOME)).toEqual({ channel: 'none' });
	});

	/*
	 * Deduplication is by name, not by an "already spoke" flag, so that leaving
	 * a route and coming back to it announces again - Ctrl+N, Esc, Ctrl+N.
	 */
	it('announces again on re-entry, because the previous landing named something else', () => {
		expect(routeLandingDelivery(false, COMPOSE, INBOX)).toEqual({
			channel: 'announce',
			text: COMPOSE
		});
	});

	/*
	 * The guard must not outlive the case it is for: a landing that moved focus
	 * to the landmark is still a focus-main, whatever was announced before it.
	 */
	it('still moves focus to the landmark when the same name was announced earlier', () => {
		expect(routeLandingDelivery(true, WELCOME, WELCOME)).toEqual({ channel: 'focus-main' });
	});
});
