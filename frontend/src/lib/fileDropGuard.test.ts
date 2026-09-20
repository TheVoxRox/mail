// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest';
import { installFileDropGuard } from './fileDropGuard.js';

/** jsdom has no DataTransfer, and the guard reads only `types` and `dropEffect`. */
function dragEvent(type: 'dragover' | 'drop', types: string[]): DragEvent {
	const event = new Event(type, { bubbles: true, cancelable: true }) as DragEvent;
	Object.defineProperty(event, 'dataTransfer', { value: { types, dropEffect: 'copy' } });
	return event;
}

const FILE_DRAG = ['Files'];
const LINK_DRAG = ['text/uri-list', 'text/plain'];
const SELECTION_DRAG = ['text/plain', 'text/html'];

const removers: Array<() => void> = [];

afterEach(() => {
	removers.splice(0).forEach((remove) => remove());
	document.body.replaceChildren();
});

describe('installFileDropGuard', () => {
	it('cancels a file dragged over and dropped outside every drop zone', () => {
		removers.push(installFileDropGuard());

		const over = dragEvent('dragover', FILE_DRAG);
		const drop = dragEvent('drop', FILE_DRAG);
		document.body.dispatchEvent(over);
		document.body.dispatchEvent(drop);

		expect(over.defaultPrevented).toBe(true);
		expect(drop.defaultPrevented).toBe(true);
	});

	it('says the drop does nothing, rather than leaving the copy cursor on', () => {
		removers.push(installFileDropGuard());

		const over = dragEvent('dragover', FILE_DRAG);
		document.body.dispatchEvent(over);

		expect(over.dataTransfer?.dropEffect).toBe('none');
	});

	it('cancels a link dropped outside a text field, which would navigate away', () => {
		removers.push(installFileDropGuard());

		const over = dragEvent('dragover', LINK_DRAG);
		const drop = dragEvent('drop', LINK_DRAG);
		document.body.dispatchEvent(over);
		document.body.dispatchEvent(drop);

		expect(over.defaultPrevented).toBe(true);
		expect(drop.defaultPrevented).toBe(true);
	});

	it('leaves a link dropped in a text field alone, so it inserts its address', () => {
		removers.push(installFileDropGuard());
		const body = document.createElement('textarea');
		document.body.append(body);

		const drop = dragEvent('drop', LINK_DRAG);
		body.dispatchEvent(drop);

		expect(drop.defaultPrevented).toBe(false);
	});

	/*
	 * Measured against Chromium with a real drag, not assumed: a link dropped on
	 * a checkbox fires no drop event on the page at all — it behaves like a plain
	 * div, so nothing can claim it and the browser's own default for a link is
	 * what runs. Every text-ish input type does take the drop and insert the
	 * address, which is why the guard must stand aside for those and only those.
	 */
	it('cancels a link dropped on an input that takes no text, which a text field would have kept', () => {
		removers.push(installFileDropGuard());
		const box = document.createElement('input');
		box.type = 'checkbox';
		document.body.append(box);

		const drop = dragEvent('drop', LINK_DRAG);
		box.dispatchEvent(drop);

		expect(drop.defaultPrevented).toBe(true);
	});

	it('leaves a link dropped in any text-entry input alone', () => {
		removers.push(installFileDropGuard());

		for (const type of ['text', 'search', 'url', 'email', 'tel']) {
			const field = document.createElement('input');
			field.type = type;
			document.body.append(field);

			const drop = dragEvent('drop', LINK_DRAG);
			field.dispatchEvent(drop);

			expect(drop.defaultPrevented, type).toBe(false);
		}
	});

	it('leaves a text drag alone, so moving a selection in the editor still works', () => {
		removers.push(installFileDropGuard());

		const over = dragEvent('dragover', SELECTION_DRAG);
		const drop = dragEvent('drop', SELECTION_DRAG);
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

		const drop = dragEvent('drop', FILE_DRAG);
		zone.dispatchEvent(drop);

		// The zone ran first (element before window) and found nothing cancelled,
		// so a zone that bails on defaultPrevented is not starved by the guard.
		expect(cancelledWhenZoneRan).toEqual([false]);
		expect(drop.defaultPrevented).toBe(true);
	});

	it('lets a zone sharing the window claim the drop after the guard has refused it', () => {
		// The contacts page listens on `window` too, and which of the two was
		// added first depends on whether the page was navigated to or loaded
		// into. Setting its own dropEffect is what makes that not matter.
		removers.push(installFileDropGuard());
		const zone = (event: DragEvent) => {
			event.preventDefault();
			if (event.dataTransfer) event.dataTransfer.dropEffect = 'copy';
		};
		window.addEventListener('dragover', zone);
		removers.push(() => window.removeEventListener('dragover', zone));

		const over = dragEvent('dragover', FILE_DRAG);
		document.body.dispatchEvent(over);

		expect(over.dataTransfer?.dropEffect).toBe('copy');
	});

	it('stops guarding once removed', () => {
		const remove = installFileDropGuard();
		remove();

		const drop = dragEvent('drop', FILE_DRAG);
		document.body.dispatchEvent(drop);

		expect(drop.defaultPrevented).toBe(false);
	});
});
