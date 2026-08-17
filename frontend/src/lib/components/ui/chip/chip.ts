/**
 * The small labelled pill: an attachment, a typed-in recipient.
 *
 * Class strings only — the callers are a `<button>`, an `<li>` and a `<span>`,
 * and each needs its own element for reasons that have nothing to do with the
 * look. Same arrangement as `menuItemVariants`.
 *
 * Three copies had drifted apart: the attachment chip in the message view was
 * `rounded-md`, the identical-looking one in the composer was bare `rounded`
 * (4px, off the radius scale entirely), and the recipient token carried a
 * border the other two lacked. `bg-secondary` and `bg-muted` were being used
 * as if they were different fills — they resolve to the same colour in both
 * themes, so the tones below are the ones that actually differ on screen.
 */
import { tv } from 'tailwind-variants';

export const chipVariants = tv({
	base: 'inline-flex max-w-full items-center gap-1 rounded-md border px-2 py-1 text-xs',
	variants: {
		tone: {
			default: 'border-border bg-secondary text-secondary-foreground',
			/** Reads as an affordance rather than a value — the file picker. */
			outline: 'border-input bg-background text-foreground',
			danger: 'border-destructive/40 bg-destructive/10 text-destructive-foreground'
		}
	},
	defaultVariants: {
		tone: 'default'
	}
});
