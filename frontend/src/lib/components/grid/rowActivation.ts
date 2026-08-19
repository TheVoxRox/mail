/**
 * What a mouse click on a data-grid row means, and how a row change that opens
 * a message keeps its refocus from being overtaken by an older one.
 *
 * Both halves were copied verbatim into every grid that opens something. The
 * mail list, the conversation list and the search results each carried the same
 * `closest('input, button, a')` line; the first two also carried the same token
 * counter under the same comment. That is what #259 cost — the fix that made a
 * click open the message again had to be written out three times, and a fourth
 * grid would have started from whichever copy its author happened to read.
 *
 * The reasoning lives here now, next to the code it explains, the same way the
 * roving-cell state moved into `rovingGrid.svelte.ts`.
 */

/**
 * Whether a click landed on the row itself rather than on a control inside it.
 *
 * The mouse follows the web-mail model (Gmail, Outlook Web): a click anywhere
 * on the row opens the message and moves the reading cursor into the body, and
 * the checkbox is the only thing that selects. #201 briefly made a single click
 * select instead — the Outlook *desktop* model — and that silently broke Enter
 * for a screen reader in browse mode: the reader keeps the unmodified keys for
 * its own navigation and never delivers Enter as a keydown, it activates the
 * row instead, and the activation arrives as an ordinary click. Treating that
 * click as "select" made Enter look dead.
 *
 * Inputs, buttons and anchors are excluded because each already owns its click:
 * the checkbox and the row-actions trigger stop theirs, and the row's link
 * handles its own so the navigation stays client-side and carries the
 * body-focus intent, which a native follow of the href could not.
 *
 * `label` is excluded for the same reason one step removed. A label inside a
 * row exists to enlarge a control's pointer target — in the message lists it is
 * the 24px box around the 16px checkbox that WCAG 2.5.8 asks for — so a click
 * on the padding around the box is that checkbox's click, and the browser
 * forwards it there. Without this the row would open *and* the checkbox would
 * toggle from one press. The contact list is where the exclusion comes from; it
 * was the only copy that had it, and the message lists were saved only by their
 * select cell stopping propagation a level up. Sharing the stricter rule costs
 * nothing and removes the need for that to keep being true.
 *
 * A click with no target at all counts as a background click — the row is the
 * only thing that could have produced it.
 */
export function isRowBackgroundClick(event: MouseEvent): boolean {
	const target = event.target as HTMLElement | null;
	return !target?.closest('input, button, a, label');
}

/** A "newest selection wins" guard. See `createLatestSelection`. */
export interface LatestSelection {
	/**
	 * Retires whatever is queued without starting anything new — for an open
	 * whose focus belongs elsewhere (the message body), so a refocus queued by
	 * an earlier row change cannot pull the reading cursor back to the grid.
	 */
	retire(): void;
	/**
	 * Starts a selection and returns the predicate that reports whether it is
	 * still the newest one. Call it inside the `.finally` that would move focus.
	 */
	begin(): () => boolean;
}

/**
 * Guards the refocus that follows a selection in a grid where changing rows
 * opens a message.
 *
 * Opening navigates, and SvelteKit cancels an in-flight navigation when a newer
 * one starts (rapid Arrow / Page keys). The superseded navigation's promise
 * still settles, and its `.finally` would re-focus a row that is no longer the
 * selected one — landing the reading cursor behind where the user had already
 * arrived. So a refocus first asks whether its own selection is still the
 * newest, and a deliberate open retires the queue outright.
 */
export function createLatestSelection(): LatestSelection {
	let token = 0;
	return {
		retire() {
			token += 1;
		},
		begin() {
			const mine = ++token;
			return () => mine === token;
		}
	};
}
