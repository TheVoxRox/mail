import { describe, expect, it } from 'vitest';
import { filesFromFileSystem } from './dragPayload.js';

/**
 * The items a paste hands over, shaped as measured in Edge 155: a file from
 * the file system has an entry, an image from the clipboard has none.
 */
function clipboard(
	items: Array<
		| { kind: 'string'; type: string }
		| { kind: 'file'; file: File; entry: { isFile: boolean } | null }
	>
): DataTransfer {
	return {
		items: items.map((item) =>
			item.kind === 'string'
				? { kind: 'string', type: item.type, getAsFile: () => null, webkitGetAsEntry: () => null }
				: {
						kind: 'file',
						type: item.file.type,
						getAsFile: () => item.file,
						webkitGetAsEntry: () => item.entry
					}
		)
	} as unknown as DataTransfer;
}

const copiedFile = new File(['text'], 'poznamka.txt', { type: 'text/plain' });
const clipboardImage = new File(['png'], 'image.png', { type: 'image/png' });

describe('filesFromFileSystem', () => {
	it('takes a file copied from the file system', () => {
		const pasted = clipboard([{ kind: 'file', file: copiedFile, entry: { isFile: true } }]);
		expect(filesFromFileSystem(pasted)).toEqual([copiedFile]);
	});

	it('leaves out the picture Excel puts beside the text of copied cells', () => {
		const pasted = clipboard([
			{ kind: 'string', type: 'text/plain' },
			{ kind: 'string', type: 'text/html' },
			{ kind: 'file', file: clipboardImage, entry: null }
		]);
		expect(filesFromFileSystem(pasted)).toEqual([]);
	});

	it('leaves out an image that is the only thing on the clipboard', () => {
		const pasted = clipboard([{ kind: 'file', file: clipboardImage, entry: null }]);
		expect(filesFromFileSystem(pasted)).toEqual([]);
	});

	it('leaves out a copied folder', () => {
		const folder = new File([], 'Slozka');
		const pasted = clipboard([{ kind: 'file', file: folder, entry: { isFile: false } }]);
		expect(filesFromFileSystem(pasted)).toEqual([]);
	});

	it('returns nothing without clipboard data', () => {
		expect(filesFromFileSystem(null)).toEqual([]);
	});
});
