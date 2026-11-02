/**
 * Checkboxes and radios, which stay native `<input>`s: they are bound
 * directly, carry the ARIA grid's roving tabindex in the mail lists, and are
 * the one control screen readers and the OS both already know.
 *
 * The app had two spellings. Four call sites used `size-4 accent-primary`;
 * seven used `size-4 rounded border-input bg-background text-primary` plus a
 * ring. Screenshotting the two against an unstyled box of the same size in
 * Chromium (the engine the WebView is): of `color`, `border`, `border-radius`
 * and `background`, none changes a single painted pixel, alone or together —
 * `accent-color` is the only one of the five that does, and only while the box
 * is checked. So the two groups did not merely disagree in source: the mail
 * lists drew their checkmarks in the brand colour and every form drew the
 * browser default, while the four decorative properties read like styling
 * nobody could see.
 *
 * `accent-color` is what the shared string keeps. Anything that has to look
 * different from a native control needs to stop being one.
 */
import { focusRing } from '../focus-ring/index.js';

export const nativeControlClass = `size-4 accent-primary ${focusRing}`;

/**
 * A checkbox or radio and its visible text, on the element that holds the two:
 * the `<label>` itself, or a span inside it when the label is a card or a row
 * with more in it. The box stays an inline box on the text's own line.
 *
 * Inside a flex or grid container the box is an item, and CSS makes every item
 * a block, whatever its class says. NVDA's browse mode starts a new line at
 * anything whose `display` is not inline, inline-block or inline-flex
 * (nvdaHelper/vbufBackends/gecko_ia2), and while the label is on screen it
 * leaves the name off the box. So the flex labels the app had read as two
 * lines, "check box not checked" and then the text, where JAWS read one.
 *
 * The hanging indent does what `items-start gap-…` did: the box sits in the
 * padding, so a wrapped line starts under the text rather than under the box.
 * The whitespace between the box and the text is part of the gap.
 *
 * Pair it with `<label for>` and an `id` even where the label wraps the box:
 * the WAI forms tutorial prefers the explicit association as the better
 * supported one. `choiceLabelLosses` in the e2e helpers holds both.
 */
export const nativeChoiceClass = 'block pl-7';
export const nativeChoiceControlClass = `-ml-7 mr-2 align-middle ${nativeControlClass}`;
