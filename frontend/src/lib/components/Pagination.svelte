<script lang="ts" module>
	/**
	 * The two label sets the pager is read with. They were eight optional key
	 * props before, which meant the contacts list restated all eight at its call
	 * site to say one thing: "this pager is over contacts, not messages". The
	 * keys stay written out as literals so `check:i18n` keeps seeing them used.
	 */
	const PAGINATION_LABELS = {
		messages: {
			pageInfo: 'messages.pageInfo',
			totalCount: 'messages.totalCount',
			prev: 'messages.prevPage',
			next: 'messages.nextPage',
			first: 'messages.firstPage',
			last: 'messages.lastPage',
			jumpLabel: 'messages.jumpLabel',
			jumpButton: 'messages.jumpButton'
		},
		contacts: {
			pageInfo: 'contacts.pageInfo',
			totalCount: 'contacts.totalCount',
			prev: 'contacts.prev',
			next: 'contacts.next',
			first: 'contacts.firstPage',
			last: 'contacts.lastPage',
			jumpLabel: 'contacts.jumpLabel',
			jumpButton: 'contacts.jumpButton'
		}
	} as const;

	export type PaginationVariant = keyof typeof PAGINATION_LABELS;
</script>

<script lang="ts">
	import { Button } from '$lib/components/ui/button/index.js';
	import { Input } from '$lib/components/ui/input/index.js';
	import { _ } from '$lib/i18n/index.js';
	import { clampPageTarget } from '$lib/paging.js';

	interface Props {
		page: number;
		totalPages: number;
		totalElements: number;
		first: boolean;
		last: boolean;
		/**
		 * Where to go, as a 0-based page index. The pager resolves every one of
		 * its five controls — including the 1-based jump box — to this, clamps it
		 * to the range and calls back only for a real move, so the caller is left
		 * with the load and nothing to re-derive. See lib/paging.ts.
		 */
		onNavigate: (target: number) => void;
		landmarkLabel: string;
		/** Which set of labels to read the pager with. */
		variant?: PaginationVariant;
	}

	let {
		page,
		totalPages,
		totalElements,
		first,
		last,
		onNavigate,
		landmarkLabel,
		variant = 'messages'
	}: Props = $props();

	const keys = $derived(PAGINATION_LABELS[variant]);
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

	/**
	 * A boundary button asking to move. The direction is remembered before the
	 * navigation so the effect above can rescue focus, and it comes from the
	 * target rather than from the call site — the two could not disagree, but
	 * only one of them can be wrong.
	 */
	function navigate(target: number) {
		const next = clampPageTarget(target, totalPages, page);
		if (next === null) return;
		pendingFocus = next > page ? 'forward' : 'backward';
		onNavigate(next);
	}

	/*
	 * The jump box deliberately does not go through `navigate`: its own button
	 * never gets disabled, so there is no focus to rescue, and arming
	 * `pendingFocus` here would move the reading cursor off the box the user is
	 * still typing in whenever the jump happened to land on a boundary page.
	 *
	 * It is the one control that speaks 1-based page numbers, because that is
	 * what the field shows. `clampPageTarget` works in indices, so the
	 * conversion happens here — once, instead of at every call site.
	 */
	function handleJump(event: SubmitEvent) {
		event.preventDefault();
		const target = Math.trunc(Number(jumpValue));
		if (!Number.isFinite(target)) {
			jumpValue = page + 1;
			return;
		}
		const clamped = Math.min(Math.max(1, target), pageCount);
		jumpValue = clamped;
		const next = clampPageTarget(clamped - 1, totalPages, page);
		if (next !== null) onNavigate(next);
	}
</script>

<nav
	aria-label={landmarkLabel}
	class="flex items-center justify-between gap-2 border-t border-border bg-muted px-4 py-2 text-xs"
>
	<span class="text-muted-foreground">
		{$_(keys.pageInfo, {
			values: {
				current: page + 1,
				total: pageCount,
				totalCount: $_(keys.totalCount, { values: { count: totalElements } })
			}
		})}
	</span>
	<div class="flex items-center gap-1">
		<Button
			bind:ref={firstButtonEl}
			variant="outline"
			size="xs"
			onclick={() => navigate(0)}
			disabled={first}
		>
			{$_(keys.first)}
		</Button>
		<Button
			bind:ref={prevButtonEl}
			variant="outline"
			size="xs"
			onclick={() => navigate(page - 1)}
			disabled={first}
		>
			{$_(keys.prev)}
		</Button>
		<!--
				`novalidate`, with min/max kept: the two attributes are what tells a
				screen reader the spin button's range, but leaving the browser to
				enforce them meant it refused to submit an out-of-range page and
				`handleJump`'s clamping never ran — the entry was rejected with a
				transient native bubble and the field kept a value that contradicted
				the page on screen. Clamping in the handler lands the user on a real
				page and the pager's live region says which one.
			-->
		<form class="flex items-center gap-1" novalidate onsubmit={handleJump}>
			<label class="flex items-center">
				<span class="sr-only">{$_(keys.jumpLabel)}</span>
				<Input
					type="number"
					inputmode="numeric"
					min="1"
					max={pageCount}
					bind:value={jumpValue}
					size="sm"
					class="h-6 w-14 px-1 text-center"
				/>
			</label>
			<Button type="submit" variant="outline" size="xs">
				{$_(keys.jumpButton)}
			</Button>
		</form>
		<Button
			bind:ref={nextButtonEl}
			variant="outline"
			size="xs"
			onclick={() => navigate(page + 1)}
			disabled={last}
		>
			{$_(keys.next)}
		</Button>
		<Button
			bind:ref={lastButtonEl}
			variant="outline"
			size="xs"
			onclick={() => navigate(totalPages - 1)}
			disabled={last}
		>
			{$_(keys.last)}
		</Button>
	</div>
</nav>
