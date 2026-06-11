<script lang="ts">
	import { goto } from '$app/navigation';
	import { resolve } from '$app/paths';
	import { accountsState, activeAccount, setActiveAccount } from '$lib/stores/accounts.js';
	import { accountOptionLabel } from '$lib/accounts/accountLabel.js';
	import Icon from '$lib/components/Icon.svelte';
	import { Select } from '$lib/components/ui/select/index.js';
	import { _ } from '$lib/i18n/index.js';

	const accounts = $derived($accountsState.status === 'ready' ? $accountsState.accounts : []);
	const current = $derived($activeAccount);
	const currentValue = $derived(current ? String(current.id) : '');

	async function selectAccountById(id: number): Promise<void> {
		setActiveAccount(id);
		await goto(resolve('/'), { replaceState: true });
	}

	function handleChange(event: Event): void {
		const value = (event.currentTarget as HTMLSelectElement).value;
		const id = Number(value);
		if (!Number.isFinite(id) || id === current?.id) return;
		void selectAccountById(id);
	}
</script>

{#if accounts.length > 0}
	<div class="relative">
		<label for="sidebar-account-switcher" class="sr-only">
			{$_('nav.switchAccountLabel')}
		</label>
		<Icon
			name="user-circle"
			size={14}
			class="pointer-events-none absolute left-2 top-1/2 -translate-y-1/2 text-muted-foreground"
		/>
		<Select
			id="sidebar-account-switcher"
			value={currentValue}
			onchange={handleChange}
			class="block w-full appearance-none rounded-md border border-input bg-background py-1.5 pl-7 pr-7 text-xs text-foreground shadow-xs hover:bg-muted focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-ring"
		>
			{#if !current}
				<option value="" disabled>{$_('nav.switchAccount')}</option>
			{/if}
			{#each accounts as account (account.id)}
				<option value={String(account.id)}>
					{accountOptionLabel(account.accountName, account.email)}
				</option>
			{/each}
		</Select>
		<span
			aria-hidden="true"
			class="pointer-events-none absolute right-2 top-1/2 -translate-y-1/2 text-muted-foreground"
		>
			▾
		</span>
	</div>
{/if}
