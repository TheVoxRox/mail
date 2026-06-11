<script lang="ts">
	import type { Snippet } from 'svelte';
	import { cn } from '$lib/utils.js';

	interface Props {
		title: string;
		description?: string | null;
		class?: string;
		contentClass?: string;
		titleClass?: string;
		actions?: Snippet;
		children?: Snippet;
	}

	let {
		title,
		description = null,
		class: className,
		contentClass,
		titleClass,
		actions,
		children
	}: Props = $props();
</script>

<!--
	The <section> is the scrollable content container, so it keeps tabindex=0
	(axe `scrollable-region-focusable`). It deliberately has NO accessible name:
	a named <section> becomes a `region` landmark that the screen reader would
	announce right before the <h1> — duplicating the heading that already names
	this area inside the parent <main> landmark.
-->
<!-- svelte-ignore a11y_no_noninteractive_tabindex -->
<section tabindex="0" class={cn('flex-1 overflow-y-auto bg-background outline-none', className)}>
	<div class="border-b border-border bg-background px-6 py-4">
		<div class="flex items-center justify-between gap-3">
			<div class="min-w-0">
				<h1 class={cn('truncate text-[0.95rem] font-semibold text-foreground', titleClass)}>
					{title}
				</h1>
				{#if description}
					<p class="mt-1 text-sm text-muted-foreground">{description}</p>
				{/if}
			</div>
			{#if actions}
				<div class="flex shrink-0 flex-wrap justify-end gap-2">
					{@render actions()}
				</div>
			{/if}
		</div>
	</div>

	<div class={cn('p-6', contentClass)}>
		{@render children?.()}
	</div>
</section>
