<script lang="ts">
	import { _ } from '$lib/i18n/index.js';
	import { Button } from '$lib/components/ui/button/index.js';
	import { DialogDescription, DialogShell, DialogTitle } from '$lib/components/ui/dialog/index.js';

	type Props = {
		open: boolean;
		busy: boolean;
		/**
		 * 'leave' = blocked navigation (discard abandons only the in-memory
		 * edits); 'discard' = explicit Zahodit (deletes the whole draft).
		 */
		intent: 'leave' | 'discard';
		/** Discard intent with a persisted draft: the copy must say it is deleted. */
		draftWillBeDeleted: boolean;
		onStay: () => void;
		onSave: () => void;
		onDiscard: () => void;
	};

	let { open, busy, intent, draftWillBeDeleted, onStay, onSave, onDiscard }: Props = $props();

	const title = $derived(
		intent === 'discard' ? $_('compose.discardDialog.title') : $_('compose.unsavedDialog.title')
	);
	const description = $derived(
		intent === 'discard'
			? draftWillBeDeleted
				? $_('compose.discardDialog.descriptionDraftDeleted')
				: $_('compose.discardDialog.description')
			: $_('compose.unsavedDialog.description')
	);
	const discardLabel = $derived(
		intent === 'discard' ? $_('compose.discardDialog.discard') : $_('compose.unsavedDialog.discard')
	);
</script>

<DialogShell {open} onOpenChange={(nextOpen) => !nextOpen && onStay()}>
	<DialogTitle>
		{title}
	</DialogTitle>
	<DialogDescription>
		{description}
	</DialogDescription>

	<div class="mt-5 flex flex-wrap justify-end gap-2">
		<Button variant="outline" onclick={onStay}>
			{$_('compose.unsavedDialog.stay')}
		</Button>
		<Button variant="outline" onclick={onSave} disabled={busy}>
			{$_('compose.unsavedDialog.save')}
		</Button>
		<Button variant="destructive" onclick={onDiscard}>
			{discardLabel}
		</Button>
	</div>
</DialogShell>
