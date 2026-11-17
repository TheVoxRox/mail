/**
 * What a mouse click on a data-grid row means.
 *
 * This was copied verbatim into every grid that opens something. The mail list,
 * the conversation list and the search results each carried the same
 * `closest('input, button, a')` line. That is what #259 cost — the fix that made
 * a click open the message again had to be written out three times, and a
 * fourth grid would have started from whichever copy its author happened to
 * read.
 *
 * The reasoning lives here now, next to the code it explains, the same way the
 * roving-cell state moved into `rovingGrid.svelte.ts`.
 *
 * A second half used to live here too: a token that kept a refocus queued
 * after a row-change navigation from being overtaken by an older one. Both
 * message lists now navigate with `reset: false`, so focus never leaves the
 * cell and there is no refocus left to guard.
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
