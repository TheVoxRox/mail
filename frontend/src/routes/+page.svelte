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
	import { toErrorMessage } from '$lib/api/errors.js';
	import { Button } from '$lib/components/ui/button/index.js';
	import { Surface } from '$lib/components/ui/surface/index.js';
	import { _ } from '$lib/i18n/index.js';

	let emptyMail = $state(false);
	let loadError = $state<string | null>(null);
	let handledContext = $state<string | null>(null);

	async function redirect() {
		const state = get(accountsState);
		if (state.status !== 'ready') {
			return;
		}
		const context = state.accounts.map((account) => account.id).join(',');
		if (handledContext === context) return;
		handledContext = context;

		emptyMail = false;
		loadError = null;
		if (state.accounts.length === 0) {
			emptyMail = true;
			return;
		}

		const accountId = get(resolvedActiveAccountId) ?? state.accounts[0].id;
		setActiveAccount(accountId);

		const folders = await loadFolders(accountId).catch((err) => {
			loadError = toErrorMessage(err);
			return [];
		});
		const inbox = folders.find((f) => f.role === 'INBOX') ?? folders[0];
		if (!inbox) {
			emptyMail = true;
			return;
		}

		await goto(
			resolve('/mail/[accountId]/[folderName]', {
				accountId: String(accountId),
				folderName: encodeURIComponent(inbox.folderRef)
			}),
			{ replaceState: true }
		);
	}

	$effect(() => {
		if ($accountsState.status !== 'ready') return;
		void redirect();
	});
</script>

<div class="flex h-full items-center justify-center text-sm text-muted-foreground">
	{#if loadError}
		<Surface variant="danger" role="alert" class="max-w-md text-center">
			{loadError}
		</Surface>
	{:else if emptyMail}
		<div
			class="max-w-md rounded-md border border-border bg-card p-6 text-center text-card-foreground"
		>
			<h1 class="text-title font-semibold">{$_('workspace.mail')}</h1>
			<p class="mt-2 text-sm text-muted-foreground">{$_('accounts.none')}</p>
			<Button autofocus class="mt-4" onclick={() => goto(resolve('/settings/accounts/new'))}>
				{$_('accounts.addAccount')}
			</Button>
		</div>
	{:else}
		<span role="status">{$_('root.loadingMail')}</span>
	{/if}
</div>
