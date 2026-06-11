<script lang="ts">
	import { Dialog } from 'bits-ui';
	import { _ } from '$lib/i18n/index.js';
	import { Button } from '$lib/components/ui/button/index.js';

	type Props = {
		open: boolean;
		busy: boolean;
		onStay: () => void;
		onSave: () => void;
		onDiscard: () => void;
	};

	let { open, busy, onStay, onSave, onDiscard }: Props = $props();
</script>

<Dialog.Root {open} onOpenChange={(nextOpen) => !nextOpen && onStay()}>
	<Dialog.Portal>
		<Dialog.Overlay class="fixed inset-0 z-50 bg-black/40 backdrop-blur-[2px]" />
		<Dialog.Content
			class="fixed left-1/2 top-1/2 z-50 w-[calc(100vw-2rem)] max-w-md -translate-x-1/2 -translate-y-1/2 rounded-2xl border border-border bg-popover p-5 text-popover-foreground shadow-2xl"
			aria-describedby="compose-unsaved-description"
		>
			<Dialog.Title class="text-base font-semibold">
				{$_('compose.unsavedDialog.title')}
			</Dialog.Title>
			<Dialog.Description
				id="compose-unsaved-description"
				class="mt-2 text-sm text-muted-foreground"
			>
				{$_('compose.unsavedDialog.description')}
			</Dialog.Description>

			<div class="mt-5 flex flex-wrap justify-end gap-2">
				<Button variant="outline" onclick={onStay}>
					{$_('compose.unsavedDialog.stay')}
				</Button>
				<Button variant="outline" onclick={onSave} disabled={busy}>
					{$_('compose.unsavedDialog.save')}
				</Button>
				<Button variant="destructive" onclick={onDiscard}>
					{$_('compose.unsavedDialog.discard')}
				</Button>
			</div>
		</Dialog.Content>
	</Dialog.Portal>
</Dialog.Root>
