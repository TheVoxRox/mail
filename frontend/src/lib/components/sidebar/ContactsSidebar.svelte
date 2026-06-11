<script lang="ts">
	import { goto } from '$app/navigation';
	import { resolve } from '$app/paths';
	import { page } from '$app/stores';
	import { SvelteURLSearchParams } from 'svelte/reactivity';
	import { get } from 'svelte/store';
	import { exportVCard } from '$lib/api/contacts.js';
	import { toErrorMessage } from '$lib/api/errors.js';
	import { activeAccount, accountsState } from '$lib/stores/accounts.js';
	import { pushToast } from '$lib/stores/toasts.js';
	import AccountSwitcher from '$lib/components/AccountSwitcher.svelte';
	import { Input } from '$lib/components/ui/input/index.js';
	import { SidebarNavItem } from '$lib/components/ui/sidebar-nav-item/index.js';
	import { SidebarSection } from '$lib/components/ui/sidebar-section/index.js';
	import { SidebarShell } from '$lib/components/ui/sidebar-shell/index.js';
	import Icon from '$lib/components/Icon.svelte';
	import { _ } from '$lib/i18n/index.js';

	let query = $derived($page.url.searchParams.get('q') ?? '');
	let exporting = $state(false);

	function buildContactsHref(options?: { query?: string; create?: boolean }): string | null {
		const account = get(activeAccount);
		if (!account) return null;

		const params = new SvelteURLSearchParams();
		const nextQuery = options?.query?.trim() ?? '';
		if (nextQuery) params.set('q', nextQuery);
		if (options?.create) params.set('create', '1');

		const queryString = params.toString();
		return `${resolve('/contacts/[accountId]', {
			accountId: String(account.id)
		})}${queryString ? `?${queryString}` : ''}`;
	}

	function handleSearch(event: SubmitEvent) {
		event.preventDefault();
		const href = buildContactsHref({ query });
		if (!href) return;
		void goto(href);
	}

	function openCreate() {
		const href = buildContactsHref({ query, create: true });
		if (!href) return;
		void goto(href);
	}

	function resetContacts() {
		const href = buildContactsHref();
		if (!href) return;
		void goto(href);
	}

	const createActive = $derived($page.url.searchParams.get('create') === '1');

	async function handleExport() {
		const account = get(activeAccount);
		if (!account || exporting) return;

		exporting = true;
		try {
			const { blob, filename } = await exportVCard(account.id);
			const url = URL.createObjectURL(blob);
			const a = document.createElement('a');
			a.href = url;
			a.download = filename;
			document.body.appendChild(a);
			a.click();
			a.remove();
			URL.revokeObjectURL(url);
			pushToast($_('contacts.exportDone'), { tone: 'success' });
		} catch (err) {
			pushToast(toErrorMessage(err), { tone: 'error' });
		} finally {
			exporting = false;
		}
	}
</script>

{#snippet header()}
	<h2 class="text-base font-semibold">{$_('workspace.contacts')}</h2>

	{#if $accountsState.status === 'ready' && $accountsState.accounts.length > 0}
		<div class="mt-3">
			<AccountSwitcher />
		</div>
	{/if}

	{#if $activeAccount}
		<form
			onsubmit={handleSearch}
			class="mt-3"
			role="search"
			aria-label={$_('search.contactsLabel')}
		>
			<label for="contacts-sidebar-search" class="sr-only">{$_('search.contactsLabel')}</label>
			<Input
				id="contacts-sidebar-search"
				type="search"
				bind:value={query}
				placeholder={$_('search.contactsPlaceholder')}
				size="sm"
			/>
		</form>
	{/if}
{/snippet}

<SidebarShell
	label={$_('workspace.contactsSidebarLabel')}
	{header}
	headerClass="px-4 py-4"
	contentClass="p-2.5"
>
	{#if !$activeAccount}
		<div class="rounded-md border border-sidebar-border bg-background/80 p-3">
			<p class="text-sm text-muted-foreground">{$_('contacts.noActiveAccount')}</p>
		</div>
	{:else}
		<SidebarSection id="contacts-sidebar-actions" label={$_('contacts.sidebarActions')}>
			<ul role="list" class="space-y-1">
				<li>
					<SidebarNavItem onclick={openCreate} active={createActive}>
						{#snippet icon()}
							<Icon name="user-circle" />
						{/snippet}

						{$_('contacts.newContact')}

						{#snippet badge()}
							<kbd class="text-[0.68rem] font-medium text-muted-foreground">Ctrl+N</kbd>
						{/snippet}
					</SidebarNavItem>
				</li>
				<li>
					<SidebarNavItem onclick={resetContacts} active={!createActive}>
						{#snippet icon()}
							<Icon name="book-open" />
						{/snippet}

						{$_('contacts.allContacts')}
					</SidebarNavItem>
				</li>
				{#if !createActive}
					<li>
						<SidebarNavItem
							onclick={handleExport}
							disabled={exporting}
							ariaLabel={$_('contacts.exportVCard')}
							ariaBusy={exporting ? 'true' : 'false'}
						>
							{#snippet icon()}
								<Icon name="arrow-down-tray" />
							{/snippet}

							{exporting ? $_('contacts.exporting') : $_('contacts.exportVCard')}
						</SidebarNavItem>
					</li>
				{/if}
			</ul>
		</SidebarSection>
	{/if}
</SidebarShell>
