<script lang="ts">
	/**
	 * Status-flag icons (flagged / has attachments / answered) for a message
	 * row, shared by the inbox grid (MessageList) and the search results grid
	 * (SearchResultsGrid). The visible icons mirror the screen-reader text from
	 * `messageStatusLabel`, which is set as the grid cell's aria-label — so the
	 * icons themselves only carry a `title` and are otherwise decorative.
	 */
	import Icon from '$lib/components/Icon.svelte';
	import { _ } from '$lib/i18n/index.js';
	import type { MailSummaryResponse } from '$lib/types.js';

	interface Props {
		message: Pick<MailSummaryResponse, 'flagged' | 'hasAttachments' | 'answered'>;
	}

	let { message }: Props = $props();
</script>

{#if message.flagged}
	<Icon name="star" size={14} title={$_('messages.flaggedTitle')} class="text-yellow-500" />
{/if}
{#if message.hasAttachments}
	<Icon name="paperclip" size={14} title={$_('messages.attachmentsTitle')} />
{/if}
{#if message.answered}
	<Icon name="arrow-uturn-left" size={14} title={$_('messages.answeredTitle')} />
{/if}
