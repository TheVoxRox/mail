<script lang="ts">
	/**
	 * Paper for the ticked messages: each on its own sheet, a header and the body.
	 *
	 * The bodies go through MessageContent, so every one renders in the same
	 * sandboxed, CSP-pinned frame as the reading pane — this is a second place
	 * that frame is mounted, not a second way to render mail. It is covered by
	 * CONTENT_RENDERING_AUDIT.md like the first.
	 *
	 * The sheet is laid out off screen rather than hidden: a frame that is not
	 * laid out measures nothing, and the print needs each frame's height (see
	 * app.css, `[data-print='selection']`). It is `inert` and hidden from the
	 * accessibility tree for as long as it exists, which is from the fetch to the
	 * end of the print dialog. The dialog opens once every body has reported a
	 * height, or after READY_TIMEOUT_MS, so one frame that never answers cannot
	 * hold the rest back; that body then prints at its on-screen height.
	 */
	import { onDestroy } from 'svelte';
	import { Portal } from 'bits-ui';
	import { _, appLocale } from '$lib/i18n/index.js';
	import { formatFullDateTime } from '$lib/formatters.js';
	import { isMailHtml } from '$lib/mail/content-sanitizer.js';
	import type { PrintJob } from '$lib/mail/printMessages.js';
	import { resolvedActiveAccountId } from '$lib/stores/accounts.js';
	import MessageContent from './MessageContent.svelte';

	type Props = {
		job: PrintJob;
		/** The print dialog closed, printed or cancelled. */
		onDone: () => void;
	};

	let { job, onDone }: Props = $props();

	const READY_TIMEOUT_MS = 5000;

	let readyCount = $state(0);
	let started = false;

	function markReady(): void {
		readyCount += 1;
	}

	/*
	 * `data-printing` is what switches the print rules from "the open message"
	 * to this sheet. Set before the dialog and removed on afterprint, which
	 * fires for a cancelled dialog too.
	 */
	function startPrint(): void {
		if (started) return;
		started = true;
		document.documentElement.dataset.printing = 'selection';
		window.addEventListener('afterprint', finish, { once: true });
		// One frame, so the attribute's rules apply before the browser lays out.
		requestAnimationFrame(() => window.print());
	}

	function finish(): void {
		delete document.documentElement.dataset.printing;
		onDone();
	}

	$effect(() => {
		if (readyCount >= job.items.length) startPrint();
	});

	$effect(() => {
		const timer = setTimeout(startPrint, READY_TIMEOUT_MS);
		return () => clearTimeout(timer);
	});

	onDestroy(() => {
		window.removeEventListener('afterprint', finish);
		delete document.documentElement.dataset.printing;
	});
</script>

<Portal>
	<div data-print="selection" inert aria-hidden="true">
		{#each job.items as item (item.stableId)}
			<article data-stable-id={item.stableId}>
				<div class="px-5 pt-4">
					<h1 class="text-lg font-semibold leading-tight">
						{item.detail.subject || $_('messages.noSubject')}
					</h1>
					<dl class="mt-3 grid gap-1 text-sm">
						<div>
							<dt class="mr-1.5 inline font-medium">{$_('detail.from')}</dt>
							<dd class="inline">{item.detail.sender}</dd>
						</div>
						{#if item.detail.recipientsTo}
							<div>
								<dt class="mr-1.5 inline font-medium">{$_('detail.to')}</dt>
								<dd class="inline">{item.detail.recipientsTo}</dd>
							</div>
						{/if}
						{#if item.detail.recipientsCc}
							<div>
								<dt class="mr-1.5 inline font-medium">{$_('detail.cc')}</dt>
								<dd class="inline">{item.detail.recipientsCc}</dd>
							</div>
						{/if}
						<div>
							<dt class="mr-1.5 inline font-medium">{$_('detail.date')}</dt>
							<dd class="inline">
								{formatFullDateTime(item.detail.receivedAt, $appLocale ?? 'cs')}
							</dd>
						</div>
					</dl>
				</div>
				<MessageContent
					printOnly
					content={item.content.content}
					looksLikeHtml={isMailHtml(item.content.content)}
					stableId={item.stableId}
					senderEmail={item.content.senderEmail}
					accountId={$resolvedActiveAccountId ?? 0}
					remoteImagesAllowedForSender={item.content.remoteImagesAllowedForSender}
					onPrintReady={markReady}
				/>
			</article>
		{/each}
	</div>
</Portal>
