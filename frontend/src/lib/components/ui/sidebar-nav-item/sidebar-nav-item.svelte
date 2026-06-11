<script lang="ts" module>
	import { cn } from '$lib/utils.js';
	import type { Snippet } from 'svelte';
	import { tv } from 'tailwind-variants';

	export const sidebarNavItemVariants = tv({
		base: 'flex w-full items-center gap-2 rounded-md px-2.5 py-2 text-left text-sm transition-colors',
		variants: {
			active: {
				true: 'bg-primary/12 font-semibold text-primary shadow-[inset_2px_0_0_var(--primary)]',
				false:
					'text-sidebar-foreground/85 hover:bg-sidebar-accent hover:text-sidebar-accent-foreground'
			}
		},
		defaultVariants: {
			active: false
		}
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
