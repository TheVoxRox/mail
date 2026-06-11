<script lang="ts">
	import AppearanceSettings from '$lib/components/settings/AppearanceSettings.svelte';
	import { PageShell } from '$lib/components/ui/page-shell/index.js';
	import { _ } from '$lib/i18n/index.js';
</script>

<svelte:head>
	<title>{$_('app.title')} – {$_('settings.nav.appearance')}</title>
</svelte:head>

<PageShell title={$_('settings.nav.appearance')} description={$_('settings.appearance.intro')}>
	<AppearanceSettings />
</PageShell>
