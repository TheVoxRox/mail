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
