<script lang="ts" module>
	import { cn, type WithElementRef } from '$lib/utils.js';
	import type { Snippet } from 'svelte';
	import type { HTMLSelectAttributes } from 'svelte/elements';
	import { type VariantProps, tv } from 'tailwind-variants';

	export const selectVariants = tv({
		base: 'block rounded-md border border-input bg-background text-foreground shadow-xs outline-none transition-colors focus-visible:border-ring focus-visible:ring-2 focus-visible:ring-ring/40 disabled:cursor-not-allowed disabled:opacity-50 aria-invalid:border-destructive aria-invalid:ring-destructive/20',
		variants: {
			size: {
				default: 'px-2.5 py-1.5 text-sm',
				sm: 'h-8 px-2.5 text-xs'
			},
			width: {
				auto: '',
				full: 'w-full'
			}
		},
		defaultVariants: {
			size: 'default',
			width: 'auto'
		}
	});

	export type SelectSize = VariantProps<typeof selectVariants>['size'];
	export type SelectWidth = VariantProps<typeof selectVariants>['width'];
	export type SelectProps = Omit<
		WithElementRef<HTMLSelectAttributes, HTMLSelectElement>,
		'size'
	> & {
		children?: Snippet;
		size?: SelectSize;
		width?: SelectWidth;
		value?: unknown;
	};
</script>

<script lang="ts">
	let {
		class: className,
		ref = $bindable(null),
		value = $bindable(),
		children,
		size = 'default',
		width = 'auto',
		onkeydown,
		...restProps
	}: SelectProps = $props();

	function handleKeydown(
		event: KeyboardEvent & { currentTarget: EventTarget & HTMLSelectElement }
	): void {
		onkeydown?.(event);
		if (event.defaultPrevented) return;
		if (event.key !== 'ArrowDown') return;
		if (event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return;

		const select = event.currentTarget as HTMLSelectElement & { showPicker?: () => void };
		if (typeof select.showPicker !== 'function') return;

		event.preventDefault();
		try {
			select.showPicker();
		} catch {
			// Some WebViews expose showPicker but can reject it outside trusted input.
		}
	}
</script>

<select
	bind:this={ref}
	bind:value
	class={cn(selectVariants({ size, width }), className)}
	onkeydown={handleKeydown}
	{...restProps}
>
	{@render children?.()}
</select>
