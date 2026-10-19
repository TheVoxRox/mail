<script lang="ts">
	import { tick } from 'svelte';
	import { autocompleteContacts, forgetHistorySuggestion } from '#lib/api/contacts.js';
	import { chipVariants } from '#lib/components/ui/chip/index.js';
	import { focusRing, focusRingInset } from '#lib/components/ui/focus-ring/index.js';
	import { menuContentVariants, menuItemVariants } from '#lib/components/ui/menu/index.js';
	import { clientConfig } from '#lib/stores/clientConfig.js';
	import { announcePolite, pushToast } from '#lib/stores/toasts.js';
	import { cn } from '#lib/utils.js';
	import { _ } from '#lib/i18n/index.js';
	import {
		isValidEmailAddress,
		parseAddressList,
		serializeAddressList
	} from '#lib/compose/addresses.js';
	import type { ContactAutocompleteResponse } from '#lib/types.js';

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
	/*
	 * Part of every option id, bumped when a forgotten suggestion leaves the list.
	 * The highlight then stays on the same position, so without a new id the
	 * active descendant would keep its value and the screen reader would not
	 * read the suggestion that took the forgotten one's place.
	 */
	let suggestionGeneration = $state(0);
	/*
	 * Whether the user has moved through the open list with an arrow key. Delete
	 * forgets a suggestion only then: the list opens with its first row
	 * highlighted, and the key must not remove a suggestion the user has not
	 * heard.
	 */
	let navigatedSuggestions = false;
	/** The suggestion being forgotten, so a second press does not send it twice. */
	let forgettingId: number | null = null;
	let inputElement = $state<HTMLInputElement | null>(null);
	let lastEmittedValue = '';
	let autocompleteRun = 0;

	$effect(() => {
		if (value === lastEmittedValue) return;
		tokens = parseAddressList(value);
		inputValue = '';
		lastEmittedValue = value;
	});

	/*
	 * Autofocus belongs to the first render. It waits for the field to be enabled,
	 * because the composer stays disabled until prefill lands, but it fires once:
	 * `disabled` also flips on every save and send, and re-running here pulled
	 * focus into this field each time one of them failed.
	 */
	let autofocused = false;
	$effect(() => {
		if (autofocused || !autofocus || disabled) return;
		autofocused = true;
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
		showSuggestions([]);
	}

	function showSuggestions(next: ContactAutocompleteResponse[]): void {
		suggestions = next;
		activeSuggestion = 0;
		navigatedSuggestions = false;
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
			showSuggestions([]);
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
			showSuggestions(results.filter((item) => !existing.includes(item.email.toLowerCase())));
		} catch {
			if (run === autocompleteRun) showSuggestions([]);
		}
	}

	/*
	 * Forgets a history suggestion on the server, then takes it out of the open
	 * list with the highlight left on the same position, which is the next
	 * suggestion. The address comes back with the next message exchanged with it.
	 */
	async function forgetSuggestion(suggestion: ContactAutocompleteResponse): Promise<void> {
		if (suggestion.historyId == null || suggestion.historyId === forgettingId) return;
		forgettingId = suggestion.historyId;
		// A lookup still in flight was answered before the forget and would put the
		// suggestion back.
		autocompleteRun += 1;
		try {
			await forgetHistorySuggestion(suggestion.historyId);
		} catch {
			pushToast($_('compose.suggestionForgetFailed', { values: { address: suggestion.email } }), {
				tone: 'error'
			});
			return;
		} finally {
			forgettingId = null;
		}
		const index = suggestions.findIndex((item) => item.email === suggestion.email);
		if (index >= 0) {
			suggestions = suggestions.filter((_, itemIndex) => itemIndex !== index);
			if (index < activeSuggestion) activeSuggestion -= 1;
			activeSuggestion = Math.min(activeSuggestion, Math.max(suggestions.length - 1, 0));
			suggestionGeneration += 1;
		}
		announcePolite($_('compose.suggestionForgotten', { values: { address: suggestion.email } }));
	}

	/*
	 * Delete on the highlighted suggestion forgets it, as in Outlook's address
	 * list. Only where the key does nothing in the field anyway (caret at the end,
	 * nothing selected, no modifier), so it never takes deleting a character
	 * away; and never on auto-repeat, which in Outlook empties the whole list
	 * while the key is held. Changing when it applies means changing its row in
	 * routes/settings/shortcuts/+page.svelte — it is a hand-maintained mirror.
	 */
	function isForgetGesture(event: KeyboardEvent): boolean {
		if (event.key !== 'Delete' || event.repeat) return false;
		if (event.ctrlKey || event.altKey || event.shiftKey || event.metaKey) return false;
		if (!navigatedSuggestions || !suggestions[activeSuggestion] || !inputElement) return false;
		const end = inputElement.value.length;
		return inputElement.selectionStart === end && inputElement.selectionEnd === end;
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
			navigatedSuggestions = true;
			return;
		}
		if (event.key === 'ArrowUp' && suggestions.length > 0) {
			event.preventDefault();
			activeSuggestion = Math.max(activeSuggestion - 1, 0);
			navigatedSuggestions = true;
			return;
		}
		if (isForgetGesture(event)) {
			event.preventDefault();
			const suggestion = suggestions[activeSuggestion];
			if (suggestion.source === 'HISTORY') {
				void forgetSuggestion(suggestion);
			} else {
				// Said rather than ignored: a key that does nothing is silence, and
				// silence does not tell the user where the address can be removed.
				announcePolite($_('compose.suggestionIsContact'));
			}
			return;
		}
		if (event.key === 'Escape' && suggestions.length > 0) {
			event.preventDefault();
			// Closing the suggestion popup must not bubble to the compose-level
			// Escape shortcut, which would open the discard dialog on top of it.
			event.stopPropagation();
			showSuggestions([]);
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
		<label for={id} class="w-20 shrink-0 py-2.5 text-sm font-medium text-foreground">
			{label}
		</label>
		<div class="relative flex min-h-10 min-w-0 flex-1 flex-wrap items-center gap-1.5 py-1.5">
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
					? `${id}-suggestion-${suggestionGeneration}-${activeSuggestion}`
					: undefined}
				aria-keyshortcuts={shortcut}
				class={cn(
					'min-w-[min(10rem,100%)] flex-1 border-0 bg-transparent py-1 text-sm text-foreground disabled:cursor-not-allowed disabled:opacity-50',
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
							id={`${id}-suggestion-${suggestionGeneration}-${index}`}
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
								<span class="flex shrink-0 items-center gap-1">
									<!-- `bg-background`, not `bg-muted`: the highlighted row is muted. -->
									<span
										class="rounded-sm bg-background px-1.5 py-0.5 text-xs text-muted-foreground"
									>
										{$_('compose.suggestionFromHistory')}
									</span>
									<!--
										The mouse's way to forget the suggestion, as Outlook's list has one.
										Hidden from the accessibility tree and not focusable: a control
										inside an option is not one a listbox can reach, and the keyboard
										way is Delete on the highlighted row. stopPropagation keeps the
										click from picking the suggestion it removes. 24 px for the
										target size, with a negative margin so the row stays as tall as a
										contact's.
									-->
									<span
										aria-hidden="true"
										title={$_('compose.forgetSuggestion')}
										class="-my-0.5 inline-flex size-6 cursor-pointer items-center justify-center rounded-sm text-muted-foreground hover:bg-background hover:text-foreground"
										onclick={(event) => {
											event.stopPropagation();
											void forgetSuggestion(suggestion);
										}}
									>
										x
									</span>
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
