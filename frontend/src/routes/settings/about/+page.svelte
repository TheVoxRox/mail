<script lang="ts">
	import AboutSettings from '$lib/components/settings/AboutSettings.svelte';
	import { PageShell } from '$lib/components/ui/page-shell/index.js';
	import { _ } from '$lib/i18n/index.js';
</script>

<svelte:head>
	<title>{$_('app.title')} – {$_('settings.nav.about')}</title>
</svelte:head>

<PageShell title={$_('settings.nav.about')} description={$_('settings.about.intro')}>
	<AboutSettings />
</PageShell>
