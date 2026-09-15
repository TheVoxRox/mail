// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest';
import { installFileDropGuard } from './fileDropGuard.js';

/** jsdom has no DataTransfer, and the guard reads only its `types`. */
function dragEvent(type: 'dragover' | 'drop', types: string[]): Event {
	const event = new Event(type, { bubbles: true, cancelable: true });
	Object.defineProperty(event, 'dataTransfer', { value: { types } });
	return event;
}

const removers: Array<() => void> = [];

afterEach(() => {
	removers.splice(0).forEach((remove) => remove());
	document.body.replaceChildren();
});

describe('installFileDropGuard', () => {
	it('cancels a file dragged over and dropped outside every drop zone', () => {
		removers.push(installFileDropGuard());

		const over = dragEvent('dragover', ['Files']);
		const drop = dragEvent('drop', ['Files']);
		document.body.dispatchEvent(over);
		document.body.dispatchEvent(drop);

		expect(over.defaultPrevented).toBe(true);
		expect(drop.defaultPrevented).toBe(true);
	});

	it('leaves a text drag alone, so moving a selection in the editor still works', () => {
		removers.push(installFileDropGuard());

		const over = dragEvent('dragover', ['text/plain', 'text/html']);
		const drop = dragEvent('drop', ['text/plain', 'text/html']);
		document.body.dispatchEvent(over);
		document.body.dispatchEvent(drop);

		expect(over.defaultPrevented).toBe(false);
		expect(drop.defaultPrevented).toBe(false);
	});

	it('reaches a drop zone only after the zone has handled the drop itself', () => {
		removers.push(installFileDropGuard());
		const zone = document.createElement('div');
		document.body.append(zone);
		const cancelledWhenZoneRan: boolean[] = [];
		zone.addEventListener('drop', (event) => {
			cancelledWhenZoneRan.push(event.defaultPrevented);
			event.preventDefault();
		});

		const drop = dragEvent('drop', ['Files']);
		zone.dispatchEvent(drop);

		// The zone ran first (element before window) and found nothing cancelled,
		// so a zone that bails on defaultPrevented is not starved by the guard.
		expect(cancelledWhenZoneRan).toEqual([false]);
		expect(drop.defaultPrevented).toBe(true);
	});

	it('stops guarding once removed', () => {
		const remove = installFileDropGuard();
		remove();

		const drop = dragEvent('drop', ['Files']);
		document.body.dispatchEvent(drop);

		expect(drop.defaultPrevented).toBe(false);
	});
});
