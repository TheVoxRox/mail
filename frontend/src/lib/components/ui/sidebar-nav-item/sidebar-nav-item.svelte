<script lang="ts" module>
	import { cn, tv } from '$lib/utils.js';
	import { badgeVariants } from '../badge/index.js';
	import { focusRing } from '../focus-ring/index.js';
	import type { Snippet } from 'svelte';

	export const sidebarNavItemVariants = tv({
		base: `flex w-full items-center gap-2 rounded-md px-2.5 py-2 text-left text-sm transition-colors ${focusRing}`,
		variants: {
			active: {
				/* Same 3px bar as the current row of a message list — one marker for "this is where you are". */
				true: 'bg-primary/10 font-semibold text-primary shadow-[inset_3px_0_0_var(--primary)]',
				false:
					'text-sidebar-foreground/80 hover:bg-sidebar-accent hover:text-sidebar-accent-foreground'
			}
		},
		defaultVariants: {
			active: false
		}
	});

	/*
	 * The count pill a caller renders into `badge`. Its tint stacks on the
	 * current item's own `bg-primary/10`, and the number then fell to 4.38:1 on
	 * the light sidebar (WCAG 1.4.3 asks 4.5:1), so on the current item the pill
	 * sits on the plain background instead. axe reports this only with
	 * `ignoreLength` (CHECK_OPTIONS in routes/a11y-target.ts): by default it
	 * files failing text of one character under "incomplete".
	 */
	export const sidebarNavBadge = badgeVariants({
		kind: 'count',
		class: 'in-aria-[current=page]:bg-background'
	});

	export type SidebarNavItemProps = {
		active?: boolean;
		icon?: Snippet;
		badge?: Snippet;
		children?: Snippet;
		class?: string;
		href?: string;
		type?: 'button' | 'submit' | 'reset';
		disabled?: boolean;
		ariaLabel?: string;
		ariaBusy?: 'true' | 'false';
		onclick?: (event: MouseEvent) => void;
	};
</script>

<script lang="ts">
	let {
		active = false,
		icon,
		badge,
		children,
		class: className,
		href,
		type = 'button',
		disabled = false,
		ariaLabel,
		ariaBusy,
		onclick
	}: SidebarNavItemProps = $props();
</script>

{#if href}
	<a
		{href}
		class={cn(sidebarNavItemVariants({ active }), className)}
		aria-current={active ? 'page' : undefined}
		aria-label={ariaLabel}
		aria-busy={ariaBusy}
		{onclick}
	>
		{@render icon?.()}
		<span class="flex-1 truncate">
			{@render children?.()}
		</span>
		{@render badge?.()}
	</a>
{:else}
	<button
		{type}
		class={cn(sidebarNavItemVariants({ active }), className)}
		aria-current={active ? 'page' : undefined}
		aria-label={ariaLabel}
		aria-busy={ariaBusy}
		{disabled}
		{onclick}
	>
		{@render icon?.()}
		<span class="flex-1 truncate">
			{@render children?.()}
		</span>
		{@render badge?.()}
	</button>
{/if}
