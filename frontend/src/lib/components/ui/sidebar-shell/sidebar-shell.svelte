<script lang="ts" module>
	import { cn } from '$lib/utils.js';
	import type { Snippet } from 'svelte';
	import type { HTMLAttributes } from 'svelte/elements';

	export type SidebarShellProps = HTMLAttributes<HTMLElement> & {
		/**
		 * Accessible name of the pane's region landmark („Podokno …"). Every
		 * workspace sidebar renders as a named region so screen-reader users
		 * reach all panes the same way — never as <nav>: a search landmark
		 * nested inside nav is semantically wrong, and panes announcing with
		 * different roles break landmark navigation consistency.
		 */
		label: string;
		/**
		 * Wraps the scrollable content in its own <nav> landmark with this
		 * label, as a sibling of the header. Use it only when the pane hosts
		 * other landmarks the nav must stand apart from (mail: folder list
		 * vs. search); when links are the pane's sole content, omit it — a
		 * nested nav is just landmark noise (settings).
		 */
		contentNavLabel?: string;
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
		contentNavLabel,
		class: className,
		header,
		footer,
		children,
		headerClass,
		contentClass,
		footerClass,
		...restProps
	}: SidebarShellProps = $props();

	const contentTag = $derived(contentNavLabel ? 'nav' : 'div');
</script>

<section
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

	<svelte:element
		this={contentTag}
		aria-label={contentNavLabel}
		class={cn('flex-1 overflow-y-auto', contentClass)}
	>
		{@render children?.()}
	</svelte:element>

	{#if footer}
		<div class={cn('border-t border-sidebar-border p-2.5', footerClass)}>
			{@render footer()}
		</div>
	{/if}
</section>
