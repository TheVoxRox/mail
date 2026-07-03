<script lang="ts" module>
	import { cn } from '$lib/utils.js';
	import type { Snippet } from 'svelte';

	export type SidebarSectionProps = {
		id: string;
		label: string;
		/**
		 * A named section is a `region` landmark. Keep the default only for
		 * significant sidebar content (e.g. the mail folder list); for mere
		 * link groups pass `false` so the heading alone structures the group
		 * without adding landmark noise.
		 */
		landmark?: boolean;
		children?: Snippet;
		class?: string;
	};
</script>

<script lang="ts">
	let { id, label, landmark = true, children, class: className }: SidebarSectionProps = $props();
</script>

<section aria-labelledby={landmark ? id : undefined} class={cn('space-y-1', className)}>
	<h3
		{id}
		class="px-2.5 pb-1 text-caption font-semibold uppercase tracking-[0.16em] text-muted-foreground"
	>
		{label}
	</h3>
	{@render children?.()}
</section>
