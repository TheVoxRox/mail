/**
 * Tells a screen reader once per selection that the bulk actions appeared.
 *
 * The bulk toolbar renders only while something is selected, so its arrival is
 * a change that has to be announced — but only the arrival. Repeating it as the
 * selection grows would talk over every further tick, and staying silent after
 * the selection empties would leave the next session unannounced.
 *
 * Three grids carried this by hand, differing only in which message they
 * announce; the copy in the contact list even carried the comment "(mirrors
 * MessageList)", which is the tell that it was transcribed rather than derived.
 */

/**
 * Returns the function to call with the current "is anything selected" answer
 * — typically the whole body of an `$effect`.
 *
 * The flag stays a plain closure variable rather than `$state` on purpose: the
 * caller drives this from inside an effect, and a reactive flag written there
 * would make that effect depend on itself.
 */
export function createBulkAnnouncer(announce: () => void): (hasSelection: boolean) => void {
	let announced = false;
	return (hasSelection) => {
		if (!hasSelection) {
			announced = false;
			return;
		}
		if (announced) return;
		announced = true;
		announce();
	};
}
