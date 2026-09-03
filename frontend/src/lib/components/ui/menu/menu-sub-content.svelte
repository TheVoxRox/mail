<script lang="ts">
	import { DropdownMenu } from 'bits-ui';
	import type { Snippet } from 'svelte';
	import { cn } from '$lib/utils.js';
	import { menuContentVariants } from './menu.js';

	/**
	 * The panel of a *sub*menu — its own component rather than a variant of
	 * MenuContent, because the two differ in the one thing that matters here.
	 *
	 * **No `focusFirstMenuItem`, and that is measured rather than assumed.**
	 * bits-ui runs a different handler for `SubContent` than for `Content`, so
	 * the container-focus defect the four top-level menus had does not carry over
	 * by inference. Recorded in the running preview on 2026-09-03: opening the
	 * Move submenu with ArrowRight produces the focus sequence
	 * `["menuitem"]` — the first item, once, with the container never taking a
	 * turn. Adding the handler here would guard nothing and would have to be
	 * justified by a defect nobody observed.
	 *
	 * `label` is required for the same reason it is on MenuContent, and the same
	 * measurement found this half genuinely missing: the submenu carried neither
	 * `aria-label` nor `aria-labelledby`, so the one menu in the app without a
	 * name was the one nested inside another.
	 */
	interface Props {
		/** Accessible name, normally the sub-trigger's own label. */
		label: string;
		sideOffset?: number;
		width?: 'sm' | 'md';
		scroll?: boolean;
		class?: string;
		children: Snippet;
	}

	let {
		label,
		sideOffset = 4,
		width = 'sm',
		scroll = false,
		class: className,
		children
	}: Props = $props();
</script>

<DropdownMenu.SubContent
	{sideOffset}
	aria-label={label}
	class={cn(menuContentVariants({ width, scroll }), className)}
>
	{@render children()}
</DropdownMenu.SubContent>
