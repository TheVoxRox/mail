<script lang="ts">
	import MessageDetail from '$lib/components/MessageDetail.svelte';
	import { selectMessage } from '$lib/stores/selectedMessage.js';
	import { _ } from '$lib/i18n/index.js';

	let { data } = $props();

	$effect(() => {
		void selectMessage(data.stableId);
	});
</script>

<svelte:head>
	<title>{$_('detail.pageTitle')}</title>
</svelte:head>

<div class="flex flex-1 flex-col overflow-hidden">
	<MessageDetail />
</div>
