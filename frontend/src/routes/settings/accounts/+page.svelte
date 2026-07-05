<script lang="ts">
	import { resolve } from '$app/paths';
	import { goto } from '$app/navigation';
	import { accountsState, loadAccounts } from '$lib/stores/accounts.js';
	import { deleteAccount } from '$lib/api/accounts.js';
	import { toErrorMessage } from '$lib/api/errors.js';
	import { startOAuthLogin } from '$lib/api/googleAuth.js';
	import Icon from '$lib/components/Icon.svelte';
	import { ListRow } from '$lib/components/ui/list-row/index.js';
	import { PageShell } from '$lib/components/ui/page-shell/index.js';
	import { StateMessage } from '$lib/components/ui/state-message/index.js';
	import { Surface } from '$lib/components/ui/surface/index.js';
	import { Button } from '$lib/components/ui/button/index.js';
	import { _ } from '$lib/i18n/index.js';
	import { confirmAction } from '$lib/stores/confirmDialog.js';
	import { pushToast } from '$lib/stores/toasts.js';
	import type { AccountResponse } from '$lib/types.js';

	let deletingId = $state<number | null>(null);
	let reauthingId = $state<number | null>(null);
	let errorMessage = $state('');

	function cleanAccountName(account: AccountResponse): string {
		const stripped = account.accountName
			.replace(/\S+@\S+\.\S+/g, '')
			.replace(/[\s:,\-–—]+$/u, '')
			.replace(/^[\s:,\-–—]+/u, '')
			.trim();
		const normalized = stripped.toLowerCase();
		const email = account.email.toLowerCase();
		const localPart = email.split('@')[0] ?? '';
		return !stripped || normalized === email || normalized === localPart ? '' : stripped;
	}

	function accountTitle(account: AccountResponse): string {
		return cleanAccountName(account) || account.displayName?.trim() || account.email;
	}

	function shouldShowEmail(account: AccountResponse): boolean {
		return accountTitle(account).toLowerCase() !== account.email.toLowerCase();
	}

	function shouldShowProvider(account: AccountResponse): boolean {
		const provider = account.providerName?.trim();
		if (!provider) return true;
		return provider.toLowerCase() !== accountTitle(account).toLowerCase();
	}

	function accountStatusLabel(account: AccountResponse): string {
		if (account.requiresReauth) return $_('accounts.statusRequiresReauth');
		return account.active ? $_('accounts.statusActive') : $_('accounts.statusInactive');
	}

	function accountStatusClass(account: AccountResponse): string {
		if (account.requiresReauth) {
			return 'border-warning/30 bg-warning/10 text-warning-foreground';
		}
		if (!account.active) {
			return 'border-border bg-muted text-muted-foreground';
		}
		return 'border-emerald-500/25 bg-emerald-500/10 text-emerald-700 dark:text-emerald-300';
	}

	async function handleReauth(account: AccountResponse) {
		if (!account.oauth2Provider) return;
		reauthingId = account.id;
		errorMessage = '';
		try {
			await startOAuthLogin(account.oauth2Provider);
		} catch (err) {
			errorMessage = toErrorMessage(err);
		} finally {
			reauthingId = null;
		}
	}

	async function handleDelete(id: number, label: string) {
		const confirmed = await confirmAction({
			title: $_('accounts.deleteConfirmTitle'),
			description: $_('accounts.deleteConfirm', { values: { name: label } }),
			confirmLabel: $_('common.delete'),
			cancelLabel: $_('common.cancel'),
			tone: 'destructive'
		});
		if (!confirmed) return;
		deletingId = id;
		errorMessage = '';
		try {
			await deleteAccount(id);
			await loadAccounts();
			pushToast($_('accounts.deletedToast'), { tone: 'success' });
		} catch (err) {
			errorMessage = toErrorMessage(err);
		} finally {
			deletingId = null;
		}
	}
</script>

<svelte:head>
	<title>{$_('accounts.listPageTitle')}</title>
</svelte:head>

<PageShell
	title={$_('accounts.heading')}
	description={$_('settings.accounts.description')}
	contentClass="max-w-4xl space-y-4"
>
	{#snippet actions()}
		<Button size="sm" onclick={() => goto(resolve('/settings/accounts/new'))}>
			<Icon name="plus" />
			{$_('accounts.addAccount')}
		</Button>
	{/snippet}

	{#if errorMessage}
		<Surface variant="danger" padding="sm">
			<p class="text-sm" role="alert">{errorMessage}</p>
		</Surface>
	{/if}

	<Surface as="section" variant="list" padding="none">
		{#if $accountsState.status === 'loading'}
			<StateMessage>{$_('settings.accounts.loading')}</StateMessage>
		{:else if $accountsState.status === 'error'}
			<StateMessage variant="error">{$accountsState.error.message}</StateMessage>
		{:else if $accountsState.status === 'ready'}
			{#if $accountsState.accounts.length === 0}
				<div class="space-y-3 p-6">
					<div class="space-y-1">
						<h2 class="text-sm font-semibold text-foreground">{$_('accounts.emptyHeading')}</h2>
						<p class="max-w-xl text-sm text-muted-foreground">{$_('accounts.emptyDescription')}</p>
					</div>
					<Button size="sm" onclick={() => goto(resolve('/settings/accounts/new'))}>
						<Icon name="plus" />
						{$_('accounts.addFirstAccount')}
					</Button>
				</div>
			{:else}
				<ul class="divide-y divide-border">
					{#each $accountsState.accounts as account (account.id)}
						<ListRow align="center" class="gap-4">
							<div class="flex min-w-0 items-start gap-3">
								<div
									class="mt-0.5 flex size-9 shrink-0 items-center justify-center rounded-md border border-border bg-muted/45 text-muted-foreground"
								>
									<Icon name="user-circle" size={20} />
								</div>
								<div class="min-w-0 flex-1">
									<div class="flex min-w-0 flex-wrap items-center gap-2">
										<p class="min-w-0 truncate font-medium text-foreground">
											{accountTitle(account)}
										</p>
										<span
											class={`shrink-0 rounded-full border px-2 py-0.5 text-caption font-medium leading-4 ${accountStatusClass(account)}`}
										>
											{accountStatusLabel(account)}
										</span>
									</div>
									{#if shouldShowEmail(account)}
										<p class="mt-0.5 truncate text-xs text-muted-foreground">{account.email}</p>
									{/if}
									{#if shouldShowProvider(account)}
										<p class="mt-1 truncate text-xs text-muted-foreground">
											{account.providerName ?? $_('accounts.noProvider')}
										</p>
									{/if}
								</div>
							</div>
							{#if account.lastError}
								<p class="mt-1 text-xs text-destructive">{account.lastError}</p>
							{/if}

							{#snippet actions()}
								{#if account.requiresReauth && account.oauth2Provider}
									<Button
										variant="default"
										size="xs"
										onclick={() => handleReauth(account)}
										disabled={reauthingId === account.id}
									>
										{reauthingId === account.id
											? $_('accounts.reauthStarting')
											: $_('accounts.reauth')}
									</Button>
								{/if}
								<Button
									variant="outline"
									size="xs"
									onclick={() =>
										goto(resolve('/settings/accounts/[id]', { id: String(account.id) }))}
								>
									{$_('accounts.edit')}
								</Button>
								<Button
									variant="destructive"
									size="xs"
									onclick={() => handleDelete(account.id, account.accountName)}
									disabled={deletingId === account.id}
								>
									{deletingId === account.id ? $_('accounts.deleting') : $_('accounts.delete')}
								</Button>
							{/snippet}
						</ListRow>
					{/each}
				</ul>
			{/if}
		{/if}
	</Surface>
</PageShell>
