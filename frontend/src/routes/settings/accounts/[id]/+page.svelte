<script lang="ts">
	import { goto } from '$app/navigation';
	import { resolve } from '$app/paths';
	import AccountForm from '$lib/components/AccountForm.svelte';
	import { deleteAccount, getAccount, updateAccount } from '$lib/api/accounts.js';
	import { toErrorMessage } from '$lib/api/errors.js';
	import { getProvider } from '$lib/api/providers.js';
	import { loadAccounts } from '$lib/stores/accounts.js';
	import { Button } from '$lib/components/ui/button/index.js';
	import { PageShell } from '$lib/components/ui/page-shell/index.js';
	import { StateMessage } from '$lib/components/ui/state-message/index.js';
	import { Surface } from '$lib/components/ui/surface/index.js';
	import { _ } from '$lib/i18n/index.js';
	import { confirmAction } from '$lib/stores/confirmDialog.js';
	import type {
		AccountCreateRequest,
		AccountResponse,
		AccountUpdateRequest,
		MailProviderResponse
	} from '$lib/types.js';
	import { onMount } from 'svelte';

	let { data } = $props();

	let account = $state<AccountResponse | null>(null);
	let loadError = $state<string | null>(null);
	let deleting = $state(false);
	let provider = $state<MailProviderResponse | null>(null);

	onMount(async () => {
		try {
			account = await getAccount(data.id);
			if (account.providerId != null) {
				try {
					provider = await getProvider(account.providerId);
				} catch {
					provider = null;
				}
			}
		} catch (err) {
			loadError = toErrorMessage(err);
		}
	});

	async function handleSubmit(payload: AccountCreateRequest | AccountUpdateRequest) {
		await updateAccount(data.id, payload as AccountUpdateRequest);
		await loadAccounts();
		await goto(resolve('/settings/accounts'));
	}

	async function handleDelete() {
		if (!account) return;
		const confirmed = await confirmAction({
			title: $_('accounts.deleteConfirmTitle'),
			description: $_('accounts.deleteConfirm', { values: { name: account.accountName } }),
			confirmLabel: $_('common.delete'),
			cancelLabel: $_('common.cancel'),
			tone: 'destructive'
		});
		if (!confirmed) return;
		deleting = true;
		try {
			await deleteAccount(data.id);
			await loadAccounts();
			await goto(resolve('/settings/accounts'));
		} finally {
			deleting = false;
		}
	}
</script>

<svelte:head>
	<title>{$_('accounts.editPageTitle', { values: { name: account?.accountName ?? '' } })}</title>
</svelte:head>

<PageShell
	title={account
		? $_('accounts.headingEditWithName', { values: { name: account.accountName } })
		: $_('accounts.headingEdit')}
	description={$_('settings.accounts.description')}
	contentClass="max-w-4xl"
>
	{#snippet actions()}
		<a
			href={resolve('/settings/accounts')}
			class="text-sm text-muted-foreground hover:text-foreground hover:underline"
		>
			{$_('accounts.backToList')}
		</a>
	{/snippet}

	{#if loadError}
		<Surface variant="danger" padding="sm" class="max-w-2xl">
			<p role="alert" class="text-sm">{loadError}</p>
		</Surface>
	{:else if !account}
		<StateMessage padding="none">{$_('common.loading')}</StateMessage>
	{:else}
		<Surface as="section" class="max-w-2xl">
			{#if account.providerId == null}
				<p
					class="mb-3 inline-flex items-center gap-1 rounded-full bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary"
					aria-label={$_('accounts.customAccountBadge')}
				>
					<span aria-hidden="true">★</span>
					{account.providerName ?? $_('accounts.customAccountBadge')}
				</p>
			{/if}
			<AccountForm
				mode="edit"
				initial={account}
				onSubmit={handleSubmit}
				submitLabel={$_('accounts.saveSubmitLabel')}
			/>
			{#if provider}
				<dl
					class="mt-4 space-y-1 border-t border-border pt-4 text-xs text-muted-foreground"
					aria-label={$_('accounts.providerTemplate.heading')}
				>
					<div class="flex gap-2">
						<dt class="font-medium text-foreground">{$_('accounts.providerTemplate.heading')}</dt>
						<dd>{provider.name}</dd>
					</div>
					<div class="flex gap-2">
						<dt class="font-medium">{$_('accounts.providerTemplate.domains')}</dt>
						<dd>{provider.domains || '—'}</dd>
					</div>
				</dl>
			{/if}
			<div class="mt-4 border-t border-border pt-4">
				<Button variant="destructive" size="sm" onclick={handleDelete} disabled={deleting}>
					{deleting ? $_('accounts.deleting') : $_('accounts.deleteAccount')}
				</Button>
			</div>
		</Surface>
	{/if}
</PageShell>
