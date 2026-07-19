<script lang="ts">
	import { goto } from '$app/navigation';
	import { resolve } from '$app/paths';
	import { page } from '$app/stores';
	import { get } from 'svelte/store';
	import { accountsState, activeAccountId } from '$lib/stores/accounts.js';
	import { sendMail } from '$lib/api/mailWrite.js';
	import { sendDraft } from '$lib/api/drafts.js';
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
		handleComposeShortcuts,
		mapComposePrefill,
		resolveComposeFromAccount,
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
		type ComposeAttachment,
		type ComposeDraft
	} from '$lib/compose/request.js';
	import { invalidAddressList, parseAddressList } from '$lib/compose/addresses.js';
	import { mentionsAttachment } from '$lib/compose/attachmentReminder.js';
	import { confirmAction } from '$lib/stores/confirmDialog.js';
	import { composeSession, composeSnapshotFingerprint } from '$lib/compose/session.js';
	import { installLeaveGuard } from '$lib/leaveGuard.js';
	import { onDestroy, onMount, tick, untrack } from 'svelte';

	let to = $state('');
	let cc = $state('');
	let bcc = $state('');
	let subject = $state('');
	let body = $state('');
	let attachments = $state<ComposeAttachment[]>([]);
	let attachmentReading = $state(false);
	let inReplyTo = $state<string | null>(null);
	let references = $state<string | null>(null);
	let fromAccountId = $state<number | null>(get(activeAccountId));
	let errorMessage = $state('');
	let busy = $state(false);
	let prefillDone = $state(false);
	let autofocusTo = $state(true);
	let recipientErrorMessage = $state('');
	let ccErrorMessage = $state('');
	let bccErrorMessage = $state('');
	let subjectErrorMessage = $state('');
	// Signature the composer last inserted into `body`. `null` means the composer
	// is not managing a signature for this compose kind (reply/forward/draft);
	// a string (possibly empty) means it is, and tracks what to swap on account change.
	let appliedSignature = $state<string | null>(null);
	let signatureSyncedAccountId = $state<number | null>(null);
	// Set when the composer was opened on an existing draft (?draft=). Lets us send
	// the original MIME (attachments + threading) as-is while it stays untouched.
	let openedDraftId = $state<string | null>(null);
	// Unsaved work worth guarding = edits that differ from BOTH the regenerable
	// prefill baseline and whatever was last persisted. An untouched reply
	// (== baseline) or content already autosaved (== saved) is safe to leave.
	const hasUnsavedChanges = $derived.by(() => {
		if (!prefillDone) return false;
		const snap = currentDraftSnapshot();
		return (
			snap !== $composeSession.baselineFingerprint && snap !== $composeSession.savedFingerprint
		);
	});
	let confirmLeaveOpen = $state(false);
	let dialogIntent = $state<'leave' | 'discard'>('leave');
	let pendingNavigationHref = $state<string | null>(null);
	let formElement = $state<HTMLFormElement | null>(null);
	let bodyTextarea = $state<HTMLTextAreaElement | null>(null);
	const recipientErrorId = 'compose-to-error';
	const ccErrorId = 'compose-cc-error';
	const bccErrorId = 'compose-bcc-error';
	const subjectErrorId = 'compose-subject-error';
	let busyAction = $state<'send' | 'save' | null>(null);
	// The lifecycle state (draft identity, save queue, fingerprint) lives in the
	// composeSession module so it survives this component (async send outcome,
	// a save still in flight during navigation).
	const autosaving = $derived($composeSession.saving && busyAction !== 'save');
	const autoSavedAt = $derived($composeSession.savedAt);
	const autosaveError = $derived(errorMessage ? '' : ($composeSession.saveError ?? ''));

	let bypassLeaveGuard = false;

	let availableAccounts = $state<AccountResponse[]>([]);
	const unsubscribeAccounts = accountsState.subscribe((state) => {
		availableAccounts = state.status === 'ready' ? state.accounts : [];
		fromAccountId = resolveComposeFromAccount(
			fromAccountId,
			availableAccounts,
			get(activeAccountId)
		);
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
			composeSession.begin({
				accountId: fromAccountId,
				draft: currentDraft(),
				draftStableId: openedDraftId,
				isSuspended: () => busy
			});
			prefillDone = true;
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
		return composeSnapshotFingerprint(fromAccountId, currentDraft());
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

	function validateSendFields(): boolean {
		recipientErrorMessage = '';
		ccErrorMessage = '';
		bccErrorMessage = '';
		subjectErrorMessage = '';

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

		// Product decision: the backend rejects a blank subject (@NotBlank), so
		// the UI requires it instead of offering a doomed "send anyway".
		if (!subject.trim()) {
			subjectErrorMessage = $_('compose.errorNoSubject');
			formElement?.querySelector<HTMLInputElement>('#compose-subject')?.focus();
			return false;
		}

		return true;
	}

	function openLeaveConfirmation(href: string, intent: 'leave' | 'discard' = 'leave') {
		dialogIntent = intent;
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
		// attachmentReading counts as unsaved work: the in-flight file is not in
		// `attachments` yet, so the fingerprint alone would let it leave silently.
		shouldGuard: () =>
			!bypassLeaveGuard && !busy && prefillDone && (hasUnsavedChanges || attachmentReading),
		isSameTarget: (next, current) => targetHref(next) === targetHref(current),
		onBlocked: (target) => openLeaveConfirmation(targetHref(target), 'leave')
	});

	/**
	 * Sending while the picker is still reading a file would build the request
	 * without it — the in-flight file is not in `attachments` yet. (Saving is
	 * guarded inside the session queue, which retries once reading settles.)
	 */
	function blockedByAttachmentRead(): boolean {
		if (!attachmentReading) return false;
		errorMessage = $_('compose.errorAttachmentStillReading');
		return true;
	}

	// Guards handleSend re-entry while the attachment-reminder dialog is open
	// (`busy` is still false there so the composer stays editable on cancel);
	// a second confirmAction would orphan the first dialog's resolver.
	let confirmingSend = false;

	async function handleSend() {
		if (busy || confirmingSend || !prefillDone) return;
		const acc = currentFromAccount();
		if (!acc) {
			recipientErrorMessage = '';
			errorMessage = $_('compose.errorNoActiveAccount');
			return;
		}
		if (!validateSendFields()) {
			errorMessage = '';
			return;
		}
		if (blockedByAttachmentRead()) return;
		// After the attachment-read guard: an in-flight file lands in
		// `attachments` once reading settles, so it must not count as missing.
		if (attachments.length === 0 && mentionsAttachment(body)) {
			confirmingSend = true;
			let sendAnyway: boolean;
			try {
				sendAnyway = await confirmAction({
					title: $_('compose.attachmentReminder.title'),
					description: $_('compose.attachmentReminder.description'),
					confirmLabel: $_('compose.attachmentReminder.sendAnyway'),
					cancelLabel: $_('common.cancel')
				});
			} finally {
				confirmingSend = false;
			}
			if (!sendAnyway) return;
		}
		busy = true;
		busyAction = 'send';
		errorMessage = '';
		recipientErrorMessage = '';
		try {
			// Stop the autosave queue and pin the draft identity: the backend
			// deletes the superseded draft only after successful delivery (B2),
			// so a failed send keeps it as the recovery copy.
			const supersedesDraftId = await composeSession.prepareForSend();
			let accepted;
			if (openedDraftId != null && supersedesDraftId === openedDraftId && !hasUnsavedChanges) {
				// Untouched draft: send the original MIME as-is. The backend re-sends
				// it (keeping attachments + threading) and hard-deletes the draft.
				accepted = await sendDraft(acc.id, openedDraftId);
			} else {
				// New message, or an edited draft: rebuild the MIME from the fields.
				accepted = await sendMail(
					acc.id,
					buildMailRequest(currentDraft()),
					supersedesDraftId ?? undefined
				);
			}
			// Delivery runs async on the backend; show a pending indicator and let the
			// real outcome arrive over the notification stream (send_completed / failed).
			registerPendingSend(accepted.sendId, to.trim());
			composeSession.end();
			void refreshFolders(acc.id);
			await navigateWithoutPrompt(resolve('/'));
		} catch (err) {
			errorMessage = toErrorMessage(err);
		} finally {
			busy = false;
			busyAction = null;
		}
	}

	async function saveDraftNow(options: { navigateAfterSave?: boolean } = {}): Promise<boolean> {
		const { navigateAfterSave = true } = options;
		if (busy || !prefillDone) return false;
		const acc = currentFromAccount();
		if (!acc) {
			errorMessage = $_('compose.errorNoActiveAccount');
			return false;
		}
		busy = true;
		busyAction = 'save';
		errorMessage = '';
		try {
			const outcome = await composeSession.flush();
			if (outcome === 'blocked') {
				errorMessage = $_('compose.errorAttachmentStillReading');
				return false;
			}
			if (outcome === 'failed') {
				errorMessage = $composeSession.saveError ?? $_('compose.autosaveFailed');
				return false;
			}
			// 'skipped' with no draft = an empty compose the floor refused to save;
			// don't claim a save happened. A real draft (saved now or by autosave)
			// gets the confirmation toast.
			const draftExists = $composeSession.draftStableId != null;
			if (navigateAfterSave) {
				if (draftExists) pushToast($_('compose.draftSaved'), { tone: 'success' });
				void refreshFolders(acc.id);
				await navigateWithoutPrompt(resolve('/'));
			}
			return true;
		} finally {
			busy = false;
			busyAction = null;
		}
	}

	async function handleSaveDraft() {
		await saveDraftNow();
	}

	function handleDiscard() {
		if (!prefillDone) return;
		const hasPersistedDraft = $composeSession.draftStableId != null;
		if (!hasUnsavedChanges && !attachmentReading && !hasPersistedDraft) {
			void navigateWithoutPrompt(resolve('/'));
			return;
		}
		// A persisted draft (autosaved or opened) always confirms: discard
		// deletes it, and the dialog copy says so.
		openLeaveConfirmation(resolve('/'), 'discard');
	}

	async function handleSaveBeforeLeave() {
		const saved = await saveDraftNow({ navigateAfterSave: false });
		if (!saved) {
			// The reason renders as the form-level alert, which the modal overlay
			// would cover — close the dialog and drop the pending navigation so
			// the message is visible (and announced) on the composer.
			handleStay();
			return;
		}
		await continuePendingNavigation();
	}

	async function handleDiscardConfirmed() {
		if (dialogIntent === 'discard') {
			const acc = currentFromAccount();
			// Deletes the whole draft chain (decided). Runs in the session module,
			// so navigating away immediately is safe.
			void composeSession.discard().then((deletedId) => {
				if (deletedId != null && acc) void refreshFolders(acc.id);
			});
		} else {
			// Leaving discards only the in-memory edits; the autosaved draft stays
			// (server-side crash recovery). Stop the pending autosave of the
			// abandoned changes.
			composeSession.detach();
		}
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
		// Depend on every draft field — currentDraft() reads to/cc/bcc/subject/body/attachments,
		// plus fromAccountId. Mirrors the change into the session (debounced autosave).
		const draft = currentDraft();
		const accountId = fromAccountId;
		if (
			parseAddressList(draft.to).length > 0 &&
			invalidAddressList(draft.to).length === 0 &&
			recipientErrorMessage
		) {
			recipientErrorMessage = '';
		}
		if (invalidAddressList(draft.cc).length === 0 && ccErrorMessage) ccErrorMessage = '';
		if (invalidAddressList(draft.bcc).length === 0 && bccErrorMessage) bccErrorMessage = '';
		if (draft.subject.trim() && subjectErrorMessage) subjectErrorMessage = '';
		if (prefillDone) composeSession.noteChange(accountId, draft);
	});

	$effect(() => {
		composeSession.setAttachmentReading(attachmentReading);
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
		// Future autosaves stop; a save already in flight finishes in the session.
		composeSession.detach();
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
		subjectError={subjectErrorMessage}
		{subjectErrorId}
		{autofocusTo}
	/>

	<AttachmentPicker
		bind:attachments
		bind:reading={attachmentReading}
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
	intent={dialogIntent}
	draftWillBeDeleted={dialogIntent === 'discard' && $composeSession.draftStableId != null}
	onStay={handleStay}
	onSave={handleSaveBeforeLeave}
	onDiscard={handleDiscardConfirmed}
/>
