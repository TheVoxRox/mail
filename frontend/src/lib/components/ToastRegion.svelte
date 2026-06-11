<script lang="ts">
	import { toasts, dismissToast, type ToastTone } from '$lib/stores/toasts.js';
	import { _ } from '$lib/i18n/index.js';
	import { cn } from '$lib/utils.js';

	const toneClass: Record<ToastTone, string> = {
		info: 'border-border bg-popover text-popover-foreground',
		success: 'border-chart-2/50 bg-chart-2/10 text-foreground',
		error: 'border-destructive/50 bg-destructive/10 text-destructive'
	};
</script>

<!--
	Visual toasts only (sighted users). Screen-reader announcements are handled
	by the persistent live regions in LiveAnnouncer, so these are intentionally
	NOT live regions — otherwise a message would be announced twice.
-->
<div
	role="region"
	aria-label={$_('toast.regionLabel')}
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
			<span class="min-w-0 flex-1 break-words">{toast.message}</span>
			<button
				type="button"
				onclick={() => dismissToast(toast.id)}
				aria-label={$_('toast.dismiss')}
				class="shrink-0 rounded text-muted-foreground hover:text-foreground focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-ring"
			>
				<span aria-hidden="true">×</span>
			</button>
		</div>
	{/each}
</div>
