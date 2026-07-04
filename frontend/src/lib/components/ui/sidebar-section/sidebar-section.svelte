<script lang="ts" module>
	import { cn } from '$lib/utils.js';
	import type { Snippet } from 'svelte';

	export type SidebarSectionProps = {
		id: string;
		label: string;
		/**
		 * A named section is a `region` landmark. Off by default: link and
		 * action groups are structured by their heading alone — extra regions
		 * nested inside a sidebar pane are landmark noise. Opt in only for
		 * content significant enough to deserve its own landmark (no current
		 * caller does).
		 */
		landmark?: boolean;
		children?: Snippet;
		class?: string;
	};
</script>

<script lang="ts">
	let { id, label, landmark = false, children, class: className }: SidebarSectionProps = $props();
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
