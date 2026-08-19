// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import { createLatestSelection, isRowBackgroundClick } from './rowActivation.js';

/** Builds a row whose innerHTML is `markup` and clicks the element at `selector`. */
function clickInRow(markup: string, selector: string): MouseEvent {
	const row = document.createElement('div');
	row.setAttribute('role', 'row');
	row.innerHTML = markup;
	document.body.replaceChildren(row);
	// The grids preventDefault on a link click of their own; doing it here keeps
	// jsdom from warning about a navigation it will not perform either way.
	row.addEventListener('click', (event) => event.preventDefault());
	const target = selector === ':scope' ? row : row.querySelector(selector);
	if (!target) throw new Error(`no element matched ${selector}`);
	const event = new MouseEvent('click', { bubbles: true, cancelable: true });
	target.dispatchEvent(event);
	return event;
}

describe('isRowBackgroundClick', () => {
	it('is true for the row itself', () => {
		expect(isRowBackgroundClick(clickInRow('<span>Predmet</span>', ':scope'))).toBe(true);
	});

	it('is true for a plain cell inside the row', () => {
		expect(isRowBackgroundClick(clickInRow('<span id="date">10:24</span>', '#date'))).toBe(true);
	});

	it('is false for the select checkbox', () => {
		expect(isRowBackgroundClick(clickInRow('<input id="pick" type="checkbox" />', '#pick'))).toBe(
			false
		);
	});

	it('is false for the row-actions trigger', () => {
		expect(isRowBackgroundClick(clickInRow('<button id="menu">…</button>', '#menu'))).toBe(false);
	});

	it('is false for the subject link', () => {
		expect(
			isRowBackgroundClick(clickInRow('<a id="subject" href="/mail">Re:</a>', '#subject'))
		).toBe(false);
	});

	it('is false for the padding of the label around a checkbox', () => {
		// The 24px pointer target WCAG 2.5.8 asks for is a label around a 16px
		// box, so a press near the edge lands on the label. The browser forwards
		// it to the checkbox; treating it as a row click would open the row from
		// the same press that ticked it.
		const event = clickInRow('<label id="box"><input type="checkbox" /></label>', '#box');
		expect(isRowBackgroundClick(event)).toBe(false);
	});

	it('is false for anything nested inside a control', () => {
		// The click a screen reader sends in place of Enter can land on the label
		// span inside the link, not on the link element.
		const event = clickInRow('<a href="/mail"><span id="label">Re:</span></a>', '#label');
		expect(isRowBackgroundClick(event)).toBe(false);
	});

	it('treats a targetless click as the row', () => {
		expect(isRowBackgroundClick(new MouseEvent('click'))).toBe(true);
	});
});

describe('createLatestSelection', () => {
	it('a lone selection is still the newest when it settles', () => {
		const isLatest = createLatestSelection().begin();
		expect(isLatest()).toBe(true);
	});

	it('a superseded selection is not, but the newer one is', () => {
		const selection = createLatestSelection();
		const first = selection.begin();
		const second = selection.begin();
		expect(first()).toBe(false);
		expect(second()).toBe(true);
	});

	it('only the last of a rapid burst may refocus', () => {
		const selection = createLatestSelection();
		const pending = [selection.begin(), selection.begin(), selection.begin()];
		expect(pending.map((isLatest) => isLatest())).toEqual([false, false, true]);
	});

	it('retire() cancels a queued refocus without arming a new one', () => {
		// A deliberate open (click, Enter) sends the reading cursor into the
		// message body; the refocus an earlier row change queued must not drag it
		// back to the grid cell.
		const selection = createLatestSelection();
		const queued = selection.begin();
		selection.retire();
		expect(queued()).toBe(false);
	});

	it('a selection started after retire() still counts as the newest', () => {
		const selection = createLatestSelection();
		selection.retire();
		expect(selection.begin()()).toBe(true);
	});

	it('grids do not share a guard', () => {
		const one = createLatestSelection();
		const other = createLatestSelection();
		const pending = one.begin();
		other.begin();
		expect(pending()).toBe(true);
	});
});
