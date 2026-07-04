<script lang="ts">
	import { page } from '$app/stores';
	import { resolve } from '$app/paths';
	import { goto } from '$app/navigation';
	import { get } from 'svelte/store';
	import { accountsState, activeAccount } from '$lib/stores/accounts.js';
	import { folders, foldersState, refreshFolders } from '$lib/stores/folders.js';
	import { triggerAccountSync } from '$lib/api/mailAction.js';
	import { toErrorMessage } from '$lib/api/errors.js';
	import SearchBar from '$lib/components/SearchBar.svelte';
	import AccountSwitcher from '$lib/components/AccountSwitcher.svelte';
	import Icon from '$lib/components/Icon.svelte';
	import { Button } from '$lib/components/ui/button/index.js';
	import { SidebarNavItem } from '$lib/components/ui/sidebar-nav-item/index.js';
	import { SidebarSection } from '$lib/components/ui/sidebar-section/index.js';
	import { SidebarShell } from '$lib/components/ui/sidebar-shell/index.js';
	import { Surface } from '$lib/components/ui/surface/index.js';
	import { _ } from '$lib/i18n/index.js';
	import { folderLabel } from '$lib/mail/folderLabel.js';
	import type { FolderResponse } from '$lib/types.js';

	function folderHref(folder: FolderResponse): string {
		const acc = get(activeAccount);
		if (!acc) return resolve('/');
		return resolve('/mail/[accountId]/[folderName]', {
			accountId: String(acc.id),
			folderName: encodeURIComponent(folder.folderRef)
		});
	}

	function isActive(folder: FolderResponse): boolean {
		const acc = get(activeAccount);
		if (!acc) return false;
		const pathname = $page.url.pathname;
		return pathname.startsWith(
			resolve('/mail/[accountId]/[folderName]', {
				accountId: String(acc.id),
				folderName: encodeURIComponent(folder.folderRef)
			})
		);
	}

	let syncing = $state(false);

	async function handleSync() {
		const acc = get(activeAccount);
		if (!acc || syncing) return;
		syncing = true;
		try {
			await triggerAccountSync(acc.id);
			await refreshFolders(acc.id);
		} finally {
			syncing = false;
		}
	}

	function openCompose() {
		void goto(resolve('/compose'));
	}
</script>

{#snippet header()}
	<h2 class="text-base font-semibold">{$_('workspace.mail')}</h2>

	{#if $accountsState.status === 'ready' && $accountsState.accounts.length > 0}
		<div class="mt-3">
			<AccountSwitcher />
		</div>
	{/if}

	<Button size="lg" class="mt-3 w-full justify-start shadow-sm" onclick={openCompose}>
		<Icon name="pencil-square" />
		<span class="flex-1 text-left">{$_('nav.compose')}</span>
		<kbd class="text-caption font-medium text-primary-foreground/80"> Ctrl+N </kbd>
	</Button>

	{#if $activeAccount}
		<div class="mt-3">
			<SearchBar />
		</div>
	{/if}
{/snippet}

{#snippet footer()}
	<Button
		variant="ghost"
		size="lg"
		onclick={handleSync}
		disabled={syncing || !$activeAccount}
		class="w-full justify-start"
	>
		<Icon name="arrow-path" />
		<span>{syncing ? $_('nav.syncing') : $_('nav.sync')}</span>
	</Button>
{/snippet}

<!--
	region root + inner nav: the header hosts a search landmark, which must not
	nest inside <nav>. The folder list is the actual navigation, so it gets its
	own <nav> (contentNavLabel) as a sibling of the search; the SidebarSection
	below must not double it with a region landmark of the same name.
-->
<SidebarShell
	label={$_('workspace.mailSidebarLabel')}
	contentNavLabel={$_('nav.foldersSection')}
	{header}
	{footer}
	headerClass="px-4 py-4"
	contentClass="p-2.5"
>
	<SidebarSection id="mail-sidebar-folders" label={$_('nav.foldersSection')}>
		{#if !$activeAccount}
			<Surface variant="subtle" padding="sm" class="border-sidebar-border bg-background/80">
				<p class="text-sm text-muted-foreground">{$_('nav.noActiveAccount')}</p>
			</Surface>
		{:else}
			<ul role="list" class="space-y-1">
				{#if $foldersState.status === 'loading'}
					<li class="px-3 py-2 text-xs text-muted-foreground">{$_('nav.foldersLoading')}</li>
				{:else if $foldersState.status === 'error'}
					<li>
						<Surface variant="danger" padding="sm">
							<p class="text-sm" role="alert">{toErrorMessage($foldersState.error)}</p>
						</Surface>
					</li>
				{:else}
					{#each $folders as folder (folder.folderRef)}
						{@const active = isActive(folder)}
						<li>
							<SidebarNavItem onclick={() => goto(folderHref(folder))} {active}>
								{folderLabel(folder, $_)}

								{#snippet badge()}
									{#if folder.unreadCount > 0}
										<span
											class="min-w-5 rounded-full bg-primary/12 px-1.5 py-0.5 text-center text-caption font-semibold text-primary"
											aria-label={$_('nav.unreadBadge', { values: { count: folder.unreadCount } })}
										>
											{folder.unreadCount}
										</span>
									{/if}
								{/snippet}
							</SidebarNavItem>
						</li>
					{/each}
				{/if}
			</ul>
		{/if}
	</SidebarSection>
</SidebarShell>
