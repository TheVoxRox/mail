<script lang="ts">
	import { assignContactLabels } from '$lib/api/contactLabels.js';
	import { toErrorMessage } from '$lib/api/errors.js';
	import { _ } from '$lib/i18n/index.js';
	import { contactCounts } from '$lib/stores/contactCounts.js';
	import { announcePolite, pushToast } from '$lib/stores/toasts.js';
	import { Button } from '$lib/components/ui/button/index.js';
	import { DialogDescription, DialogShell, DialogTitle } from '$lib/components/ui/dialog/index.js';
	import { StateMessage } from '$lib/components/ui/state-message/index.js';
	import { nativeControlClass } from '$lib/components/ui/native-control/index.js';
	import type { ContactResponse } from '$lib/types.js';

	interface Props {
		open: boolean;
		/** The selected contacts, so the initial tri-state can be derived. */
		contacts: ContactResponse[];
		onOpenChange: (open: boolean) => void;
		onAssigned: () => void | Promise<void>;
	}

	let { open, contacts, onOpenChange, onAssigned }: Props = $props();

	const labels = $derived($contactCounts?.labels ?? []);

	/**
	 * How many of the selected contacts already carry each label. Drives the
	 * initial state of its checkbox: all → checked, none → unchecked, anything
	 * between → indeterminate.
	 */
	const initialState = $derived.by(() => {
		const state: Record<number, 'all' | 'none' | 'some'> = {};
		for (const label of labels) {
			const carriers = carrierCount(label.id);
			state[label.id] = carriers === 0 ? 'none' : carriers === contacts.length ? 'all' : 'some';
		}
		return state;
	});

	/**
	 * How many of the *selected* contacts carry the label. Deliberately not the
	 * account-wide count from the store: this dialog is about the selection, and
	 * a global figure next to a mixed checkbox reads as "this many of the ones I
	 * picked".
	 */
	function carrierCount(labelId: number): number {
		return contacts.filter((contact) => contact.labels.some((item) => item.id === labelId)).length;
	}

	/*
	 * Only the labels the user actually toggled. A label left alone keeps its
	 * mixed state and is sent in neither list — that is the whole point of the
	 * tri-state: applying the dialog must not flatten a partial selection into
	 * "everyone has it" just because the user came to change a different label.
	 */
	let pending = $state<Record<number, boolean>>({});
	let busy = $state(false);
	let serverError = $state<string | null>(null);

	$effect(() => {
		if (!open) return;
		pending = {};
		serverError = null;
		busy = false;
	});

	function effectiveChecked(labelId: number): boolean {
		const override = pending[labelId];
		if (override != null) return override;
		return initialState[labelId] === 'all';
	}

	function isIndeterminate(labelId: number): boolean {
		return pending[labelId] == null && initialState[labelId] === 'some';
	}

	function toggle(labelId: number, checked: boolean): void {
		const next = { ...pending };
		const original = initialState[labelId];
		// Toggling back to where it started drops the entry, so a label the user
		// flipped twice is not sent as a no-op change.
		if ((original === 'all' && checked) || (original === 'none' && !checked)) {
			delete next[labelId];
		} else {
			next[labelId] = checked;
		}
		pending = next;
	}

	const pendingEntries = $derived(
		Object.entries(pending).map(([id, checked]) => ({ id: Number(id), checked }))
	);
	const addLabelIds = $derived(
		pendingEntries.filter((entry) => entry.checked).map((entry) => entry.id)
	);
	const removeLabelIds = $derived(
		pendingEntries.filter((entry) => !entry.checked).map((entry) => entry.id)
	);
	const hasChanges = $derived(addLabelIds.length > 0 || removeLabelIds.length > 0);

	function close(): void {
		if (busy) return;
		onOpenChange(false);
	}

	async function submit(): Promise<void> {
		if (!hasChanges || busy) return;
		busy = true;
		serverError = null;
		try {
			const result = await assignContactLabels({
				contactIds: contacts.map((contact) => contact.id),
				addLabelIds,
				removeLabelIds
			});
			// `changed` is the backend's honest count, not the selection size — a
			// re-applied label reports 0 rather than claiming work it did not do.
			const message =
				result.changed === 0
					? $_('contacts.assignNoChange')
					: $_('contacts.assignDone', {
							values: { changed: result.changed, total: result.total }
						});
			pushToast(message, { tone: 'success' });
			announcePolite(message);
			onOpenChange(false);
			await onAssigned();
		} catch (err) {
			serverError = toErrorMessage(err);
		} finally {
			busy = false;
		}
	}
</script>

<DialogShell {open} onOpenChange={(next) => (next ? onOpenChange(true) : close())} size="lg" scroll>
	<DialogTitle>
		{$_('contacts.assignDialogTitle')}
	</DialogTitle>
	<DialogDescription>
		{$_('contacts.assignDialogIntro')}
	</DialogDescription>

	{#if labels.length === 0}
		<p class="mt-4 text-sm text-muted-foreground">{$_('contacts.formLabelsEmpty')}</p>
	{:else}
		<fieldset class="mt-4 space-y-1.5">
			<legend class="sr-only">{$_('contacts.assignDialogTitle')}</legend>
			{#each labels as label (label.id)}
				{@const inputId = `contact-label-assign-${label.id}`}
				{@const partial = isIndeterminate(label.id)}
				<label
					for={inputId}
					class="flex cursor-pointer items-center gap-2 rounded-md border border-border bg-background px-3 py-2 text-sm hover:bg-muted/40 has-[:checked]:border-primary has-[:checked]:bg-primary/10"
				>
					<input
						id={inputId}
						type="checkbox"
						class={nativeControlClass}
						checked={effectiveChecked(label.id)}
						indeterminate={partial}
						onchange={(event) => toggle(label.id, event.currentTarget.checked)}
						disabled={busy}
					/>
					<span class="min-w-0 flex-1 truncate">{label.name}</span>
					{#if partial}
						<!--
							A native indeterminate checkbox announces "mixed", which says the
							state but not what it means here. The sr-only text spells out that
							only part of the selection carries the label.
						-->
						<span class="sr-only">
							{$_('contacts.assignPartial', { values: { label: label.name } })}
						</span>
						<span class="shrink-0 text-xs text-muted-foreground" aria-hidden="true">
							{$_('contacts.assignPartialCount', {
								values: { carriers: carrierCount(label.id), total: contacts.length }
							})}
						</span>
					{/if}
				</label>
			{/each}
		</fieldset>
	{/if}

	{#if serverError}
		<StateMessage variant="error" padding="none" role="alert" class="mt-3">
			{serverError}
		</StateMessage>
	{/if}

	<div class="mt-5 flex flex-wrap justify-end gap-2">
		<Button variant="outline" onclick={close} disabled={busy}>
			{$_('common.cancel')}
		</Button>
		<Button onclick={submit} disabled={!hasChanges || busy}>
			{busy ? $_('contacts.assignBusy') : $_('contacts.assignApply')}
		</Button>
	</div>
</DialogShell>
