/*
 * Outer geometry of a workspace sidebar pane, in one place because four
 * elements have to occupy that slot at exactly the same size: the real pane
 * (sidebar-shell.svelte), the placeholder held while its lazy chunk loads and
 * the message shown when that chunk fails (both in routes/+layout.svelte), and
 * the boot skeleton drawn before the shell exists (boot/BootLoadingView.svelte).
 *
 * A copy that drifts is not a cosmetic bug. The sidebar is a `shrink-0` flex
 * item to the left of `<main>`, so its width sets the x of everything in the
 * content area; a placeholder of the wrong width means the whole app slides
 * sideways the moment it is swapped for the real pane. That shift is what made
 * mail/links.functional.e2e.ts flaky: the lazy chunk resolved mid-test and moved
 * the message-body iframe a pane-width to the right, and Playwright's
 * actionability check cannot see it — for an element inside a frame it watches
 * the element's box in the FRAME's coordinates, which never moved (measured:
 * 127,51 before and after). The click went to the stale page coordinate, landed
 * on the iframe's border, and the frame document never saw it, so the link
 * relay the test waits for was never posted.
 *
 * A plain module rather than an export from sidebar-shell.svelte (or its
 * barrel): importing either would pull the component into the main bundle and
 * undo the per-workspace code splitting that sidebar/loader.ts exists for.
 */
export const SIDEBAR_PANE_FRAME =
	'flex h-full w-68 shrink-0 flex-col border-r border-sidebar-border bg-sidebar';
