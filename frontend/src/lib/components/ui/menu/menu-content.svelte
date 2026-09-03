<script lang="ts">
	import { DropdownMenu } from 'bits-ui';
	import type { Snippet } from 'svelte';
	import { cn } from '$lib/utils.js';
	import { focusFirstMenuItem, menuContentVariants } from './menu.js';

	/**
	 * The floating panel of a dropdown menu: the portal, the content element, the
	 * classes and the focus handling, in one place that a new menu cannot forget.
	 *
	 * `focusFirstMenuItem` already lived in one module, and that was not enough —
	 * every `DropdownMenu.Content` still had to call it by hand, so the guarantee
	 * held only for the menus somebody remembered. It did not hold: the detail
	 * toolbar's menu never got the handler at all and still gave focus to its own
	 * container, four months and two fix commits after the defect was named.
	 *
	 * `label` is a required prop, which is the point of the component. A menu with
	 * no accessible name is a container a screen reader can only describe by
	 * reading what is inside it, and a required prop refuses that at type-check
	 * time — when the menu is written, not when somebody happens to listen to it.
	 * A test can only cover the menus it knows about.
	 *
	 * `loop` is fixed rather than a prop: all five menus wanted it, and one that
	 * silently stops wrapping at the last item is exactly the kind of drift the
	 * copies produced. The children arrive as a snippet, which renders in place,
	 * so `DropdownMenu.Item` stays a direct child of the content element and
	 * bits-ui still collects it for arrow navigation and typeahead — asserted
	 * rather than assumed, see the walk in row-actions-menu.functional.e2e.ts.
	 */
	interface Props {
		/** Accessible name, normally the trigger's own label. */
		label: string;
		align?: 'start' | 'center' | 'end';
		sideOffset?: number;
		width?: 'sm' | 'md';
		/** A folder list can outgrow the viewport; a fixed set of actions cannot. */
		scroll?: boolean;
		id?: string;
		class?: string;
		children: Snippet;
	}

	let {
		label,
		align = 'start',
		sideOffset = 4,
		width = 'sm',
		scroll = false,
		id,
		class: className,
		children
	}: Props = $props();
</script>

<DropdownMenu.Portal>
	<DropdownMenu.Content
		{id}
		{align}
		{sideOffset}
		loop
		aria-label={label}
		onfocus={focusFirstMenuItem}
		class={cn(menuContentVariants({ width, scroll }), className)}
	>
		{@render children()}
	</DropdownMenu.Content>
</DropdownMenu.Portal>
