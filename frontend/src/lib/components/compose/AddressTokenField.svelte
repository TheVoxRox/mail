<script lang="ts">
	import { tick } from 'svelte';
	import { autocompleteContacts } from '$lib/api/contacts.js';
	import { chipVariants } from '$lib/components/ui/chip/index.js';
	import { focusRing, focusRingInset } from '$lib/components/ui/focus-ring/index.js';
	import { menuContentVariants, menuItemVariants } from '$lib/components/ui/menu/index.js';
	import { clientConfig } from '$lib/stores/clientConfig.js';
	import { cn } from '$lib/utils.js';
	import { _ } from '$lib/i18n/index.js';
	import {
		isValidEmailAddress,
		parseAddressList,
		serializeAddressList
	} from '$lib/compose/addresses.js';
	import type { ContactAutocompleteResponse } from '$lib/types.js';

	interface Props {
		id: string;
		label: string;
		value: string;
		accountId: number | null;
		disabled?: boolean;
		error?: string;
		errorId?: string;
		autofocus?: boolean;
		shortcut?: string;
	}

	let {
		id,
		label,
		value = $bindable(''),
		accountId,
		disabled = false,
		error = '',
		errorId = `${id}-error`,
		autofocus = false,
		shortcut
	}: Props = $props();

	let tokens = $state<string[]>([]);
	let inputValue = $state('');
	let suggestions = $state<ContactAutocompleteResponse[]>([]);
	let activeSuggestion = $state(0);
	let inputElement = $state<HTMLInputElement | null>(null);
	let lastEmittedValue = '';
	let autocompleteRun = 0;

	$effect(() => {
		if (value === lastEmittedValue) return;
		tokens = parseAddressList(value);
		inputValue = '';
		lastEmittedValue = value;
	});

	$effect(() => {
		if (!autofocus || disabled) return;
		void tick().then(() => inputElement?.focus());
	});

	function emitTokens(nextTokens: string[]): void {
		tokens = nextTokens;
		lastEmittedValue = serializeAddressList(tokens);
		value = lastEmittedValue;
	}

	function emitDraftValue(): void {
		lastEmittedValue = serializeAddressList([...tokens, ...parseAddressList(inputValue)]);
		value = lastEmittedValue;
	}

	function addAddresses(addresses: string[]): void {
		const normalized = addresses.map((address) => address.trim()).filter(Boolean);
		if (normalized.length === 0) return;
		const seen = tokens.map((address) => address.toLowerCase());
		const next = [...tokens];
		for (const address of normalized) {
			const key = address.toLowerCase();
			if (seen.includes(key)) continue;
			seen.push(key);
			next.push(address);
		}
		emitTokens(next);
		inputValue = '';
		suggestions = [];
		activeSuggestion = 0;
	}

	function removeAddress(index: number): void {
		emitTokens(tokens.filter((_, tokenIndex) => tokenIndex !== index));
		void tick().then(() => inputElement?.focus());
	}

	function commitInput(): void {
		addAddresses(parseAddressList(inputValue));
	}

	function selectSuggestion(suggestion: ContactAutocompleteResponse): void {
		addAddresses([suggestion.email]);
	}

	function suggestionLabel(suggestion: ContactAutocompleteResponse): string {
		const name = [suggestion.name, suggestion.surname].filter(Boolean).join(' ').trim();
		return name ? `${name} <${suggestion.email}>` : suggestion.email;
	}

	async function updateSuggestions(query: string): Promise<void> {
		const run = (autocompleteRun += 1);
		const normalized = query.trim();
		if (!accountId || normalized.length < 2) {
			suggestions = [];
			return;
		}
		try {
			const limit = Math.min(
				$clientConfig.contactAutocompleteDefaultLimit,
				$clientConfig.contactAutocompleteMaxLimit
			);
			const results = await autocompleteContacts(accountId, normalized, limit);
			if (run !== autocompleteRun) return;
			const existing = tokens.map((address) => address.toLowerCase());
			suggestions = results.filter((item) => !existing.includes(item.email.toLowerCase()));
			activeSuggestion = 0;
		} catch {
			if (run === autocompleteRun) suggestions = [];
		}
	}

	function handleInput(): void {
		emitDraftValue();
		void updateSuggestions(inputValue);
	}

	function handlePaste(event: ClipboardEvent): void {
		const text = event.clipboardData?.getData('text') ?? '';
		if (!text || !/[,;\n]/.test(text)) return;
		event.preventDefault();
		addAddresses(parseAddressList(text));
	}

	function handleKeydown(event: KeyboardEvent): void {
		if (event.key === 'ArrowDown' && suggestions.length > 0) {
			event.preventDefault();
			activeSuggestion = Math.min(activeSuggestion + 1, suggestions.length - 1);
			return;
		}
		if (event.key === 'ArrowUp' && suggestions.length > 0) {
			event.preventDefault();
			activeSuggestion = Math.max(activeSuggestion - 1, 0);
			return;
		}
		if (event.key === 'Escape' && suggestions.length > 0) {
			event.preventDefault();
			// Closing the suggestion popup must not bubble to the compose-level
			// Escape shortcut, which would open the discard dialog on top of it.
			event.stopPropagation();
			suggestions = [];
			return;
		}
		if (event.key === 'Enter' && suggestions[activeSuggestion]) {
			event.preventDefault();
			selectSuggestion(suggestions[activeSuggestion]);
			return;
		}
		if (event.key === 'Enter' || event.key === 'Tab' || event.key === ',' || event.key === ';') {
			if (!inputValue.trim()) return;
			event.preventDefault();
			commitInput();
			return;
		}
		if (event.key === 'Backspace' && !inputValue && tokens.length > 0) {
			event.preventDefault();
			removeAddress(tokens.length - 1);
		}
	}
</script>

<div class="border-b border-border">
	<div class="flex items-start gap-2 px-4">
		<label for={id} class="w-20 shrink-0 py-2.5 text-sm font-medium text-muted-foreground">
			{label}
		</label>
		<div class="relative flex min-h-10 flex-1 flex-wrap items-center gap-1.5 py-1.5">
			{#each tokens as address, index (address)}
				<span class={chipVariants({ tone: isValidEmailAddress(address) ? 'default' : 'danger' })}>
					<span class="truncate">{address}</span>
					<button
						type="button"
						class={cn(
							'rounded-sm px-1 text-muted-foreground hover:text-foreground disabled:cursor-not-allowed disabled:opacity-50',
							focusRing
						)}
						{disabled}
						aria-label={$_('compose.removeAddress', { values: { address } })}
						onclick={() => removeAddress(index)}
					>
						x
					</button>
				</span>
			{/each}
			<input
				{id}
				bind:this={inputElement}
				type="text"
				bind:value={inputValue}
				{disabled}
				aria-invalid={error ? 'true' : undefined}
				aria-describedby={error ? errorId : undefined}
				aria-autocomplete="list"
				aria-controls={suggestions.length > 0 ? `${id}-suggestions` : undefined}
				aria-activedescendant={suggestions.length > 0
					? `${id}-suggestion-${activeSuggestion}`
					: undefined}
				aria-keyshortcuts={shortcut}
				class={cn(
					'min-w-40 flex-1 border-0 bg-transparent py-1 text-sm text-foreground disabled:cursor-not-allowed disabled:opacity-50',
					focusRingInset
				)}
				oninput={handleInput}
				onkeydown={handleKeydown}
				onpaste={handlePaste}
				onblur={commitInput}
			/>
			{#if suggestions.length > 0}
				<div
					id={`${id}-suggestions`}
					role="listbox"
					aria-label={$_('compose.suggestionsLabel')}
					class={cn(menuContentVariants(), 'absolute left-0 right-0 top-full z-20 mt-1 min-w-0')}
				>
					<!--
						Keyed by email, not emailId: history suggestions have no emailId, so
						every one of them would key to undefined and Svelte would treat them
						as the same item. Email is unique because the server deduplicates
						across both sources before responding.
					-->
					{#each suggestions as suggestion, index (suggestion.email)}
						<button
							type="button"
							id={`${id}-suggestion-${index}`}
							role="option"
							tabindex="-1"
							aria-selected={index === activeSuggestion}
							class={cn(
								menuItemVariants(),
								'items-center justify-between gap-2 hover:bg-muted',
								index === activeSuggestion && 'bg-muted'
							)}
							onmousedown={(event) => event.preventDefault()}
							onclick={() => selectSuggestion(suggestion)}
						>
							<span class="truncate">{suggestionLabel(suggestion)}</span>
							{#if suggestion.source === 'HISTORY'}
								<!--
									Real text inside the option, not a decorative badge next to it:
									in a listbox the screen reader announces the option's accessible
									name, so a marker rendered outside the button — or hidden from
									the accessibility tree — would never be heard, and the one thing
									distinguishing this row from an address book entry would be
									visual only.
								-->
								<!-- `bg-background`, not `bg-muted`: the highlighted row is muted. -->
								<span
									class="shrink-0 rounded-sm bg-background px-1.5 py-0.5 text-xs text-muted-foreground"
								>
									{$_('compose.suggestionFromHistory')}
								</span>
							{/if}
						</button>
					{/each}
				</div>
			{/if}
		</div>
	</div>
	{#if error}
		<div
			id={errorId}
			class="border-t border-border px-4 py-2 text-xs text-destructive-foreground"
			role="alert"
		>
			<span class="ml-20 block">{error}</span>
		</div>
	{/if}
</div>
