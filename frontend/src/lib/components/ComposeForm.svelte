<script lang="ts">
	import { goto } from '$app/navigation';
	import { resolve } from '$app/paths';
	import { page } from '$app/stores';
	import { get } from 'svelte/store';
	import { accountsState, activeAccountId } from '$lib/stores/accounts.js';
	import { sendMail } from '$lib/api/mailWrite.js';
	import { sendDraft } from '$lib/api/drafts.js';
	import { deleteMessage } from '$lib/api/mailAction.js';
	import { toErrorMessage } from '$lib/api/errors.js';
	import { refreshFolders } from '$lib/stores/folders.js';
	import { registerPendingSend } from '$lib/stores/notifications.js';
	import { _ } from '$lib/i18n/index.js';
	import { pushToast, announcePolite } from '$lib/stores/toasts.js';
	import type { AccountResponse } from '$lib/types.js';
	import AttachmentPicker from '$lib/components/compose/AttachmentPicker.svelte';
	import ComposeActionsBar from '$lib/components/compose/ComposeActionsBar.svelte';
	import RecipientFields from '$lib/components/compose/RecipientFields.svelte';
	import UnsavedChangesDialog from '$lib/components/compose/UnsavedChangesDialog.svelte';
	import {
		createComposeAutosaveScheduler,
		handleComposeShortcuts,
		mapComposePrefill,
		shouldFocusComposeBody,
		targetHref
	} from '$lib/components/compose/controller.js';
	import { loadComposePrefill } from '$lib/compose/prefill.js';
	import {
		appendSignature,
		autoSignature,
		composeKind,
		insertSignatureAt,
		signatureManagedForKind,
		swapSignature
	} from '$lib/compose/signature.js';
	import {
		buildMailRequest,
		draftFingerprint,
		type ComposeAttachment,
		type ComposeDraft
	} from '$lib/compose/request.js';
	import { invalidAddressList, parseAddressList } from '$lib/compose/addresses.js';
	import { ComposeDraftSaveCoordinator } from '$lib/compose/draft-save.js';
	import { confirmAction } from '$lib/stores/confirmDialog.js';
	import { installLeaveGuard } from '$lib/leaveGuard.js';
	import { onDestroy, onMount, tick, untrack } from 'svelte';

	let to = $state('');
	let cc = $state('');
	let bcc = $state('');
	let subject = $state('');
	let body = $state('');
	let attachments = $state<ComposeAttachment[]>([]);
	let inReplyTo = $state<string | null>(null);
	let references = $state<string | null>(null);
	let fromAccountId = $state<number | null>(get(activeAccountId));
	let errorMessage = $state('');
	let autosaveError = $state('');
	let busy = $state(false);
	let autoSavedAt = $state<Date | null>(null);
	let autosaving = $state(false);
	let prefillDone = $state(false);
	let autofocusTo = $state(true);
	let recipientErrorMessage = $state('');
	let ccErrorMessage = $state('');
	let bccErrorMessage = $state('');
	let lastSavedSnapshot = $state('');
	// Signature the composer last inserted into `body`. `null` means the composer
	// is not managing a signature for this compose kind (reply/forward/draft);
	// a string (possibly empty) means it is, and tracks what to swap on account change.
	let appliedSignature = $state<string | null>(null);
	let signatureSyncedAccountId = $state<number | null>(null);
	// Set when the composer was opened on an existing draft (?draft=). Lets us send
	// the original MIME (attachments + threading) as-is while it stays untouched.
	let openedDraftId = $state<string | null>(null);
	const hasUnsavedChanges = $derived(prefillDone && currentDraftSnapshot() !== lastSavedSnapshot);
	let confirmLeaveOpen = $state(false);
	let pendingNavigationHref = $state<string | null>(null);
	let formElement = $state<HTMLFormElement | null>(null);
	let bodyTextarea = $state<HTMLTextAreaElement | null>(null);
	const recipientErrorId = 'compose-to-error';
	const ccErrorId = 'compose-cc-error';
	const bccErrorId = 'compose-bcc-error';
	let busyAction = $state<'send' | 'save' | null>(null);

	let bypassLeaveGuard = false;
	const draftSaveCoordinator = new ComposeDraftSaveCoordinator();
	const autosaveScheduler = createComposeAutosaveScheduler({
		delayMs: 3000,
		isBusy: () => busy,
		onAutosave: () => saveDraftNow({ silent: true })
	});

	let availableAccounts = $state<AccountResponse[]>([]);
	const unsubscribeAccounts = accountsState.subscribe((state) => {
		availableAccounts = state.status === 'ready' ? state.accounts : [];
		if (fromAccountId != null && !availableAccounts.some((a) => a.id === fromAccountId)) {
			fromAccountId = availableAccounts[0]?.id ?? null;
		} else if (fromAccountId == null && availableAccounts.length > 0) {
			fromAccountId = availableAccounts[0].id;
		}
	});

	function currentFromAccount(): AccountResponse | null {
		return availableAccounts.find((a) => a.id === fromAccountId) ?? null;
	}

	// Show the manual "insert signature" toolbar action only when the From account
	// actually has a signature to insert.
	const canInsertSignature = $derived((currentFromAccount()?.signature ?? '').trim().length > 0);

	/**
	 * Inserts the From account's signature at the caret (replacing any selection),
	 * then returns focus to the body with the caret just after the inserted block.
	 * One-off and manual: it does not touch the swap-on-account-change tracking, so
	 * a signature the user places in a reply/forward stays exactly where they put it.
	 */
	function handleInsertSignature() {
		if (!prefillDone || busy) return;
		const el = bodyTextarea;
		const start = el?.selectionStart ?? body.length;
		const end = el?.selectionEnd ?? body.length;
		const result = insertSignatureAt(body, currentFromAccount()?.signature, start, end);
		if (result.body === body) return;
		body = result.body;
		// Moving focus into the textarea only announces the field, not that a
		// signature was just inserted — say so explicitly for screen readers.
		announcePolite($_('compose.signatureInserted'));
		void tick().then(() => {
			el?.focus();
			el?.setSelectionRange(result.caret, result.caret);
		});
	}

	function applyPrefill(prefill: Awaited<ReturnType<typeof loadComposePrefill>>) {
		const values = mapComposePrefill(prefill);
		if (!values) return;
		to = values.to;
		cc = values.cc;
		bcc = values.bcc;
		subject = values.subject;
		body = values.body;
		attachments = values.attachments;
		inReplyTo = values.inReplyTo;
		references = values.references;
		openedDraftId = values.replacesDraftId;
		draftSaveCoordinator.setReplacesDraftId(values.replacesDraftId);
	}

	onMount(async () => {
		const searchParams = get(page).url.searchParams;
		const kind = composeKind(searchParams);
		const focusBodyAfterPrefill = shouldFocusComposeBody(searchParams);
		autofocusTo = !focusBodyAfterPrefill;
		try {
			const prefill = await loadComposePrefill(searchParams);
			applyPrefill(prefill);
			if (signatureManagedForKind(kind)) {
				// Append the From account's signature to new messages / mailto, but only
				// when that account opts into auto-insert. `appliedSignature` stays
				// non-null (possibly '') so this compose keeps managing the block across
				// later From-account switches.
				const sig = autoSignature(currentFromAccount());
				body = appendSignature(body, sig);
				appliedSignature = sig;
				signatureSyncedAccountId = fromAccountId;
			}
		} catch (err) {
			errorMessage = toErrorMessage(err);
		} finally {
			prefillDone = true;
			lastSavedSnapshot = currentDraftSnapshot();
			if (focusBodyAfterPrefill) {
				await tick();
				bodyTextarea?.focus();
			}
		}
	});

	function currentDraft(): ComposeDraft {
		return {
			to,
			cc,
			bcc,
			subject,
			body,
			attachments,
			inReplyTo,
			references
		};
	}

	function currentDraftSnapshot(): string {
		return JSON.stringify({
			fromAccountId,
			draft: draftFingerprint(currentDraft())
		});
	}

	function draftIdentityPendingMessage(): string {
		return $_('compose.draftIdentityPending');
	}

	async function navigateWithoutPrompt(href: string): Promise<void> {
		bypassLeaveGuard = true;
		try {
			await goto(href);
		} finally {
			bypassLeaveGuard = false;
		}
	}

	function invalidAddressMessage(value: string): string {
		return $_('compose.invalidAddress', { values: { address: value } });
	}

	function validateRecipientFields(): boolean {
		recipientErrorMessage = '';
		ccErrorMessage = '';
		bccErrorMessage = '';

		if (parseAddressList(to).length === 0) {
			recipientErrorMessage = $_('compose.errorNoRecipient');
			formElement?.querySelector<HTMLInputElement>('#compose-to')?.focus();
			return false;
		}

		const invalidTo = invalidAddressList(to)[0];
		if (invalidTo) {
			recipientErrorMessage = invalidAddressMessage(invalidTo);
			formElement?.querySelector<HTMLInputElement>('#compose-to')?.focus();
			return false;
		}

		const invalidCc = invalidAddressList(cc)[0];
		if (invalidCc) {
			ccErrorMessage = invalidAddressMessage(invalidCc);
			formElement?.querySelector<HTMLInputElement>('#compose-cc')?.focus();
			return false;
		}

		const invalidBcc = invalidAddressList(bcc)[0];
		if (invalidBcc) {
			bccErrorMessage = invalidAddressMessage(invalidBcc);
			formElement?.querySelector<HTMLInputElement>('#compose-bcc')?.focus();
			return false;
		}

		return true;
	}

	function openLeaveConfirmation(href: string) {
		pendingNavigationHref = href;
		confirmLeaveOpen = true;
	}

	function handleStay() {
		confirmLeaveOpen = false;
		pendingNavigationHref = null;
	}

	async function continuePendingNavigation(): Promise<void> {
		const href = pendingNavigationHref;
		handleStay();
		if (!href) return;
		await navigateWithoutPrompt(href);
	}

	installLeaveGuard({
		shouldGuard: () => !bypassLeaveGuard && !busy && prefillDone && hasUnsavedChanges,
		isSameTarget: (next, current) => targetHref(next) === targetHref(current),
		onBlocked: (target) => openLeaveConfirmation(targetHref(target))
	});

	async function handleSend() {
		if (busy || !prefillDone) return;
		const acc = currentFromAccount();
		if (!acc) {
			recipientErrorMessage = '';
			errorMessage = $_('compose.errorNoActiveAccount');
			return;
		}
		if (!validateRecipientFields()) {
			errorMessage = '';
			return;
		}
		if (!subject.trim()) {
			const confirmed = await confirmAction({
				title: $_('compose.noSubjectConfirmTitle'),
				description: $_('compose.noSubjectConfirm'),
				confirmLabel: $_('compose.noSubjectConfirmAction'),
				cancelLabel: $_('common.cancel')
			});
			if (!confirmed) return;
		}
		busy = true;
		busyAction = 'send';
		errorMessage = '';
		recipientErrorMessage = '';
		autosaveError = '';
		try {
			const currentDraftId = draftSaveCoordinator.replacesDraftId;
			let accepted;
			if (openedDraftId != null && currentDraftId === openedDraftId && !hasUnsavedChanges) {
				// Untouched draft: send the original MIME as-is. The backend re-sends
				// it (keeping attachments + threading) and hard-deletes the draft.
				accepted = await sendDraft(acc.id, openedDraftId);
			} else {
				// New message, or an edited draft: rebuild the MIME from the fields.
				accepted = await sendMail(acc.id, buildMailRequest(currentDraft()));
				if (currentDraftId) {
					// Best-effort: drop the now-superseded draft so it does not linger.
					try {
						await deleteMessage(currentDraftId);
					} catch {
						// Sending succeeded; a stale draft is reconciled on the next sync.
					}
				}
			}
			// Delivery runs async on the backend; show a pending indicator and let the
			// real outcome arrive over the notification stream (send_completed / failed).
			registerPendingSend(accepted.sendId, to.trim());
			void refreshFolders(acc.id);
			await navigateWithoutPrompt(resolve('/'));
		} catch (err) {
			errorMessage = toErrorMessage(err);
		} finally {
			busy = false;
			busyAction = null;
		}
	}

	async function saveDraftNow(
		options: { silent?: boolean; navigateAfterSave?: boolean } = {}
	): Promise<boolean> {
		const { silent = false, navigateAfterSave = !silent } = options;
		if (busy && !silent) return false;
		if (!prefillDone) return false;
		const acc = currentFromAccount();
		if (!acc) {
			if (!silent) errorMessage = $_('compose.errorNoActiveAccount');
			return false;
		}
		if (!silent) busy = true;
		if (!silent) busyAction = 'save';
		autosaving = silent;
		autosaveError = '';
		if (!silent) errorMessage = '';
		try {
			const result = await draftSaveCoordinator.saveDraft(acc.id, currentDraft(), { silent });
			if (result === 'identity-pending') {
				const message = draftIdentityPendingMessage();
				if (silent) autosaveError = message;
				else errorMessage = message;
				return false;
			}

			lastSavedSnapshot = currentDraftSnapshot();
			autoSavedAt = new Date();
			if (!silent && navigateAfterSave) {
				pushToast($_('compose.draftSaved'), { tone: 'success' });
				void refreshFolders(acc.id);
				await navigateWithoutPrompt(resolve('/'));
			}
			return true;
		} catch (err) {
			const message = toErrorMessage(err);
			if (!silent) {
				errorMessage = message;
			} else {
				autosaveError = message;
			}
			return false;
		} finally {
			if (!silent) busy = false;
			if (!silent) busyAction = null;
			autosaving = false;
		}
	}

	async function handleSaveDraft() {
		await saveDraftNow();
	}

	function handleDiscard() {
		if (!prefillDone) return;
		if (!hasUnsavedChanges) {
			void navigateWithoutPrompt(resolve('/'));
			return;
		}
		openLeaveConfirmation(resolve('/'));
	}

	async function handleSaveBeforeLeave() {
		const saved = await saveDraftNow({ navigateAfterSave: false });
		if (!saved) return;
		await continuePendingNavigation();
	}

	async function handleDiscardConfirmed() {
		lastSavedSnapshot = currentDraftSnapshot();
		await continuePendingNavigation();
	}

	function handleKeydown(event: KeyboardEvent) {
		handleComposeShortcuts(event, {
			formElement,
			onSend: () => void handleSend(),
			onSave: () => void handleSaveDraft(),
			onDiscard: handleDiscard,
			onFocusField: (fieldId) =>
				formElement?.querySelector<HTMLInputElement>(`#${fieldId}`)?.focus()
		});
	}

	$effect(() => {
		// Depend on every draft field — currentDraft() reads to/cc/bcc/subject/body/attachments/fromAccountId.
		const draft = currentDraft();
		autosaveError = '';
		if (
			parseAddressList(draft.to).length > 0 &&
			invalidAddressList(draft.to).length === 0 &&
			recipientErrorMessage
		) {
			recipientErrorMessage = '';
		}
		if (invalidAddressList(draft.cc).length === 0 && ccErrorMessage) ccErrorMessage = '';
		if (invalidAddressList(draft.bcc).length === 0 && bccErrorMessage) bccErrorMessage = '';
		autosaveScheduler.schedule(draft, prefillDone);
	});

	$effect(() => {
		// Swap the signature when the From account changes. Only fromAccountId is
		// tracked; untrack the rest so a body keystroke does not re-run this.
		const accountId = fromAccountId;
		untrack(() => {
			if (!prefillDone || appliedSignature === null) return;
			if (accountId === signatureSyncedAccountId) return;
			const nextSig = autoSignature(availableAccounts.find((a) => a.id === accountId));
			const result = swapSignature(body, appliedSignature, nextSig);
			body = result.body;
			appliedSignature = result.appliedSignature;
			signatureSyncedAccountId = accountId;
		});
	});

	onDestroy(() => {
		autosaveScheduler.clear();
		unsubscribeAccounts();
	});
</script>

<svelte:window onkeydown={handleKeydown} />

<form
	bind:this={formElement}
	novalidate
	onsubmit={(e) => {
		e.preventDefault();
		void handleSend();
	}}
	class="flex flex-1 flex-col"
	aria-label={$_('compose.formLabel')}
>
	<ComposeActionsBar
		{autosaving}
		{autoSavedAt}
		{autosaveError}
		{busy}
		{prefillDone}
		{busyAction}
		{canInsertSignature}
		onInsertSignature={handleInsertSignature}
		onDiscard={handleDiscard}
		onSaveDraft={handleSaveDraft}
	/>

	<RecipientFields
		bind:to
		bind:cc
		bind:bcc
		bind:subject
		bind:fromAccountId
		accounts={availableAccounts}
		disabled={!prefillDone || busy}
		toError={recipientErrorMessage}
		toErrorId={recipientErrorId}
		ccError={ccErrorMessage}
		{ccErrorId}
		bccError={bccErrorMessage}
		{bccErrorId}
		{autofocusTo}
	/>

	<AttachmentPicker
		bind:attachments
		disabled={!prefillDone || busy}
		onSelectStart={() => (errorMessage = '')}
		onError={(message) => (errorMessage = message)}
	/>

	<div class="flex flex-1 flex-col p-4">
		{#if !prefillDone}
			<div class="flex flex-1 items-center justify-center" role="status">
				<p class="text-sm text-muted-foreground">{$_('compose.prefillLoading')}</p>
			</div>
		{:else}
			<label for="compose-body" class="sr-only">{$_('compose.bodyLabel')}</label>
			<textarea
				id="compose-body"
				bind:this={bodyTextarea}
				bind:value={body}
				disabled={busy}
				class="flex-1 resize-none border-0 bg-transparent text-sm leading-relaxed text-foreground outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-60"
			></textarea>
		{/if}
	</div>

	{#if errorMessage}
		<div
			role="alert"
			class="border-t border-destructive/30 bg-destructive/10 px-4 py-2 text-sm text-destructive"
		>
			{errorMessage}
		</div>
	{/if}
</form>

<UnsavedChangesDialog
	open={confirmLeaveOpen}
	{busy}
	onStay={handleStay}
	onSave={handleSaveBeforeLeave}
	onDiscard={handleDiscardConfirmed}
/>
