/*
 * A file added to a message that already carries it, told by name and size,
 * since a file's content is read only after this: a file whose name and size
 * match an attachment in the message, one still being read, or one earlier
 * in the same batch is taken for the same file added again. It is skipped
 * and the rest of the batch goes on, where an empty or oversized file refuses
 * the whole batch, because a duplicate is nearly always a second drop or
 * paste of what is already there.
 */

export function duplicateKey(name: string, size: number): string {
	return `${size}/${name}`;
}

/** Splits a batch into the files to add and those already in `taken` or earlier in it. */
export function splitDuplicates(
	files: File[],
	taken: Iterable<string>
): { fresh: File[]; duplicates: File[] } {
	const seen = new Set(taken);
	const fresh: File[] = [];
	const duplicates: File[] = [];
	for (const file of files) {
		const key = duplicateKey(file.name, file.size);
		if (seen.has(key)) {
			duplicates.push(file);
		} else {
			seen.add(key);
			fresh.push(file);
		}
	}
	return { fresh, duplicates };
}
