/**
 * How the currently open message detail is closed — declared by whoever
 * renders it.
 *
 * The mail route shows the detail as its own page, so closing means going back
 * to the folder list. The search screen renders the same component *in place
 * of* its results, where that navigation would throw the user into the inbox
 * and lose both the results and the query. The optimistic pipeline
 * (`mail/mailbox.ts`) also has to close the detail when the open message is
 * deleted or moved, and it is invoked from places that know nothing about the
 * screen — the toolbar, the command palette, a keyboard shortcut. Rather than
 * having it guess from the route, the host registers its own closer while the
 * detail is mounted.
 */

export type DetailCloseContext = {
	/**
	 * Set when the detail is closing because that message was removed (deleted
	 * or moved away). The host must not then restore focus to its row — the row
	 * is gone — and may want to refresh what it is showing.
	 */
	removedStableId?: string;
};

export type DetailCloser = (context: DetailCloseContext) => void | Promise<void>;

let currentCloser: DetailCloser | null = null;

/** Registers `closer` for as long as the detail is mounted; returns the undo. */
export function registerDetailCloser(closer: DetailCloser): () => void {
	currentCloser = closer;
	return () => {
		if (currentCloser === closer) currentCloser = null;
	};
}

/**
 * Closes the open detail through its host. Returns false when no host is
 * registered, so the caller can fall back to its own behaviour.
 */
export async function closeOpenDetail(context: DetailCloseContext = {}): Promise<boolean> {
	const closer = currentCloser;
	if (!closer) return false;
	await closer(context);
	return true;
}
