<script lang="ts">
	import { get } from 'svelte/store';
	import { selectedMessage } from '$lib/stores/selectedMessage.js';
	import { downloadAttachment } from '$lib/api/mailRead.js';
	import { toErrorMessage } from '$lib/api/errors.js';
	import { saveBlobAsFile } from '$lib/download.js';
	import { pushToast } from '$lib/stores/toasts.js';
	import { _ } from '$lib/i18n/index.js';
	import { StateMessage } from '$lib/components/ui/state-message/index.js';
	import { Surface } from '$lib/components/ui/surface/index.js';
	import AttachmentList from '$lib/components/AttachmentList.svelte';
	import MessageContent from '$lib/components/message-detail/MessageContent.svelte';
	import MessageHeaderCard from '$lib/components/message-detail/MessageHeaderCard.svelte';
	import { isMailHtml } from '$lib/mail/content-sanitizer.js';
	import { resolvedActiveAccountId } from '$lib/stores/accounts.js';
	import {
		markMessageSeen,
		resetSeenTrackerForSelection,
		shouldMarkSelectedMessageSeen
	} from '$lib/mail/message-seen.js';
	import type { AttachmentResponse } from '$lib/types.js';
	import { closeCurrentMessageDetail } from '$lib/mail/actions.js';
	import { registerDetailCloser, type DetailCloseContext } from '$lib/mail/detailHost.js';
	import MailToolbar from '$lib/components/MailToolbar.svelte';

	type Props = {
		/**
		 * How to close the detail. Defaults to the mail route's own closing path
		 * — back to the folder list with focus on the message's row. The search
		 * results render this component in place instead, and navigating to a
		 * mail folder there would throw the user out of their results, so that
		 * screen passes its own handler.
		 */
		onClose?: (context: DetailCloseContext) => void | Promise<void>;
	};

	let { onClose }: Props = $props();

	/*
	 * The same closing behaviour is needed when the pipeline removes the open
	 * message (delete/move from the toolbar, palette or a shortcut), so it is
	 * registered for as long as this detail is mounted — see mail/detailHost.ts.
	 */
	$effect(() => registerDetailCloser((context) => closeDetail(context)));

	function closeDetail(context: DetailCloseContext): void | Promise<void> {
		if (onClose) return onClose(context);
		// A message that was just removed has no row left to return focus to;
		// the pipeline has already pointed the list at a neighbour.
		return closeCurrentMessageDetail({ restoreFocus: !context.removedStableId });
	}

	const msgContent = $derived($selectedMessage?.content?.content ?? '');
	const looksLikeHtml = $derived(isMailHtml(msgContent));

	let markingSeenFor = $state<string | null>(null);
	let selectedStableId = $state<string | null>(null);
	let seenAttemptedFor = $state<string | null>(null);

	$effect(() => {
		const message = $selectedMessage;
		const tracker = resetSeenTrackerForSelection(message?.stableId ?? null, {
			selectedStableId,
			markingSeenFor,
			seenAttemptedFor
		});

		if (
			tracker.selectedStableId !== selectedStableId ||
			tracker.markingSeenFor !== markingSeenFor ||
			tracker.seenAttemptedFor !== seenAttemptedFor
		) {
			selectedStableId = tracker.selectedStableId;
			markingSeenFor = tracker.markingSeenFor;
			seenAttemptedFor = tracker.seenAttemptedFor;
		}

		if (!shouldMarkSelectedMessageSeen(message, { markingSeenFor, seenAttemptedFor })) {
			return;
		}

		const stableId = message.stableId;
		markingSeenFor = stableId;
		seenAttemptedFor = stableId;
		void markMessageSeen(stableId)
			.catch(() => {
				// just swallow the error and retry on the next open
			})
			.finally(() => {
				if (markingSeenFor === stableId) {
					markingSeenFor = null;
				}
			});
	});

	function handleClose() {
		void closeDetail({});
	}

	function handleWindowKeydown(event: KeyboardEvent) {
		if (event.key !== 'Escape') return;
		if (event.ctrlKey || event.metaKey || event.altKey || event.shiftKey) return;
		if (!get(selectedMessage)) return;
		const target = event.target;
		if (target instanceof HTMLElement) {
			if (
				target.isContentEditable ||
				target.closest('[contenteditable="true"]') !== null ||
				target instanceof HTMLInputElement ||
				target instanceof HTMLTextAreaElement ||
				target instanceof HTMLSelectElement
			) {
				return;
			}
			// Don't close the detail if a dialog is open (let it close on Esc)
			if (target.closest('[role="dialog"]') !== null) return;
		}
		event.preventDefault();
		handleClose();
	}

	// Success/error toast mirrors the contacts vCard export: the save-file
	// dialog gives no screen-reader feedback and a failure was silently lost.
	async function handleDownload(att: AttachmentResponse) {
		const message = get(selectedMessage);
		if (!message) return;
		try {
			const blob = await downloadAttachment(message.stableId, att.partPath, att.fileName);
			saveBlobAsFile(blob, att.fileName);
			pushToast($_('detail.attachmentDownloadDone', { values: { name: att.fileName } }), {
				tone: 'success'
			});
		} catch (err) {
			pushToast(toErrorMessage(err), { tone: 'error' });
		}
	}
</script>

<svelte:window onkeydown={handleWindowKeydown} />

{#if $selectedMessage}
	{@const state = $selectedMessage}
	{@const detail = state.detail}
	<div class="flex flex-1 flex-col overflow-y-auto" tabindex="-1">
		<div
			class="sticky top-0 z-10 flex items-center gap-3 border-b border-border bg-background px-3 py-2"
		>
			<MailToolbar />
		</div>

		{#if state.error}
			<div class="border-b border-border bg-background px-5 py-4">
				<Surface variant="danger" padding="sm">
					<StateMessage variant="error" padding="none" role="alert">
						{state.error.message}
					</StateMessage>
				</Surface>
			</div>
		{/if}

		{#if state.notFound}
			<div class="px-5 py-4">
				<Surface variant="subtle" padding="sm">
					<StateMessage padding="none" role="status">{$_('detail.notFound')}</StateMessage>
				</Surface>
			</div>
		{/if}

		{#if detail}
			<MessageHeaderCard {detail} onBack={handleClose} />
		{/if}

		{#if detail?.attachments && detail.attachments.length > 0}
			<AttachmentList attachments={detail.attachments} onDownload={handleDownload} />
		{/if}

		{#if state.loading && !state.content}
			<div class="px-5 py-4">
				<Surface variant="subtle" padding="sm">
					<StateMessage padding="none" role="status">{$_('detail.contentLoading')}</StateMessage>
				</Surface>
			</div>
		{:else if state.content}
			<MessageContent
				content={msgContent}
				{looksLikeHtml}
				stableId={state.stableId}
				senderEmail={state.content.senderEmail}
				accountId={$resolvedActiveAccountId ?? 0}
				remoteImagesAllowedForSender={state.content.remoteImagesAllowedForSender}
			/>
		{/if}

		{#if detail?.contentError}
			<div class="border-t border-border bg-background px-5 py-4">
				<Surface variant="danger" padding="sm">
					<StateMessage variant="error" padding="none" role="alert">
						{detail.contentError}
					</StateMessage>
				</Surface>
			</div>
		{/if}
	</div>
{/if}
