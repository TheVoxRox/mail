<script lang="ts" module>
	import { cn } from '$lib/utils.js';
	import type { Snippet } from 'svelte';
	import type { HTMLAttributes } from 'svelte/elements';

	export type SidebarShellProps = HTMLAttributes<HTMLElement> & {
		label: string;
		/**
		 * Landmark role of the shell root. Keep the default <nav> only when
		 * the whole sidebar is genuinely navigation (e.g. settings links). Use
		 * "region" when the header hosts landmarks of its own (search) or the
		 * content is actions rather than navigation — a search landmark nested
		 * inside nav is semantically wrong, inside a named region it is fine.
		 */
		landmarkRole?: 'navigation' | 'region';
		/**
		 * With landmarkRole="region": wraps the scrollable content in its own
		 * <nav> landmark with this label, as a sibling of the header (so e.g.
		 * the folder list stays reachable by landmark navigation).
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
		landmarkRole = 'navigation',
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

	const rootTag = $derived(landmarkRole === 'region' ? 'section' : 'nav');
	const contentTag = $derived(contentNavLabel ? 'nav' : 'div');
</script>

<svelte:element
	this={rootTag}
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
</svelte:element>
