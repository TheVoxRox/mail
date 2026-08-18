<script lang="ts">
	import { goto } from '$app/navigation';
	import { resolve } from '$app/paths';
	import { SvelteURLSearchParams } from 'svelte/reactivity';
	import { accountsState, setActiveAccount } from '$lib/stores/accounts.js';
	import { reloadSearch, runSearch, searchState } from '$lib/stores/search.js';
	import { selectMessage, selectedMessage, clearSelection } from '$lib/stores/selectedMessage.js';
	import { announcePolite } from '$lib/stores/toasts.js';
	import { requestBodyFocus } from '$lib/mail/bodyFocus.js';
	import type { DetailCloseContext } from '$lib/mail/detailHost.js';
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
	let lastSearchKey = '';
	let restoreFocusStableId = $state<string | null>(null);
	let emptyResultsElement = $state<HTMLDivElement | null>(null);

	/**
	 * The address of one view of this screen. Everything visible — the query,
	 * the page, the open result — lives in the URL, so Back, reload and a
	 * bookmark all reproduce what a click produced. Each option defaults to its
	 * current value; `message: null` closes the open result.
	 *
	 * Same shape as `contactsHref` on the contacts route, deliberately: two
	 * screens with the same problem should not have two different answers.
	 */
	function searchHref(
		options: { query?: string; page?: number; message?: string | null } = {}
	): string {
		const query = (options.query ?? data.query).trim();
		const pageNumber = options.page ?? data.page;
		const message = 'message' in options ? options.message : data.message;

		const params = new SvelteURLSearchParams();
		if (query) params.set('q', query);
		if (pageNumber > 0) params.set('page', String(pageNumber));
		if (message) params.set('message', message);

		const queryString = params.toString();
		const base = resolve('/search/[accountId]', { accountId: String(data.accountId) });
		return `${base}${queryString ? `?${queryString}` : ''}`;
	}

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

		/*
		 * Everything below belongs to a *changed* search, and `data` is a fresh
		 * object on every navigation — including one that only moved
		 * `?message=`. Re-running it there is not merely a wasted fetch: the
		 * second `runSearch` takes the store through a ready transition of its
		 * own, and the row-removal bookkeeping further down consumes the first
		 * ready it sees. Deleting an open result then dropped its focus restore
		 * on the floor and left focus on the main landmark.
		 */
		const key = `${data.accountId}:${data.query}:${data.page}`;
		if (key === lastSearchKey) return;
		lastSearchKey = key;

		// A new query throws away any pending focus restore: it points at a row
		// of the previous result set, which must not grab focus if the same
		// message happens to match again.
		restoreFocusStableId = null;
		if (data.query) {
			void runSearch(data.accountId, data.query, data.page);
		}
	});

	/*
	 * The open result follows `?message=`, the same way the mail route's detail
	 * follows its `[stableId]` segment. Deriving it from the URL rather than
	 * from the click is what makes the deep link, the reload and Back land in
	 * the state a click produces.
	 */
	$effect(() => {
		const stableId = data.message;
		if (stableId) void selectMessage(stableId);
		else clearSelection();
	});

	/**
	 * Opens `m` at its address within the search context — results and query
	 * stay behind it, which the message's own address under its folder could
	 * not do.
	 *
	 * Opening is always deliberate here (Enter or a click): the results grid
	 * has no reading pane that could follow focus, so the reading cursor moves
	 * into the body (see mail/bodyFocus.ts). Focus is not moved here — the
	 * navigation fires `afterNavigate`, and the layout hands focus to the main
	 * landmark once the grid cell the user was on unmounts.
	 */
	function handleSelect(m: MailSummaryResponse) {
		requestBodyFocus(m.stableId);
		void goto(searchHref({ message: m.stableId }));
	}

	/*
	 * Closing a result's detail returns to the results, in place. The default
	 * closing path (mail/actions.ts) navigates to the folder the *mail* list
	 * last browsed — from here that means being dumped into the inbox with the
	 * search results gone, so this screen closes on its own terms and hands the
	 * roving focus back to the row the result was opened from.
	 */
	async function handleDetailClose(context: DetailCloseContext) {
		// Read before the await: `data` is the new URL's by the time it resolves.
		const openStableId = data.message;
		/*
		 * The URL held the open result, so closing has to take it out of the URL
		 * — otherwise Back would land straight back on the detail the user just
		 * left. `keepFocus` because the restore below is ours to do: SvelteKit's
		 * own focus reset would send focus to the document first, and the
		 * restore would be racing it rather than taking over from it.
		 */
		await goto(searchHref({ message: null }), { keepFocus: true, noScroll: true });
		if (!context.removedStableId) {
			restoreFocusStableId = openStableId;
			return;
		}
		/*
		 * The open result was deleted or moved away: it must disappear from the
		 * results too, and focus belongs on whatever takes its place — the same
		 * bookkeeping a row action does, so it reuses it.
		 */
		noteRowRemoval(context.removedStableId);
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

	function noteRowRemoval(stableId: string) {
		const content = $searchState.status === 'ready' ? $searchState.page.content : [];
		const idx = content.findIndex((row) => row.stableId === stableId);
		pendingRowRemoval =
			idx < 0
				? null
				: {
						stableId,
						neighbour: content[idx + 1]?.stableId ?? content[idx - 1]?.stableId ?? null
					};
		void reloadSearch();
	}

	function handleAfterRowAction(message: MailSummaryResponse) {
		noteRowRemoval(message.stableId);
	}

	$effect(() => {
		if ($searchState.status !== 'ready') return;
		const pending = pendingRowRemoval;
		if (!pending) return;
		pendingRowRemoval = null;
		// The row survived (flag / mark read) — the menu already returned focus
		// to its own trigger, which is still mounted.
		if ($searchState.page.content.some((row) => row.stableId === pending.stableId)) return;
		if (pending.neighbour) {
			restoreFocusStableId = pending.neighbour;
			return;
		}
		/*
		 * That was the last result: the grid is replaced by the "no results"
		 * message, so focus goes there instead of falling to <body>.
		 */
		const target = emptyResultsElement;
		if (target) requestAnimationFrame(() => target.focus());
	});

	function navigateToPage(target: number) {
		if ($searchState.status !== 'ready') return;
		announcePageInfoOnReady = true;
		void goto(searchHref({ page: target }));
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
				class="flex flex-1 items-center justify-center p-8 text-sm text-destructive-foreground"
				role="alert"
			>
				{$_('messages.errorPrefix', { values: { message: $searchState.error.message } })}
			</div>
		{:else if $searchState.status === 'ready' && $searchState.page.content.length === 0}
			<!-- Focus target after the last result was removed by a row action. -->
			<div
				bind:this={emptyResultsElement}
				tabindex="-1"
				class="flex flex-1 items-center justify-center p-8 text-sm text-muted-foreground outline-hidden"
				role="status"
			>
				{$_('search.noResults')}
			</div>
		{:else if $searchState.status === 'ready'}
			{@const pageData = $searchState.page}
			<SearchResultsGrid
				results={pageData}
				hrefFor={(message) => searchHref({ message: message.stableId })}
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
				onNavigate={navigateToPage}
				landmarkLabel={$_('search.paginationLandmark')}
			/>
		{/if}
	{/if}
</div>
