/**
 * The small rounded pill that sits beside something and tells one thing about
 * it: an unread count, an account's state, the provider an address belongs
 * to, the role of an address in a merge.
 *
 * Class strings only — the callers are a `<span>` inside a link, a heading or
 * a nav item, and a `<p>` that carries a logo, and each needs its own element
 * for reasons that have nothing to do with the look. Same arrangement as
 * `chipVariants`.
 *
 * Copies had drifted into three looks: counts at 11px semibold, the account
 * state at 11px medium with a border, and the provider at 12px medium. One
 * size now, and the weight says what the pill holds — a number is semibold, a
 * word is medium. The status tones keep the border the account list gave
 * them; the other tones never had one.
 */
import { tv } from '$lib/utils.js';

export const badgeVariants = tv({
	base: 'inline-flex shrink-0 items-center rounded-full py-0.5 text-caption',
	variants: {
		kind: {
			/** Centred, and never narrower than it is tall, so a single digit is a circle. */
			count: 'min-w-5 justify-center px-1.5 font-semibold',
			label: 'gap-1 px-2 font-medium'
		},
		tone: {
			primary: 'bg-primary/10 text-primary',
			muted: 'bg-muted text-muted-foreground',
			success: 'border border-success/30 bg-success/10 text-success-foreground',
			warning: 'border border-warning/30 bg-warning/10 text-warning-foreground',
			danger: 'border border-destructive/30 bg-destructive/10 text-destructive-foreground',
			neutral: 'border border-border bg-muted text-muted-foreground'
		}
	},
	defaultVariants: {
		kind: 'label',
		tone: 'primary'
	}
});
