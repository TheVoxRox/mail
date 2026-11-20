/**
 * Where a pager control wants to go, resolved against the page it is on.
 *
 * Every list in the app derived its five pager callbacks from one target index
 * and then clamped that index itself — the same three lines in
 * MessageList, ConversationList, the search page and the contacts page, plus
 * the 1-based-to-0-based conversion of the jump box repeated at each call site.
 * The clamp belongs to the pager, which is the only thing that knows both the
 * requested target and the page count; the caller is left with the load.
 */

/**
 * The page `target` resolves to, or null when it is the page already shown.
 *
 * Null rather than the current index so a caller cannot mistake a no-op for a
 * move: the contacts page arms a "the list reloaded" announcement before it
 * navigates, and announcing a reload that never happens is worse than silence.
 *
 * Out-of-range targets clamp instead of being rejected — the jump box can ask
 * for page 900 of 12, and landing on the last page is what the user meant.
 */
export function clampPageTarget(
	target: number,
	totalPages: number,
	currentPage: number
): number | null {
	if (!Number.isFinite(target)) return null;
	const lastPage = Math.max(0, totalPages - 1);
	const next = Math.min(Math.max(0, Math.trunc(target)), lastPage);
	return next === currentPage ? null : next;
}

/**
 * The page to load in place of `shown` when `shown` lies past the end of the
 * list: the last page that still exists. Null when `shown` is fine as it came —
 * it has rows, or the whole list is empty and page 0 is where that is said.
 *
 * A list reloads the page it is on after a removal (a delete, a merge, a label
 * taken off the view filtered by it). When that removal took the last rows of
 * the last page, the server answers the same index with no rows and a positive
 * total, and a list that renders the page as it came reads as empty and draws
 * no pager to leave it by.
 *
 * The answer is always before `shown.page`, so a caller that keeps asking until
 * it gets null stops even when the list shrinks again between two requests.
 */
export function lastPageIfPastEnd(shown: {
	content: readonly unknown[];
	page: number;
	totalPages: number;
	totalElements: number;
}): number | null {
	if (shown.content.length > 0 || shown.page <= 0 || shown.totalElements <= 0) return null;
	return Math.max(0, Math.min(shown.page - 1, shown.totalPages - 1));
}
