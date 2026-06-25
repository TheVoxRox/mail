<script lang="ts">
	import { get } from 'svelte/store';
	import { selectedMessage } from '$lib/stores/selectedMessage.js';
	import { downloadAttachment } from '$lib/api/mailRead.js';
	import { _ } from '$lib/i18n/index.js';
	import { StateMessage } from '$lib/components/ui/state-message/index.js';
	import { Surface } from '$lib/components/ui/surface/index.js';
	import AttachmentList from '$lib/components/AttachmentList.svelte';
	import MessageContent from '$lib/components/message-detail/MessageContent.svelte';
	import MessageHeaderCard from '$lib/components/message-detail/MessageHeaderCard.svelte';
	import { isMailHtml } from '$lib/mail/content-sanitizer.js';
	import {
		markMessageSeen,
		resetSeenTrackerForSelection,
		shouldMarkSelectedMessageSeen
	} from '$lib/mail/message-seen.js';
	import type { AttachmentResponse } from '$lib/types.js';
	import { closeCurrentMessageDetail } from '$lib/mail/actions.js';
	import MailToolbar from '$lib/components/MailToolbar.svelte';

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
		void closeCurrentMessageDetail({ restoreFocus: true });
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

	async function handleDownload(att: AttachmentResponse) {
		const message = get(selectedMessage);
		if (!message) return;
		const blob = await downloadAttachment(message.stableId, att.partPath, att.fileName);
		const url = URL.createObjectURL(blob);
		const a = document.createElement('a');
		a.href = url;
		a.download = att.fileName;
		document.body.appendChild(a);
		a.click();
		a.remove();
		URL.revokeObjectURL(url);
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
			<MessageContent content={msgContent} {looksLikeHtml} />
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
