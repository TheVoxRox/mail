<script lang="ts">
	import { _ } from '$lib/i18n/index.js';
	import { sanitizeMailHtml } from '$lib/mail/content-sanitizer.js';
	import { messageBodyView } from '$lib/stores/uiLayout.js';

	type Props = {
		content: string;
		looksLikeHtml: boolean;
	};

	let { content, looksLikeHtml }: Props = $props();

	const renderAsHtml = $derived(looksLikeHtml && $messageBodyView === 'html');

	function hardenMailFrame(node: HTMLIFrameElement) {
		node.setAttribute(
			'csp',
			"default-src 'none'; img-src data:; base-uri 'none'; form-action 'none'"
		);
	}
</script>

<div class="flex-1 bg-background p-5">
	{#if renderAsHtml}
		<iframe
			use:hardenMailFrame
			title={$_('detail.iframeTitle')}
			sandbox=""
			srcdoc={sanitizeMailHtml(content)}
			class="h-[65vh] min-h-96 w-full rounded-md border border-border bg-background"
		></iframe>
	{:else}
		<div class="max-w-3xl whitespace-pre-wrap text-sm leading-relaxed text-foreground">
			{content}
		</div>
	{/if}
</div>
