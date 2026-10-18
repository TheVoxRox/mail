<script lang="ts">
	import { tick } from 'svelte';
	import {
		toasts,
		dismissToast,
		pauseToastCountdowns,
		resumeToastCountdowns,
		type ToastTone
	} from '#lib/stores/toasts.js';
	import { _ } from '#lib/i18n/index.js';
	import { focusRing } from '#lib/components/ui/focus-ring/index.js';
	import { cn } from '#lib/utils.js';

	const toneClass: Record<ToastTone, string> = {
		info: 'border-border bg-popover text-popover-foreground',
		success: 'border-success/50 bg-success/10 text-success-foreground',
		error: 'border-destructive/50 bg-destructive/10 text-destructive-foreground'
	};

	let regionEl = $state<HTMLDivElement | null>(null);
	/**
	 * Where focus was before it entered the region, so dismissing the last toast
	 * can put it back. A persistent (`ttl: 0`) toast is reached by tabbing to the
	 * very end of the app, so "wherever focus lands by itself" means starting the
	 * walk over from the top.
	 */
	let focusReturn: HTMLElement | null = null;

	function rememberFocusOrigin(event: FocusEvent): void {
		const from = event.relatedTarget;
		if (from instanceof HTMLElement && regionEl && !regionEl.contains(from)) {
			focusReturn = from;
		}
	}

	/*
	 * The clocks stop while the pointer or focus is on a toast, so the one being
	 * read or about to be closed does not go mid-sentence (WCAG 2.2.1). The
	 * region itself takes no pointer events; over and out bubble up from the
	 * toasts, and a move between two toasts is not a leave.
	 */
	let pointerInside = false;
	let focusInside = false;

	function syncCountdowns(): void {
		if (pointerInside || focusInside) pauseToastCountdowns();
		else resumeToastCountdowns();
	}

	const leftRegion = (to: EventTarget | null) => !(to instanceof Node && regionEl?.contains(to));

	function handleFocusIn(event: FocusEvent): void {
		rememberFocusOrigin(event);
		focusInside = true;
		syncCountdowns();
	}

	function handleFocusOut(event: FocusEvent): void {
		if (!leftRegion(event.relatedTarget)) return;
		focusInside = false;
		syncCountdowns();
	}

	function handlePointerOver(): void {
		pointerInside = true;
		syncCountdowns();
	}

	function handlePointerOut(event: PointerEvent): void {
		if (!leftRegion(event.relatedTarget)) return;
		pointerInside = false;
		syncCountdowns();
	}

	/*
	 * The stack lies over the content, and one kept until closed (errors, or
	 * every toast under the `manual` dismissal) can stay over a control that
	 * then takes focus: the row actions and dates of the list run along the
	 * right edge under it. When it covers the element that has focus outside
	 * it, it moves to the other end of the window if it covers less of that
	 * element there (WCAG 2.4.11, and 2.4.12 where it can). An element too
	 * large to get out of the way of, such as the message body, leaves it be.
	 *
	 * The other end is worked out rather than tried, so nothing is drawn in
	 * between: the same toasts, shifted by the distance between the two
	 * positions, which are the `top-14` and `bottom-4` of the classes below.
	 */
	let placement = $state<'top' | 'bottom'>('top');
	const TOP_OFFSET_REM = 3.5;
	const BOTTOM_OFFSET_REM = 1;

	function coveredArea(box: DOMRect, shift: number): number {
		let area = 0;
		for (const toast of regionEl?.children ?? []) {
			const r = toast.getBoundingClientRect();
			const width = Math.min(r.right, box.right) - Math.max(r.left, box.left);
			const height = Math.min(r.bottom + shift, box.bottom) - Math.max(r.top + shift, box.top);
			if (width > 0 && height > 0) area += width * height;
		}
		return area;
	}

	function keepClearOf(target: Element | null): void {
		// Every focus move in the app comes through here: no toast, no layout read.
		if (!regionEl || regionEl.childElementCount === 0) return;
		if (!(target instanceof Element) || regionEl.contains(target)) return;
		const box = target.getBoundingClientRect();
		const here = coveredArea(box, 0);
		if (here === 0) return;
		const stack = regionEl.getBoundingClientRect();
		const rem = parseFloat(getComputedStyle(document.documentElement).fontSize);
		const shift =
			placement === 'top'
				? window.innerHeight - BOTTOM_OFFSET_REM * rem - stack.bottom
				: TOP_OFFSET_REM * rem - stack.top;
		if (coveredArea(box, shift) < here) placement = placement === 'top' ? 'bottom' : 'top';
	}

	// A toast that arrives over the focused element, or one that grows the stack.
	$effect(() => {
		if ($toasts.length === 0) return;
		keepClearOf(document.activeElement);
	});

	// The last toast going takes the pointer and focus with it, without an out
	// event, and the next stack starts at the top again.
	$effect(() => {
		if ($toasts.length > 0) return;
		pointerInside = false;
		focusInside = false;
		resumeToastCountdowns();
		placement = 'top';
	});

	/**
	 * Dismissing removes the focused element from the DOM, and nothing used to
	 * put focus anywhere afterwards — the browser dropped it to `<body>`, which
	 * an NVDA session on 2026-09-03 heard as landing "somewhere else" twice in a
	 * row while clearing two stacked failure toasts.
	 *
	 * The next remaining dismiss button is the target while any toast is left:
	 * the user is clearing a stack and the next one is what they act on. The
	 * buttons are read before the store update, and the nodes of the toasts that
	 * survive stay valid because the `{#each}` is keyed by id.
	 */
	async function dismiss(id: number): Promise<void> {
		const buttons = regionEl ? [...regionEl.querySelectorAll<HTMLButtonElement>('button')] : [];
		const index = buttons.findIndex((button) => button === document.activeElement);
		const hadFocus = index !== -1;

		dismissToast(id);
		if (!hadFocus) return;

		const neighbour = buttons[index + 1] ?? buttons[index - 1] ?? null;
		await tick();
		if (neighbour?.isConnected) {
			neighbour.focus();
			return;
		}
		if (focusReturn?.isConnected) focusReturn.focus();
	}
</script>

<!--
	Visual toasts only (sighted users). Screen-reader announcements are handled
	by the persistent live regions in LiveAnnouncer, so these are intentionally
	NOT live regions — otherwise a message would be announced twice.
	data-print="chrome": a toast still on screen when the print dialog opens
	would otherwise land on every sheet, being fixed (see app.css).
-->
<div
	bind:this={regionEl}
	role="region"
	aria-label={$_('toast.regionLabel')}
	data-print="chrome"
	onfocusin={handleFocusIn}
	onfocusout={handleFocusOut}
	onpointerover={handlePointerOver}
	onpointerout={handlePointerOut}
	class={cn(
		'pointer-events-none fixed right-4 z-50 flex w-80 max-w-full flex-col gap-2',
		placement === 'top' ? 'top-14' : 'bottom-4'
	)}
>
	{#each $toasts as toast (toast.id)}
		<div
			role={toast.tone === 'error' ? 'alert' : 'status'}
			aria-live="off"
			class={cn(
				'pointer-events-auto flex items-start justify-between gap-2 rounded-md border px-3 py-2 text-sm shadow-md',
				toneClass[toast.tone]
			)}
		>
			<span id="toast-message-{toast.id}" class="min-w-0 flex-1 break-words">{toast.message}</span>
			<!--
				Named by the message it closes, not by the action alone. Two failures
				of the same account produce two toasts word for word alike, and a
				button named only by the action left no way to tell which one was about
				to go, or how many were left.
			-->
			<button
				type="button"
				onclick={() => void dismiss(toast.id)}
				aria-labelledby="toast-dismiss-{toast.id} toast-message-{toast.id}"
				class={cn('shrink-0 rounded-sm text-muted-foreground hover:text-foreground', focusRing)}
			>
				<span id="toast-dismiss-{toast.id}" class="sr-only">{$_('toast.dismiss')}</span>
				<span aria-hidden="true">×</span>
			</button>
		</div>
	{/each}
</div>

<svelte:document onfocusin={(event) => keepClearOf(event.target as Element | null)} />
