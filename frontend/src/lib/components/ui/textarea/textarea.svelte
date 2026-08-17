<script lang="ts" module>
	import { cn, type WithElementRef } from '$lib/utils.js';
	import { focusRing } from '../focus-ring/index.js';
	import type { HTMLTextareaAttributes } from 'svelte/elements';
	import { type VariantProps, tv } from 'tailwind-variants';

	export const textareaVariants = tv({
		base: `block w-full rounded-md border border-input bg-background text-foreground shadow-xs transition-colors placeholder:text-muted-foreground disabled:cursor-not-allowed disabled:opacity-50 aria-invalid:border-destructive aria-invalid:ring-2 aria-invalid:ring-destructive/20 ${focusRing}`,
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
