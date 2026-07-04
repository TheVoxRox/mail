<script lang="ts">
	import { getContext } from 'svelte';
	import { _ } from '$lib/i18n/index.js';
	import {
		buildMailFrameSrcdoc,
		isMailFrameKeyMessage,
		mailFrameKeyToEvent
	} from '$lib/mail/mailFrame.js';
	import { mailHtmlToPlainText } from '$lib/mail/content-sanitizer.js';
	import { messageBodyView } from '$lib/stores/uiLayout.js';
	import {
		MESSAGE_HEADING_CONTEXT_KEY,
		type MessageHeadingContext
	} from '$lib/components/message-detail/messageHeadingContext.js';

	type Props = {
		content: string;
		looksLikeHtml: boolean;
		stableId: string;
	};

	let { content, looksLikeHtml, stableId }: Props = $props();

	/*
	 * The body heading sits one level below the subject heading in
	 * MessageHeaderCard (h1 in off mode, h2 in split — see
	 * messageHeadingContext.ts). Same fallback as there for use outside the
	 * mail route.
	 */
	const heading: MessageHeadingContext = getContext<MessageHeadingContext>(
		MESSAGE_HEADING_CONTEXT_KEY
	) ?? { level: 2, showBackButton: true };

	const renderAsHtml = $derived(looksLikeHtml && $messageBodyView === 'html');

	let frameElement = $state<HTMLIFrameElement | null>(null);
	let textElement = $state<HTMLElement | null>(null);

	/*
	 * Screen-reader guidance: opening a message moves focus to the start of
	 * the body (the Outlook model; Esc already restores it to the list row),
	 * so the reading cursor lands on the text instead of the top of the page.
	 * Guarded per stableId so a background content refresh cannot re-steal
	 * focus, and deferred a frame so it lands after SvelteKit's own
	 * post-navigation focus reset (content can render mid-navigation on an
	 * LRU cache hit in selectedMessage).
	 */
	let focusedStableId: string | null = null;

	$effect(() => {
		const target = renderAsHtml ? frameElement : textElement;
		if (!target || focusedStableId === stableId) return;
		focusedStableId = stableId;
		const frame = requestAnimationFrame(() => target.focus());
		return () => cancelAnimationFrame(frame);
	});

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

<!--
	Named region + visually hidden heading: both carry the same label via
	aria-labelledby, so the body is reachable with the screen-reader landmark
	key (D) as well as the heading key (H) after the initial auto-focus.
-->
<section class="flex-1 bg-background p-5" aria-labelledby="message-body-heading">
	{#if heading.level === 1}
		<h2 id="message-body-heading" class="sr-only">{$_('detail.bodyHeading')}</h2>
	{:else}
		<h3 id="message-body-heading" class="sr-only">{$_('detail.bodyHeading')}</h3>
	{/if}
	{#if renderAsHtml}
		<!--
			bg-white (not bg-background): the srcdoc paints a fixed light surface
			(see MAIL_FRAME_STYLE) because mail HTML assumes white; matching the
			element background avoids a dark flash before the frame renders.
		-->
		<iframe
			bind:this={frameElement}
			use:mailFrame
			title={$_('detail.iframeTitle')}
			sandbox="allow-scripts"
			srcdoc={buildMailFrameSrcdoc(content)}
			class="h-[65vh] min-h-96 w-full rounded-md border border-border bg-white"
		></iframe>
	{:else}
		<div
			bind:this={textElement}
			tabindex="-1"
			class="max-w-3xl whitespace-pre-wrap text-sm leading-relaxed text-foreground"
		>
			{plainTextBody}
		</div>
	{/if}
</section>
