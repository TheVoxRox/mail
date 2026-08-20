<script lang="ts">
	import { getContext } from 'svelte';
	import { page } from '$app/stores';
	import type { ContactResponse, MailDetailResponse } from '$lib/types.js';
	import { _, appLocale } from '$lib/i18n/index.js';
	import { Button } from '$lib/components/ui/button/index.js';
	import { Surface } from '$lib/components/ui/surface/index.js';
	import { formatFullDateTime } from '$lib/formatters.js';
	import { contactCreateHref, contactEditHref } from '$lib/contacts/prefill.js';
	import { findContactByEmail } from '$lib/contacts/lookup.js';
	import { senderContactSeed } from '$lib/contacts/senderContact.js';
	import {
		MESSAGE_HEADING_CONTEXT_KEY,
		type MessageHeadingContext
	} from '$lib/components/message-detail/messageHeadingContext.js';

	type Props = {
		detail: MailDetailResponse;
		onBack: () => void;
	};

	let { detail, onBack }: Props = $props();

	/*
	 * The sender line carries one address action: put this person in the address
	 * book, or open the contact they already are. It sits by the address rather
	 * than in the message action menu because it is about the address — reading
	 * order gives it right after the name it belongs to, and a menu item would
	 * have to name the address in its label to say the same thing.
	 */
	const seed = $derived(senderContactSeed(detail.sender));

	/**
	 * The lookup answer, tagged with the address it answers for. Both halves
	 * matter: the request is per opened message and the component is reused
	 * across messages, so an answer that arrives after the reader has moved on
	 * belongs to the previous sender and must not be shown.
	 */
	let lookup = $state<{ email: string; contact: ContactResponse | null } | null>(null);

	$effect(() => {
		const email = seed?.email;
		if (!email) {
			lookup = null;
			return;
		}
		let current = true;
		findContactByEmail(email)
			.then((contact) => {
				if (current) lookup = { email, contact };
			})
			.catch(() => {
				// A failed lookup leaves the line as it was. It is an affordance, not
				// content: a toast for it would interrupt reading the message to
				// report something the reader did not ask for.
				if (current) lookup = null;
			});
		return () => {
			current = false;
		};
	});

	/** Non-null only once the answer for the sender currently on screen is in. */
	const resolved = $derived(seed && lookup?.email === seed.email ? lookup : null);

	/** Back to this message once the contact form is done with. */
	const returnTo = $derived($page.url.pathname);

	/*
	 * `+layout.svelte` passes the heading level and back-button visibility
	 * via Svelte context — in off mode (standalone detail route, no split)
	 * the subject is `<h1>` and the back button is replaced by a breadcrumb
	 * in the top bar. The default fallback (level=2 + back button) keeps
	 * the component functional outside the mail route (e.g. in an isolated
	 * story / dev preview) without throwing.
	 */
	const heading: MessageHeadingContext = getContext<MessageHeadingContext>(
		MESSAGE_HEADING_CONTEXT_KEY
	) ?? { level: 2, showBackButton: true };
</script>

<!--
	The address action, in whichever of its two states. Inline with the address
	it belongs to, so it stays on the same line and baseline as the sender —
	the accessible name is what says which address it means.
-->
{#snippet addressLink(href: string, text: string, label: string)}
	<Button
		variant="link"
		size="sm"
		class="ml-1.5 h-auto p-0 align-baseline"
		{href}
		aria-label={label}
	>
		{text}
	</Button>
{/snippet}

<div class="border-b border-border bg-background px-5 py-4">
	<Surface variant="subtle" padding="default" class="min-w-0">
		{#if heading.showBackButton}
			<div class="mb-3">
				<Button variant="ghost" size="sm" class="-ml-2" onclick={onBack}>
					{$_('detail.actionBack')}
				</Button>
			</div>
		{/if}
		{#if heading.level === 1}
			<h1 class="text-lg font-semibold leading-tight">
				{detail.subject || $_('messages.noSubject')}
			</h1>
		{:else}
			<h2 class="text-lg font-semibold leading-tight">
				{detail.subject || $_('messages.noSubject')}
			</h2>
		{/if}
		<div class="mt-3 grid gap-1.5 text-sm text-muted-foreground">
			<p>
				<span class="mr-1.5 font-medium text-foreground">{$_('detail.from')}</span>
				{detail.sender}
				{#if resolved?.contact}
					{@render addressLink(
						contactEditHref(resolved.contact.id, returnTo),
						$_('detail.editContact'),
						$_('detail.editContactFor', { values: { email: resolved.email } })
					)}
				{:else if resolved && seed}
					{@render addressLink(
						contactCreateHref(seed, returnTo),
						$_('detail.addToContacts'),
						$_('detail.addToContactsFor', { values: { email: seed.email } })
					)}
				{/if}
			</p>
			{#if detail.recipientsTo}
				<p>
					<span class="mr-1.5 font-medium text-foreground">{$_('detail.to')}</span>
					{detail.recipientsTo}
				</p>
			{/if}
			{#if detail.recipientsCc}
				<p>
					<span class="mr-1.5 font-medium text-foreground">{$_('detail.cc')}</span>
					{detail.recipientsCc}
				</p>
			{/if}
			<p>
				<span class="mr-1.5 font-medium text-foreground">{$_('detail.date')}</span>
				{formatFullDateTime(detail.receivedAt, $appLocale ?? 'cs')}
			</p>
		</div>
	</Surface>
</div>
