<script lang="ts">
	import { goto } from '$app/navigation';
	import { resolve } from '$app/paths';
	import { get } from 'svelte/store';
	import { activeAccount } from '$lib/stores/accounts.js';
	import { clientConfig } from '$lib/stores/clientConfig.js';
	import { page } from '$app/stores';
	import { _ } from '$lib/i18n/index.js';
	import { Input } from '$lib/components/ui/input/index.js';
	import { onDestroy } from 'svelte';

	let query = $state(get(page).url.searchParams.get('q') ?? '');

	const unsubscribe = page.subscribe(($page) => {
		const nextQuery = $page.url.searchParams.get('q') ?? '';
		if (nextQuery !== query) {
			query = nextQuery;
		}
	});

	onDestroy(() => {
		unsubscribe();
	});

	function handleSubmit(event: SubmitEvent) {
		event.preventDefault();
		const trimmed = query.trim();
		if (!trimmed) return;
		const acc = get(activeAccount);
		if (!acc) return;
		void goto(
			`${resolve('/search/[accountId]', { accountId: String(acc.id) })}?q=${encodeURIComponent(
				trimmed
			)}`
		);
	}
</script>

<form onsubmit={handleSubmit} class="flex-1" role="search" aria-label={$_('search.landmark')}>
	<label for="search-input" class="sr-only">{$_('search.label')}</label>
	<Input
		id="search-input"
		type="search"
		bind:value={query}
		maxlength={$clientConfig.searchQueryMaxLength}
		placeholder={$_('search.placeholder')}
		size="sm"
	/>
</form>
