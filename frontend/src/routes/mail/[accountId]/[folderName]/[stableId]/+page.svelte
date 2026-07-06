<script lang="ts">
	import MessageDetail from '$lib/components/MessageDetail.svelte';
	import { selectMessage, selectedMessage } from '$lib/stores/selectedMessage.js';
	import { _ } from '$lib/i18n/index.js';

	let { data } = $props();

	$effect(() => {
		void selectMessage(data.stableId);
	});

	/*
	 * Loading-aware window title (the #93 pattern): the generic "Message"
	 * placeholder holds only until the detail for *this* route arrives, then
	 * the subject takes over — the window title is the most reliable channel
	 * for a screen-reader user to identify the opened message. The stableId
	 * guard keeps a previously selected message's subject from leaking in.
	 */
	const detailSubject = $derived(
		$selectedMessage?.stableId === data.stableId ? ($selectedMessage.detail?.subject ?? null) : null
	);
</script>

<svelte:head>
	<title>
		{detailSubject !== null
			? $_('detail.pageTitleWithSubject', {
					values: { subject: detailSubject || $_('messages.noSubject') }
				})
			: $_('detail.pageTitle')}
	</title>
</svelte:head>

<div class="flex flex-1 flex-col overflow-hidden">
	<MessageDetail />
</div>
