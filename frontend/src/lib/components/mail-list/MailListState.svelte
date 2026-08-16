<script lang="ts" generics="TItem">
	/**
	 * The idle/loading, error and empty presentations a mail list shows instead
	 * of its rows; the ready page is handed straight to the caller's `ready`
	 * snippet, so the branch that renders rows keeps its own markup.
	 *
	 * Shared by MessageList and ConversationList — the three non-ready states
	 * were identical in both down to the wording, and the empty one is a focus
	 * target: when the last row goes with a delete or a move, the control that
	 * removed it is gone too, and focus has to land on something that says so
	 * rather than falling to `<body>`. That is what the callers' focus-restore
	 * effects aim `emptyRef` at.
	 */
	import type { Snippet } from 'svelte';
	import { toErrorMessage } from '$lib/api/errors.js';
	import { StateMessage } from '$lib/components/ui/state-message/index.js';
	import { Surface } from '$lib/components/ui/surface/index.js';
	import { _ } from '$lib/i18n/index.js';
	import type { PagedListState } from '$lib/stores/createPagedListStore.js';
	import type { PagedResponse } from '$lib/types.js';

	interface Props {
		/** The list store's state. The context type is irrelevant here. */
		state: PagedListState<unknown, TItem>;
		/** The empty-state message element — see the note above. */
		emptyRef?: HTMLParagraphElement | null;
		ready: Snippet<[PagedResponse<TItem>]>;
	}

	let { state, emptyRef = $bindable(null), ready }: Props = $props();
</script>

{#if state.status === 'idle' || state.status === 'loading'}
	<div class="flex flex-1 items-center justify-center bg-background p-6">
		<Surface variant="subtle" padding="lg" class="max-w-sm text-center">
			<StateMessage padding="none" role="status">{$_('messages.loading')}</StateMessage>
		</Surface>
	</div>
{:else if state.status === 'error'}
	<div class="flex flex-1 items-center justify-center bg-background p-6">
		<Surface variant="danger" padding="sm" class="max-w-md">
			<StateMessage variant="error" padding="none" role="alert">
				{$_('messages.errorPrefix', { values: { message: toErrorMessage(state.error) } })}
			</StateMessage>
		</Surface>
	</div>
{:else if state.page.content.length === 0}
	<div class="flex flex-1 items-center justify-center bg-background p-6">
		<Surface variant="subtle" padding="lg" class="max-w-sm text-center">
			<StateMessage bind:ref={emptyRef} padding="none" role="status" tabindex={-1}>
				{$_('messages.empty')}
			</StateMessage>
		</Surface>
	</div>
{:else}
	{@render ready(state.page)}
{/if}
