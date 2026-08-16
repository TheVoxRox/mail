<script lang="ts">
	import { Button } from '$lib/components/ui/button/index.js';
	import { DialogDescription, DialogShell, DialogTitle } from '$lib/components/ui/dialog/index.js';
	import { confirmDialog, resolveConfirmDialog } from '$lib/stores/confirmDialog.js';

	const open = $derived($confirmDialog !== null);

	function handleOpenChange(nextOpen: boolean): void {
		if (!nextOpen) {
			resolveConfirmDialog(false);
		}
	}

	function handleCancel(): void {
		resolveConfirmDialog(false);
	}

	function handleConfirm(): void {
		resolveConfirmDialog(true);
	}
</script>

<DialogShell {open} onOpenChange={handleOpenChange}>
	{#if $confirmDialog}
		<DialogTitle>
			{$confirmDialog.title}
		</DialogTitle>
		<DialogDescription>
			{$confirmDialog.description}
		</DialogDescription>

		<div class="mt-5 flex flex-wrap justify-end gap-2">
			<Button variant="outline" onclick={handleCancel}>
				{$confirmDialog.cancelLabel}
			</Button>
			<Button
				variant={$confirmDialog.tone === 'destructive' ? 'destructive' : 'default'}
				onclick={handleConfirm}
			>
				{$confirmDialog.confirmLabel}
			</Button>
		</div>
	{/if}
</DialogShell>
