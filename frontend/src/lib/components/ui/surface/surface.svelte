<script lang="ts" module>
	import { cn } from '$lib/utils.js';
	import type { Snippet } from 'svelte';
	import type { HTMLAttributes } from 'svelte/elements';
	import { type VariantProps, tv } from 'tailwind-variants';

	export const surfaceVariants = tv({
		base: 'rounded-md border text-card-foreground shadow-xs',
		variants: {
			variant: {
				default: 'border-border bg-card',
				list: 'overflow-hidden border-border bg-card',
				subtle: 'border-border bg-background/80',
				danger: 'border-destructive/30 bg-destructive/10 text-destructive',
				success: 'border-primary/30 bg-primary/10 text-primary',
				warning: 'border-warning/40 bg-warning/10 text-warning-foreground'
			},
			padding: {
				none: '',
				sm: 'p-3',
				default: 'p-4',
				lg: 'p-6'
			}
		},
		defaultVariants: {
			variant: 'default',
			padding: 'default'
		}
	});

	export type SurfaceVariant = VariantProps<typeof surfaceVariants>['variant'];
	export type SurfacePadding = VariantProps<typeof surfaceVariants>['padding'];
	export type SurfaceProps = HTMLAttributes<HTMLElement> & {
		as?: 'div' | 'section' | 'article';
		children?: Snippet;
		variant?: SurfaceVariant;
		padding?: SurfacePadding;
	};
</script>

<script lang="ts">
	let {
		as = 'div',
		class: className,
		children,
		variant = 'default',
		padding = 'default',
		...restProps
	}: SurfaceProps = $props();
</script>

{#if as === 'section'}
	<section class={cn(surfaceVariants({ variant, padding }), className)} {...restProps}>
		{@render children?.()}
	</section>
{:else if as === 'article'}
	<article class={cn(surfaceVariants({ variant, padding }), className)} {...restProps}>
		{@render children?.()}
	</article>
{:else}
	<div class={cn(surfaceVariants({ variant, padding }), className)} {...restProps}>
		{@render children?.()}
	</div>
{/if}
