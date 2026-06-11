<script lang="ts">
	import ComposeForm from '$lib/components/ComposeForm.svelte';
	import { _ } from '$lib/i18n/index.js';
</script>

<svelte:head>
	<title>{$_('compose.pageTitle')}</title>
</svelte:head>

<ComposeForm />
