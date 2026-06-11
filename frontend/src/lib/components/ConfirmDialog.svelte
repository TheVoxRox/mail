<script lang="ts">
	import { Dialog } from 'bits-ui';
	import { Button } from '$lib/components/ui/button/index.js';
	import { confirmDialog, resolveConfirmDialog } from '$lib/stores/confirmDialog.js';

	const descriptionId = $derived(
		$confirmDialog ? `confirm-dialog-${$confirmDialog.id}` : undefined
	);
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

<Dialog.Root {open} onOpenChange={handleOpenChange}>
	<Dialog.Portal>
		<Dialog.Overlay class="fixed inset-0 z-50 bg-black/40 backdrop-blur-[2px]" />
		<Dialog.Content
			class="fixed left-1/2 top-1/2 z-50 w-[calc(100vw-2rem)] max-w-md -translate-x-1/2 -translate-y-1/2 rounded-2xl border border-border bg-popover p-5 text-popover-foreground shadow-2xl"
			aria-describedby={descriptionId}
		>
			{#if $confirmDialog}
				<Dialog.Title class="text-base font-semibold">
					{$confirmDialog.title}
				</Dialog.Title>
				<Dialog.Description id={descriptionId} class="mt-2 text-sm text-muted-foreground">
					{$confirmDialog.description}
				</Dialog.Description>

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
		</Dialog.Content>
	</Dialog.Portal>
</Dialog.Root>
