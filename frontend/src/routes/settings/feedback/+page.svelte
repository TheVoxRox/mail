<script lang="ts">
	import FeedbackForm from '#lib/components/settings/FeedbackForm.svelte';
	import { PageShell } from '#lib/components/ui/page-shell/index.js';
	import { _ } from '#lib/i18n/index.js';
	import { FEEDBACK_ADDRESS } from '#lib/feedback/message.js';
</script>

<svelte:head>
	<title>{$_('settings.feedback.pageTitle')}</title>
</svelte:head>

<PageShell
	title={$_('settings.nav.feedback')}
	description={$_('settings.feedback.intro', { values: { address: FEEDBACK_ADDRESS } })}
>
	<FeedbackForm />
</PageShell>
