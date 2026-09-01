<script lang="ts">
	import { page } from '$app/state';
	import { resolve } from '$app/paths';
	import { goto } from '$app/navigation';
	import { get } from 'svelte/store';
	import { accountsState, activeAccount } from '$lib/stores/accounts.js';
	import { folders, foldersState, refreshFolders } from '$lib/stores/folders.js';
	import { failingSyncAccounts } from '$lib/stores/syncHealth.js';
	import { abandonManualSync, beginManualSync, syncingAccountIds } from '$lib/stores/manualSync.js';
	import { announcePolite, pushToast } from '$lib/stores/toasts.js';
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
		const pathname = page.url.pathname;
		return pathname.startsWith(
			resolve('/mail/[accountId]/[folderName]', {
				accountId: String(acc.id),
				folderName: encodeURIComponent(folder.folderRef)
			})
		);
	}

	/*
	 * "Is a sync running" is not this component's to decide: the endpoint
	 * returns 202 and the pass finishes minutes later, so the answer arrives on
	 * the notification stream as `sync_cycle_completed`. The store holds it;
	 * the button only reads it.
	 */
	const syncing = $derived(
		$activeAccount != null && $syncingAccountIds.includes($activeAccount.id)
	);

	async function handleSync() {
		const acc = get(activeAccount);
		if (!acc || syncing) return;
		beginManualSync(acc.id);
		try {
			await triggerAccountSync(acc.id);
		} catch (err) {
			// Nothing was accepted, so no pass is coming to report the end and
			// the button would otherwise wait out its whole timeout.
			abandonManualSync(acc.id);
			pushToast(toErrorMessage(err), { tone: 'error' });
			return;
		}
		/*
		 * Announce on the 202, not after the folder refresh below. "Started"
		 * becomes true the moment the trigger is accepted, and waiting for the
		 * refresh put the message ~3.7 s late (measured) for a reason that only
		 * gets worse on a big mailbox: the refresh queues behind the IMAP
		 * connection lock that the sync it just triggered is now holding, so the
		 * announcement was blocked by the very thing it announces.
		 */
		announcePolite($_('nav.syncStarted'));
		try {
			await refreshFolders(acc.id);
		} catch (err) {
			// A refresh that fails does not make "started" untrue, and the pass
			// keeps running — so this reports itself without ending the wait.
			pushToast(toErrorMessage(err), { tone: 'error' });
		}
	}

	function openCompose() {
		void goto(resolve('/compose'));
	}

	const accountSettingsHref = resolve('/settings/accounts');
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
	<!--
		A standing sync failure has to be visible where the user notices the
		symptom — mail not arriving — not only in Settings → Accounts. It sits next
		to the Sync button because that is the control it is about. A real button,
		never a decorative icon: the same mouse-only trap the expand toggle fell
		into (#221).

		Amber, not red: a sync that stopped working is a degraded state the user
		can act on, not a destructive one. (The red text token exists too —
		--destructive-foreground — this is a semantic choice, not a contrast
		workaround.)
	-->
	{#if $failingSyncAccounts.length > 0}
		<!--
			A link: it takes the user to the accounts page, which exists whether or
			not a sync is failing. That it also reports a state does not make it an
			action — nothing happens here but the navigation.
		-->
		<Button
			variant="ghost"
			size="lg"
			href={accountSettingsHref}
			class="mb-1 w-full justify-start text-warning-foreground hover:text-warning-foreground"
		>
			<Icon name="exclamation-triangle" />
			<span class="flex-1 truncate text-left">
				{$failingSyncAccounts.length === 1
					? $_('nav.syncProblem', { values: { account: $failingSyncAccounts[0].email } })
					: $_('nav.syncProblemMultiple', { values: { count: $failingSyncAccounts.length } })}
			</span>
		</Button>
	{/if}

	<Button
		variant="ghost"
		size="lg"
		onclick={handleSync}
		disabled={syncing || !$activeAccount}
		aria-busy={syncing ? 'true' : 'false'}
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
							<!-- Folders are navigation targets, so render real links (href),
							     consistent with the app rail — not buttons inside a <nav>. -->
							<SidebarNavItem href={folderHref(folder)} {active}>
								{folderLabel(folder, $_)}

								{#snippet badge()}
									{#if folder.unreadCount > 0}
										<span
											class="min-w-5 rounded-full bg-primary/10 px-1.5 py-0.5 text-center text-caption font-semibold text-primary"
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
