/**
 * How much of the mail view the message list takes beside or above the
 * reading pane, as presets one click can choose (WCAG 2.5.7).
 *
 * The divider between the list and the reading pane (SplitPane.svelte) sizes
 * them by dragging, or by the arrow keys once it has focus. The criterion asks
 * for a way that needs neither a drag nor a keyboard, so Settings › Appearance
 * offers these presets as a select. They write the same values the divider
 * keeps, under the same keys, so the divider opens where the preset put it and
 * a later drag simply moves on from there. SplitPane reads its value when the
 * mail view mounts, which is the next time Mail opens after the setting.
 */
import { browser } from '$app/env';

export const LIST_SIZES = ['smaller', 'default', 'larger'] as const;
export type ListSize = (typeof LIST_SIZES)[number];

/** Where the split is: the list beside the pane, or above it. */
export type SplitOrientation = 'right' | 'bottom';

/** The divider's stored percentage per reading-pane position. */
export const SPLIT_SIZE_KEYS: Record<SplitOrientation, string> = {
	right: 'mail.splitSize.right',
	bottom: 'mail.splitSize.bottom'
};

/**
 * The list's share of the split per preset, in percent: its width with the
 * pane on the right, its height with the pane below. `default` is what the
 * divider starts at, and every value sits inside its 20–80 range.
 */
export const LIST_SIZE_PERCENT: Record<ListSize, Record<SplitOrientation, number>> = {
	smaller: { right: 25, bottom: 30 },
	default: { right: 35, bottom: 45 },
	larger: { right: 50, bottom: 60 }
};

function stored(orientation: SplitOrientation): number {
	if (!browser) return LIST_SIZE_PERCENT.default[orientation];
	try {
		const raw = window.localStorage.getItem(SPLIT_SIZE_KEYS[orientation]);
		const value = raw === null ? NaN : Number(raw);
		return Number.isFinite(value) ? value : LIST_SIZE_PERCENT.default[orientation];
	} catch {
		return LIST_SIZE_PERCENT.default[orientation];
	}
}

/**
 * The preset both stored sizes match, or `custom` once a drag of the divider
 * has left either of them somewhere else.
 */
export function currentListSize(): ListSize | 'custom' {
	const right = stored('right');
	const bottom = stored('bottom');

	return (
		LIST_SIZES.find(
			(size) => LIST_SIZE_PERCENT[size].right === right && LIST_SIZE_PERCENT[size].bottom === bottom
		) ?? 'custom'
	);
}

/** Writes the preset for both pane positions, so it holds whichever is in use. */
export function setListSize(size: ListSize): void {
	if (!browser) return;
	try {
		for (const orientation of ['right', 'bottom'] as const) {
			window.localStorage.setItem(
				SPLIT_SIZE_KEYS[orientation],
				String(LIST_SIZE_PERCENT[size][orientation])
			);
		}
	} catch {
		// Persistence is best-effort, as for the divider itself.
	}
}
