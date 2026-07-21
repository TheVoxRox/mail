<script lang="ts">
	import { page } from '$app/stores';
	import { goto } from '$app/navigation';
	import { resolve } from '$app/paths';
	import { setContext } from 'svelte';
	import { get } from 'svelte/store';
	import MessageList from '$lib/components/MessageList.svelte';
	import SplitPane from '$lib/components/SplitPane.svelte';
	import { accountsState, setActiveAccount } from '$lib/stores/accounts.js';
	import { folders } from '$lib/stores/folders.js';
	import { loadPage, messagesState } from '$lib/stores/messages.js';
	import { closeCurrentMessageDetail } from '$lib/mail/actions.js';
	import { clearSelection } from '$lib/stores/selectedMessage.js';
	import { announcePolite } from '$lib/stores/toasts.js';
	import { readingPane } from '$lib/stores/uiLayout.js';
	import { _ } from '$lib/i18n/index.js';
	import { folderLabel as toFolderLabel } from '$lib/mail/folderLabel.js';
	import { messagesPageInfo } from '$lib/mail/pageInfoAnnouncement.js';
	import {
		MESSAGE_HEADING_CONTEXT_KEY,
		type MessageHeadingContext
	} from '$lib/components/message-detail/messageHeadingContext.js';
	import {
		EFFECTIVE_READING_PANE_CONTEXT_KEY,
		type EffectiveReadingPaneContext
	} from '$lib/mail/readingPaneContext.js';
	import { cn } from '$lib/utils.js';

	let { children } = $props();

	let innerWidth = $state(1200);

	const accountId = $derived(Number($page.params.accountId ?? '0'));
	const folderName = $derived(decodeURIComponent($page.params.folderName ?? ''));
	const stableId = $derived(
		$page.params.stableId ? decodeURIComponent($page.params.stableId) : null
	);

	// One-shot bookkeeping for the folder-switch announcement, not reactive state.
	let lastLoadedContext = '';

	$effect(() => {
		if (!Number.isInteger(accountId) || accountId <= 0 || !folderName) return;
		if ($accountsState.status !== 'ready') return;
		if (!$accountsState.accounts.some((account) => account.id === accountId)) {
			void goto(resolve('/'), { replaceState: true });
			return;
		}

		setActiveAccount(accountId);

		/*
		 * Switching folder/account keeps focus on the sidebar item while the
		 * list swaps underneath, so the reload is otherwise silent for a
		 * screen reader — announce the loaded page once. The initial load
		 * stays quiet (route entry is already announced via focus handling).
		 */
		const contextKey = `${accountId}:${folderName}`;
		const isContextSwitch = lastLoadedContext !== '' && lastLoadedContext !== contextKey;
		lastLoadedContext = contextKey;

		const state = get(messagesState);
		if (
			state.status === 'ready' &&
			state.context.accountId === accountId &&
			state.context.folderName === folderName
		) {
			return;
		}

		void loadPage(accountId, folderName).then(() => {
			if (!isContextSwitch) return;
			// loadPage discards stale results; only announce when the loaded
			// page really belongs to the folder this effect run asked for.
			const snapshot = get(messagesState);
			if (snapshot.status !== 'ready') return;
			if (snapshot.context.accountId !== accountId || snapshot.context.folderName !== folderName)
				return;
			announcePolite(messagesPageInfo(get(_), snapshot.page));
		});
	});

	$effect(() => {
		if (!stableId) {
			clearSelection();
		}
	});

	const folderLabel = $derived.by(() => {
		const folder = $folders.find((f) => f.folderRef === folderName);
		return folder ? toFolderLabel(folder, $_) : folderName;
	});

	const effectiveReadingPane = $derived.by(() => {
		if ($readingPane === 'right' && innerWidth < 900) return 'off';
		if ($readingPane === 'bottom' && innerWidth < 600) return 'off';
		return $readingPane;
	});

	// MessageList adjusts its keyboard model to the effective pane mode (see
	// readingPaneContext.ts) — same pattern as the message heading below.
	const readingPaneContext = $state<EffectiveReadingPaneContext>({ pane: 'off' });
	setContext<EffectiveReadingPaneContext>(EFFECTIVE_READING_PANE_CONTEXT_KEY, readingPaneContext);

	$effect(() => {
		readingPaneContext.pane = effectiveReadingPane;
	});

	const hasDetailRoute = $derived(Boolean(stableId));
	const folderEntry = $derived($folders.find((folder) => folder.folderRef === folderName));
	const unreadCount = $derived(folderEntry?.unreadCount ?? 0);
	const folderHeadingLabel = $derived(
		unreadCount > 0
			? `${folderLabel} ${$_('nav.unreadBadge', { values: { count: unreadCount } })}`
			: folderLabel
	);

	/*
	 * Off mode with an open message: the detail is the dominant page
	 * content, so we promote the message subject to `<h1>` and demote the
	 * folder in the top bar to a breadcrumb-style back link. In split
	 * modes the folder stays `<h1>`.
	 */
	const detailIsOffMode = $derived(hasDetailRoute && effectiveReadingPane === 'off');

	const messageHeading = $state<MessageHeadingContext>({
		level: 2,
		showBackButton: true
	});
	setContext<MessageHeadingContext>(MESSAGE_HEADING_CONTEXT_KEY, messageHeading);

	$effect(() => {
		messageHeading.level = detailIsOffMode ? 1 : 2;
		messageHeading.showBackButton = !detailIsOffMode;
	});

	const folderHref = $derived(
		resolve('/mail/[accountId]/[folderName]', {
			accountId: String(accountId),
			folderName: encodeURIComponent(folderName)
		})
	);

	function handleBackToFolder(event: MouseEvent) {
		// Modifier clicks keep the browser's own link handling.
		if (event.defaultPrevented || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey)
			return;
		event.preventDefault();
		void closeCurrentMessageDetail({ restoreFocus: true });
	}

	function panePlaceholderClass() {
		return cn(
			'flex items-center justify-center p-8 text-sm text-muted-foreground',
			effectiveReadingPane === 'bottom' ? 'border-t border-border' : ''
		);
	}
</script>

<svelte:head>
	<title>
		{hasDetailRoute
			? $_('detail.pageTitle')
			: $_('detail.folderPageTitle', { values: { folder: folderLabel } })}
	</title>
</svelte:head>

<svelte:window bind:innerWidth />

<div class="flex flex-1 flex-col overflow-hidden bg-background">
	<div class="flex items-center gap-3 border-b border-border bg-muted/35 px-3 py-2">
		<div class="flex min-w-0 shrink-0 items-center pr-1">
			{#if detailIsOffMode}
				<!--
					Same closing path as Esc and the split-mode Back button, so the
					visible way back restores focus to the row the message was
					opened from instead of dropping the user on <main>. The href
					stays real (semantics, SSR, middle-click safety) — the handler
					only takes over the in-app case.
				-->
				<a
					href={folderHref}
					onclick={handleBackToFolder}
					class="inline-flex min-w-0 items-center gap-1 truncate rounded-md px-2 py-1 text-sm font-medium text-muted-foreground hover:bg-muted hover:text-foreground focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring"
					aria-label={$_('detail.backToFolder', { values: { folder: folderLabel } })}
				>
					<span aria-hidden="true">←</span>
					<span class="truncate">{folderLabel}</span>
				</a>
			{:else}
				<h1
					class="flex min-w-0 items-baseline gap-2 px-2 text-base font-semibold text-foreground"
					aria-label={folderHeadingLabel}
				>
					<span class="truncate" aria-hidden="true">{folderLabel}</span>
					{#if unreadCount > 0}
						<span
							class="shrink-0 min-w-5 rounded-full bg-primary/12 px-1.5 py-0.5 text-center text-caption font-semibold text-primary"
							aria-hidden="true"
						>
							{unreadCount}
						</span>
					{/if}
				</h1>
			{/if}
		</div>
	</div>

	{#if effectiveReadingPane === 'right'}
		<SplitPane
			orientation="vertical"
			storageKey="mail.splitSize.right"
			initialPercent={35}
			ariaLabel={$_('settings.appearance.splitPane.vertical')}
		>
			{#snippet first()}
				<MessageList />
			{/snippet}

			{#snippet second()}
				{#if hasDetailRoute}
					{@render children()}
				{:else}
					<div class={panePlaceholderClass()}>{$_('settings.appearance.readingPane.empty')}</div>
				{/if}
			{/snippet}
		</SplitPane>
	{:else if effectiveReadingPane === 'bottom'}
		<SplitPane
			orientation="horizontal"
			storageKey="mail.splitSize.bottom"
			initialPercent={45}
			ariaLabel={$_('settings.appearance.splitPane.horizontal')}
		>
			{#snippet first()}
				<MessageList />
			{/snippet}

			{#snippet second()}
				{#if hasDetailRoute}
					{@render children()}
				{:else}
					<div class={panePlaceholderClass()}>{$_('settings.appearance.readingPane.empty')}</div>
				{/if}
			{/snippet}
		</SplitPane>
	{:else if hasDetailRoute}
		{@render children()}
	{:else}
		<MessageList />
	{/if}
</div>
