<script lang="ts">
	import { _ } from '$lib/i18n/index.js';
	import AddressTokenField from '$lib/components/compose/AddressTokenField.svelte';
	import { Select } from '$lib/components/ui/select/index.js';
	import type { AccountResponse } from '$lib/types.js';

	interface Props {
		to: string;
		cc: string;
		bcc: string;
		subject: string;
		fromAccountId: number | null;
		accounts: AccountResponse[];
		disabled?: boolean;
		toError?: string;
		toErrorId?: string;
		ccError?: string;
		ccErrorId?: string;
		bccError?: string;
		bccErrorId?: string;
		autofocusTo?: boolean;
	}

	let {
		to = $bindable(''),
		cc = $bindable(''),
		bcc = $bindable(''),
		subject = $bindable(''),
		fromAccountId = $bindable<number | null>(null),
		accounts,
		disabled = false,
		toError = '',
		toErrorId = 'compose-to-error',
		ccError = '',
		ccErrorId = 'compose-cc-error',
		bccError = '',
		bccErrorId = 'compose-bcc-error',
		autofocusTo = true
	}: Props = $props();

	function formatAccountLabel(account: AccountResponse): string {
		const display = account.displayName?.trim();
		return display ? `${display} <${account.email}>` : account.email;
	}
</script>

<div class="flex flex-col border-b border-border">
	{#if accounts.length > 1}
		<div class="flex items-center border-b border-border px-4">
			<label for="compose-from" class="w-20 shrink-0 text-sm font-medium text-muted-foreground">
				{$_('compose.fieldFrom')}
			</label>
			<Select
				id="compose-from"
				bind:value={fromAccountId}
				{disabled}
				class="flex-1 border-0 bg-transparent py-2.5 text-sm text-foreground outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-60"
			>
				{#each accounts as account (account.id)}
					<option value={account.id}>{formatAccountLabel(account)}</option>
				{/each}
			</Select>
		</div>
	{/if}
	<AddressTokenField
		id="compose-to"
		label={$_('compose.fieldTo')}
		bind:value={to}
		accountId={fromAccountId}
		{disabled}
		error={toError}
		errorId={toErrorId}
		autofocus={autofocusTo}
	/>
	<AddressTokenField
		id="compose-cc"
		label={$_('compose.fieldCc')}
		bind:value={cc}
		accountId={fromAccountId}
		{disabled}
		error={ccError}
		errorId={ccErrorId}
		shortcut="Control+Shift+C"
	/>
	<AddressTokenField
		id="compose-bcc"
		label={$_('compose.fieldBcc')}
		bind:value={bcc}
		accountId={fromAccountId}
		{disabled}
		error={bccError}
		errorId={bccErrorId}
		shortcut="Control+Shift+B"
	/>
	<div class="flex items-center px-4">
		<label for="compose-subject" class="w-20 shrink-0 text-sm font-medium text-muted-foreground">
			{$_('compose.fieldSubject')}
		</label>
		<input
			id="compose-subject"
			type="text"
			bind:value={subject}
			{disabled}
			class="flex-1 border-0 bg-transparent py-2.5 text-sm text-foreground outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring/50"
		/>
	</div>
</div>
