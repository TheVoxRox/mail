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
