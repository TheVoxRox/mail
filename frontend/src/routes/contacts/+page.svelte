<script lang="ts">
	import { goto } from '$app/navigation';
	import { resolve } from '$app/paths';
	import { accountsState, resolvedActiveAccountId } from '$lib/stores/accounts.js';
	import { Button } from '$lib/components/ui/button/index.js';
	import { _ } from '$lib/i18n/index.js';

	let ready = $state(false);
	let redirecting = $state(false);

	$effect(() => {
		if ($accountsState.status !== 'ready') {
			ready = false;
			redirecting = false;
			return;
		}

		const accountId = $resolvedActiveAccountId;
		if (accountId != null) {
			if (redirecting) return;
			redirecting = true;
			void goto(resolve('/contacts/[accountId]', { accountId: String(accountId) }), {
				replaceState: true
			});
			return;
		}

		redirecting = false;
		ready = true;
	});
</script>

<svelte:head>
	<title>{$_('contacts.pageTitle')}</title>
</svelte:head>

<div class="flex flex-1 items-center justify-center p-6">
	{#if ready}
		<div
			class="max-w-md rounded-md border border-border bg-card p-6 text-center text-card-foreground"
		>
			<h1 class="text-[0.95rem] font-semibold">{$_('contacts.noAccountHeading')}</h1>
			<p class="mt-2 text-sm text-muted-foreground">
				{#if $accountsState.status === 'ready' && $accountsState.accounts.length === 0}
					{$_('accounts.none')}
				{:else}
					{$_('contacts.noActiveAccount')}
				{/if}
			</p>
			<Button autofocus class="mt-4" onclick={() => goto(resolve('/settings/accounts/new'))}>
				{$_('accounts.addAccount')}
			</Button>
		</div>
	{:else}
		<span class="text-sm text-muted-foreground" role="status">{$_('common.loading')}</span>
	{/if}
</div>
