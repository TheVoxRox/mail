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
 *
 * The same argument covers how a menu behaves, hence `focusFirstMenuItem`
 * below: a menu that *sounds* different depending on where it was opened from
 * is the same defect one sense over.
 */
import { tv } from 'tailwind-variants';

/**
 * `onfocus` for a menu panel. Moves focus off the container in the same task
 * it arrived in, so the container never takes a turn at holding it.
 *
 * bits-ui parks focus on the panel when the menu opens and only reaches the
 * first item an `afterTick` later (`MenuContentState.onfocus`) — measured in
 * the running app as `menu -> menuitem -> menu -> menuitem` inside 12 ms. A
 * screen reader processes the container during that gap, and `role="menu"`
 * carries no value of its own, so it describes the container by reading what
 * is inside it: NVDA announced the menu and then every item in it (heard
 * 2026-09-02, Space and Enter alike).
 *
 * This handler runs before the library's (composeHandlers), so landing the
 * first item synchronously closes the gap; their `afterTick` then finds focus
 * already where it wanted to put it. Disabled items are skipped because focus
 * has to land somewhere a keyboard user can act on.
 */
export function focusFirstMenuItem(event: FocusEvent & { currentTarget: HTMLElement }): void {
	// Only the panel itself — an item bubbling its own focus must not restart
	// the walk from the top, which would trap focus on the first item.
	if (event.target !== event.currentTarget) return;
	event.currentTarget
		.querySelector<HTMLElement>('[role="menuitem"]:not([aria-disabled="true"])')
		?.focus();
}

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
