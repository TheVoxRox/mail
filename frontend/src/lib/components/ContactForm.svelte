<script lang="ts">
	import { _ } from '$lib/i18n/index.js';
	import { toErrorMessage } from '$lib/api/errors.js';
	import { Button } from '$lib/components/ui/button/index.js';
	import { Field } from '$lib/components/ui/field/index.js';
	import { Input } from '$lib/components/ui/input/index.js';
	import { Select } from '$lib/components/ui/select/index.js';
	import { Surface } from '$lib/components/ui/surface/index.js';
	import { Textarea } from '$lib/components/ui/textarea/index.js';
	import { onMount } from 'svelte';
	import type { ContactCreateRequest, ContactEmailRequest, EmailLabel } from '$lib/types.js';

	interface Props {
		onSubmit: (payload: ContactCreateRequest) => Promise<void> | void;
		onCancel?: () => void;
	}

	let { onSubmit, onCancel }: Props = $props();

	const MAX_EMAILS = 10;

	type EmailDraft = { email: string; label: EmailLabel | '' };

	let name = $state('');
	let surname = $state('');
	let note = $state('');
	let emails = $state<EmailDraft[]>([{ email: '', label: '' }]);
	let emailErrors = $state<(string | null)[]>([null]);
	let busy = $state(false);
	let error = $state<string | null>(null);
	let nameInputEl = $state<HTMLInputElement | null>(null);

	onMount(() => {
		nameInputEl?.focus();
	});

	function emailErrorId(index: number): string {
		return `contact-email-${index}-error`;
	}

	function emailLabelStatusId(index: number): string {
		return `contact-email-${index}-label-status`;
	}

	function emailLabelAnnouncement(index: number, label: EmailLabel | ''): string {
		const labelText =
			label === '' ? $_('contacts.labelOptions.none') : $_(`contacts.labelOptions.${label}`);
		return $_('contacts.emailLabelSelection', { values: { index: index + 1, label: labelText } });
	}

	function clearEmailError(index: number) {
		if (!emailErrors[index]) return;
		emailErrors = emailErrors.map((value, i) => (i === index ? null : value));
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
		if (emails.length === 0) {
			emails = [{ email: '', label: '' }];
			emailErrors = [null];
		}
	}

	function validate(): boolean {
		let ok = true;
		const nextErrors: (string | null)[] = emails.map(() => null);
		const nonEmpty = emails.filter((e) => e.email.trim().length > 0);
		if (nonEmpty.length === 0) {
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

		const payloadEmails: ContactEmailRequest[] = emails
			.map((e) => ({ email: e.email.trim(), label: e.label === '' ? null : e.label }))
			.filter((e) => e.email.length > 0);

		busy = true;
		try {
			await onSubmit({
				name: name || null,
				surname: surname || null,
				note: note || null,
				emails: payloadEmails
			});
			name = '';
			surname = '';
			note = '';
			emails = [{ email: '', label: '' }];
			emailErrors = [null];
		} catch (err) {
			error = toErrorMessage(err);
		} finally {
			busy = false;
		}
	}
</script>

<form
	onsubmit={handleSubmit}
	class="flex min-h-[32rem] max-w-4xl flex-col overflow-hidden rounded-lg border border-border bg-background"
	aria-label={$_('contacts.formLabel')}
	novalidate
>
	<div class="flex flex-wrap items-center justify-between gap-3 border-b border-border p-4">
		<div class="min-w-0">
			<h1 class="text-[0.95rem] font-semibold text-foreground">{$_('contacts.formHeading')}</h1>
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
				<div class="grid gap-2 md:grid-cols-[minmax(0,1fr)_9rem_auto_auto] md:items-start">
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
