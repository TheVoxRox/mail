<script lang="ts">
	import { getContext } from 'svelte';
	import type { MailDetailResponse } from '$lib/types.js';
	import { _, appLocale } from '$lib/i18n/index.js';
	import { Button } from '$lib/components/ui/button/index.js';
	import { Surface } from '$lib/components/ui/surface/index.js';
	import { formatFullDateTime } from '$lib/formatters.js';
	import {
		MESSAGE_HEADING_CONTEXT_KEY,
		type MessageHeadingContext
	} from '$lib/components/message-detail/messageHeadingContext.js';

	type Props = {
		detail: MailDetailResponse;
		onBack: () => void;
	};

	let { detail, onBack }: Props = $props();

	/*
	 * `+layout.svelte` passes the heading level and back-button visibility
	 * via Svelte context — in off mode (standalone detail route, no split)
	 * the subject is `<h1>` and the back button is replaced by a breadcrumb
	 * in the top bar. The default fallback (level=2 + back button) keeps
	 * the component functional outside the mail route (e.g. in an isolated
	 * story / dev preview) without throwing.
	 */
	const heading: MessageHeadingContext = getContext<MessageHeadingContext>(
		MESSAGE_HEADING_CONTEXT_KEY
	) ?? { level: 2, showBackButton: true };
</script>

<div class="border-b border-border bg-background px-5 py-4">
	<Surface variant="subtle" padding="default" class="min-w-0">
		{#if heading.showBackButton}
			<div class="mb-3">
				<Button variant="ghost" size="sm" class="-ml-2" onclick={onBack}>
					{$_('detail.actionBack')}
				</Button>
			</div>
		{/if}
		{#if heading.level === 1}
			<h1 class="text-lg font-semibold leading-tight">
				{detail.subject || $_('messages.noSubject')}
			</h1>
		{:else}
			<h2 class="text-lg font-semibold leading-tight">
				{detail.subject || $_('messages.noSubject')}
			</h2>
		{/if}
		<div class="mt-3 grid gap-1.5 text-sm text-muted-foreground">
			<p>
				<span class="mr-1.5 font-medium text-foreground">{$_('detail.from')}</span>
				{detail.sender}
			</p>
			{#if detail.recipientsTo}
				<p>
					<span class="mr-1.5 font-medium text-foreground">{$_('detail.to')}</span>
					{detail.recipientsTo}
				</p>
			{/if}
			{#if detail.recipientsCc}
				<p>
					<span class="mr-1.5 font-medium text-foreground">{$_('detail.cc')}</span>
					{detail.recipientsCc}
				</p>
			{/if}
			<p>
				{formatFullDateTime(detail.receivedAt, $appLocale ?? 'cs')}
			</p>
		</div>
	</Surface>
</div>
