<script lang="ts">
	import { getContext } from 'svelte';
	import { _ } from '$lib/i18n/index.js';
	import {
		buildMailFrameSrcdoc,
		countRemoteImages,
		isMailFrameKeyMessage,
		isMailFrameLinkMessage,
		isOpenableMailLink,
		mailFrameKeyToEvent
	} from '$lib/mail/mailFrame.js';
	import { mailHtmlToPlainText } from '$lib/mail/content-sanitizer.js';
	import { allowSenderRemoteImages } from '$lib/api/remoteImages.js';
	import { pushToast } from '$lib/stores/toasts.js';
	import { Button } from '$lib/components/ui/button/index.js';
	import { messageBodyView } from '$lib/stores/uiLayout.js';
	import {
		MESSAGE_HEADING_CONTEXT_KEY,
		type MessageHeadingContext
	} from '$lib/components/message-detail/messageHeadingContext.js';

	type Props = {
		content: string;
		looksLikeHtml: boolean;
		stableId: string;
		senderEmail: string;
		accountId: number;
		remoteImagesAllowedForSender: boolean;
	};

	let {
		content,
		looksLikeHtml,
		stableId,
		senderEmail,
		accountId,
		remoteImagesAllowedForSender
	}: Props = $props();

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

	/*
	 * Remote images are blocked by default (tracking-pixel defense, audit F2). The
	 * user can load them for this message (ephemeral) or trust the sender (persisted
	 * via the allow-list). The decision resets whenever the open message changes,
	 * re-seeded from whether the sender is already trusted.
	 */
	let loadRemoteImages = $state(false);
	let remoteImagesStableId: string | null = null;
	$effect(() => {
		// Seed on first render and re-seed whenever the open message changes.
		if (remoteImagesStableId === stableId) return;
		remoteImagesStableId = stableId;
		loadRemoteImages = remoteImagesAllowedForSender;
	});

	const remoteImageCount = $derived(renderAsHtml ? countRemoteImages(content) : 0);
	const showRemoteBanner = $derived(remoteImageCount > 0 && !loadRemoteImages);
	let allowInFlight = $state(false);

	/* "Always from this sender": persist to the allow-list, then load for this view. */
	async function trustSender(): Promise<void> {
		if (accountId <= 0 || !senderEmail) {
			// No key to persist against — still honor the intent for this view only.
			loadRemoteImages = true;
			return;
		}
		allowInFlight = true;
		try {
			await allowSenderRemoteImages(accountId, senderEmail);
			loadRemoteImages = true;
		} catch (error) {
			console.warn('[mail] failed to trust sender for remote images', error);
			pushToast($_('detail.remoteImages.allowError'), { tone: 'error' });
		} finally {
			allowInFlight = false;
		}
	}

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
	 * script is a hash-pinned forwarder (see mailFrame.ts). Accept its
	 * postMessages — strictly from THIS frame — and act on them: keystrokes are
	 * replayed as synthetic keydowns on window so global shortcuts (?, Delete, …)
	 * keep working while the reader's focus is inside the body; link clicks are
	 * opened in the OS browser (the sandbox has no allow-popups, so the frame
	 * cannot navigate them itself). Synthetic events are `isTrusted === false`,
	 * so the frame forwarder never re-forwards them.
	 */
	function mailFrame(node: HTMLIFrameElement) {
		function onMessage(event: MessageEvent) {
			// The frame is an opaque origin (sandbox allow-scripts, no
			// allow-same-origin), so genuine relays arrive with origin "null";
			// pair that with an exact source match to this frame's window.
			if (event.origin !== 'null') return;
			if (event.source !== node.contentWindow) return;
			if (isMailFrameKeyMessage(event.data)) {
				window.dispatchEvent(mailFrameKeyToEvent(event.data));
				return;
			}
			if (isMailFrameLinkMessage(event.data)) {
				void openBodyLink(event.data.href);
			}
		}
		window.addEventListener('message', onMessage);
		return {
			destroy() {
				window.removeEventListener('message', onMessage);
			}
		};
	}

	/*
	 * A mail-body link cannot navigate the opaque-origin sandbox itself (no
	 * allow-popups), so the in-frame forwarder relays the click here. Re-validate
	 * the protocol against the same allow-list the sanitizer enforces before
	 * handing the URL to the OS browser via the shell:allow-open capability.
	 */
	async function openBodyLink(href: string): Promise<void> {
		if (!isOpenableMailLink(href)) return;
		try {
			const { open } = await import('@tauri-apps/plugin-shell');
			await open(href);
		} catch (error) {
			console.warn('[mail] failed to open body link', error);
		}
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
		{#if showRemoteBanner}
			<!--
				Opt-in banner for blocked remote images. Polite live region (does not
				steal focus from the auto-focused body); the two buttons are the only
				way remote content ever loads.
			-->
			<div
				class="mb-2 flex flex-wrap items-center gap-2 rounded-md border border-border bg-muted px-3 py-2 text-sm"
				role="region"
				aria-label={$_('detail.remoteImages.regionLabel')}
				aria-live="polite"
			>
				<span class="text-muted-foreground">
					{$_('detail.remoteImages.blocked', { values: { count: remoteImageCount } })}
				</span>
				<div class="ml-auto flex gap-2">
					<Button variant="outline" size="sm" onclick={() => (loadRemoteImages = true)}>
						{$_('detail.remoteImages.load')}
					</Button>
					<Button variant="ghost" size="sm" disabled={allowInFlight} onclick={trustSender}>
						{$_('detail.remoteImages.alwaysAllow')}
					</Button>
				</div>
			</div>
		{/if}
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
			srcdoc={buildMailFrameSrcdoc(content, { loadRemoteImages })}
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
