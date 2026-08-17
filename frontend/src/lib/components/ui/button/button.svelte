<script lang="ts" module>
	import { cn, type WithElementRef } from '$lib/utils.js';
	import { focusRing } from '../focus-ring/index.js';
	import type { HTMLAnchorAttributes, HTMLButtonAttributes } from 'svelte/elements';
	import { type VariantProps, tv } from 'tailwind-variants';

	export const buttonVariants = tv({
		/*
		 * Unavailable is drawn with the muted tokens, not with opacity. Opacity
		 * was tried and axe caught it on the boot screen's diagnostics button:
		 * 50% takes the label to 2.31:1, and while WCAG exempts an inactive
		 * control from 1.4.3, an unreadable one is still unreadable. Flat muted
		 * fill plus muted text says the same thing at 6.71:1.
		 *
		 * `aria-disabled` gets the same treatment as `disabled`: a button that
		 * has to stay in the focus order — a cell of an ARIA grid row cannot go
		 * missing without punching a hole in the roving sequence — still has to
		 * look unavailable, and doing that per call site is how one of them ends
		 * up looking enabled.
		 */
		base: `${focusRing} aria-invalid:ring-destructive/20 dark:aria-invalid:ring-destructive/40 aria-invalid:border-destructive dark:aria-invalid:border-destructive/50 rounded-lg border border-transparent bg-clip-padding text-sm font-medium active:not-aria-[haspopup]:translate-y-px aria-invalid:ring-2 [&_svg:not([class*='size-'])]:size-4 group/button inline-flex shrink-0 items-center justify-center whitespace-nowrap transition-all select-none disabled:pointer-events-none disabled:border-border disabled:bg-muted disabled:text-muted-foreground aria-disabled:cursor-not-allowed aria-disabled:border-border aria-disabled:bg-muted aria-disabled:text-muted-foreground [&_svg]:pointer-events-none [&_svg]:shrink-0`,
		variants: {
			variant: {
				default: 'bg-primary text-primary-foreground [a]:hover:bg-primary/80',
				outline:
					'border-border bg-background hover:bg-muted hover:text-foreground dark:bg-input/30 dark:border-input dark:hover:bg-input/50 aria-expanded:bg-muted aria-expanded:text-foreground',
				secondary:
					'bg-secondary text-secondary-foreground hover:bg-secondary/80 aria-expanded:bg-secondary aria-expanded:text-secondary-foreground',
				ghost:
					'hover:bg-muted hover:text-foreground dark:hover:bg-muted/50 aria-expanded:bg-muted aria-expanded:text-foreground',
				/*
				 * No focus ring of its own. It used to re-tint the ring red at 20%
				 * opacity, which is both a second focus indicator to keep in step
				 * and, at that opacity, one that does not reach the 3:1 a focus
				 * indicator owes. The button reads as destructive from its border
				 * and its text; where the focus is has one answer everywhere.
				 */
				destructive:
					'border-destructive/30 bg-background text-destructive-foreground hover:bg-destructive/10 dark:border-destructive/50 dark:bg-background dark:hover:bg-destructive/20',
				link: 'text-primary underline-offset-4 hover:underline'
			},
			size: {
				default:
					'h-8 gap-1.5 px-2.5 has-data-[icon=inline-end]:pr-2 has-data-[icon=inline-start]:pl-2',
				/*
				 * `rounded-md`, not the `min(var(--radius-md), 10px)` this was
				 * carried in on: `--radius-md` is 8px at the default root size and
				 * 9.25px at the largest text-size step, so the cap never applied at
				 * any size the app can be in — it only read like a second radius
				 * scale living outside app.css.
				 */
				xs: "h-6 gap-1 rounded-md px-2 text-xs in-data-[slot=button-group]:rounded-lg has-data-[icon=inline-end]:pr-1.5 has-data-[icon=inline-start]:pl-1.5 [&_svg:not([class*='size-'])]:size-3",
				sm: "h-7 gap-1 rounded-md px-2.5 text-sm in-data-[slot=button-group]:rounded-lg has-data-[icon=inline-end]:pr-1.5 has-data-[icon=inline-start]:pl-1.5 [&_svg:not([class*='size-'])]:size-3.5",
				lg: 'h-9 gap-1.5 px-2.5 has-data-[icon=inline-end]:pr-2 has-data-[icon=inline-start]:pl-2',
				icon: 'size-8',
				'icon-xs':
					"size-6 rounded-md in-data-[slot=button-group]:rounded-lg [&_svg:not([class*='size-'])]:size-3",
				'icon-sm': 'size-7 rounded-md in-data-[slot=button-group]:rounded-lg',
				'icon-lg': 'size-9'
			}
		},
		defaultVariants: {
			variant: 'default',
			size: 'default'
		}
	});

	export type ButtonVariant = VariantProps<typeof buttonVariants>['variant'];
	export type ButtonSize = VariantProps<typeof buttonVariants>['size'];

	export type ButtonProps = WithElementRef<HTMLButtonAttributes> &
		WithElementRef<HTMLAnchorAttributes> & {
			variant?: ButtonVariant;
			size?: ButtonSize;
		};
</script>

<script lang="ts">
	let {
		class: className,
		variant = 'default',
		size = 'default',
		ref = $bindable(null),
		href = undefined,
		type = 'button',
		disabled,
		children,
		...restProps
	}: ButtonProps = $props();
</script>

{#if href}
	<a
		bind:this={ref}
		data-slot="button"
		class={cn(buttonVariants({ variant, size }), className)}
		href={disabled ? undefined : href}
		aria-disabled={disabled}
		role={disabled ? 'link' : undefined}
		tabindex={disabled ? -1 : undefined}
		{...restProps}
	>
		{@render children?.()}
	</a>
{:else}
	<button
		bind:this={ref}
		data-slot="button"
		class={cn(buttonVariants({ variant, size }), className)}
		{type}
		{disabled}
		{...restProps}
	>
		{@render children?.()}
	</button>
{/if}
