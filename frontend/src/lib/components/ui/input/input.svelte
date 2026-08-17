<script lang="ts" module>
	import { cn, type WithElementRef } from '$lib/utils.js';
	import { focusRing } from '../focus-ring/index.js';
	import type { HTMLInputAttributes } from 'svelte/elements';
	import { type VariantProps, tv } from 'tailwind-variants';

	export const inputVariants = tv({
		/*
		 * `aria-invalid:ring-2` alongside the ring colour: a `ring-<colour>`
		 * on its own sets no width, so the invalid halo the three form
		 * primitives all declared was never painted on any of them.
		 */
		base: `block w-full rounded-md border border-input bg-background text-foreground shadow-xs transition-colors placeholder:text-muted-foreground disabled:cursor-not-allowed disabled:opacity-50 aria-invalid:border-destructive aria-invalid:ring-2 aria-invalid:ring-destructive/20 ${focusRing}`,
		variants: {
			size: {
				default: 'px-2.5 py-1.5 text-sm',
				sm: 'h-8 px-2.5 text-xs'
			}
		},
		defaultVariants: {
			size: 'default'
		}
	});

	export type InputSize = VariantProps<typeof inputVariants>['size'];
	export type InputProps = Omit<WithElementRef<HTMLInputAttributes, HTMLInputElement>, 'size'> & {
		size?: InputSize;
	};
</script>

<script lang="ts">
	let {
		class: className,
		ref = $bindable(null),
		value = $bindable(),
		size = 'default',
		...restProps
	}: InputProps = $props();
</script>

<input bind:this={ref} bind:value class={cn(inputVariants({ size }), className)} {...restProps} />
