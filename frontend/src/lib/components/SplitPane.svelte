<script lang="ts">
	import { browser } from '$app/environment';
	import { cn } from '$lib/utils.js';
	import type { Snippet } from 'svelte';

	interface Props {
		orientation: 'vertical' | 'horizontal';
		storageKey: string;
		initialPercent?: number;
		minPercent?: number;
		maxPercent?: number;
		ariaLabel: string;
		first: Snippet;
		second: Snippet;
	}

	let {
		orientation,
		storageKey,
		initialPercent = 35,
		minPercent = 20,
		maxPercent = 80,
		ariaLabel,
		first,
		second
	}: Props = $props();

	let percent = $state(35);
	let loadedKey = $state<string | null>(null);
	let dragging = $state(false);
	let startPercent = $state(35);
	let startPointer = $state(0);
	let clientWidth = $state(0);
	let clientHeight = $state(0);

	const containerSize = $derived(orientation === 'vertical' ? clientWidth : clientHeight);
	const clampedPercent = $derived(clamp(percent));
	const separatorNow = $derived(Math.round(clampedPercent));

	function clamp(value: number): number {
		return Math.min(maxPercent, Math.max(minPercent, value));
	}

	function readStoredPercent(key: string): number | null {
		if (!browser) return null;
		try {
			const raw = window.localStorage.getItem(key);
			if (!raw) return null;
			const parsed = Number(raw);
			return Number.isFinite(parsed) ? clamp(parsed) : null;
		} catch {
			return null;
		}
	}

	function savePercent(key: string, value: number): void {
		if (!browser) return;
		try {
			window.localStorage.setItem(key, String(clamp(value)));
		} catch {
			// ignore
		}
	}

	function applyDelta(delta: number): void {
		percent = clamp(percent + delta);
	}

	function startDrag(event: PointerEvent): void {
		if (event.button !== 0) return;
		dragging = true;
		startPercent = clampedPercent;
		startPointer = orientation === 'vertical' ? event.clientX : event.clientY;
	}

	function stopDrag(): void {
		dragging = false;
	}

	function onPointerMove(event: PointerEvent): void {
		if (!dragging || containerSize <= 0) return;
		const currentPointer = orientation === 'vertical' ? event.clientX : event.clientY;
		const delta = currentPointer - startPointer;
		const nextPercent = startPercent + (delta / containerSize) * 100;
		percent = clamp(nextPercent);
	}

	function onKeydown(event: KeyboardEvent): void {
		switch (event.key) {
			case 'ArrowLeft':
				if (orientation !== 'vertical') return;
				event.preventDefault();
				applyDelta(-5);
				break;
			case 'ArrowRight':
				if (orientation !== 'vertical') return;
				event.preventDefault();
				applyDelta(5);
				break;
			case 'ArrowUp':
				if (orientation !== 'horizontal') return;
				event.preventDefault();
				applyDelta(-5);
				break;
			case 'ArrowDown':
				if (orientation !== 'horizontal') return;
				event.preventDefault();
				applyDelta(5);
				break;
			case 'Home':
				event.preventDefault();
				percent = minPercent;
				break;
			case 'End':
				event.preventDefault();
				percent = maxPercent;
				break;
		}
	}

	$effect(() => {
		if (loadedKey === storageKey) return;
		loadedKey = storageKey;
		percent = readStoredPercent(storageKey) ?? initialPercent;
	});

	$effect(() => {
		savePercent(storageKey, clampedPercent);
	});

	const layoutStyle = $derived.by(() => {
		if (orientation === 'vertical') {
			return `grid-template-columns: minmax(0, ${clampedPercent}fr) 0.625rem minmax(0, ${100 - clampedPercent}fr);`;
		}
		return `grid-template-rows: minmax(0, ${clampedPercent}fr) 0.625rem minmax(0, ${100 - clampedPercent}fr);`;
	});
</script>

<svelte:window onpointermove={onPointerMove} onpointerup={stopDrag} onpointercancel={stopDrag} />

<div
	class={cn('grid min-h-0 flex-1', orientation === 'vertical' ? 'grid-flow-col' : 'grid-flow-row')}
	style={layoutStyle}
	bind:clientWidth
	bind:clientHeight
>
	<section class="min-h-0 overflow-hidden">
		{@render first()}
	</section>

	<!-- svelte-ignore a11y_no_noninteractive_tabindex, a11y_no_noninteractive_element_interactions -->
	<div
		role="separator"
		tabindex="0"
		aria-label={ariaLabel}
		aria-orientation={orientation}
		aria-valuemin={minPercent}
		aria-valuemax={maxPercent}
		aria-valuenow={separatorNow}
		class={cn(
			'group relative flex items-center justify-center bg-border/80 outline-none transition-colors hover:bg-border focus-visible:bg-ring/60 focus-visible:ring-2 focus-visible:ring-ring',
			orientation === 'vertical' ? 'cursor-col-resize' : 'cursor-row-resize'
		)}
		onpointerdown={startDrag}
		onkeydown={onKeydown}
	>
		<div
			class={cn(
				'rounded-full bg-muted-foreground/60 group-hover:bg-foreground/50 group-focus-visible:bg-foreground/60',
				orientation === 'vertical' ? 'h-12 w-1.5' : 'h-1.5 w-12'
			)}
		></div>
	</div>

	<section class="min-h-0 overflow-hidden">
		{@render second()}
	</section>
</div>
