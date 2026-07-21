<script lang="ts">
	import { goto } from '$app/navigation';
	import { page as pageStore } from '$app/stores';
	import { resolve } from '$app/paths';
	import { accountsState, setActiveAccount } from '$lib/stores/accounts.js';
	import { reloadSearch, runSearch, searchState } from '$lib/stores/search.js';
	import { selectMessage, selectedMessage, clearSelection } from '$lib/stores/selectedMessage.js';
	import { announcePolite } from '$lib/stores/toasts.js';
	import { requestBodyFocus } from '$lib/mail/bodyFocus.js';
	import { messagesPageInfo } from '$lib/mail/pageInfoAnnouncement.js';
	import MessageDetail from '$lib/components/MessageDetail.svelte';
	import Pagination from '$lib/components/Pagination.svelte';
	import SearchResultsGrid from '$lib/components/SearchResultsGrid.svelte';
	import { _ } from '$lib/i18n/index.js';
	import type { MailSummaryResponse } from '$lib/types.js';

	let { data } = $props();

	/*
	 * Focus stays in the search box (new query) or on the pagination button
	 * (page change) while the results swap underneath, so neither transition
	 * is audible on its own. Announce the incoming results once per
	 * account+query (count) and every explicit page change (page info) —
	 * one-shot plain variables, same pattern as the contacts list.
	 */
	let announcePageInfoOnReady = false;
	let lastAnnouncedResultsKey = '';
	let restoreFocusStableId = $state<string | null>(null);

	$effect(() => {
		if ($searchState.status !== 'ready') return;
		const ctx = $searchState.context;
		// Only announce results belonging to this page's current URL state.
		// The page check matters after pagination: the URL change re-runs this
		// effect while the store still holds the *previous* page, and without
		// it the one-shot flag would fire with the stale page number.
		if (ctx.accountId !== data.accountId || ctx.query !== data.query || ctx.page !== data.page)
			return;
		const key = `${ctx.accountId}:${ctx.query}`;
		if (announcePageInfoOnReady) {
			announcePageInfoOnReady = false;
			lastAnnouncedResultsKey = key;
			announcePolite(messagesPageInfo($_, $searchState.page));
			return;
		}
		if (lastAnnouncedResultsKey === key) return;
		lastAnnouncedResultsKey = key;
		announcePolite(
			$_('search.resultsAnnounce', {
				values: {
					totalCount: $_('messages.totalCount', {
						values: { count: $searchState.page.totalElements }
					})
				}
			})
		);
	});

	$effect(() => {
		if ($accountsState.status !== 'ready') return;
		if (!$accountsState.accounts.some((account) => account.id === data.accountId)) {
			const fallbackId = $accountsState.accounts[0]?.id;
			const query = data.query ? `?q=${encodeURIComponent(data.query)}` : '';
			void goto(
				fallbackId
					? `${resolve('/search/[accountId]', { accountId: String(fallbackId) })}${query}`
					: resolve('/'),
				{ replaceState: true }
			);
			return;
		}

		setActiveAccount(data.accountId);
		clearSelection();
		if (data.query) {
			void runSearch(data.accountId, data.query, data.page);
		}
	});

	function handleSelect(m: MailSummaryResponse) {
		// Opening a result is always deliberate here (Enter or a click) — the
		// results grid has no reading pane that could follow focus, so the
		// reading cursor moves into the body (see mail/bodyFocus.ts).
		requestBodyFocus(m.stableId);
		void selectMessage(m.stableId);
		/*
		 * Opening a result swaps the list for the detail in place — there is no
		 * route change, so afterNavigate (which normally moves focus to <main>)
		 * does not fire. Move focus to the main landmark ourselves so the detail
		 * is announced instead of focus falling back to <body> when the focused
		 * grid cell unmounts.
		 */
		if (typeof window !== 'undefined') {
			requestAnimationFrame(() => {
				document.getElementById('main-content')?.focus({ preventScroll: true });
			});
		}
	}

	/*
	 * Closing a result's detail returns to the results, in place. The default
	 * closing path (mail/actions.ts) navigates to the folder the *mail* list
	 * last browsed — from here that means being dumped into the inbox with the
	 * search results gone, so this screen closes on its own terms and hands the
	 * roving focus back to the row the result was opened from.
	 */
	function handleDetailClose() {
		restoreFocusStableId = $selectedMessage?.stableId ?? null;
		clearSelection();
	}

	/*
	 * A row action can remove the row it was invoked from (delete, move) — its
	 * menu trigger unmounts with it and focus drops to <body>. The mail list
	 * gets its restore from `messagesState`, which knows nothing about search
	 * results, so this screen tracks the neighbour itself. It has to live here
	 * rather than in the grid: the reload takes the search state through
	 * `loading`, which unmounts the grid and with it any state it held.
	 */
	let pendingRowRemoval: { stableId: string; neighbour: string | null } | null = null;

	function handleAfterRowAction(message: MailSummaryResponse) {
		const content = $searchState.status === 'ready' ? $searchState.page.content : [];
		const idx = content.findIndex((row) => row.stableId === message.stableId);
		pendingRowRemoval =
			idx < 0
				? null
				: {
						stableId: message.stableId,
						neighbour: content[idx + 1]?.stableId ?? content[idx - 1]?.stableId ?? null
					};
		void reloadSearch();
	}

	$effect(() => {
		if ($searchState.status !== 'ready') return;
		const pending = pendingRowRemoval;
		if (!pending) return;
		pendingRowRemoval = null;
		// The row survived (flag / mark read) — the menu already returned focus
		// to its own trigger, which is still mounted.
		if ($searchState.page.content.some((row) => row.stableId === pending.stableId)) return;
		restoreFocusStableId = pending.neighbour;
	});

	function navigateToPage(target: number) {
		if ($searchState.status !== 'ready') return;
		const lastPage = Math.max(0, $searchState.page.totalPages - 1);
		const next = Math.min(Math.max(0, target), lastPage);
		if (next === $searchState.context.page) return;
		announcePageInfoOnReady = true;
		const q = $pageStore.url.searchParams.get('q') ?? '';
		const qs = `?q=${encodeURIComponent(q)}&page=${next}`;
		void goto(`${resolve('/search/[accountId]', { accountId: String(data.accountId) })}${qs}`);
	}
</script>

<svelte:head>
	<title>{$_('search.pageTitle', { values: { query: data.query } })}</title>
</svelte:head>

<div class="flex flex-1 flex-col overflow-hidden">
	{#if $selectedMessage}
		<MessageDetail onClose={handleDetailClose} />
	{:else}
		<div class="flex items-center justify-between border-b border-border px-4 py-3">
			<h1 class="text-title font-semibold">
				{$_('search.resultsTitle', { values: { query: data.query } })}
			</h1>
			{#if $searchState.status === 'ready'}
				<span class="text-xs text-muted-foreground">
					{$_('messages.totalCount', { values: { count: $searchState.page.totalElements } })}
				</span>
			{/if}
		</div>

		{#if !data.query}
			<div
				class="flex flex-1 items-center justify-center p-8 text-sm text-muted-foreground"
				role="status"
			>
				{$_('search.promptEnterQuery')}
			</div>
		{:else if $searchState.status === 'loading' || $searchState.status === 'idle'}
			<div
				class="flex flex-1 items-center justify-center p-8 text-sm text-muted-foreground"
				role="status"
			>
				{$_('search.searchingStatus')}
			</div>
		{:else if $searchState.status === 'error'}
			<div
				class="flex flex-1 items-center justify-center p-8 text-sm text-destructive"
				role="alert"
			>
				{$_('messages.errorPrefix', { values: { message: $searchState.error.message } })}
			</div>
		{:else if $searchState.status === 'ready' && $searchState.page.content.length === 0}
			<div
				class="flex flex-1 items-center justify-center p-8 text-sm text-muted-foreground"
				role="status"
			>
				{$_('search.noResults')}
			</div>
		{:else if $searchState.status === 'ready'}
			{@const pageData = $searchState.page}
			<SearchResultsGrid
				results={pageData}
				onSelect={handleSelect}
				onAfterAction={handleAfterRowAction}
				{restoreFocusStableId}
				onFocusRestored={() => (restoreFocusStableId = null)}
			/>

			<Pagination
				page={pageData.page}
				totalPages={pageData.totalPages}
				totalElements={pageData.totalElements}
				first={pageData.first}
				last={pageData.last}
				onFirst={() => navigateToPage(0)}
				onPrev={() => navigateToPage(pageData.page - 1)}
				onNext={() => navigateToPage(pageData.page + 1)}
				onLast={() => navigateToPage(pageData.totalPages - 1)}
				onJump={(target) => navigateToPage(target - 1)}
				landmarkLabel={$_('search.paginationLandmark')}
			/>
		{/if}
	{/if}
</div>
