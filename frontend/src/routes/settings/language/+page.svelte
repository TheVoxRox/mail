<script lang="ts">
	import LanguageSettings from '$lib/components/settings/LanguageSettings.svelte';
	import { PageShell } from '$lib/components/ui/page-shell/index.js';
	import { _ } from '$lib/i18n/index.js';
</script>

<svelte:head>
	<title>{$_('settings.language.pageTitle')}</title>
</svelte:head>

<PageShell title={$_('settings.nav.language')} description={$_('settings.language.intro')}>
	<LanguageSettings />
</PageShell>
