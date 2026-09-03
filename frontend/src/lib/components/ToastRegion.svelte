<script lang="ts">
	import { tick } from 'svelte';
	import { toasts, dismissToast, type ToastTone } from '$lib/stores/toasts.js';
	import { _ } from '$lib/i18n/index.js';
	import { focusRing } from '$lib/components/ui/focus-ring/index.js';
	import { cn } from '$lib/utils.js';

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
-->
<div
	bind:this={regionEl}
	role="region"
	aria-label={$_('toast.regionLabel')}
	onfocusin={rememberFocusOrigin}
	class="pointer-events-none fixed right-4 top-14 z-50 flex w-80 max-w-full flex-col gap-2"
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
