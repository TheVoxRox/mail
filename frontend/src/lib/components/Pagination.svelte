<script lang="ts">
	import { Button } from '$lib/components/ui/button/index.js';
	import { _ } from '$lib/i18n/index.js';

	interface Props {
		page: number;
		totalPages: number;
		totalElements: number;
		first: boolean;
		last: boolean;
		onPrev: () => void;
		onNext: () => void;
		landmarkLabel: string;
		onFirst?: () => void;
		onLast?: () => void;
		onJump?: (page: number) => void;
		pageInfoKey?: string;
		totalCountKey?: string;
		prevLabelKey?: string;
		nextLabelKey?: string;
		firstLabelKey?: string;
		lastLabelKey?: string;
		jumpLabelKey?: string;
		jumpButtonKey?: string;
	}

	let {
		page,
		totalPages,
		totalElements,
		first,
		last,
		onPrev,
		onNext,
		landmarkLabel,
		onFirst,
		onLast,
		onJump,
		pageInfoKey = 'messages.pageInfo',
		totalCountKey = 'messages.totalCount',
		prevLabelKey = 'messages.prevPage',
		nextLabelKey = 'messages.nextPage',
		firstLabelKey = 'messages.firstPage',
		lastLabelKey = 'messages.lastPage',
		jumpLabelKey = 'messages.jumpLabel',
		jumpButtonKey = 'messages.jumpButton'
	}: Props = $props();

	const pageCount = $derived(Math.max(1, totalPages));

	let jumpValue = $derived(page + 1);

	let firstButtonEl = $state<HTMLButtonElement | null>(null);
	let prevButtonEl = $state<HTMLButtonElement | null>(null);
	let nextButtonEl = $state<HTMLButtonElement | null>(null);
	let lastButtonEl = $state<HTMLButtonElement | null>(null);

	// The boundary buttons get disabled when their direction is exhausted, which
	// drops focus to the document body — disorienting for keyboard / screen-reader
	// users. We remember which direction the user navigated and, once the new page
	// lands, move focus to the still-enabled counterpart if their button is now
	// disabled. Plain (non-reactive) so it never itself re-triggers the effect.
	let pendingFocus: 'forward' | 'backward' | null = null;
	let lastSeenPage = -1;

	$effect(() => {
		const landedPage = page;
		const atFirst = first;
		const atLast = last;
		if (landedPage === lastSeenPage) return;
		lastSeenPage = landedPage;
		const direction = pendingFocus;
		pendingFocus = null;
		if (direction === 'forward' && atLast) {
			(prevButtonEl ?? firstButtonEl)?.focus();
		} else if (direction === 'backward' && atFirst) {
			(nextButtonEl ?? lastButtonEl)?.focus();
		}
	});

	function navigate(direction: 'forward' | 'backward', action: (() => void) | undefined) {
		if (!action) return;
		pendingFocus = direction;
		action();
	}

	function handleJump(event: SubmitEvent) {
		event.preventDefault();
		const target = Math.trunc(Number(jumpValue));
		if (!Number.isFinite(target)) {
			jumpValue = page + 1;
			return;
		}
		const clamped = Math.min(Math.max(1, target), pageCount);
		jumpValue = clamped;
		onJump?.(clamped);
	}
</script>

<nav
	aria-label={landmarkLabel}
	class="flex items-center justify-between gap-2 border-t border-border bg-muted px-4 py-2 text-xs"
>
	<span class="text-muted-foreground">
		{$_(pageInfoKey, {
			values: {
				current: page + 1,
				total: pageCount,
				totalCount: $_(totalCountKey, { values: { count: totalElements } })
			}
		})}
	</span>
	<div class="flex items-center gap-1">
		{#if onFirst}
			<Button
				bind:ref={firstButtonEl}
				variant="outline"
				size="xs"
				onclick={() => navigate('backward', onFirst)}
				disabled={first}
			>
				{$_(firstLabelKey)}
			</Button>
		{/if}
		<Button
			bind:ref={prevButtonEl}
			variant="outline"
			size="xs"
			onclick={() => navigate('backward', onPrev)}
			disabled={first}
		>
			{$_(prevLabelKey)}
		</Button>
		{#if onJump}
			<form class="flex items-center gap-1" onsubmit={handleJump}>
				<label class="flex items-center">
					<span class="sr-only">{$_(jumpLabelKey)}</span>
					<input
						type="number"
						inputmode="numeric"
						min="1"
						max={pageCount}
						bind:value={jumpValue}
						class="h-6 w-14 rounded-md border border-border bg-background px-1 text-center text-xs outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
					/>
				</label>
				<Button type="submit" variant="outline" size="xs">
					{$_(jumpButtonKey)}
				</Button>
			</form>
		{/if}
		<Button
			bind:ref={nextButtonEl}
			variant="outline"
			size="xs"
			onclick={() => navigate('forward', onNext)}
			disabled={last}
		>
			{$_(nextLabelKey)}
		</Button>
		{#if onLast}
			<Button
				bind:ref={lastButtonEl}
				variant="outline"
				size="xs"
				onclick={() => navigate('forward', onLast)}
				disabled={last}
			>
				{$_(lastLabelKey)}
			</Button>
		{/if}
	</div>
</nav>
