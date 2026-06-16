<script lang="ts">
	import { onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import { resolve } from '$app/paths';
	import { loadAccounts } from '$lib/stores/accounts.js';
	import { Button } from '$lib/components/ui/button/index.js';
	import { _ } from '$lib/i18n/index.js';
	import { toErrorMessage } from '$lib/api/errors.js';

	let countdown = $state(3);
	let errorMessage = $state<string | null>(null);

	onMount(() => {
		void (async () => {
			try {
				await loadAccounts();
			} catch (err) {
				errorMessage = toErrorMessage(err);
			}
			const timer = setInterval(() => {
				countdown -= 1;
				if (countdown <= 0) {
					clearInterval(timer);
					void goto(resolve('/settings/accounts'));
				}
			}, 1000);
		})();
	});
</script>

<svelte:head>
	<title>{$_('auth.pageTitle')}</title>
</svelte:head>

<div class="flex flex-1 items-center justify-center p-8">
	<div
		class="max-w-md rounded-md border border-border bg-card p-6 text-center text-card-foreground"
	>
		<h1 class="text-title font-semibold">{$_('auth.heading')}</h1>
		<p class="mt-2 text-sm text-muted-foreground" aria-live="polite">
			{$_('auth.description', { values: { countdown } })}
		</p>
		{#if errorMessage}
			<p class="mt-2 text-sm text-destructive" role="alert">{errorMessage}</p>
		{/if}
		<Button href={resolve('/settings/accounts')} class="mt-4">
			{$_('auth.goToAccounts')}
		</Button>
	</div>
</div>
