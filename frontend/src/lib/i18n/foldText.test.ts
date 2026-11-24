import { describe, expect, it } from 'vitest';
import { foldText } from './foldText.js';

describe('foldText', () => {
	it('lowercases and drops combining marks', () => {
		expect(foldText('Ñoño ÜBER')).toBe('nono uber');
	});

	/*
	 * NFD splits most accented letters into a base letter and a mark, but not
	 * these: without the table, a Polish "zalacznik" typed without diacritics
	 * would not match the word with its "ł", nor a Danish "ore" its "ø".
	 */
	it('writes letters NFD keeps whole as their base letters', () => {
		expect(foldText('Łaska')).toBe('laska');
		expect(foldText('Øre')).toBe('ore');
		expect(foldText('Straße')).toBe('strasse');
		expect(foldText('Ærø')).toBe('aero');
		expect(foldText('Œuvre')).toBe('oeuvre');
		expect(foldText('Þing')).toBe('thing');
	});
});
