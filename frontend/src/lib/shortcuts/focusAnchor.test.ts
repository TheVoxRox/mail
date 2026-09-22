// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest';
import { focusAnchor } from './focusAnchor.js';

function mount(html: string): void {
	document.body.innerHTML = html;
}

function byId(id: string): Element {
	const element = document.getElementById(id);
	if (!element) throw new Error(`no #${id}`);
	return element;
}

afterEach(() => {
	document.body.innerHTML = '';
});

describe('focusAnchor', () => {
	it('lets an element outside any menu stand for itself', () => {
		mount('<main><button id="plain">Reply</button></main>');

		expect(focusAnchor(byId('plain'))).toBe(byId('plain'));
	});

	it('takes an item of an open menu back to the trigger that names it', () => {
		mount(`
			<section data-print="document">
				<button id="trigger" aria-haspopup="menu" aria-controls="more-menu">More</button>
			</section>
			<div role="menu" id="more-menu"><div role="menuitem" id="item">Delete</div></div>
		`);

		expect(focusAnchor(byId('item'))).toBe(byId('trigger'));
		expect(focusAnchor(byId('item'))?.closest('[data-print="document"]')).not.toBeNull();
	});

	it('walks out of a submenu to the trigger of the menu it hangs from', () => {
		mount(`
			<button id="trigger" aria-controls="outer">Move</button>
			<div role="menu" id="outer">
				<div role="menuitem" id="sub-trigger" aria-controls="inner">Archive</div>
			</div>
			<div role="menu" id="inner"><div role="menuitem" id="deep">2026</div></div>
		`);

		expect(focusAnchor(byId('deep'))).toBe(byId('trigger'));
	});

	it('reads aria-controls as an id list', () => {
		mount(`
			<button id="trigger" aria-controls="panel more-menu">More</button>
			<div role="menu" id="more-menu"><div role="menuitem" id="item">Delete</div></div>
		`);

		expect(focusAnchor(byId('item'))).toBe(byId('trigger'));
	});

	it('leaves a menu nothing names where it is', () => {
		mount('<div role="menu" id="orphan"><div role="menuitem" id="item">Copy</div></div>');

		expect(focusAnchor(byId('item'))).toBe(byId('item'));
	});

	it('stops on a menu whose trigger sits inside the menu itself', () => {
		mount(`
			<div role="menu" id="loop">
				<div role="menuitem" id="item" aria-controls="loop">Again</div>
			</div>
		`);

		expect(focusAnchor(byId('item'))).toBe(byId('item'));
	});

	it('passes nothing through as nothing', () => {
		expect(focusAnchor(null)).toBeNull();
	});
});
