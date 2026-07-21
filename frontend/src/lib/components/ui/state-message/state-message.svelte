<script lang="ts" module>
	import { cn } from '$lib/utils.js';
	import type { Snippet } from 'svelte';
	import type { HTMLAttributes } from 'svelte/elements';
	import { type VariantProps, tv } from 'tailwind-variants';

	export const stateMessageVariants = tv({
		base: 'text-sm',
		variants: {
			variant: {
				muted: 'text-muted-foreground',
				error: 'text-destructive',
				default: 'text-foreground'
			},
			padding: {
				none: '',
				sm: 'p-3',
				default: 'p-4',
				lg: 'p-6'
			}
		},
		defaultVariants: {
			variant: 'muted',
			padding: 'default'
		}
	});

	export type StateMessageVariant = VariantProps<typeof stateMessageVariants>['variant'];
	export type StateMessagePadding = VariantProps<typeof stateMessageVariants>['padding'];
	export type StateMessageProps = HTMLAttributes<HTMLParagraphElement> & {
		children?: Snippet;
		variant?: StateMessageVariant;
		padding?: StateMessagePadding;
		/**
		 * Bindable element. An empty-state message is a focus target: when the
		 * last row of a list is removed, the control that removed it is gone and
		 * focus has to land somewhere that says what happened.
		 */
		ref?: HTMLParagraphElement | null;
	};
</script>

<script lang="ts">
	let {
		class: className,
		children,
		variant = 'muted',
		padding = 'default',
		ref = $bindable(null),
		...restProps
	}: StateMessageProps = $props();
</script>

<p bind:this={ref} class={cn(stateMessageVariants({ variant, padding }), className)} {...restProps}>
	{@render children?.()}
</p>
