<script lang="ts">
	import { goto } from '$app/navigation';
	import { resolve } from '$app/paths';
	import { SvelteURLSearchParams } from 'svelte/reactivity';
	import {
		bulkCreateContacts,
		createContact,
		listContacts,
		type ContactSort
	} from '$lib/api/contacts.js';
	import { toError, toErrorMessage } from '$lib/api/errors.js';
	import { accountsState, setActiveAccount } from '$lib/stores/accounts.js';
	import { getClientConfigSnapshot } from '$lib/stores/clientConfig.js';
	import { _ } from '$lib/i18n/index.js';
	import { announcePolite, pushToast } from '$lib/stores/toasts.js';
	import ContactList from '$lib/components/ContactList.svelte';
	import ContactForm from '$lib/components/ContactForm.svelte';
	import { parseVCard } from '$lib/contacts/vcard.js';
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

	let listState = $state<ListState>({ status: 'idle' });
	let pageNumber = $state(0);
	let lastContext = $state('');
	// Set by pagination handlers so the next successful load announces the new
	// page to screen-reader users (matches the Messages list behaviour). Plain
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
				announcePolite(
					$_('contacts.pageInfo', {
						values: {
							current: result.page + 1,
							total: Math.max(1, result.totalPages),
							totalCount: $_('contacts.totalCount', { values: { count: result.totalElements } })
						}
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

		const context = `${data.accountId}:${data.query}:${data.sort ?? ''}:${data.label ?? ''}`;
		if (lastContext !== context) {
			lastContext = context;
			pageNumber = 0;
		}
		void load(data.accountId, data.query, pageNumber, data.sort, data.label);
	});

	function contactsHref(options?: {
		query?: string;
		create?: boolean;
		sort?: ContactSort | null;
		label?: EmailLabel | null;
	}): string {
		const params = new SvelteURLSearchParams();
		const nextQuery = options?.query?.trim() ?? data.query;
		if (nextQuery) params.set('q', nextQuery);
		if (options?.create) params.set('create', '1');
		const nextSort = options && 'sort' in options ? options.sort : data.sort;
		const nextLabel = options && 'label' in options ? options.label : data.label;
		if (nextSort) params.set('sort', nextSort);
		if (nextLabel) params.set('label', nextLabel);

		const queryString = params.toString();
		return `${resolve('/contacts/[accountId]', { accountId: String(data.accountId) })}${queryString ? `?${queryString}` : ''}`;
	}

	function handleFilterApply(filters: { sort: ContactSort | null; label: EmailLabel | null }) {
		void goto(contactsHref(filters), { keepFocus: true, noScroll: true });
	}

	async function handleCreate(payload: ContactCreateRequest) {
		await createContact(data.accountId, payload);
		await goto(contactsHref({ create: false }));
		await load(data.accountId, data.query, pageNumber, data.sort, data.label);
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

	let dragActive = $state(false);
	let importing = $state(false);

	function dragHasFiles(event: DragEvent): boolean {
		const dt = event.dataTransfer;
		if (!dt) return false;
		if (dt.types && Array.from(dt.types).includes('Files')) return true;
		if (dt.files && dt.files.length > 0) return true;
		const items = dt.items;
		if (!items) return false;
		for (let i = 0; i < items.length; i++) {
			if (items[i].kind === 'file') return true;
		}
		return false;
	}

	function looksLikeVCardFile(file: File): boolean {
		const type = file.type.toLowerCase();
		if (type === 'text/vcard' || type === 'text/x-vcard') return true;
		return file.name.toLowerCase().endsWith('.vcf');
	}

	function handleWindowDragOver(event: DragEvent) {
		if (data.create) return;
		if (!dragHasFiles(event)) return;
		event.preventDefault();
		dragActive = true;
	}

	function handleWindowDragLeave(event: DragEvent) {
		if (event.relatedTarget) return;
		dragActive = false;
	}

	async function handleWindowDrop(event: DragEvent) {
		if (data.create) return;
		if (!dragHasFiles(event)) return;
		event.preventDefault();
		dragActive = false;
		if (importing) return;

		const files = Array.from(event.dataTransfer?.files ?? []).filter(looksLikeVCardFile);
		if (files.length === 0) {
			pushToast($_('contacts.vcardImportNoFiles'), { tone: 'error' });
			return;
		}

		importing = true;
		try {
			const allContacts: ContactCreateRequest[] = [];
			for (const file of files) {
				const text = await file.text();
				allContacts.push(...parseVCard(text));
			}
			if (allContacts.length === 0) {
				pushToast($_('contacts.vcardImportEmpty'), { tone: 'error' });
				return;
			}
			const result = await bulkCreateContacts(data.accountId, { contacts: allContacts });
			pushToast(
				$_('contacts.vcardImportDone', {
					values: { created: result.created ?? 0, failed: result.failed ?? 0 }
				}),
				{ tone: (result.failed ?? 0) > 0 ? 'error' : 'success' }
			);
			await load(data.accountId, data.query, pageNumber, data.sort, data.label);
		} catch (err) {
			pushToast(toErrorMessage(err), { tone: 'error' });
		} finally {
			importing = false;
		}
	}
</script>

<svelte:head>
	<title>{$_('contacts.pageTitle')}</title>
</svelte:head>

<svelte:window
	ondragover={handleWindowDragOver}
	ondragleave={handleWindowDragLeave}
	ondrop={handleWindowDrop}
/>

{#if !data.create && dragActive}
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
{:else}
	<PageShell title={$_('contacts.listHeading')} contentClass="max-w-4xl space-y-4">
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
					onFilterApply={handleFilterApply}
					onPrev={prevPage}
					onNext={nextPage}
					onFirst={() => goToPage(0)}
					onLast={lastPage}
					onJump={(target) => goToPage(target - 1)}
				/>
			{/if}
		</Surface>
	</PageShell>
{/if}
