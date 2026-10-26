import { describe, expect, it } from 'vitest';
import { duplicateKey, splitDuplicates } from './attachmentDuplicates.js';

function file(name: string, size: number): File {
	return new File([new Uint8Array(size)], name);
}

describe('splitDuplicates', () => {
	it('skips a file whose name and size an attachment already has, and keeps the rest', () => {
		const note = file('poznamka.txt', 12);
		const other = file('dalsi.txt', 5);

		const { fresh, duplicates } = splitDuplicates(
			[note, other],
			[duplicateKey('poznamka.txt', 12)]
		);

		expect(fresh).toEqual([other]);
		expect(duplicates).toEqual([note]);
	});

	it('skips the second of two equal files in one batch', () => {
		const first = file('a.txt', 3);
		const second = file('a.txt', 3);

		expect(splitDuplicates([first, second], [])).toEqual({ fresh: [first], duplicates: [second] });
	});

	it('keeps a file of the same name but another size, and of the same size but another name', () => {
		const files = [file('a.txt', 4), file('b.txt', 3)];

		expect(splitDuplicates(files, [duplicateKey('a.txt', 3)]).fresh).toEqual(files);
	});
});
