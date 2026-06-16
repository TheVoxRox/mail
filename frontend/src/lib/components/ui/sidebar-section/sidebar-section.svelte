<script lang="ts" module>
	import { cn } from '$lib/utils.js';
	import type { Snippet } from 'svelte';

	export type SidebarSectionProps = {
		id: string;
		label: string;
		children?: Snippet;
		class?: string;
	};
</script>

<script lang="ts">
	let { id, label, children, class: className }: SidebarSectionProps = $props();
</script>

<section aria-labelledby={id} class={cn('space-y-1', className)}>
	<h3
		{id}
		class="px-2.5 pb-1 text-caption font-semibold uppercase tracking-[0.16em] text-muted-foreground"
	>
		{label}
	</h3>
	{@render children?.()}
</section>
