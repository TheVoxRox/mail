<script lang="ts">
	import { _ } from '$lib/i18n/index.js';
	import { Button } from '$lib/components/ui/button/index.js';
	import AutosaveStatus from '$lib/components/compose/AutosaveStatus.svelte';

	type BusyAction = 'send' | 'save' | null;

	type Props = {
		autosaving: boolean;
		autoSavedAt: Date | null;
		autosaveError: string;
		busy: boolean;
		prefillDone: boolean;
		busyAction: BusyAction;
		onDiscard: () => void;
		onSaveDraft: () => void;
	};

	let {
		autosaving,
		autoSavedAt,
		autosaveError,
		busy,
		prefillDone,
		busyAction,
		onDiscard,
		onSaveDraft
	}: Props = $props();
</script>

<div class="flex items-center justify-between border-b border-border p-4">
	<h1 class="text-title font-semibold">{$_('compose.heading')}</h1>
	<div class="flex items-center gap-2">
		<AutosaveStatus {autosaving} {autoSavedAt} {autosaveError} />
		<Button
			variant="ghost"
			size="sm"
			onclick={onDiscard}
			disabled={!prefillDone || busy}
			aria-keyshortcuts="Control+Shift+D"
		>
			{$_('compose.discard')}
		</Button>
		<Button
			variant="outline"
			size="sm"
			onclick={onSaveDraft}
			disabled={busy || !prefillDone}
			aria-keyshortcuts="Control+S"
		>
			{busyAction === 'save' ? $_('compose.savingDraft') : $_('compose.saveDraft')}
		</Button>
		<Button type="submit" disabled={busy || !prefillDone} aria-keyshortcuts="Control+Enter">
			{busyAction === 'send' ? $_('compose.sending') : $_('compose.send')}
		</Button>
	</div>
</div>
