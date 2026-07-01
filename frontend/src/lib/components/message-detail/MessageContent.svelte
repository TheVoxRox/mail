<script lang="ts">
	import { _ } from '$lib/i18n/index.js';
	import {
		buildMailFrameSrcdoc,
		isMailFrameKeyMessage,
		mailFrameKeyToEvent
	} from '$lib/mail/mailFrame.js';
	import { mailHtmlToPlainText } from '$lib/mail/content-sanitizer.js';
	import { messageBodyView } from '$lib/stores/uiLayout.js';

	type Props = {
		content: string;
		looksLikeHtml: boolean;
	};

	let { content, looksLikeHtml }: Props = $props();

	const renderAsHtml = $derived(looksLikeHtml && $messageBodyView === 'html');

	/*
	 * In plain-text view the content endpoint still returns display HTML (wrapped in
	 * mail-content-wrapper), so flatten it to readable text — the same transform as
	 * the reply/forward prefill — instead of dumping raw <div>/<table> tags into the
	 * pane. Genuine plain-text bodies (not looksLikeHtml) pass through unchanged.
	 */
	const plainTextBody = $derived(looksLikeHtml ? mailHtmlToPlainText(content) : content);

	/*
	 * The body renders in a script-sandboxed, opaque-origin iframe whose only
	 * script is a hash-pinned key forwarder (see mailFrame.ts). Accept its
	 * postMessages — strictly from THIS frame — and replay them as synthetic
	 * keydowns on window, so global shortcuts (?, Delete, …) keep working even
	 * while the reader's focus is inside the body. Synthetic events are
	 * `isTrusted === false`, so the frame forwarder never re-forwards them.
	 */
	function mailFrame(node: HTMLIFrameElement) {
		function onMessage(event: MessageEvent) {
			// The frame is an opaque origin (sandbox allow-scripts, no
			// allow-same-origin), so genuine relays arrive with origin "null";
			// pair that with an exact source match to this frame's window.
			if (event.origin !== 'null') return;
			if (event.source !== node.contentWindow) return;
			if (!isMailFrameKeyMessage(event.data)) return;
			window.dispatchEvent(mailFrameKeyToEvent(event.data));
		}
		window.addEventListener('message', onMessage);
		return {
			destroy() {
				window.removeEventListener('message', onMessage);
			}
		};
	}
</script>

<div class="flex-1 bg-background p-5">
	{#if renderAsHtml}
		<iframe
			use:mailFrame
			title={$_('detail.iframeTitle')}
			sandbox="allow-scripts"
			srcdoc={buildMailFrameSrcdoc(content)}
			class="h-[65vh] min-h-96 w-full rounded-md border border-border bg-background"
		></iframe>
	{:else}
		<div class="max-w-3xl whitespace-pre-wrap text-sm leading-relaxed text-foreground">
			{plainTextBody}
		</div>
	{/if}
</div>
