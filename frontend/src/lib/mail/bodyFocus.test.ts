import { describe, expect, it } from 'vitest';
import { shouldFocusBody, type BodyFocusIntent } from './bodyFocus.js';

const open = (stableId: string): BodyFocusIntent => ({ stableId, mode: 'open' });
const follow = (stableId: string): BodyFocusIntent => ({ stableId, mode: 'follow' });

describe('shouldFocusBody', () => {
	it('focuses on a deliberate open', () => {
		expect(shouldFocusBody(open('a'), 'a', true)).toBe(true);
	});

	it('focuses on a deliberate open even when the pane already showed the message', () => {
		// Enter on the row whose message is already open: nothing re-renders, but
		// the user asked for the body, so the intent has to win over firstRender.
		expect(shouldFocusBody(open('a'), 'a', false)).toBe(true);
	});

	it('leaves the list alone when the selection merely followed the roving focus', () => {
		expect(shouldFocusBody(follow('a'), 'a', true)).toBe(false);
	});

	it('focuses with no intent recorded at all - a deep link, a reload, the route mounting alone', () => {
		expect(shouldFocusBody(null, 'a', true)).toBe(true);
	});

	it('does not focus again on a re-render with no intent', () => {
		expect(shouldFocusBody(null, 'a', false)).toBe(false);
	});

	/*
	 * The case the rule was extracted for. The intent is single-use, so one that
	 * still names another message means that message's body never rendered: the
	 * user paged past it and two navigations are in flight. The late arrival used
	 * to read as "no intent" and take the deep-link default, dragging the reading
	 * cursor into the sandboxed iframe and out of the grid. Measured before the
	 * fix as 2 escapes in 40 fast paging cycles, and it is the signature of the
	 * a11y CI flake on Home/End/PageDown/PageUp.
	 */
	it('does not focus when the intent names a different message - navigations overlapped', () => {
		expect(shouldFocusBody(follow('b'), 'a', true)).toBe(false);
	});

	it('does not focus on a stale open intent either - it belongs to the message paged past', () => {
		expect(shouldFocusBody(open('b'), 'a', true)).toBe(false);
	});
});
