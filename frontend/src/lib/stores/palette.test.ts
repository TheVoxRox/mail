// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { get } from 'svelte/store';
import { closePalette, openPalette, paletteCloseAutoFocus, paletteOpen } from './palette.js';

vi.mock('$app/environment', () => ({
	browser: true,
	dev: false,
	building: false,
	version: 'test'
}));

function mountButton(): HTMLButtonElement {
	const button = document.createElement('button');
	document.body.appendChild(button);
	return button;
}

async function flushFocusRestore(): Promise<void> {
	// closePalette restores focus in a queueMicrotask; one awaited microtask
	// later the restore has run.
	await Promise.resolve();
}

afterEach(async () => {
	closePalette({ restoreFocus: false });
	await flushFocusRestore();
	document.body.innerHTML = '';
});

describe('palette store', () => {
	it('open/close toggles the store', () => {
		expect(get(paletteOpen)).toBe(false);
		openPalette();
		expect(get(paletteOpen)).toBe(true);
		closePalette();
		expect(get(paletteOpen)).toBe(false);
	});

	it('closePalette restores focus to the element focused before opening', async () => {
		const button = mountButton();
		button.focus();

		openPalette();
		closePalette();
		await flushFocusRestore();

		expect(document.activeElement).toBe(button);
	});

	it('re-invoking openPalette while open keeps the original focus target', async () => {
		const button = mountButton();
		const paletteInput = document.createElement('input');
		document.body.appendChild(paletteInput);

		button.focus();
		openPalette();
		// The palette autofocuses its input; a second Ctrl+K arrives now.
		paletteInput.focus();
		openPalette();

		closePalette();
		await flushFocusRestore();

		expect(document.activeElement).toBe(button);
	});

	it('restoreFocus: false leaves focus alone and clears the memory', async () => {
		const button = mountButton();
		const other = mountButton();

		button.focus();
		openPalette();
		other.focus();
		closePalette({ restoreFocus: false });
		await flushFocusRestore();

		expect(document.activeElement).toBe(other);

		// The stale target must not leak into a later close cycle.
		closePalette();
		await flushFocusRestore();
		expect(document.activeElement).toBe(other);
	});

	it('skips the restore when the remembered element left the DOM', async () => {
		const button = mountButton();
		button.focus();

		openPalette();
		button.remove();
		closePalette();
		await flushFocusRestore();

		expect(document.activeElement).not.toBe(button);
	});

	describe('the dialog close', () => {
		const closeEvent = () => new Event('focusScope.onCloseAutoFocus', { cancelable: true });

		it('hands focus to the element closePalette chose, in place of bits-ui', async () => {
			const button = mountButton();
			const paletteInput = document.createElement('input');
			document.body.appendChild(paletteInput);
			button.focus();
			openPalette();
			paletteInput.focus();
			closePalette();
			await flushFocusRestore();
			// bits-ui's own move would have gone to the input it saw focused first.
			paletteInput.focus();

			const event = closeEvent();
			paletteCloseAutoFocus(event);

			expect(event.defaultPrevented).toBe(true);
			expect(document.activeElement).toBe(button);
		});

		it('still returns focus for a command that runs after the close', async () => {
			// restoreFocus: false skips the immediate restore only. The command
			// runs once the dialog is gone and starts from where the palette was
			// opened, as it did when bits-ui made this move: printing relies on it.
			const button = mountButton();
			const paletteInput = document.createElement('input');
			document.body.appendChild(paletteInput);
			button.focus();
			openPalette();
			paletteInput.focus();
			closePalette({ restoreFocus: false });
			await flushFocusRestore();
			expect(document.activeElement).toBe(paletteInput);

			const event = closeEvent();
			paletteCloseAutoFocus(event);

			expect(event.defaultPrevented).toBe(true);
			expect(document.activeElement).toBe(button);
		});

		it('moves nothing when the element left the DOM', async () => {
			const button = mountButton();
			const other = mountButton();
			button.focus();
			openPalette();
			closePalette();
			await flushFocusRestore();
			button.remove();
			other.focus();

			paletteCloseAutoFocus(closeEvent());

			expect(document.activeElement).toBe(other);
		});
	});
});
