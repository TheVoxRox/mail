<script lang="ts">
	import { goto } from '$app/navigation';
	import { resolve } from '$app/paths';
	import { SvelteURLSearchParams } from 'svelte/reactivity';
	import {
		createContact,
		getContact,
		listContacts,
		updateContact,
		type ContactSort
	} from '$lib/api/contacts.js';
	import { toError } from '$lib/api/errors.js';
	import { accountsState, setActiveAccount } from '$lib/stores/accounts.js';
	import { refreshContactCounts } from '$lib/stores/contactCounts.js';
	import { contactSortPreference } from '$lib/stores/contactSort.js';
	import { getClientConfigSnapshot } from '$lib/stores/clientConfig.js';
	import { _ } from '$lib/i18n/index.js';
	import { announcePolite, pushToast } from '$lib/stores/toasts.js';
	import ContactList from '$lib/components/ContactList.svelte';
	import ContactForm from '$lib/components/ContactForm.svelte';
	import { dragHasFiles, importVCardFiles } from '$lib/contacts/importVCards.js';
	import { PageShell } from '$lib/components/ui/page-shell/index.js';
	import { StateMessage } from '$lib/components/ui/state-message/index.js';
	import { Surface } from '$lib/components/ui/surface/index.js';
	import type {
		ContactCreateRequest,
		ContactResponse,
		EmailLabel,
		PagedResponse
	} from '$lib/types.js';

	let { data } = $props();

	type ListState =
		| { status: 'idle' }
		| { status: 'loading' }
		| { status: 'ready'; page: PagedResponse<ContactResponse> }
		| { status: 'error'; error: Error };

	type EditState =
		| { status: 'idle' }
		| { status: 'loading' }
		| { status: 'ready'; contact: ContactResponse }
		| { status: 'error'; error: Error };

	let listState = $state<ListState>({ status: 'idle' });
	let editState = $state<EditState>({ status: 'idle' });
	let loadedEditId: number | null = null;
	let pageNumber = $state(0);
	let lastContext = $state('');
	// Set by pagination handlers and list-context switches (search, filter,
	// account change) so the next successful load announces the result to
	// screen-reader users (matches the Messages list behaviour). Plain
	// variable — it's a one-shot signal, not reactive state.
	let announceNextLoad = false;

	async function load(
		accountId: number,
		query: string,
		page: number,
		sort: ContactSort | null,
		label: EmailLabel | null,
		preserveCurrent = false
	) {
		if (!preserveCurrent) listState = { status: 'loading' };
		// Every list reload — mutation callbacks (create/edit/delete/merge/import
		// all funnel into onChanged → load), filters, pagination — also refreshes
		// the sidebar count badges. Fire-and-forget: badge staleness must never
		// block or fail the list itself.
		void refreshContactCounts(accountId);
		try {
			const result = await listContacts(accountId, {
				q: query || undefined,
				page,
				size: getClientConfigSnapshot().contactDefaultPageSize,
				sort: sort ?? undefined,
				label: label ?? undefined
			});
			listState = { status: 'ready', page: result };
			if (announceNextLoad) {
				announceNextLoad = false;
				const info = $_('contacts.pageInfo', {
					values: {
						current: result.page + 1,
						total: Math.max(1, result.totalPages),
						totalCount: $_('contacts.totalCount', { values: { count: result.totalElements } })
					}
				});
				// With a label filter the reload is otherwise indistinguishable from
				// the unfiltered list for a screen-reader user — name the view.
				announcePolite(
					label == null
						? info
						: $_('contacts.pageInfoLabeled', {
								values: { label: $_(`contacts.labelOptions.${label}`), info }
							})
				);
			}
		} catch (err) {
			listState = { status: 'error', error: toError(err) };
		}
	}

	$effect(() => {
		if ($accountsState.status !== 'ready') return;
		if (!$accountsState.accounts.some((account) => account.id === data.accountId)) {
			const fallbackId = $accountsState.accounts[0]?.id;
			void goto(
				fallbackId
					? resolve('/contacts/[accountId]', { accountId: String(fallbackId) })
					: resolve('/settings/accounts'),
				{ replaceState: true }
			);
			return;
		}

		setActiveAccount(data.accountId);
		if (data.create) {
			listState = { status: 'idle' };
			return;
		}
		// Edit renders the full-page form instead of the list; loading is handled
		// by the dedicated edit effect below.
		if (data.edit != null) return;

		const context = `${data.accountId}:${data.query}:${data.sort ?? ''}:${data.label ?? ''}`;
		if (lastContext !== context) {
			// A context switch is always user-triggered (search, filter, account
			// change) and often keeps focus in place, so the reload is otherwise
			// silent — announce the result. The initial load stays quiet.
			if (lastContext !== '') announceNextLoad = true;
			lastContext = context;
			pageNumber = 0;
		}
		void load(data.accountId, data.query, pageNumber, data.sort, data.label);
	});

	$effect(() => {
		if (data.edit == null) {
			editState = { status: 'idle' };
			loadedEditId = null;
			return;
		}
		if ($accountsState.status !== 'ready') return;
		// Guard purely on the id — do NOT read editState here, or the effect would
		// re-run on every load transition and refetch in a loop after an error.
		if (loadedEditId === data.edit) return;
		loadedEditId = data.edit;
		void loadEditContact(data.accountId, data.edit);
	});

	async function loadEditContact(accountId: number, contactId: number) {
		editState = { status: 'loading' };
		try {
			const contact = await getContact(accountId, contactId);
			editState = { status: 'ready', contact };
		} catch (err) {
			editState = { status: 'error', error: toError(err) };
		}
	}

	function contactsHref(
		options: {
			query?: string;
			create?: boolean;
			edit?: number | null;
			sort?: ContactSort | null;
			label?: EmailLabel | null;
		} = {}
	): string {
		// Each list param defaults to its current value unless the option is
		// present (so passing `sort: null` clears it, while omitting it keeps the
		// current sort); `create`/`edit` are one-shot view switches. Every param
		// is then set only when truthy.
		const query = (options.query ?? data.query)?.trim();
		const sort = 'sort' in options ? options.sort : data.sort;
		const label = 'label' in options ? options.label : data.label;

		const params = new SvelteURLSearchParams();
		if (query) params.set('q', query);
		if (options.create) params.set('create', '1');
		if (options.edit != null) params.set('edit', String(options.edit));
		if (sort) params.set('sort', sort);
		if (label) params.set('label', label);

		const queryString = params.toString();
		return `${resolve('/contacts/[accountId]', { accountId: String(data.accountId) })}${queryString ? `?${queryString}` : ''}`;
	}

	function handleFilterApply(filters: { sort: ContactSort | null; label: EmailLabel | null }) {
		// Remember the sort as a view preference so sidebar view links (clean
		// URLs) do not reset it; null means the 'surname' default.
		contactSortPreference.set(filters.sort ?? 'surname');
		void goto(contactsHref(filters), { keepFocus: true, noScroll: true });
	}

	/*
	 * The form replaces the list, so the roving focus cannot survive in the
	 * grid — coming back, focus would land on <main> and the user would have to
	 * find their contact again. The list takes the row to return to from here.
	 */
	let restoreFocusContactId = $state<number | null>(null);

	async function handleCreate(payload: ContactCreateRequest) {
		const created = await createContact(data.accountId, payload);
		pushToast($_('contacts.createDone'), { tone: 'success' });
		restoreFocusContactId = created.id;
		// Returning to the list view clears `create`, which re-runs the list
		// effect and reloads — no explicit load() here, that would fetch twice.
		await goto(contactsHref({ create: false }));
	}

	async function handleEditSave(payload: ContactCreateRequest) {
		if (data.edit == null) return;
		await updateContact(data.accountId, data.edit, payload);
		pushToast($_('contacts.saveDone'), { tone: 'success' });
		// Clearing `edit` re-runs the list effect, which reloads the list.
		await leaveEditForm();
	}

	/**
	 * Back to the list from the edit form. The target is remembered *before*
	 * navigating: a dirty form routes the navigation through the leave guard
	 * inside ContactForm, which never comes back through here.
	 */
	async function leaveEditForm() {
		restoreFocusContactId = data.edit;
		await goto(contactsHref({ edit: null }));
	}

	function goToPage(target: number) {
		if (listState.status !== 'ready') return;
		const lastPage = Math.max(0, listState.page.totalPages - 1);
		const next = Math.min(Math.max(0, target), lastPage);
		if (next === pageNumber) return;
		announceNextLoad = true;
		pageNumber = next;
	}
	function prevPage() {
		if (pageNumber > 0) goToPage(pageNumber - 1);
	}
	function nextPage() {
		if (listState.status === 'ready' && !listState.page.last) goToPage(pageNumber + 1);
	}
	function lastPage() {
		if (listState.status !== 'ready') return;
		goToPage(listState.page.totalPages - 1);
	}

	// The list heading, window title and reload announcement all carry the
	// active label so the view is identifiable without inspecting the filter UI.
	const activeLabelName = $derived(data.label ? $_(`contacts.labelOptions.${data.label}`) : null);
	const windowTitle = $derived(
		activeLabelName
			? $_('contacts.pageTitleLabeled', { values: { label: activeLabelName } })
			: $_('contacts.pageTitle')
	);

	let dragActive = $state(false);
	let importing = $state(false);

	function handleWindowDragOver(event: DragEvent) {
		if (data.create || data.edit != null) return;
		if (!dragHasFiles(event)) return;
		event.preventDefault();
		dragActive = true;
	}

	function handleWindowDragLeave(event: DragEvent) {
		if (event.relatedTarget) return;
		dragActive = false;
	}

	async function handleWindowDrop(event: DragEvent) {
		if (data.create || data.edit != null) return;
		if (!dragHasFiles(event)) return;
		event.preventDefault();
		dragActive = false;
		if (importing) return;

		importing = true;
		try {
			const imported = await importVCardFiles(
				data.accountId,
				Array.from(event.dataTransfer?.files ?? []),
				$_
			);
			if (imported) await load(data.accountId, data.query, pageNumber, data.sort, data.label);
		} finally {
			importing = false;
		}
	}
</script>

<svelte:head>
	<title>{windowTitle}</title>
</svelte:head>

<svelte:window
	ondragover={handleWindowDragOver}
	ondragleave={handleWindowDragLeave}
	ondrop={handleWindowDrop}
/>

{#if !data.create && data.edit == null && dragActive}
	<div
		class="fixed inset-0 z-50 flex items-center justify-center bg-background/85 backdrop-blur-sm"
		aria-hidden="true"
	>
		<div
			class="rounded-xl border-2 border-dashed border-primary/70 bg-background/90 px-10 py-8 text-center shadow-lg"
		>
			<p class="text-base font-semibold text-foreground">{$_('contacts.vcardImportPrompt')}</p>
			<p class="mt-1 text-sm text-muted-foreground">{$_('contacts.vcardImportHint')}</p>
		</div>
	</div>
{/if}

{#if data.create}
	<section class="flex-1 overflow-y-auto bg-background outline-none">
		<div class="max-w-4xl space-y-4 p-6">
			<ContactForm onSubmit={handleCreate} onCancel={() => goto(contactsHref({ create: false }))} />
		</div>
	</section>
{:else if data.edit != null}
	<section class="flex-1 overflow-y-auto bg-background outline-none">
		<div class="max-w-4xl space-y-4 p-6">
			{#if editState.status === 'loading' || editState.status === 'idle'}
				<StateMessage>{$_('contacts.loading')}</StateMessage>
			{:else if editState.status === 'error'}
				<StateMessage variant="error" role="alert">{editState.error.message}</StateMessage>
			{:else if editState.status === 'ready'}
				<ContactForm
					contact={editState.contact}
					onSubmit={handleEditSave}
					onCancel={() => void leaveEditForm()}
				/>
			{/if}
		</div>
	</section>
{:else}
	<PageShell
		title={activeLabelName ?? $_('contacts.listHeading')}
		contentClass="max-w-4xl space-y-4"
	>
		<Surface as="section" variant="list" padding="none">
			{#if listState.status === 'loading'}
				<StateMessage>{$_('contacts.loading')}</StateMessage>
			{:else if listState.status === 'error'}
				<StateMessage variant="error" role="alert">{listState.error.message}</StateMessage>
			{:else if listState.status === 'ready'}
				<ContactList
					accountId={data.accountId}
					page={listState.page}
					sort={data.sort}
					label={data.label}
					onChanged={() =>
						load(data.accountId, data.query, pageNumber, data.sort, data.label, true)}
					onEdit={(id) => goto(contactsHref({ edit: id }))}
					onFilterApply={handleFilterApply}
					onPrev={prevPage}
					onNext={nextPage}
					onFirst={() => goToPage(0)}
					onLast={lastPage}
					onJump={(target) => goToPage(target - 1)}
					{restoreFocusContactId}
					onFocusRestored={() => (restoreFocusContactId = null)}
				/>
			{/if}
		</Surface>
	</PageShell>
{/if}
