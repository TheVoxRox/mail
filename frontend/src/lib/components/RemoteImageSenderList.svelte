<script lang="ts">
	import { onMount, tick } from 'svelte';
	import { SvelteSet } from 'svelte/reactivity';
	import { listRemoteImageSenders } from '#lib/api/remoteImages.js';
	import { distrustRemoteImageSender } from '#lib/mail/remoteImageTrust.js';
	import { toErrorMessage } from '#lib/api/errors.js';
	import { Button } from '#lib/components/ui/button/index.js';
	import { StateMessage } from '#lib/components/ui/state-message/index.js';
	import { Surface } from '#lib/components/ui/surface/index.js';
	import { _ } from '#lib/i18n/index.js';
	import { pushToast } from '#lib/stores/toasts.js';

	/*
	 * The senders an account trusts to load remote images, with the way to take
	 * that trust back. "Always load images from <address>" in an open message
	 * was a one-way door until this list existed: the backend could list and
	 * remove senders from the start, and nothing in the app called either.
	 */
	let { accountId }: { accountId: number } = $props();

	const uid = $props.id();
	const headingId = `${uid}-heading`;

	let senders = $state<string[] | null>(null);
	let loadError = $state<string | null>(null);
	let listElement = $state<HTMLUListElement | null>(null);
	let emptyElement = $state<HTMLParagraphElement | null>(null);
	// Not a disabled button: disabling the pressed one drops focus to <body>
	// for the length of the request. A second press is ignored instead.
	const removing = new SvelteSet<string>();

	onMount(async () => {
		try {
			senders = await listRemoteImageSenders(accountId);
		} catch (err) {
			loadError = toErrorMessage(err);
		}
	});

	/*
	 * The pressed button leaves with its row, so focus is given a place before
	 * it can fall to <body>: the row that moved up into the gap, the new last
	 * row when the removed one was last, and the empty-state sentence once
	 * nothing is left. The toast says what happened; focus says where you are.
	 */
	async function remove(sender: string): Promise<void> {
		if (!senders || removing.has(sender)) return;
		removing.add(sender);
		try {
			await distrustRemoteImageSender(accountId, sender);
		} catch (err) {
			console.warn('[settings] failed to remove a remote-image sender', err);
			pushToast($_('accounts.remoteImages.removeError', { values: { email: sender } }), {
				tone: 'error'
			});
			return;
		} finally {
			removing.delete(sender);
		}

		const index = senders.indexOf(sender);
		senders = senders.filter((entry) => entry !== sender);
		pushToast($_('accounts.remoteImages.removed', { values: { email: sender } }), {
			tone: 'success'
		});
		await tick();
		const buttons = listElement?.querySelectorAll<HTMLButtonElement>('button') ?? [];
		const next = buttons[Math.min(index, buttons.length - 1)];
		if (next) next.focus();
		else emptyElement?.focus();
	}
</script>

<Surface as="section" class="max-w-2xl space-y-3">
	<h2 id={headingId} class="text-sm font-semibold text-foreground">
		{$_('accounts.remoteImages.heading')}
	</h2>
	<p class="text-sm text-muted-foreground">{$_('accounts.remoteImages.description')}</p>

	{#if loadError}
		<Surface variant="danger" padding="sm">
			<p role="alert" class="text-sm">
				{$_('accounts.remoteImages.loadError', { values: { message: loadError } })}
			</p>
		</Surface>
	{:else if senders === null}
		<StateMessage padding="none">{$_('common.loading')}</StateMessage>
	{:else if senders.length === 0}
		<StateMessage bind:ref={emptyElement} padding="none" tabindex={-1}>
			{$_('accounts.remoteImages.empty')}
		</StateMessage>
	{:else}
		<ul bind:this={listElement} aria-labelledby={headingId} class="divide-y divide-border">
			{#each senders as sender (sender)}
				<li class="flex items-center justify-between gap-3 py-2">
					<span class="min-w-0 text-sm break-all text-foreground">{sender}</span>
					<Button
						variant="outline"
						size="sm"
						aria-label={$_('accounts.remoteImages.remove', { values: { email: sender } })}
						onclick={() => remove(sender)}
					>
						{$_('accounts.remoteImages.removeShort')}
					</Button>
				</li>
			{/each}
		</ul>
	{/if}
</Surface>
