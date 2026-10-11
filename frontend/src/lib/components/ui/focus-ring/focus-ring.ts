/**
 * The one focus indicator in the app.
 *
 * Class strings only, no component: the elements that need a focus ring are
 * native inputs, bits-ui triggers and ARIA grid cells that have to stay the
 * real element. Same arrangement as `menuItemVariants`.
 *
 * There were six spellings of "this element is focused" before this file —
 * `ring-3`/`ring-ring/50` on buttons, `ring-2`/`ring-ring/40` on inputs,
 * `ring-2 ring-inset ring-ring/50` on grid cells, `ring-2 ring-ring` on three
 * one-offs, an `outline-2 outline-offset-2 outline-ring` pair, and one control
 * still on plain `focus:` that drew a ring for mouse users too.
 *
 * The ring is drawn at full `--ring`, not at a tint, and that is the part not
 * to "tidy up" later: a focus indicator is a non-text contrast target (WCAG
 * 1.4.11, 3:1). Full `--ring` measures 4.10:1 against the light page and
 * 8.09:1 against the dark one, but composited at 50% over the light page it
 * falls to 1.61:1 — the tinted spellings this file replaces were failing.
 *
 * `outline-hidden` rather than `outline-none`: in Tailwind 4 those stopped
 * being synonyms. `outline-hidden` keeps a transparent outline under
 * `forced-colors`, where a box-shadow ring is not painted at all and the UA
 * outline is the only indicator left.
 */

/** Default: the ring sits outside the element's box. */
export const focusRing =
	'outline-hidden focus-visible:border-ring focus-visible:ring-2 focus-visible:ring-ring';

/**
 * For elements whose ring would be clipped or would overlap a neighbour —
 * grid cells packed edge to edge, and inputs that sit borderless inside
 * somebody else's bordered container.
 */
export const focusRingInset =
	'outline-hidden focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring';

/**
 * For a scroll container that is a tab stop of its own so the keyboard can
 * scroll it (`PageShell`). An outline drawn inside the edge rather than an
 * inset ring: an inset box-shadow is painted under the container's children,
 * so the page header's own background covered the top of it.
 */
export const focusRingScrollArea =
	'outline-hidden focus-visible:outline-2 focus-visible:outline-solid focus-visible:-outline-offset-2 focus-visible:outline-ring';

/**
 * For a visible box whose focusable element is hidden inside it: the label
 * around the attachment picker's sr-only file input, a segmented choice around
 * its transparent radio. The box draws the ring while that element has
 * keyboard focus, and a click on the label does not. The transparent outline
 * stands in for `outline-hidden`, which the hidden element cannot show.
 */
export const focusRingWithin =
	'has-focus-visible:border-ring has-focus-visible:ring-2 has-focus-visible:ring-ring has-focus-visible:outline-2 has-focus-visible:outline-offset-2 has-focus-visible:outline-transparent';
