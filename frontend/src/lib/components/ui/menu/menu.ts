/**
 * The look of a dropdown menu — the floating panel and the rows inside it.
 *
 * Class strings only, no components: bits-ui collects `DropdownMenu.Item`
 * children for keyboard navigation and typeahead, so these stay applied to the
 * real bits-ui elements at their original nesting. Same arrangement as
 * `buttonVariants`, used as `class={menuItemVariants()}`.
 *
 * The panel string was copied verbatim into every menu surface in the app —
 * the row-actions menu and its move submenu, the detail toolbar, and both bulk
 * bars — and the row string with it. A menu that reads differently depending on
 * where it was opened from is worse than one that is plain, and with a copy per
 * surface that is the direction it drifts.
 */
import { tv } from 'tailwind-variants';

export const menuContentVariants = tv({
	base: 'z-10 rounded-lg border border-border bg-popover p-1 text-popover-foreground shadow-lg',
	variants: {
		width: {
			sm: 'min-w-44',
			md: 'min-w-48'
		},
		/** A folder list can outgrow the viewport; a fixed set of actions cannot. */
		scroll: {
			true: 'max-h-64 overflow-y-auto',
			false: ''
		}
	},
	defaultVariants: {
		width: 'sm',
		scroll: false
	}
});

export const menuItemVariants = tv({
	base: 'flex w-full cursor-pointer rounded-md px-3 py-2 text-left text-sm outline-hidden data-[disabled]:cursor-not-allowed data-[disabled]:text-muted-foreground',
	variants: {
		tone: {
			default: 'data-[highlighted]:bg-muted',
			destructive: 'text-destructive-foreground data-[highlighted]:bg-destructive/10'
		}
	},
	defaultVariants: {
		tone: 'default'
	}
});
