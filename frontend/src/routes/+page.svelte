<script lang="ts">
	import { goto } from '$app/navigation';
	import { resolve } from '$app/paths';
	import { get } from 'svelte/store';
	import {
		accountsState,
		resolvedActiveAccountId,
		setActiveAccount
	} from '$lib/stores/accounts.js';
	import { loadFolders } from '$lib/stores/folders.js';
	import { folderHref, pickEntryFolder } from '$lib/mail/entryFolder.js';
	import { toErrorMessage } from '$lib/api/errors.js';
	import { Button } from '$lib/components/ui/button/index.js';
	import { Surface } from '$lib/components/ui/surface/index.js';
	import { _ } from '$lib/i18n/index.js';

	/*
	 * The two places Mail can stop for good, which used to share one card: an
	 * account whose server lists no folder was told to start by adding an
	 * account — the one it already had.
	 */
	let empty = $state<'noAccounts' | 'noFolders' | null>(null);
	let loadError = $state<string | null>(null);
	let handledContext = $state<string | null>(null);

	const pageTitle = $derived(
		empty === 'noAccounts'
			? $_('root.noAccountsPageTitle')
			: empty === 'noFolders'
				? $_('root.noFoldersPageTitle')
				: $_('workspace.mail')
	);

	/*
	 * Only the cold cases reach here: `+page.ts` redirects from cache before this
	 * component is ever built, so what is left is the boot that has no folder
	 * list yet and the account switch to a mailbox never opened. Both are a real
	 * wait, which is why they get a status message rather than a silent one.
	 */
	async function redirect() {
		const state = get(accountsState);
		if (state.status !== 'ready') {
			return;
		}
		const context = state.accounts.map((account) => account.id).join(',');
		if (handledContext === context) return;
		handledContext = context;

		empty = null;
		loadError = null;
		if (state.accounts.length === 0) {
			empty = 'noAccounts';
			return;
		}

		const accountId = get(resolvedActiveAccountId) ?? state.accounts[0].id;
		setActiveAccount(accountId);

		const folders = await loadFolders(accountId).catch((err) => {
			loadError = toErrorMessage(err);
			return [];
		});
		const entry = pickEntryFolder(folders);
		if (!entry) {
			// A failed load is not an empty mailbox; the alert already says what
			// went wrong.
			if (loadError === null) empty = 'noFolders';
			return;
		}

		await goto(folderHref(accountId, entry), { replaceState: true });
	}

	$effect(() => {
		if ($accountsState.status !== 'ready') return;
		void redirect();
	});
</script>

<!--
	The one route that had no title of its own, so it inherited the layout's
	bare `app.title` — and since <main> is named after the route title, the
	accountless welcome screen introduced itself as the application. That is the
	shape every other route was made to drop, and the strip that used to hide it
	is gone.

	It is no longer one title for all states, which is what the first fix
	assumed. "The redirect that has not resolved yet" turned out not to be the
	same place as the welcome card at all: focus lands in <main> while this route
	is still on its way somewhere else, so naming it after the welcome screen
	announced a screen the user was not on and would never see. Only the states
	the user actually stays on may claim that name; on the way through, the
	landmark says the workspace being entered and the status line below says a
	wait is on, which is the truth in the one case that still gets here.

	The name says which state this is rather than greeting. It used to be
	"Welcome", a word the card never shows: with no account the button takes
	focus on its own, so the window title and the landmark are all a screen
	reader says about the page, and a greeting did not say why Mail had nowhere
	to open.
-->
<svelte:head>
	<title>{pageTitle}</title>
</svelte:head>

<div class="flex h-full items-center justify-center text-sm text-muted-foreground">
	{#if loadError}
		<Surface variant="danger" role="alert" class="max-w-md text-center">
			{loadError}
		</Surface>
	{:else if empty}
		<div
			class="max-w-md rounded-md border border-border bg-card p-6 text-center text-card-foreground"
		>
			<h1 class="text-title font-semibold">{$_('workspace.mail')}</h1>
			<!-- Focus goes straight to the action, which a reader would announce
			     by its name alone; the description is what says why it is there. -->
			<p id="empty-mail-reason" class="mt-2 text-sm text-muted-foreground">
				{empty === 'noAccounts' ? $_('accounts.none') : $_('root.noFolders')}
			</p>
			{#if empty === 'noAccounts'}
				<Button
					autofocus
					aria-describedby="empty-mail-reason"
					class="mt-4"
					onclick={() => goto(resolve('/settings/accounts/new'))}
				>
					{$_('accounts.addAccount')}
				</Button>
			{:else}
				<Button
					aria-describedby="empty-mail-reason"
					class="mt-4"
					href={resolve('/settings/accounts')}
				>
					{$_('root.goToAccounts')}
				</Button>
			{/if}
		</div>
	{:else}
		<span role="status">{$_('root.loadingMail')}</span>
	{/if}
</div>
