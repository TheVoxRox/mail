<script lang="ts" module>
	import { cn } from '$lib/utils.js';
	import type { Snippet } from 'svelte';
	import type { HTMLAttributes } from 'svelte/elements';

	export type SidebarShellProps = HTMLAttributes<HTMLElement> & {
		label: string;
		header?: Snippet;
		footer?: Snippet;
		children?: Snippet;
		headerClass?: string;
		contentClass?: string;
		footerClass?: string;
	};
</script>

<script lang="ts">
	let {
		label,
		class: className,
		header,
		footer,
		children,
		headerClass,
		contentClass,
		footerClass,
		...restProps
	}: SidebarShellProps = $props();
</script>

<nav
	aria-label={label}
	class={cn(
		'flex h-full w-68 shrink-0 flex-col border-r border-sidebar-border bg-sidebar text-sidebar-foreground',
		className
	)}
	{...restProps}
>
	{#if header}
		<div class={cn('border-b border-sidebar-border p-3.5', headerClass)}>
			{@render header()}
		</div>
	{/if}

	<div class={cn('flex-1 overflow-y-auto', contentClass)}>
		{@render children?.()}
	</div>

	{#if footer}
		<div class={cn('border-t border-sidebar-border p-2.5', footerClass)}>
			{@render footer()}
		</div>
	{/if}
</nav>
