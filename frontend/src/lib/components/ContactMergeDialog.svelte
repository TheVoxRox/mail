<script lang="ts">
	import { Dialog } from 'bits-ui';
	import { mergeContacts } from '$lib/api/contacts.js';
	import { toErrorMessage } from '$lib/api/errors.js';
	import { _ } from '$lib/i18n/index.js';
	import { pushToast } from '$lib/stores/toasts.js';
	import { Button } from '$lib/components/ui/button/index.js';
	import { StateMessage } from '$lib/components/ui/state-message/index.js';
	import {
		MAX_EMAILS_PER_CONTACT,
		buildMergePreview,
		exceedsEmailLimit
	} from '$lib/contacts/mergePreview.js';
	import type { ContactResponse } from '$lib/types.js';

	interface Props {
		open: boolean;
		accountId: number;
		contacts: ContactResponse[];
		onOpenChange: (open: boolean) => void;
		/** Receives the surviving contact, so the caller can put focus on its row. */
		onMerged: (targetId: number) => void | Promise<void>;
	}

	let { open, accountId, contacts, onOpenChange, onMerged }: Props = $props();

	let targetId = $state<number | null>(null);
	let busy = $state(false);
	let serverError = $state<string | null>(null);

	$effect(() => {
		if (open) {
			targetId = contacts[0]?.id ?? null;
			serverError = null;
			busy = false;
		}
	});

	const target = $derived(contacts.find((c) => c.id === targetId) ?? null);
	const sources = $derived(contacts.filter((c) => c.id !== targetId));

	const mergedEmails = $derived(buildMergePreview(target, sources));

	const exceedsLimit = $derived(exceedsEmailLimit(mergedEmails.length));
	const hasEnoughContacts = $derived(contacts.length >= 2 && target !== null);

	function contactLabel(c: ContactResponse): string {
		const name = [c.name, c.surname].filter(Boolean).join(' ').trim();
		if (name) return name;
		return c.emails[0]?.email ?? $_('contacts.noName');
	}

	function close(): void {
		if (busy) return;
		onOpenChange(false);
	}

	async function submit(): Promise<void> {
		if (!target || sources.length === 0 || exceedsLimit) return;
		busy = true;
		serverError = null;
		try {
			await mergeContacts(accountId, target.id, {
				source: sources.map((c) => c.id)
			});
			pushToast($_('contacts.mergeDone'), { tone: 'success' });
			onOpenChange(false);
			await onMerged(target.id);
		} catch (err) {
			serverError = toErrorMessage(err);
		} finally {
			busy = false;
		}
	}
</script>

<Dialog.Root {open} onOpenChange={(next) => (next ? onOpenChange(true) : close())}>
	<Dialog.Portal>
		<Dialog.Overlay class="fixed inset-0 z-50 bg-black/40 backdrop-blur-[2px]" />
		<Dialog.Content
			class="fixed left-1/2 top-1/2 z-50 max-h-[90vh] w-[calc(100vw-2rem)] max-w-xl -translate-x-1/2 -translate-y-1/2 overflow-y-auto rounded-2xl border border-border bg-popover p-5 text-popover-foreground shadow-2xl"
			aria-describedby="contact-merge-dialog-intro"
		>
			<Dialog.Title class="text-base font-semibold">{$_('contacts.mergeDialogTitle')}</Dialog.Title>
			<Dialog.Description
				id="contact-merge-dialog-intro"
				class="mt-2 text-sm text-muted-foreground"
			>
				{$_('contacts.mergeDialogIntro')}
			</Dialog.Description>

			{#if !hasEnoughContacts}
				<StateMessage variant="error" padding="none" role="alert" class="mt-4">
					{$_('contacts.mergeNeedsTwo')}
				</StateMessage>
			{:else}
				<fieldset class="mt-4 space-y-2">
					<legend class="text-sm font-medium text-foreground"
						>{$_('contacts.mergeTargetLegend')}</legend
					>
					<ul class="space-y-1.5">
						{#each contacts as c (c.id)}
							{@const inputId = `contact-merge-target-${c.id}`}
							<li>
								<label
									for={inputId}
									class="flex cursor-pointer items-start gap-2 rounded-md border border-border bg-background px-3 py-2 text-sm hover:bg-muted/35 has-[:checked]:border-primary has-[:checked]:bg-primary/5"
								>
									<input
										id={inputId}
										type="radio"
										name="contact-merge-target"
										class="mt-0.5 size-4"
										value={c.id}
										checked={targetId === c.id}
										onchange={() => (targetId = c.id)}
										disabled={busy}
									/>
									<span class="min-w-0 flex-1">
										<span class="block font-medium text-foreground">{contactLabel(c)}</span>
										<span class="mt-0.5 block text-xs text-muted-foreground">
											{$_('contacts.mergeContactEmailCount', {
												values: { count: c.emails.length }
											})}{#if c.emails[0]}, {c.emails[0].email}{/if}
										</span>
									</span>
								</label>
							</li>
						{/each}
					</ul>
				</fieldset>

				<section
					class="mt-4 rounded-md border border-border bg-muted/15 p-3 text-sm"
					aria-live="polite"
					aria-atomic="true"
				>
					<h3 class="text-sm font-semibold text-foreground">
						{$_('contacts.mergePreviewHeading', {
							values: { count: mergedEmails.length }
						})}
					</h3>
					{#if target}
						<p class="mt-1 text-xs text-muted-foreground">
							{$_('contacts.mergePreviewSubject', {
								values: { label: contactLabel(target) }
							})}
						</p>
						<ul class="mt-2 space-y-1 text-xs">
							{#each mergedEmails as entry (entry.email)}
								<li class="flex flex-wrap items-center gap-1.5">
									<span class="truncate">{entry.email}</span>
									{#if entry.primary}
										<span
											class="shrink-0 rounded-full bg-primary/10 px-1.5 py-0.5 text-caption font-medium text-primary"
										>
											{$_('contacts.emailPrimary')}
										</span>
									{/if}
									{#if !entry.fromTarget}
										<span
											class="shrink-0 rounded-full bg-muted px-1.5 py-0.5 text-caption font-medium text-muted-foreground"
										>
											{$_('contacts.mergeFromSource')}
										</span>
									{/if}
								</li>
							{/each}
						</ul>
					{/if}
				</section>

				{#if exceedsLimit}
					<StateMessage
						variant="error"
						padding="none"
						role="alert"
						class="mt-3"
						id="contact-merge-limit-warning"
					>
						{$_('contacts.mergeLimitWarning', {
							values: { count: mergedEmails.length, max: MAX_EMAILS_PER_CONTACT }
						})}
					</StateMessage>
				{/if}

				{#if serverError}
					<StateMessage variant="error" padding="none" role="alert" class="mt-3">
						{serverError}
					</StateMessage>
				{/if}
			{/if}

			<div class="mt-5 flex flex-wrap justify-end gap-2">
				<Button variant="outline" onclick={close} disabled={busy}>
					{$_('common.cancel')}
				</Button>
				<Button
					onclick={submit}
					disabled={!hasEnoughContacts || exceedsLimit || busy}
					aria-describedby={exceedsLimit ? 'contact-merge-limit-warning' : undefined}
				>
					{busy ? $_('contacts.mergeBusy') : $_('contacts.mergeSubmit')}
				</Button>
			</div>
		</Dialog.Content>
	</Dialog.Portal>
</Dialog.Root>
