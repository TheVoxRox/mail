<script lang="ts">
	import {
		createContactLabel,
		deleteContactLabel,
		renameContactLabel
	} from '$lib/api/contactLabels.js';
	import { toErrorMessage } from '$lib/api/errors.js';
	import { _ } from '$lib/i18n/index.js';
	import { contactCounts, refreshContactCounts } from '$lib/stores/contactCounts.js';
	import { announcePolite, pushToast } from '$lib/stores/toasts.js';
	import { Button } from '$lib/components/ui/button/index.js';
	import { DialogDescription, DialogShell, DialogTitle } from '$lib/components/ui/dialog/index.js';
	import { Input } from '$lib/components/ui/input/index.js';
	import { StateMessage } from '$lib/components/ui/state-message/index.js';
	import { tick } from 'svelte';

	interface Props {
		open: boolean;
		onOpenChange: (open: boolean) => void;
		/** Fired after any change, so the caller can reload a list filtered by a label. */
		onChanged?: () => void | Promise<void>;
	}

	let { open, onOpenChange, onChanged }: Props = $props();

	/*
	 * The counts store is the single source for labels: it is loaded per account,
	 * refreshed on every contact-list load, and already carries the usage counts
	 * this dialog needs for the delete confirmation. A second fetch here would
	 * only add a way for the two to disagree.
	 */
	const labels = $derived($contactCounts?.labels ?? []);

	let newName = $state('');
	let creating = $state(false);
	let createError = $state<string | null>(null);
	let newNameInput = $state<HTMLInputElement | null>(null);

	/** Id of the label whose inline rename field is open, if any. */
	let renamingId = $state<number | null>(null);
	let renameValue = $state('');
	let renameBusy = $state(false);
	let renameError = $state<string | null>(null);

	let deletingId = $state<number | null>(null);
	/** Label whose inline delete confirmation is showing; see confirmDelete below. */
	let pendingDeleteId = $state<number | null>(null);

	$effect(() => {
		if (!open) return;
		newName = '';
		createError = null;
		renamingId = null;
		renameError = null;
		// Including the delete prompt: it is destructive, and leaving it armed
		// across a close/reopen puts a live "Smazat" in front of a user who never
		// asked for it this time round.
		pendingDeleteId = null;
	});

	function close(): void {
		if (creating || renameBusy || deletingId != null) return;
		onOpenChange(false);
	}

	async function notifyChanged(): Promise<void> {
		await refreshContactCounts();
		await onChanged?.();
	}

	async function handleCreate(event: SubmitEvent): Promise<void> {
		event.preventDefault();
		const name = newName.trim();
		if (name.length === 0 || creating) return;
		creating = true;
		createError = null;
		try {
			await createContactLabel({ name });
			newName = '';
			await notifyChanged();
			pushToast($_('contacts.labelCreated'), { tone: 'success' });
			// Focus stays in the field for a run of additions; the toast is visual,
			// so the live region carries the result for a screen-reader user.
			announcePolite($_('contacts.labelCreated'));
			newNameInput?.focus();
		} catch (err) {
			createError = toErrorMessage(err);
		} finally {
			creating = false;
		}
	}

	function startRename(labelId: number, currentName: string): void {
		renamingId = labelId;
		renameValue = currentName;
		renameError = null;
		void tick().then(() => document.getElementById(`contact-label-rename-${labelId}`)?.focus());
	}

	function cancelRename(): void {
		const previous = renamingId;
		renamingId = null;
		renameError = null;
		// The field is about to leave the DOM — hand focus back to the control
		// that opened it instead of letting it drop to <body>.
		void tick().then(() =>
			document.getElementById(`contact-label-rename-action-${previous}`)?.focus()
		);
	}

	async function submitRename(event: SubmitEvent, labelId: number): Promise<void> {
		event.preventDefault();
		const name = renameValue.trim();
		if (name.length === 0 || renameBusy) return;
		renameBusy = true;
		renameError = null;
		try {
			await renameContactLabel(labelId, { name });
			renamingId = null;
			await notifyChanged();
			pushToast($_('contacts.labelRenamed'), { tone: 'success' });
			announcePolite($_('contacts.labelRenamed'));
			void tick().then(() =>
				document.getElementById(`contact-label-rename-action-${labelId}`)?.focus()
			);
		} catch (err) {
			renameError = toErrorMessage(err);
		} finally {
			renameBusy = false;
		}
	}

	/*
	 * The delete confirmation is inline rather than the app-wide confirmAction
	 * dialog: this component is itself a modal, and stacking a second modal on
	 * top means two aria-modal subtrees fighting over the same focus trap. In
	 * place, the prompt stays in the reading order right after the button that
	 * raised it and focus never leaves the dialog.
	 */
	function askDelete(labelId: number): void {
		pendingDeleteId = labelId;
	}

	function cancelDelete(): void {
		const previous = pendingDeleteId;
		pendingDeleteId = null;
		void tick().then(() =>
			document.getElementById(`contact-label-delete-action-${previous}`)?.focus()
		);
	}

	async function confirmDelete(labelId: number): Promise<void> {
		pendingDeleteId = null;
		deletingId = labelId;
		try {
			await deleteContactLabel(labelId);
			await notifyChanged();
			pushToast($_('contacts.labelDeleted'), { tone: 'success' });
			announcePolite($_('contacts.labelDeleted'));
			newNameInput?.focus();
		} catch (err) {
			pushToast(toErrorMessage(err), { tone: 'error' });
		} finally {
			deletingId = null;
		}
	}
</script>

<DialogShell {open} onOpenChange={(next) => (next ? onOpenChange(true) : close())} size="lg" scroll>
	<DialogTitle>
		{$_('contacts.labelsDialogTitle')}
	</DialogTitle>
	<DialogDescription>
		{$_('contacts.labelsDialogIntro')}
	</DialogDescription>

	<form onsubmit={handleCreate} class="mt-4 flex flex-wrap items-end gap-2">
		<div class="min-w-0 flex-1">
			<label for="contact-label-new" class="mb-1 block text-sm font-medium text-foreground">
				{$_('contacts.labelNameField')}
			</label>
			<Input
				id="contact-label-new"
				type="text"
				bind:value={newName}
				bind:ref={newNameInput}
				placeholder={$_('contacts.labelNamePlaceholder')}
				maxlength={60}
				disabled={creating}
				aria-invalid={createError ? 'true' : undefined}
				aria-describedby={createError ? 'contact-label-new-error' : undefined}
			/>
		</div>
		<Button type="submit" disabled={creating || newName.trim().length === 0}>
			{$_('contacts.addLabel')}
		</Button>
	</form>
	{#if createError}
		<StateMessage
			variant="error"
			padding="none"
			role="alert"
			class="mt-2"
			id="contact-label-new-error"
		>
			{createError}
		</StateMessage>
	{/if}

	{#if labels.length === 0}
		<p class="mt-4 text-sm text-muted-foreground">{$_('contacts.noLabelsYet')}</p>
	{:else}
		<ul class="mt-4 space-y-1.5">
			{#each labels as label (label.id)}
				<li class="rounded-md border border-border bg-background px-3 py-2">
					{#if renamingId === label.id}
						<form
							onsubmit={(event) => submitRename(event, label.id)}
							class="flex flex-wrap items-end gap-2"
						>
							<div class="min-w-0 flex-1">
								<label
									for={`contact-label-rename-${label.id}`}
									class="mb-1 block text-xs text-muted-foreground"
								>
									{$_('contacts.renameLabelField', { values: { label: label.name } })}
								</label>
								<Input
									id={`contact-label-rename-${label.id}`}
									type="text"
									bind:value={renameValue}
									maxlength={60}
									size="sm"
									disabled={renameBusy}
									aria-invalid={renameError ? 'true' : undefined}
									aria-describedby={renameError
										? `contact-label-rename-${label.id}-error`
										: undefined}
								/>
							</div>
							<Button
								type="submit"
								size="xs"
								disabled={renameBusy || renameValue.trim().length === 0}
							>
								{$_('contacts.save')}
							</Button>
							<Button
								type="button"
								variant="ghost"
								size="xs"
								onclick={cancelRename}
								disabled={renameBusy}
							>
								{$_('common.cancel')}
							</Button>
						</form>
						{#if renameError}
							<StateMessage
								variant="error"
								padding="none"
								role="alert"
								class="mt-2"
								id={`contact-label-rename-${label.id}-error`}
							>
								{renameError}
							</StateMessage>
						{/if}
					{:else}
						<div class="flex flex-wrap items-center gap-2">
							<span class="min-w-0 flex-1">
								<span class="block truncate text-sm font-medium text-foreground">
									{label.name}
								</span>
								<span class="block text-xs text-muted-foreground">
									{$_('contacts.labelCount', { values: { count: label.contacts } })}
								</span>
							</span>
							<Button
								id={`contact-label-rename-action-${label.id}`}
								type="button"
								variant="outline"
								size="xs"
								onclick={() => startRename(label.id, label.name)}
								disabled={deletingId != null}
								aria-label={$_('contacts.renameLabelAction', { values: { label: label.name } })}
							>
								{$_('contacts.edit')}
							</Button>
							<Button
								id={`contact-label-delete-action-${label.id}`}
								type="button"
								variant="destructive"
								size="xs"
								onclick={() => askDelete(label.id)}
								disabled={deletingId != null}
								aria-label={$_('contacts.deleteLabelAction', { values: { label: label.name } })}
							>
								{$_('contacts.delete')}
							</Button>
						</div>
						{#if pendingDeleteId === label.id}
							<!--
								role="alert" so the prompt reaches a screen reader the moment it
								appears — it replaces a modal confirmation, and a silent inline
								strip would be missed entirely in focus mode.
							-->
							<div
								class="mt-2 rounded-md border border-destructive/30 bg-destructive/10 p-2"
								role="alert"
							>
								<p class="text-xs text-destructive-foreground">
									{$_('contacts.deleteLabelConfirm', {
										values: { label: label.name, count: label.contacts }
									})}
								</p>
								<div class="mt-2 flex flex-wrap gap-2">
									<Button
										type="button"
										variant="destructive"
										size="xs"
										onclick={() => confirmDelete(label.id)}
										disabled={deletingId != null}
									>
										{$_('common.delete')}
									</Button>
									<Button
										type="button"
										variant="ghost"
										size="xs"
										onclick={cancelDelete}
										disabled={deletingId != null}
									>
										{$_('common.cancel')}
									</Button>
								</div>
							</div>
						{/if}
					{/if}
				</li>
			{/each}
		</ul>
	{/if}

	<div class="mt-5 flex justify-end">
		<Button variant="outline" onclick={close} disabled={creating || renameBusy}>
			{$_('common.close')}
		</Button>
	</div>
</DialogShell>
