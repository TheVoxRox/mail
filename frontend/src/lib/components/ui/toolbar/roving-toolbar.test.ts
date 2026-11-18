// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { rovingToolbar } from './roving-toolbar.js';

let toolbar: HTMLElement;
let detach: (() => void) | void;

/** MutationObserver callbacks run as microtasks; one macrotask flushes them. */
const settle = () => new Promise((resolve) => setTimeout(resolve));

const button = (name: string) => toolbar.querySelector<HTMLElement>(`[data-name="${name}"]`)!;
const stops = () =>
	[...toolbar.querySelectorAll<HTMLElement>('[tabindex="0"]')].map((el) => el.dataset.name);

function press(key: string, target = document.activeElement as HTMLElement): KeyboardEvent {
	const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true });
	target.dispatchEvent(event);
	return event;
}

beforeEach(() => {
	document.body.innerHTML = `
		<div role="toolbar" aria-label="Actions">
			<label><input type="checkbox" data-name="all" /> All</label>
			<button data-name="reply">Reply</button>
			<button data-name="forward" disabled>Forward</button>
			<button data-name="move" aria-haspopup="menu">Move</button>
			<input type="text" data-name="filter" />
		</div>
		<button data-name="outside">Outside</button>`;
	toolbar = document.querySelector('[role="toolbar"]')!;
	detach = rovingToolbar()(toolbar);
});

afterEach(() => {
	if (typeof detach === 'function') detach();
	document.body.innerHTML = '';
});

describe('rovingToolbar', () => {
	it('leaves one Tab stop, the first enabled control', () => {
		expect(stops()).toEqual(['all']);
		expect(button('reply').getAttribute('tabindex')).toBe('-1');
		expect(button('forward').getAttribute('tabindex')).toBe('-1');
	});

	it('moves with the arrows, past a disabled control, wrapping at the ends', () => {
		button('all').focus();
		press('ArrowRight');
		expect(document.activeElement).toBe(button('reply'));
		press('ArrowRight');
		expect(document.activeElement).toBe(button('move'));
		press('ArrowLeft');
		expect(document.activeElement).toBe(button('reply'));
		press('Home');
		press('ArrowLeft');
		expect(document.activeElement).toBe(button('filter'));
		expect(stops()).toEqual(['filter']);
	});

	it('goes to the first and the last control with Home and End', () => {
		button('reply').focus();
		press('End');
		expect(document.activeElement).toBe(button('filter'));
		button('move').focus();
		press('Home');
		expect(document.activeElement).toBe(button('all'));
	});

	/* In a text field Home and End move the caret, as the arrows do. */
	it('leaves Home and End to a text field', () => {
		button('filter').focus();
		expect(press('Home').defaultPrevented).toBe(false);
		expect(document.activeElement).toBe(button('filter'));
	});

	/* A menu button opens its menu on ArrowDown; the toolbar must not take it. */
	it('leaves ArrowDown and ArrowUp to the control', () => {
		button('move').focus();
		expect(press('ArrowDown').defaultPrevented).toBe(false);
		expect(press('ArrowUp').defaultPrevented).toBe(false);
		expect(document.activeElement).toBe(button('move'));
	});

	it('leaves the arrows to a text field', () => {
		button('filter').focus();
		expect(press('ArrowLeft').defaultPrevented).toBe(false);
		expect(document.activeElement).toBe(button('filter'));
	});

	it('does not take a key another handler already used', () => {
		button('reply').focus();
		const event = new KeyboardEvent('keydown', {
			key: 'ArrowRight',
			bubbles: true,
			cancelable: true
		});
		event.preventDefault();
		button('reply').dispatchEvent(event);
		expect(document.activeElement).toBe(button('reply'));
	});

	it('makes the control focused last the Tab stop', () => {
		button('move').focus();
		expect(stops()).toEqual(['move']);
	});

	it('moves the Tab stop off a control that becomes disabled', async () => {
		button('move').focus();
		button('move').setAttribute('disabled', '');
		await settle();
		expect(stops()).toEqual(['all']);
	});

	it('gives a control added later no Tab stop of its own', async () => {
		const added = document.createElement('button');
		added.dataset.name = 'added';
		toolbar.append(added);
		await settle();
		expect(added.getAttribute('tabindex')).toBe('-1');
		expect(stops()).toEqual(['all']);
	});

	it('takes a control re-enabled later back as one more -1', async () => {
		button('forward').removeAttribute('disabled');
		await settle();
		expect(button('forward').getAttribute('tabindex')).toBe('-1');
	});

	it('stops handling keys once detached', () => {
		if (typeof detach === 'function') detach();
		detach = undefined;
		button('reply').focus();
		expect(press('ArrowRight').defaultPrevented).toBe(false);
	});
});
