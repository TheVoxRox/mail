<script lang="ts" module>
	import { cn, type WithElementRef } from '$lib/utils.js';
	import type { HTMLInputAttributes } from 'svelte/elements';
	import { type VariantProps, tv } from 'tailwind-variants';

	export const inputVariants = tv({
		base: 'block w-full rounded-md border border-input bg-background text-foreground shadow-xs outline-none transition-colors placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-2 focus-visible:ring-ring/40 disabled:cursor-not-allowed disabled:opacity-50 aria-invalid:border-destructive aria-invalid:ring-destructive/20',
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
