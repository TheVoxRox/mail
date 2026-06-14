<script lang="ts">
	import { beforeNavigate, goto } from '$app/navigation';
	import { _ } from '$lib/i18n/index.js';
	import { toErrorMessage } from '$lib/api/errors.js';
	import { isValidEmailAddress } from '$lib/compose/addresses.js';
	import { confirmAction } from '$lib/stores/confirmDialog.js';
	import { Button } from '$lib/components/ui/button/index.js';
	import { Field } from '$lib/components/ui/field/index.js';
	import { Input } from '$lib/components/ui/input/index.js';
	import { Select } from '$lib/components/ui/select/index.js';
	import { Surface } from '$lib/components/ui/surface/index.js';
	import { Textarea } from '$lib/components/ui/textarea/index.js';
	import { onMount } from 'svelte';
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

	type EmailDraft = { email: string; label: EmailLabel | '' };

	let name = $state('');
	let surname = $state('');
	let note = $state('');
	let emails = $state<EmailDraft[]>([{ email: '', label: '' }]);
	let emailErrors = $state<(string | null)[]>([null]);
	let primaryIndex = $state(0);
	let busy = $state(false);
	let error = $state<string | null>(null);
	let nameInputEl = $state<HTMLInputElement | null>(null);

	let loadedKey: number | 'new' | null = null;
	let originalSnapshot = '';
	// Set once a save succeeds (or the user confirms discarding) so the parent's
	// post-save navigation is not intercepted by the unsaved-changes guard.
	let bypassLeaveGuard = false;

	const isEdit = $derived(contact != null);

	function snapshotOf(
		n: string,
		s: string,
		nt: string,
		rows: EmailDraft[],
		primary: number
	): string {
		return JSON.stringify({
			name: n,
			surname: s,
			note: nt,
			emails: rows.map((r) => ({ email: r.email, label: r.label })),
			primary
		});
	}

	const isDirty = $derived(
		snapshotOf(name, surname, note, emails, primaryIndex) !== originalSnapshot
	);

	// Re-seed the form when the bound contact changes (e.g. editing a different
	// row reuses this component instance via SvelteKit's param navigation).
	$effect(() => {
		const key = contact ? contact.id : 'new';
		if (loadedKey === key) return;
		loadedKey = key;
		if (contact) {
			name = contact.name ?? '';
			surname = contact.surname ?? '';
			note = contact.note ?? '';
			const rows = contact.emails.length > 0 ? contact.emails : [{ email: '', label: null }];
			emails = rows.map((e) => ({ email: e.email, label: e.label ?? '' }));
			emailErrors = emails.map(() => null);
			const idx = contact.emails.findIndex((e) => e.primary);
			primaryIndex = idx >= 0 ? idx : 0;
		} else {
			name = '';
			surname = '';
			note = '';
			emails = [{ email: '', label: '' }];
			emailErrors = [null];
			primaryIndex = 0;
		}
		originalSnapshot = snapshotOf(name, surname, note, emails, primaryIndex);
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

	function addEmailRow() {
		if (emails.length >= MAX_EMAILS) {
			error = $_('contacts.tooManyEmails');
			return;
		}
		emails = [...emails, { email: '', label: '' }];
		emailErrors = [...emailErrors, null];
	}

	function removeEmailRow(index: number) {
		emails = emails.filter((_, i) => i !== index);
		emailErrors = emailErrors.filter((_, i) => i !== index);
		if (index === primaryIndex) {
			primaryIndex = 0;
		} else if (index < primaryIndex) {
			primaryIndex -= 1;
		}
		if (emails.length === 0) {
			emails = [{ email: '', label: '' }];
			emailErrors = [null];
			primaryIndex = 0;
		}
		if (primaryIndex > emails.length - 1) primaryIndex = 0;
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
			.map((row, index) => ({
				email: row.email.trim(),
				label: row.label,
				primary: index === primaryIndex
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

	beforeNavigate((navigation) => {
		if (bypassLeaveGuard || busy || !isDirty) return;
		if (navigation.type === 'leave') {
			navigation.cancel();
			return;
		}
		const nextUrl = navigation.to?.url;
		if (!nextUrl) return;
		navigation.cancel();
		void confirmLeaveThenNavigate(nextUrl.href);
	});
</script>

<form
	onsubmit={handleSubmit}
	class="flex min-h-[32rem] max-w-4xl flex-col overflow-hidden rounded-lg border border-border bg-background"
	aria-label={isEdit ? $_('contacts.editFormLabel') : $_('contacts.formLabel')}
	novalidate
>
	<div class="flex flex-wrap items-center justify-between gap-3 border-b border-border p-4">
		<div class="min-w-0">
			<h1 class="text-[0.95rem] font-semibold text-foreground">
				{isEdit ? $_('contacts.editFormHeading') : $_('contacts.formHeading')}
			</h1>
			<p class="mt-0.5 text-xs text-muted-foreground">{$_('contacts.formHint')}</p>
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
			{#each emails as row, index (index)}
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
							<option value="WORK">{$_('contacts.labelOptions.WORK')}</option>
							<option value="HOME">{$_('contacts.labelOptions.HOME')}</option>
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
							checked={primaryIndex === index}
							onchange={() => (primaryIndex = index)}
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
