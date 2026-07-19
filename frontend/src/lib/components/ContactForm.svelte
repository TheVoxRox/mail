<script lang="ts">
	import { goto } from '$app/navigation';
	import { _ } from '$lib/i18n/index.js';
	import { toErrorMessage } from '$lib/api/errors.js';
	import { isValidEmailAddress } from '$lib/compose/addresses.js';
	import { confirmAction } from '$lib/stores/confirmDialog.js';
	import { installLeaveGuard } from '$lib/leaveGuard.js';
	import { Button } from '$lib/components/ui/button/index.js';
	import { Field } from '$lib/components/ui/field/index.js';
	import { Input } from '$lib/components/ui/input/index.js';
	import { Select } from '$lib/components/ui/select/index.js';
	import { Surface } from '$lib/components/ui/surface/index.js';
	import { Textarea } from '$lib/components/ui/textarea/index.js';
	import { onMount, tick } from 'svelte';
	import type {
		ContactCreateRequest,
		ContactEmailRequest,
		ContactResponse,
		EmailLabel
	} from '$lib/types.js';

	interface Props {
		/** When provided the form edits an existing contact; otherwise it creates a new one. */
		contact?: ContactResponse | null;
		onSubmit: (payload: ContactCreateRequest) => Promise<void> | void;
		onCancel?: () => void;
	}

	let { contact = null, onSubmit, onCancel }: Props = $props();

	const MAX_EMAILS = 10;

	// `primary` lives on the row (single source of truth) and `id` is a stable
	// key for the {#each}, so add/remove are plain array ops with no parallel
	// index to keep in sync.
	type EmailDraft = { id: number; email: string; label: EmailLabel | ''; primary: boolean };

	let rowIdSeq = 0;
	function newRow(email = '', label: EmailLabel | '' = '', primary = false): EmailDraft {
		return { id: rowIdSeq++, email, label, primary };
	}

	let name = $state('');
	let surname = $state('');
	let note = $state('');
	let emails = $state<EmailDraft[]>([newRow('', '', true)]);
	let emailErrors = $state<(string | null)[]>([null]);
	let busy = $state(false);
	let error = $state<string | null>(null);
	let nameInputEl = $state<HTMLInputElement | null>(null);

	let loadedKey: number | 'new' | null = null;
	// $state so the isDirty derived recomputes when the baseline is re-seeded,
	// not only as a side effect of the form fields changing in the same tick.
	let originalSnapshot = $state('');
	// Set once a save succeeds (or the user confirms discarding) so the parent's
	// post-save navigation is not intercepted by the unsaved-changes guard.
	let bypassLeaveGuard = false;

	const isEdit = $derived(contact != null);

	function snapshotOf(n: string, s: string, nt: string, rows: EmailDraft[]): string {
		return JSON.stringify({
			name: n,
			surname: s,
			note: nt,
			emails: rows.map((r) => ({ email: r.email, label: r.label, primary: r.primary }))
		});
	}

	const isDirty = $derived(snapshotOf(name, surname, note, emails) !== originalSnapshot);

	// Re-seed the form when the bound contact changes (e.g. editing a different
	// row reuses this component instance via SvelteKit's param navigation).
	$effect(() => {
		const key = contact ? contact.id : 'new';
		if (loadedKey === key) return;
		loadedKey = key;
		if (contact && contact.emails.length > 0) {
			name = contact.name ?? '';
			surname = contact.surname ?? '';
			note = contact.note ?? '';
			const primaryIdx = contact.emails.findIndex((e) => e.primary);
			const chosen = primaryIdx >= 0 ? primaryIdx : 0;
			emails = contact.emails.map((e, i) => newRow(e.email, e.label ?? '', i === chosen));
			emailErrors = emails.map(() => null);
		} else if (contact) {
			name = contact.name ?? '';
			surname = contact.surname ?? '';
			note = contact.note ?? '';
			emails = [newRow('', '', true)];
			emailErrors = [null];
		} else {
			name = '';
			surname = '';
			note = '';
			emails = [newRow('', '', true)];
			emailErrors = [null];
		}
		originalSnapshot = snapshotOf(name, surname, note, emails);
	});

	onMount(() => {
		nameInputEl?.focus();
	});

	function emailErrorId(index: number): string {
		return `contact-email-${index}-error`;
	}

	function clearEmailError(index: number) {
		if (!emailErrors[index]) return;
		emailErrors = emailErrors.map((value, i) => (i === index ? null : value));
	}

	function primaryRadioLabel(index: number): string {
		return $_('contacts.primaryRadioLabel', { values: { index: index + 1 } });
	}

	function emailLabelStatusId(index: number): string {
		return `contact-email-${index}-label-status`;
	}

	function emailLabelAnnouncement(index: number, label: EmailLabel | ''): string {
		const labelText =
			label === '' ? $_('contacts.labelOptions.none') : $_(`contacts.labelOptions.${label}`);
		return $_('contacts.emailLabelSelection', { values: { index: index + 1, label: labelText } });
	}

	function setPrimary(index: number) {
		for (let i = 0; i < emails.length; i++) {
			emails[i].primary = i === index;
		}
	}

	// Adding a row moves the Add button to the new last row and removing a row
	// destroys its Remove button — either way the focused element disappears
	// and focus would silently drop to <body>. Land it on an e-mail input
	// instead (also announces the field, telling a screen-reader user where
	// they ended up).
	function focusEmailInput(index: number) {
		void tick().then(() => document.getElementById(`contact-email-${index}`)?.focus());
	}

	function addEmailRow() {
		if (emails.length >= MAX_EMAILS) {
			error = $_('contacts.tooManyEmails');
			return;
		}
		emails = [...emails, newRow()];
		emailErrors = [...emailErrors, null];
		focusEmailInput(emails.length - 1);
	}

	function removeEmailRow(index: number) {
		const wasPrimary = emails[index]?.primary ?? false;
		emails = emails.filter((_, i) => i !== index);
		emailErrors = emailErrors.filter((_, i) => i !== index);
		if (emails.length === 0) {
			emails = [newRow('', '', true)];
			emailErrors = [null];
		} else if (wasPrimary) {
			// The primary row is gone — promote the first remaining address.
			emails[0].primary = true;
		}
		focusEmailInput(Math.min(index, emails.length - 1));
	}

	function validate(): boolean {
		let ok = true;
		const nextErrors: (string | null)[] = emails.map(() => null);
		let hasFilled = false;
		emails.forEach((row, index) => {
			const value = row.email.trim();
			if (value.length === 0) return;
			hasFilled = true;
			if (!isValidEmailAddress(value)) {
				nextErrors[index] = $_('contacts.emailInvalid');
				ok = false;
			}
		});
		if (!hasFilled) {
			nextErrors[0] = $_('contacts.emailRequired');
			ok = false;
		}
		emailErrors = nextErrors;
		return ok;
	}

	async function handleSubmit(event: SubmitEvent) {
		event.preventDefault();
		error = null;
		if (!validate()) {
			const firstErrorIdx = emailErrors.findIndex((e) => e);
			if (firstErrorIdx >= 0) {
				document.getElementById(`contact-email-${firstErrorIdx}`)?.focus();
			}
			return;
		}

		// The first address in the request becomes primary (backend semantics), so
		// reorder the chosen primary to the front. If the primary row was left empty
		// it drops out — fall back to the first remaining address.
		const entries = emails
			.map((row) => ({
				email: row.email.trim(),
				label: row.label,
				primary: row.primary
			}))
			.filter((e) => e.email.length > 0);
		if (!entries.some((e) => e.primary)) entries[0].primary = true;
		const primary = entries.find((e) => e.primary)!;
		const ordered = [primary, ...entries.filter((e) => e !== primary)];
		const payloadEmails: ContactEmailRequest[] = ordered.map((e) => ({
			email: e.email,
			label: e.label === '' ? null : e.label
		}));

		busy = true;
		error = null;
		bypassLeaveGuard = true;
		try {
			await onSubmit({
				name: name || null,
				surname: surname || null,
				note: note || null,
				emails: payloadEmails
			});
		} catch (err) {
			error = toErrorMessage(err);
			bypassLeaveGuard = false;
		} finally {
			busy = false;
		}
	}

	async function confirmLeaveThenNavigate(href: string): Promise<void> {
		const proceed = await confirmAction({
			title: $_('contacts.discardEditTitle'),
			description: $_('contacts.discardEditDescription'),
			confirmLabel: $_('contacts.discardEditConfirm'),
			cancelLabel: $_('common.cancel'),
			tone: 'destructive'
		});
		if (!proceed) return;
		bypassLeaveGuard = true;
		try {
			await goto(href);
		} finally {
			bypassLeaveGuard = false;
		}
	}

	installLeaveGuard({
		shouldGuard: () => !bypassLeaveGuard && !busy && isDirty,
		onBlocked: (target) => void confirmLeaveThenNavigate(target.href)
	});
</script>

<form
	onsubmit={handleSubmit}
	class="flex min-h-[32rem] max-w-4xl flex-col overflow-hidden rounded-lg border border-border bg-background"
	aria-label={isEdit ? $_('contacts.editFormLabel') : $_('contacts.formLabel')}
	aria-describedby="contact-form-hint"
	novalidate
>
	<div class="flex flex-wrap items-center justify-between gap-3 border-b border-border p-4">
		<div class="min-w-0">
			<h1 class="text-title font-semibold text-foreground">
				{isEdit ? $_('contacts.editFormHeading') : $_('contacts.formHeading')}
			</h1>
			<!-- Linked via aria-describedby on the form — an unreferenced hint is
			     skipped entirely when tabbing through the form in focus mode. -->
			<p id="contact-form-hint" class="mt-0.5 text-xs text-muted-foreground">
				{$_('contacts.formHint')}
			</p>
		</div>
	</div>

	<div class="flex flex-1 flex-col gap-4 p-4">
		<div class="grid gap-3 md:grid-cols-2">
			<Field for="contact-name" label={$_('contacts.firstName')} class="min-w-0">
				<Input
					id="contact-name"
					type="text"
					bind:value={name}
					bind:ref={nameInputEl}
					placeholder={$_('contacts.firstName')}
					maxlength={255}
					disabled={busy}
				/>
			</Field>
			<Field for="contact-surname" label={$_('contacts.lastName')} class="min-w-0">
				<Input
					id="contact-surname"
					type="text"
					bind:value={surname}
					placeholder={$_('contacts.lastName')}
					maxlength={255}
					disabled={busy}
				/>
			</Field>
		</div>

		<fieldset class="space-y-3">
			<legend class="block text-sm font-medium text-foreground">{$_('contacts.emails')}</legend>
			{#each emails as row, index (row.id)}
				<div
					class="grid gap-2 md:grid-cols-[minmax(0,1fr)_9rem_auto_auto_auto] md:items-start"
					data-email-row={index}
				>
					<Field
						for={`contact-email-${index}`}
						label={$_('contacts.emailFieldLabel', { values: { index: index + 1 } })}
						error={emailErrors[index]}
						errorId={emailErrorId(index)}
					>
						<Input
							id={`contact-email-${index}`}
							type="email"
							bind:value={row.email}
							oninput={() => clearEmailError(index)}
							placeholder={$_('contacts.emailPlaceholder')}
							maxlength={255}
							required={index === 0}
							disabled={busy}
							aria-invalid={emailErrors[index] ? 'true' : undefined}
							aria-describedby={emailErrors[index] ? emailErrorId(index) : undefined}
						/>
					</Field>
					<Field
						for={`contact-email-${index}-label`}
						label={$_('contacts.emailLabelFieldIndexed', { values: { index: index + 1 } })}
						labelClass="sr-only"
					>
						<Select
							id={`contact-email-${index}-label`}
							bind:value={row.label}
							size="sm"
							disabled={busy}
						>
							<option value="">{$_('contacts.labelOptions.none')}</option>
							<option value="HOME">{$_('contacts.labelOptions.HOME')}</option>
							<option value="WORK">{$_('contacts.labelOptions.WORK')}</option>
							<option value="OTHER">{$_('contacts.labelOptions.OTHER')}</option>
						</Select>
						<span id={emailLabelStatusId(index)} class="sr-only" role="status" aria-atomic="true">
							{emailLabelAnnouncement(index, row.label)}
						</span>
					</Field>
					<div class="flex items-center gap-1.5 md:h-9">
						<input
							id={`contact-email-${index}-primary`}
							type="radio"
							name="contact-primary-email"
							class="size-4 border-input bg-background text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/40"
							checked={row.primary}
							onchange={() => setPrimary(index)}
							disabled={busy}
							aria-label={primaryRadioLabel(index)}
						/>
						<label
							for={`contact-email-${index}-primary`}
							class="select-none text-xs text-muted-foreground"
						>
							{$_('contacts.emailPrimary')}
						</label>
					</div>
					{#if index === emails.length - 1}
						<Button
							type="button"
							onclick={addEmailRow}
							variant="outline"
							size="xs"
							disabled={busy || emails.length >= MAX_EMAILS}
						>
							{$_('contacts.addEmail')}
						</Button>
					{/if}
					{#if emails.length > 1}
						<Button
							type="button"
							onclick={() => removeEmailRow(index)}
							variant="outline"
							size="xs"
							disabled={busy}
							aria-label={$_('contacts.removeEmail')}
						>
							{$_('contacts.removeEmail')}
						</Button>
					{/if}
				</div>
			{/each}
		</fieldset>

		<Field for="contact-note" label={$_('contacts.noteLabel')} class="flex flex-1 flex-col">
			<Textarea
				id="contact-note"
				bind:value={note}
				placeholder={$_('contacts.notePlaceholder')}
				rows={8}
				maxlength={1000}
				disabled={busy}
				class="min-h-32 flex-1 resize-none"
			/>
		</Field>

		{#if error}
			<Surface variant="danger" padding="sm" role="alert">
				<p class="text-sm">{error}</p>
			</Surface>
		{/if}

		<div class="flex flex-wrap justify-end gap-2 border-t border-border pt-4">
			{#if onCancel}
				<Button type="button" onclick={onCancel} variant="ghost" size="sm" disabled={busy}>
					{$_('contacts.cancel')}
				</Button>
			{/if}
			<Button type="submit" disabled={busy} size="sm">
				{busy ? $_('contacts.saving') : $_('contacts.save')}
			</Button>
		</div>
	</div>
</form>
