<script lang="ts" module>
	import { cn } from '$lib/utils.js';
	import type { Snippet } from 'svelte';
	import type { HTMLLiAttributes } from 'svelte/elements';
	import { type VariantProps, tv } from 'tailwind-variants';

	export const listRowVariants = tv({
		base: 'flex justify-between text-sm transition-colors hover:bg-muted/35',
		variants: {
			align: {
				start: 'items-start',
				center: 'items-center'
			},
			density: {
				default: 'gap-3 px-4 py-3.5',
				dense: 'gap-3 px-4 py-3'
			}
		},
		defaultVariants: {
			align: 'start',
			density: 'default'
		}
	});

	export type ListRowAlign = VariantProps<typeof listRowVariants>['align'];
	export type ListRowDensity = VariantProps<typeof listRowVariants>['density'];
	export type ListRowProps = HTMLLiAttributes & {
		actions?: Snippet;
		children?: Snippet;
		align?: ListRowAlign;
		density?: ListRowDensity;
		actionsClass?: string;
	};
</script>

<script lang="ts">
	let {
		class: className,
		children,
		actions,
		align = 'start',
		density = 'default',
		actionsClass,
		...restProps
	}: ListRowProps = $props();
</script>

<li class={cn(listRowVariants({ align, density }), className)} {...restProps}>
	<div class="min-w-0 flex-1">
		{@render children?.()}
	</div>
	{#if actions}
		<div class={cn('flex shrink-0 flex-wrap justify-end gap-1.5', actionsClass)}>
			{@render actions()}
		</div>
	{/if}
</li>
