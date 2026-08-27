<script lang="ts">
	import { goto, invalidateAll } from '$app/navigation';
	import { resolve } from '$app/paths';
	import { page } from '$app/state';
	import { SvelteURLSearchParams } from 'svelte/reactivity';
	import { exportVCard } from '$lib/api/contacts.js';
	import { toErrorMessage } from '$lib/api/errors.js';
	import { importVCardFiles } from '$lib/contacts/importVCards.js';
	import { saveBlobAsFile } from '$lib/download.js';
	import { activeAccount, accountsState } from '$lib/stores/accounts.js';
	import { contactCounts } from '$lib/stores/contactCounts.js';
	import { pushToast } from '$lib/stores/toasts.js';
	import AccountSwitcher from '$lib/components/AccountSwitcher.svelte';
	import ContactLabelsDialog from '$lib/components/ContactLabelsDialog.svelte';
	import { Button } from '$lib/components/ui/button/index.js';
	import { Input } from '$lib/components/ui/input/index.js';
	import { SidebarNavItem } from '$lib/components/ui/sidebar-nav-item/index.js';
	import { SidebarSection } from '$lib/components/ui/sidebar-section/index.js';
	import { SidebarShell } from '$lib/components/ui/sidebar-shell/index.js';
	import Icon from '$lib/components/Icon.svelte';
	import { _ } from '$lib/i18n/index.js';

	let query = $derived(page.url.searchParams.get('q') ?? '');
	let exporting = $state(false);
	let labelsDialogOpen = $state(false);

	function buildContactsHref(options?: { query?: string; create?: boolean }): string {
		const params = new SvelteURLSearchParams();
		const nextQuery = options?.query?.trim() ?? '';
		if (nextQuery) params.set('q', nextQuery);
		if (options?.create) params.set('create', '1');

		const queryString = params.toString();
		return `${resolve('/contacts')}${queryString ? `?${queryString}` : ''}`;
	}

	function handleSearch(event: SubmitEvent) {
		event.preventDefault();
		void goto(buildContactsHref({ query }));
	}

	function openCreate() {
		const href = buildContactsHref({ query, create: true });
		if (!href) return;
		void goto(href);
	}

	const createActive = $derived(page.url.searchParams.get('create') === '1');
	const currentLabelId = $derived(Number(page.url.searchParams.get('labelId')) || null);

	/**
	 * Sidebar view links deliberately drop `q` (and sort): like a mail folder
	 * click, choosing a view starts fresh instead of carrying the search over.
	 */
	function viewHref(labelId?: number): string {
		const params = new SvelteURLSearchParams();
		if (labelId != null) params.set('labelId', String(labelId));
		const queryString = params.toString();
		return `${resolve('/contacts')}${queryString ? `?${queryString}` : ''}`;
	}

	// The counts endpoint is the single source for the sidebar: it lists every
	// label, including the ones no contact carries yet, already
	// ordered by name.
	const labelItems = $derived($contactCounts?.labels ?? []);

	async function handleExport() {
		if (exporting) return;

		exporting = true;
		try {
			const { blob, filename } = await exportVCard();
			saveBlobAsFile(blob, filename);
			pushToast($_('contacts.exportDone'), { tone: 'success' });
		} catch (err) {
			pushToast(toErrorMessage(err), { tone: 'error' });
		} finally {
			exporting = false;
		}
	}

	// Keyboard/screen-reader path for the vCard import (the contacts page also
	// accepts drag-and-drop): a visible button proxies a hidden file input.
	let importing = $state(false);
	let importInputEl = $state<HTMLInputElement | null>(null);

	async function handleImportFiles(event: Event) {
		const input = event.currentTarget as HTMLInputElement;
		const files = Array.from(input.files ?? []);
		// Reset so picking the same file again re-fires the change event.
		input.value = '';
		if (files.length === 0 || importing) return;

		importing = true;
		try {
			const imported = await importVCardFiles(files, $_);
			// The page's list load is driven by its `data` prop — re-running the
			// route load produces a fresh object and re-triggers the list effect.
			if (imported) await invalidateAll();
		} finally {
			importing = false;
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
		<Button size="lg" class="mt-3 w-full justify-start shadow-sm" onclick={openCreate}>
			<Icon name="plus" />
			<span class="flex-1 text-left">{$_('contacts.newContact')}</span>
			<kbd class="text-caption font-medium text-primary-foreground/80">Ctrl+N</kbd>
		</Button>

		<form
			onsubmit={handleSearch}
			class="mt-3"
			role="search"
			aria-label={$_('search.contactsLandmark')}
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

{#snippet countBadge(count: number, showZero: boolean)}
	{#if showZero || count > 0}
		<span
			class="min-w-5 rounded-full bg-primary/10 px-1.5 py-0.5 text-center text-caption font-semibold text-primary"
			aria-label={$_('contacts.totalCount', { values: { count } })}
		>
			{count}
		</span>
	{/if}
{/snippet}

<!--
	region root: the header hosts a search landmark, so the view links get their
	own <nav> as its sibling (mail-pane pattern). The import/export actions stay
	outside the nav — they are buttons, not navigation.
-->
<SidebarShell
	label={$_('workspace.contactsSidebarLabel')}
	{header}
	headerClass="px-4 py-4"
	contentClass="p-2.5"
>
	<nav aria-label={$_('contacts.viewsNav')}>
		<!--
				The unfiltered view leads the label list instead of standing above it
				on its own: it is the "no label" end of the same filter, so one group
				reads as one switch — all contacts, then each label. Its old name
				("Kontakty") repeated the rail entry and the pane heading without
				saying how it differed; the current one names what the link does.
			-->
		<SidebarSection id="contacts-sidebar-labels" label={$_('contacts.labelsSection')}>
			<ul role="list" class="space-y-1">
				<li>
					<SidebarNavItem href={viewHref()} active={!createActive && currentLabelId == null}>
						{#snippet icon()}
							<Icon name="book-open" />
						{/snippet}

						{$_('contacts.allContacts')}

						{#snippet badge()}
							<!-- Total 0 renders (an empty address book is meaningful);
								     no badge means the counts have not loaded. -->
							{@render countBadge($contactCounts?.total ?? 0, $contactCounts != null)}
						{/snippet}
					</SidebarNavItem>
				</li>

				{#each labelItems as item (item.id)}
					<li>
						<SidebarNavItem
							href={viewHref(item.id)}
							active={!createActive && currentLabelId === item.id}
						>
							{#snippet icon()}
								<Icon name="tag" />
							{/snippet}

							{item.name}

							{#snippet badge()}
								<!-- A label with no contacts still renders its 0: unlike the
									     unfiltered view it can be empty simply because it is new,
									     and a missing badge would read as "not loaded". -->
								{@render countBadge(item.contacts, true)}
							{/snippet}
						</SidebarNavItem>
					</li>
				{/each}
			</ul>

			{#if labelItems.length === 0}
				<p class="px-2 py-1 text-caption text-muted-foreground">{$_('contacts.noLabelsYet')}</p>
			{/if}
		</SidebarSection>
	</nav>

	<SidebarSection id="contacts-sidebar-actions" label={$_('contacts.sidebarActions')} class="mt-4">
		<ul role="list" class="space-y-1">
			<li>
				<!--
						A button, so it belongs here rather than in the views <nav> above —
						same reason the import/export actions do. Unlike them it stays
						available while the create form is open: the form's own empty-labels
						hint points at this action, and a user filling in their first
						contact is exactly who needs to make the first label.
					-->
				<SidebarNavItem onclick={() => (labelsDialogOpen = true)}>
					{#snippet icon()}
						<Icon name="pencil-square" />
					{/snippet}

					{$_('contacts.manageLabels')}
				</SidebarNavItem>
			</li>
		</ul>
	</SidebarSection>

	{#if !createActive}
		<SidebarSection
			id="contacts-sidebar-import-export"
			label={$_('contacts.sidebarImportExport')}
			class="mt-4"
		>
			<ul role="list" class="space-y-1">
				<li>
					<SidebarNavItem
						onclick={() => importInputEl?.click()}
						disabled={importing}
						ariaLabel={$_('contacts.importVCard')}
						ariaBusy={importing ? 'true' : 'false'}
					>
						{#snippet icon()}
							<Icon name="arrow-up-tray" />
						{/snippet}

						{importing ? $_('contacts.importing') : $_('contacts.importVCard')}
					</SidebarNavItem>
					<input
						bind:this={importInputEl}
						type="file"
						accept=".vcf,text/vcard,text/x-vcard"
						multiple
						hidden
						onchange={handleImportFiles}
					/>
				</li>
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
			</ul>
		</SidebarSection>
	{/if}
</SidebarShell>

<ContactLabelsDialog
	open={labelsDialogOpen}
	onOpenChange={(next) => (labelsDialogOpen = next)}
	onChanged={() => invalidateAll()}
/>
