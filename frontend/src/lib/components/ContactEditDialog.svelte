<script lang="ts">
	import { Dialog } from 'bits-ui';
	import {
		addContactEmail,
		deleteContactEmail,
		patchContact,
		setPrimaryEmail
	} from '$lib/api/contacts.js';
	import { isValidEmailAddress } from '$lib/compose/addresses.js';
	import { toErrorMessage } from '$lib/api/errors.js';
	import { Button } from '$lib/components/ui/button/index.js';
	import { Field } from '$lib/components/ui/field/index.js';
	import { Input } from '$lib/components/ui/input/index.js';
	import { Select } from '$lib/components/ui/select/index.js';
	import { StateMessage } from '$lib/components/ui/state-message/index.js';
	import { Textarea } from '$lib/components/ui/textarea/index.js';
	import { _ } from '$lib/i18n/index.js';
	import { confirmAction } from '$lib/stores/confirmDialog.js';
	import { pushToast } from '$lib/stores/toasts.js';
	import type { ContactEmailResponse, ContactResponse, EmailLabel } from '$lib/types.js';
	import Icon from '$lib/components/Icon.svelte';

	interface Props {
		open: boolean;
		accountId: number;
		contact: ContactResponse | null;
		onOpenChange: (open: boolean) => void;
		onChanged: () => void | Promise<void>;
	}

	let { open, accountId, contact, onOpenChange, onChanged }: Props = $props();

	const MAX_EMAILS = 10;

	let loadedContactId = $state<number | null>(null);
	let editName = $state('');
	let editSurname = $state('');
	let editNote = $state('');
	let editOriginal = $state<{ name: string; surname: string; note: string } | null>(null);
	let editBusy = $state(false);
	let editError = $state<string | null>(null);
	let newEmail = $state('');
	let newEmailLabel = $state<EmailLabel | ''>('');
	let newEmailError = $state<string | null>(null);
	let emailActionBusy = $state(false);

	const editIsDirty = $derived.by(() => {
		if (!editOriginal) return false;
		return (
			editName !== editOriginal.name ||
			editSurname !== editOriginal.surname ||
			editNote !== editOriginal.note ||
			newEmail.trim() !== ''
		);
	});

	$effect(() => {
		if (!open) {
			loadedContactId = null;
			return;
		}
		if (!contact) return;
		if (loadedContactId === contact.id) return;
		loadedContactId = contact.id;
		editName = contact.name ?? '';
		editSurname = contact.surname ?? '';
		editNote = contact.note ?? '';
		editOriginal = { name: editName, surname: editSurname, note: editNote };
		editError = null;
		newEmail = '';
		newEmailLabel = '';
		newEmailError = null;
		editBusy = false;
		emailActionBusy = false;
	});

	function contactLabel(c: ContactResponse): string {
		const fullName = [c.name, c.surname].filter(Boolean).join(' ');
		return fullName || c.emails[0]?.email || $_('contacts.noName');
	}

	function emailAccessibleText(email: ContactEmailResponse): string {
		const parts = [email.email];
		if (email.label) parts.push($_(`contacts.labelOptions.${email.label}`));
		if (email.primary) parts.push($_('contacts.emailPrimary'));
		return parts.join(' ');
	}

	async function requestClose(): Promise<void> {
		if (editBusy || emailActionBusy) return;
		if (editIsDirty) {
			const proceed = await confirmAction({
				title: $_('contacts.discardEditTitle'),
				description: $_('contacts.discardEditDescription'),
				confirmLabel: $_('contacts.discardEditConfirm'),
				cancelLabel: $_('common.cancel'),
				tone: 'destructive'
			});
			if (!proceed) return;
		}
		onOpenChange(false);
	}

	async function saveEdit(): Promise<void> {
		if (!contact) return;
		editBusy = true;
		editError = null;
		try {
			await patchContact(accountId, contact.id, {
				name: editName || null,
				surname: editSurname || null,
				note: editNote || null
			});
			onOpenChange(false);
			await onChanged();
		} catch (err) {
			editError = toErrorMessage(err);
		} finally {
			editBusy = false;
		}
	}

	async function handleAddEmail(): Promise<void> {
		if (!contact) return;
		newEmailError = null;
		const trimmed = newEmail.trim();
		if (!trimmed) {
			newEmailError = $_('contacts.emailRequired');
			return;
		}
		if (!isValidEmailAddress(trimmed)) {
			newEmailError = $_('contacts.emailInvalid');
			return;
		}
		if (contact.emails.length >= MAX_EMAILS) {
			newEmailError = $_('contacts.tooManyEmails');
			return;
		}
		emailActionBusy = true;
		try {
			await addContactEmail(accountId, contact.id, {
				email: trimmed,
				label: newEmailLabel === '' ? null : newEmailLabel
			});
			newEmail = '';
			newEmailLabel = '';
			pushToast($_('contacts.emailAdded'), { tone: 'success' });
			await onChanged();
		} catch (err) {
			newEmailError = toErrorMessage(err);
		} finally {
			emailActionBusy = false;
		}
	}

	async function handleRemoveEmail(emailId: number): Promise<void> {
		if (!contact) return;
		emailActionBusy = true;
		try {
			await deleteContactEmail(accountId, contact.id, emailId);
			pushToast($_('contacts.emailRemoved'), { tone: 'success' });
			await onChanged();
		} catch (err) {
			pushToast(toErrorMessage(err), { tone: 'error' });
		} finally {
			emailActionBusy = false;
		}
	}

	async function handleSetPrimary(emailId: number): Promise<void> {
		if (!contact) return;
		emailActionBusy = true;
		try {
			await setPrimaryEmail(accountId, contact.id, emailId);
			pushToast($_('contacts.emailPrimarySet'), { tone: 'success' });
			await onChanged();
		} catch (err) {
			pushToast(toErrorMessage(err), { tone: 'error' });
		} finally {
			emailActionBusy = false;
		}
	}
</script>

<Dialog.Root {open} onOpenChange={(next) => (next ? onOpenChange(true) : void requestClose())}>
	<Dialog.Portal>
		<Dialog.Overlay class="fixed inset-0 z-50 bg-black/40 backdrop-blur-[2px]" />
		<Dialog.Content
			class="fixed left-1/2 top-1/2 z-50 flex max-h-[90vh] w-[calc(100vw-2rem)] max-w-3xl -translate-x-1/2 -translate-y-1/2 flex-col overflow-hidden rounded-lg border border-border bg-popover text-popover-foreground shadow-2xl"
			aria-describedby="contact-edit-dialog-description"
		>
			{#if contact}
				<div class="flex flex-wrap items-start justify-between gap-3 border-b border-border p-4">
					<div class="min-w-0">
						<Dialog.Title class="text-base font-semibold">
							{$_('contacts.editDialogTitle', { values: { label: contactLabel(contact) } })}
						</Dialog.Title>
						<Dialog.Description id="contact-edit-dialog-description" class="sr-only">
							{$_('contacts.editDialogDescription')}
						</Dialog.Description>
					</div>
				</div>

				<div class="flex-1 space-y-4 overflow-y-auto p-4">
					<div class="grid gap-3 sm:grid-cols-2">
						<Field label={$_('contacts.firstName')}>
							<Input
								type="text"
								bind:value={editName}
								placeholder={$_('contacts.firstName')}
								maxlength={255}
								disabled={editBusy}
							/>
						</Field>
						<Field label={$_('contacts.lastName')}>
							<Input
								type="text"
								bind:value={editSurname}
								placeholder={$_('contacts.lastName')}
								maxlength={255}
								disabled={editBusy}
							/>
						</Field>
					</div>

					<Field label={$_('contacts.noteLabel')}>
						<Textarea bind:value={editNote} rows={4} maxlength={1000} disabled={editBusy} />
					</Field>

					<fieldset class="space-y-2">
						<legend class="block text-sm font-medium text-foreground"
							>{$_('contacts.emails')}</legend
						>
						<ul class="m-0 list-none space-y-1.5 p-0">
							{#each contact.emails as email (email.id)}
								<li
									class="flex flex-wrap items-center gap-2 rounded-md border border-border bg-background px-3 py-2"
								>
									<span class="sr-only">{emailAccessibleText(email)}</span>
									<span class="flex min-w-0 flex-1 items-center gap-1.5" aria-hidden="true">
										<Icon name="envelope" size={13} class="shrink-0 text-muted-foreground/80" />
										<span class="min-w-0 truncate pr-1 text-[0.82rem]">{email.email}</span>
										{#if email.label}
											<span
												class="shrink-0 rounded-full bg-muted px-1.5 py-0.5 text-[0.66rem] font-medium text-muted-foreground"
											>
												{$_(`contacts.labelOptions.${email.label}`)}
											</span>
										{/if}
										{#if email.primary}
											<span
												class="shrink-0 rounded-full bg-primary/10 px-1.5 py-0.5 text-[0.66rem] font-medium text-primary"
											>
												{$_('contacts.emailPrimary')}
											</span>
										{/if}
									</span>
									{#if !email.primary}
										<Button
											type="button"
											onclick={() => handleSetPrimary(email.id)}
											disabled={emailActionBusy}
											variant="outline"
											size="xs"
										>
											{$_('contacts.setPrimary')}
										</Button>
									{/if}
									{#if contact.emails.length > 1}
										<Button
											type="button"
											onclick={() => handleRemoveEmail(email.id)}
											disabled={emailActionBusy}
											variant="outline"
											size="xs"
											aria-label={$_('contacts.removeEmail')}
										>
											{$_('contacts.removeEmail')}
										</Button>
									{/if}
								</li>
							{/each}
						</ul>

						{#if contact.emails.length < MAX_EMAILS}
							<div class="grid gap-2 sm:grid-cols-[1fr_auto_auto] sm:items-start">
								<Field
									for={`contact-${contact.id}-new-email`}
									label={$_('contacts.addEmail')}
									error={newEmailError}
									errorId={`contact-${contact.id}-new-email-error`}
									labelClass="sr-only"
								>
									<Input
										id={`contact-${contact.id}-new-email`}
										type="email"
										bind:value={newEmail}
										placeholder={$_('contacts.emailPlaceholder')}
										maxlength={255}
										disabled={emailActionBusy}
										aria-invalid={newEmailError ? 'true' : undefined}
										aria-describedby={newEmailError
											? `contact-${contact.id}-new-email-error`
											: undefined}
									/>
								</Field>
								<Field
									for={`contact-${contact.id}-new-email-label`}
									label={$_('contacts.emailLabelField')}
									labelClass="sr-only"
								>
									<Select
										id={`contact-${contact.id}-new-email-label`}
										bind:value={newEmailLabel}
										size="sm"
										disabled={emailActionBusy}
									>
										<option value="">{$_('contacts.labelOptions.none')}</option>
										<option value="WORK">{$_('contacts.labelOptions.WORK')}</option>
										<option value="HOME">{$_('contacts.labelOptions.HOME')}</option>
										<option value="OTHER">{$_('contacts.labelOptions.OTHER')}</option>
									</Select>
								</Field>
								<Button
									type="button"
									onclick={handleAddEmail}
									disabled={emailActionBusy}
									variant="outline"
									size="xs"
								>
									{$_('contacts.addEmail')}
								</Button>
							</div>
						{/if}
					</fieldset>

					{#if editError}
						<StateMessage variant="error" padding="none" role="alert">
							{editError}
						</StateMessage>
					{/if}
				</div>

				<div class="flex flex-wrap justify-end gap-2 border-t border-border p-4">
					<Button type="button" onclick={requestClose} variant="outline" disabled={editBusy}>
						{$_('contacts.cancel')}
					</Button>
					<Button type="button" disabled={editBusy} onclick={saveEdit}>
						{editBusy ? $_('contacts.saving') : $_('contacts.save')}
					</Button>
				</div>
			{/if}
		</Dialog.Content>
	</Dialog.Portal>
</Dialog.Root>
