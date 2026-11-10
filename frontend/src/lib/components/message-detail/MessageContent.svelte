<script lang="ts">
	import { getContext, tick } from 'svelte';
	import { _ } from '#lib/i18n/index.js';
	import {
		buildMailFrameSrcdoc,
		countRemoteImages,
		clampMailFramePrintHeight,
		isMailFrameHeightMessage,
		NO_MAIL_FRAME_PRINT_HEIGHT,
		printHeightFor,
		isMailFrameKeyMessage,
		isMailFrameLinkMessage,
		isOpenableMailLink,
		mailFrameKeyToEvent
	} from '#lib/mail/mailFrame.js';
	import { bodyFocusIntent, clearBodyFocusIntent, shouldFocusBody } from '#lib/mail/bodyFocus.js';
	import { mailHtmlToPlainText } from '#lib/mail/content-sanitizer.js';
	import { trustRemoteImageSender } from '#lib/mail/remoteImageTrust.js';
	import { publishRemoteImageOffer } from '#lib/mail/remoteImageOffer.js';
	import { announcePolite, pushToast } from '#lib/stores/toasts.js';
	import { Button } from '#lib/components/ui/button/index.js';
	import { messageBodyView } from '#lib/stores/uiLayout.js';
	import { textSize } from '#lib/stores/textSize.js';
	import {
		MESSAGE_HEADING_CONTEXT_KEY,
		type MessageHeadingContext
	} from '#lib/components/message-detail/messageHeadingContext.js';

	type Props = {
		content: string;
		looksLikeHtml: boolean;
		stableId: string;
		senderEmail: string;
		accountId: number;
		remoteImagesAllowedForSender: boolean;
		/** False in a Junk folder: the banner then offers this message's images only. */
		remoteImageTrustOffered: boolean;
		/**
		 * Rendered for paper only, inside MessagePrintSheet, where several bodies
		 * sit side by side in an inert container. The body never takes focus and
		 * the remote-image banner is left out, since nobody can press it there.
		 */
		printOnly?: boolean;
		/**
		 * Print-only: called once the body has a height worth printing — when the
		 * frame reports its measured document, or at once for plain text. The
		 * sheet waits for every body before it opens the print dialog.
		 */
		onPrintReady?: () => void;
	};

	let {
		content,
		looksLikeHtml,
		stableId,
		senderEmail,
		accountId,
		remoteImagesAllowedForSender,
		remoteImageTrustOffered,
		printOnly = false,
		onPrintReady
	}: Props = $props();

	/*
	 * Unique per instance: the print sheet renders one of these per message next
	 * to the reading pane's own, and a fixed id would point every
	 * aria-labelledby at whichever section came first in the document.
	 */
	const uid = $props.id();
	const headingId = `${uid}-body-heading`;

	let printReadySignalled = false;
	function signalPrintReady(): void {
		if (!printOnly || printReadySignalled) return;
		printReadySignalled = true;
		onPrintReady?.();
	}

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

	/*
	 * Trusting the sender is offered only where it can be kept and where the
	 * backend will honor it. Without an account or an address there is no key to
	 * store it under; in a Junk folder the backend does not apply the list at
	 * all (remoteImageTrustOffered), because its key is the From address and junk
	 * is where a message forged in a trusted name ends up. A button that says
	 * "always" and then remembers nothing is worse than no button, so the banner
	 * is left with the one that loads this message.
	 */
	const canTrustSender = $derived(remoteImageTrustOffered && accountId > 0 && senderEmail !== '');

	/*
	 * Loading the images unmounts the banner the pressed button lives in, and the
	 * button takes focus down with it — focus falls back to <body>, where a
	 * screen reader has nothing to read and no way back except the landmark key.
	 * So both halves of the press are done here rather than inline: focus moves
	 * to the frame (the target the deliberate-open path already uses, and the
	 * thing the user just asked to see), and the outcome is announced.
	 *
	 * The announcement goes through pushToast, which writes into the persistent
	 * polite region ToastRegion keeps mounted. This is not the banner's old live
	 * region coming back: that one re-read itself on every render, which in split
	 * mode meant every arrow key. This fires on a press.
	 */
	function revealRemoteImages(announcement: string): void {
		loadRemoteImages = true;
		pushToast(announcement, { tone: 'success' });
		void tick().then(() => {
			if (frameElement?.isConnected) frameElement.focus();
		});
	}

	/*
	 * "Always from <address>": persist to the allow-list, then load for this
	 * view. The button and the announcement both name the address, because the
	 * address is what gets trusted — the bare one from the From header, which a
	 * display name can differ from.
	 */
	async function trustSender(): Promise<void> {
		if (!canTrustSender) return;
		allowInFlight = true;
		try {
			await trustRemoteImageSender(accountId, senderEmail);
			revealRemoteImages(
				$_('detail.remoteImages.senderAllowedAnnouncement', { values: { email: senderEmail } })
			);
		} catch (error) {
			console.warn('[mail] failed to trust sender for remote images', error);
			pushToast($_('detail.remoteImages.allowError'), { tone: 'error' });
		} finally {
			allowInFlight = false;
		}
	}

	/*
	 * While the banner is on screen, the palette command and Ctrl+Shift+W can
	 * reach it (see mail/remoteImageOffer.ts). The print sheet's copies never
	 * publish: they have no banner, and one of them would otherwise answer for
	 * the message in the reading pane.
	 */
	let loadButton = $state<HTMLElement | null>(null);
	$effect(() => {
		if (!showRemoteBanner || printOnly) return;
		return publishRemoteImageOffer({
			stableId,
			load: () => revealRemoteImages($_('detail.remoteImages.loadedAnnouncement')),
			focus: () => loadButton?.focus()
		});
	});

	let frameElement = $state<HTMLIFrameElement | null>(null);
	let textElement = $state<HTMLElement | null>(null);

	/*
	 * Screen-reader guidance: deliberately opening a message moves focus to the
	 * start of the body (the Outlook model; Esc already restores it to the list
	 * row), so the reading cursor lands on the text instead of the top of the
	 * page. A row change that follows focus in a split pane says so (see
	 * mail/bodyFocus.ts) and leaves the user in the list. Deferred a frame so
	 * the move lands after SvelteKit's own post-navigation focus reset (content
	 * can render mid-navigation on an LRU cache hit in selectedMessage).
	 */
	let focusedStableId: string | null = null;

	$effect(() => {
		if (printOnly) return;
		const target = renderAsHtml ? frameElement : textElement;
		if (!target) return;
		const firstRender = focusedStableId !== stableId;
		focusedStableId = stableId;
		// The rule lives in mail/bodyFocus.ts, where the case that broke it is
		// written down: an intent naming another message means two navigations
		// overlapped, which is not the same as no intent at all.
		if (!shouldFocusBody($bodyFocusIntent, stableId, firstRender)) return;
		// Consumed here rather than after the frame: the intent is single-use.
		// The effect deliberately has no cleanup — clearing the store re-runs
		// it, and a cleanup would cancel the frame it just scheduled; the
		// isConnected check covers the unmount case instead (same guard as the
		// palette focus restore).
		clearBodyFocusIntent();
		requestAnimationFrame(() => {
			if (!target.isConnected) return;
			target.focus();
			/*
			 * The reading cursor has just gone past the blocked-images banner, which
			 * is silent, so a deliberate open says in one short sentence that the
			 * message has images it did not load. This is the announcement the
			 * banner lost when it stopped being a live region (see the comment on
			 * it below), back on the one path where it was wanted: as a live region
			 * it could not tell an open from a row change that follows focus, and
			 * read itself out on every arrow key in the split pane. Whether to take
			 * focus is decided by exactly that difference, so the sentence rides on
			 * the same decision and a followed row stays quiet. After the focus
			 * call, so a screen reader queues it behind what the move makes it say.
			 * Read here rather than in the effect, which must not re-run when the
			 * banner goes.
			 */
			if (showRemoteBanner) announcePolite($_('detail.remoteImages.blockedAnnouncement'));
		});
	});

	/*
	 * In plain-text view the content endpoint still returns display HTML (wrapped in
	 * mail-content-wrapper), so flatten it to readable text — the same transform as
	 * the reply/forward prefill — instead of dumping raw <div>/<table> tags into the
	 * pane. Genuine plain-text bodies (not looksLikeHtml) pass through unchanged.
	 */
	const plainTextBody = $derived(looksLikeHtml ? mailHtmlToPlainText(content) : content);

	// Plain text has no frame to wait for: its height is the element's own.
	$effect(() => {
		if (printOnly && !renderAsHtml && textElement) signalPrintReady();
	});

	/** Last height the frame reported, clamped, with the document it measured. */
	let printHeight = NO_MAIL_FRAME_PRINT_HEIGHT;

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
				return;
			}
			if (isMailFrameHeightMessage(event.data)) {
				// Kept with the document it describes: the element is reused when
				// another message is opened, so the srcdoc is what tells them apart.
				printHeight = {
					srcdoc: node.srcdoc,
					height: clampMailFramePrintHeight(event.data.height)
				};
				signalPrintReady();
			}
		}

		/*
		 * On screen the frame is a fixed window onto the mail, with its own
		 * scrollbar. On paper there is no scrolling: whatever the box does not
		 * show is simply not printed, which is how a print of a long message used
		 * to end mid-sentence without saying so. Between these two events the
		 * frame is given the height its own document reported, so the body runs
		 * onto as many sheets as it needs; Chromium paginates the frame once
		 * nothing clips it (app.css lifts the reading path's overflow for print).
		 *
		 * Set on the element rather than through state, because the browser reads
		 * the layout as soon as the last beforeprint handler returns and will not
		 * wait for a framework to flush. Restored afterwards to exactly what was
		 * there, so a print leaves no trace in the pane.
		 */
		let heightBeforePrint: string | null = null;
		function onBeforePrint() {
			const height = printHeightFor(printHeight, node.srcdoc);
			if (height <= 0 || heightBeforePrint !== null) return;
			heightBeforePrint = node.style.height;
			node.style.height = `${height}px`;
		}
		function onAfterPrint() {
			if (heightBeforePrint === null) return;
			node.style.height = heightBeforePrint;
			heightBeforePrint = null;
		}

		window.addEventListener('message', onMessage);
		window.addEventListener('beforeprint', onBeforePrint);
		window.addEventListener('afterprint', onAfterPrint);
		return {
			destroy() {
				window.removeEventListener('message', onMessage);
				window.removeEventListener('beforeprint', onBeforePrint);
				window.removeEventListener('afterprint', onAfterPrint);
				onAfterPrint();
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
		/*
		 * Every path that does not end in an open reports it. A dead click used to
		 * be indistinguishable from a working one for a screen-reader user (the
		 * failure was a console warning nobody sees in a packaged build), which is
		 * how the F1 regression stayed invisible for as long as it did.
		 *
		 * Two outcomes, two tones. A link with nothing to open — an in-message
		 * anchor, a relative href, a scheme the allow-list refuses — is the mail
		 * being what it is, so it is stated politely. Only a link that should have
		 * opened and did not is an error, and an error interrupts: pushToast sends
		 * that tone to the assertive live region.
		 */
		if (!isOpenableMailLink(href)) {
			pushToast($_('detail.linkNotOpenable'), { tone: 'info' });
			return;
		}
		try {
			const { open } = await import('@tauri-apps/plugin-shell');
			await open(href);
		} catch (error) {
			console.warn('[mail] failed to open body link', error);
			pushToast($_('detail.linkOpenError'), { tone: 'error' });
		}
	}
</script>

<!--
	Named region + visually hidden heading: both carry the same label via
	aria-labelledby, so the body is reachable with the screen-reader landmark
	key (D) as well as the heading key (H) after the initial auto-focus.
-->
<section class="flex-1 bg-background p-5" aria-labelledby={headingId}>
	{#if heading.level === 1}
		<h2 id={headingId} class="sr-only">{$_('detail.bodyHeading')}</h2>
	{:else}
		<h3 id={headingId} class="sr-only">{$_('detail.bodyHeading')}</h3>
	{/if}
	{#if renderAsHtml}
		{#if showRemoteBanner && !printOnly}
			<!--
				Opt-in banner for blocked remote images; the two buttons are the only
				way remote content ever loads.

				The text says why the images are held back, not only that they are:
				testers who heard "remote images blocked (1)" could not tell what the
				region was for. Each button name stands on its own for the same
				reason — tabbing reaches the buttons without passing the text, and
				"Always from this sender" did not say what would happen always. The
				second one names the address it will trust, and is left out where
				that trust cannot be given (see canTrustSender).

				A named region, deliberately NOT a live region. It used to be
				`aria-live="polite"` so that opening a message would mention the
				blocked images without stealing focus from the auto-focused body.
				That reasoning holds for deliberately opening a message with Enter,
				but in split mode every arrow key is a full navigation: the reading
				pane reloads per row, and the region read itself out in full —
				label, count and both button names — on top of the row the reader
				had just announced. The banner is still reachable without it: the
				region is named, so the landmark key (D) reaches it, both buttons
				sit in the tab order between the row and the body, Ctrl+Shift+W
				moves focus to the first of them, and the palette loads the images.
				The deliberate open has its mention back by another route: one
				sentence, pushed where the body takes focus, never by this region.

				Pressing either button unmounts this entire region, so neither
				button can be what reports the result — see revealRemoteImages.
			-->
			<div
				class="mb-2 flex flex-wrap items-center gap-2 rounded-md border border-border bg-muted px-3 py-2 text-sm"
				role="region"
				aria-label={$_('detail.remoteImages.regionLabel')}
			>
				<p class="min-w-0 flex-1 basis-64 text-foreground">
					{$_('detail.remoteImages.blocked', { values: { count: remoteImageCount } })}
				</p>
				<div class="ml-auto flex gap-2">
					<Button
						bind:ref={loadButton}
						variant="outline"
						size="sm"
						onclick={() => revealRemoteImages($_('detail.remoteImages.loadedAnnouncement'))}
					>
						{$_('detail.remoteImages.load')}
					</Button>
					{#if canTrustSender}
						<Button variant="ghost" size="sm" disabled={allowInFlight} onclick={trustSender}>
							{$_('detail.remoteImages.alwaysAllow', { values: { email: senderEmail } })}
						</Button>
					{/if}
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
			srcdoc={buildMailFrameSrcdoc(content, { loadRemoteImages, textSize: $textSize })}
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
