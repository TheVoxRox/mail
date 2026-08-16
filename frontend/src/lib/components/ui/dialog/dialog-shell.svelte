<script lang="ts" module>
	import { cn } from '$lib/utils.js';
	import type { Snippet } from 'svelte';
	import { type VariantProps, tv } from 'tailwind-variants';

	/**
	 * The modal surface every dialog in the app sits on: the dimmed overlay, the
	 * centred (or top-anchored) panel and the border/shadow that make it read as
	 * a layer above the page.
	 *
	 * Each dialog used to carry all of that inline, which is how the two update
	 * dialogs drifted to `rounded-lg` while the rest stayed `rounded-2xl` —
	 * nobody opens every dialog in the app to change a corner. The variants below
	 * are the axes the dialogs genuinely disagree on; everything else is fixed
	 * here on purpose.
	 *
	 * The shell sets no `aria-describedby`: a `DialogDescription` registers its
	 * id with the dialog root and bits-ui puts the attribute on the content
	 * itself. Passing one down as well meant writing the same id twice with
	 * nothing checking the two still matched.
	 */
	export const dialogContentVariants = tv({
		base: 'fixed left-1/2 z-50 w-[calc(100vw-2rem)] -translate-x-1/2 rounded-2xl border border-border bg-popover text-popover-foreground shadow-2xl',
		variants: {
			size: {
				md: 'max-w-md',
				lg: 'max-w-lg',
				xl: 'max-w-xl',
				'2xl': 'max-w-2xl'
			},
			placement: {
				/** Middle of the viewport — a dialog the user has to answer. */
				center: 'top-1/2 -translate-y-1/2',
				/** Below the top edge, so a growing result list does not shift it. */
				top: 'top-16'
			},
			padding: {
				/** For panels that draw their own sections with their own edges. */
				none: '',
				default: 'p-5'
			},
			/** Long content scrolls inside the dialog instead of off the viewport. */
			scroll: {
				true: 'max-h-[90vh] overflow-y-auto',
				false: ''
			}
		},
		defaultVariants: {
			size: 'md',
			placement: 'center',
			padding: 'default',
			scroll: false
		}
	});

	export type DialogSize = VariantProps<typeof dialogContentVariants>['size'];
	export type DialogPlacement = VariantProps<typeof dialogContentVariants>['placement'];
	export type DialogPadding = VariantProps<typeof dialogContentVariants>['padding'];
</script>

<script lang="ts">
	import { Dialog } from 'bits-ui';

	interface Props {
		open: boolean;
		onOpenChange: (open: boolean) => void;
		size?: DialogSize;
		placement?: DialogPlacement;
		padding?: DialogPadding;
		scroll?: boolean;
		class?: string;
		children: Snippet;
	}

	let {
		open,
		onOpenChange,
		size = 'md',
		placement = 'center',
		padding = 'default',
		scroll = false,
		class: className,
		children
	}: Props = $props();
</script>

<Dialog.Root {open} {onOpenChange}>
	<Dialog.Portal>
		<Dialog.Overlay class="fixed inset-0 z-50 bg-black/40 backdrop-blur-[2px]" />
		<Dialog.Content
			class={cn(dialogContentVariants({ size, placement, padding, scroll }), className)}
		>
			{@render children()}
		</Dialog.Content>
	</Dialog.Portal>
</Dialog.Root>
