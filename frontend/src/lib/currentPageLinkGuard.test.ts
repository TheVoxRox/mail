// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { installCurrentPageLinkGuard } from './currentPageLinkGuard.js';

const removers: Array<() => void> = [];

beforeEach(() => {
	window.history.replaceState(null, '', '/mail/7/INBOX');
});

afterEach(() => {
	removers.splice(0).forEach((remove) => remove());
	document.body.replaceChildren();
});

function link(href: string, attributes: Record<string, string> = {}): HTMLAnchorElement {
	const anchor = document.createElement('a');
	anchor.href = href;
	for (const [name, value] of Object.entries(attributes)) anchor.setAttribute(name, value);
	anchor.textContent = 'Inbox';
	document.body.append(anchor);
	return anchor;
}

function click(element: Element, init: MouseEventInit = {}): MouseEvent {
	const event = new MouseEvent('click', { bubbles: true, cancelable: true, button: 0, ...init });
	element.dispatchEvent(event);
	return event;
}

describe('installCurrentPageLinkGuard', () => {
	it('cancels a click on a link to the page already open', () => {
		removers.push(installCurrentPageLinkGuard());

		expect(click(link('/mail/7/INBOX')).defaultPrevented).toBe(true);
	});

	it('cancels it from an element inside the link, as a click on its text lands', () => {
		removers.push(installCurrentPageLinkGuard());
		const anchor = link('/mail/7/INBOX');
		const label = document.createElement('span');
		anchor.replaceChildren(label);

		expect(click(label).defaultPrevented).toBe(true);
	});

	it('leaves a link to another page to the router', () => {
		removers.push(installCurrentPageLinkGuard());

		expect(click(link('/mail/7/Sent')).defaultPrevented).toBe(false);
		expect(click(link('/mail/7/INBOX?page=2')).defaultPrevented).toBe(false);
		expect(click(link('/mail/7/INBOX#top')).defaultPrevented).toBe(false);
	});

	it('leaves a link with a fragment to the router, the one already in the URL included', () => {
		// The skip link, used once: its hash is now in the URL, and the second use
		// must still reach SvelteKit, which scrolls to the target and focuses it.
		window.history.replaceState(null, '', '/mail/7/INBOX#main-content');
		removers.push(installCurrentPageLinkGuard());

		expect(click(link('#main-content')).defaultPrevented).toBe(false);
		expect(click(link('/mail/7/INBOX#main-content')).defaultPrevented).toBe(false);
	});

	it('still cancels a link to the page already open once a fragment is in the URL', () => {
		window.history.replaceState(null, '', '/mail/7/INBOX#main-content');
		removers.push(installCurrentPageLinkGuard());

		expect(click(link('/mail/7/INBOX')).defaultPrevented).toBe(true);
	});

	it('leaves alone what the router would not handle either', () => {
		removers.push(installCurrentPageLinkGuard());

		expect(click(link('/mail/7/INBOX'), { ctrlKey: true }).defaultPrevented).toBe(false);
		expect(click(link('/mail/7/INBOX'), { button: 1 }).defaultPrevented).toBe(false);
		expect(click(link('/mail/7/INBOX', { target: '_blank' })).defaultPrevented).toBe(false);
		expect(click(link('/mail/7/INBOX', { download: '' })).defaultPrevented).toBe(false);
	});

	it('runs after a handler on the link itself, which keeps the click it took', () => {
		removers.push(installCurrentPageLinkGuard());
		const anchor = link('/mail/7/INBOX');
		let handled = false;
		anchor.addEventListener('click', () => {
			handled = true;
		});

		const event = click(anchor);

		expect(handled).toBe(true);
		expect(event.defaultPrevented).toBe(true);
	});

	it('stops guarding once removed', () => {
		installCurrentPageLinkGuard()();

		expect(click(link('/mail/7/INBOX')).defaultPrevented).toBe(false);
	});
});
