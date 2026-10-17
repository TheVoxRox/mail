// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
	currentListSize,
	LIST_SIZE_PERCENT,
	LIST_SIZES,
	setListSize,
	SPLIT_SIZE_KEYS
} from './listSize.js';

vi.mock('$app/env', () => ({
	browser: true,
	dev: false,
	building: false,
	version: 'test'
}));

afterEach(() => {
	window.localStorage.clear();
});

describe('list size presets', () => {
	it('reads as the default before anything was stored', () => {
		expect(currentListSize()).toBe('default');
	});

	it.each(LIST_SIZES)('writes %s for both pane positions and reads it back', (size) => {
		setListSize(size);

		expect(window.localStorage.getItem(SPLIT_SIZE_KEYS.right)).toBe(
			String(LIST_SIZE_PERCENT[size].right)
		);
		expect(window.localStorage.getItem(SPLIT_SIZE_KEYS.bottom)).toBe(
			String(LIST_SIZE_PERCENT[size].bottom)
		);
		expect(currentListSize()).toBe(size);
	});

	it('reads as custom once the divider has moved either size off the presets', () => {
		setListSize('larger');
		// The divider moves in steps of 5 and writes only the position in use.
		window.localStorage.setItem(SPLIT_SIZE_KEYS.right, '55');

		expect(currentListSize()).toBe('custom');
	});

	it('keeps every preset inside the divider’s 20–80 range', () => {
		for (const size of LIST_SIZES) {
			for (const value of Object.values(LIST_SIZE_PERCENT[size])) {
				expect(value).toBeGreaterThanOrEqual(20);
				expect(value).toBeLessThanOrEqual(80);
			}
		}
	});
});
