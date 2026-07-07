<script lang="ts" module>
	import { cn, type WithElementRef } from '$lib/utils.js';
	import type { HTMLTextareaAttributes } from 'svelte/elements';
	import { type VariantProps, tv } from 'tailwind-variants';

	export const textareaVariants = tv({
		base: 'block w-full rounded-md border border-input bg-background text-foreground shadow-xs outline-none transition-colors placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-2 focus-visible:ring-ring/40 disabled:cursor-not-allowed disabled:opacity-50 aria-invalid:border-destructive aria-invalid:ring-destructive/20',
		variants: {
			resize: {
				vertical: 'resize-y',
				none: 'resize-none'
			},
			size: {
				default: 'px-2.5 py-1.5 text-sm',
				sm: 'px-2.5 py-1.5 text-xs'
			}
		},
		defaultVariants: {
			resize: 'vertical',
			size: 'default'
		}
	});

	export type TextareaResize = VariantProps<typeof textareaVariants>['resize'];
	export type TextareaSize = VariantProps<typeof textareaVariants>['size'];
	export type TextareaProps = WithElementRef<HTMLTextareaAttributes, HTMLTextAreaElement> & {
		resize?: TextareaResize;
		size?: TextareaSize;
	};
</script>

<script lang="ts">
	let {
		class: className,
		ref = $bindable(null),
		value = $bindable(),
		resize = 'vertical',
		size = 'default',
		...restProps
	}: TextareaProps = $props();
</script>

<textarea
	bind:this={ref}
	bind:value
	class={cn(textareaVariants({ resize, size }), className)}
	{...restProps}></textarea>
