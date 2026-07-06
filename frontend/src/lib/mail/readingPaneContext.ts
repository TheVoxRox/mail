/**
 * Shares the *effective* reading-pane mode between `+layout.svelte` (which
 * derives it from the `readingPane` setting + window width — narrow windows
 * degrade split modes to `off`) and `MessageList.svelte` (which must know
 * whether a row change may follow focus with a selection).
 *
 * In split modes (`right`/`bottom`) the list stays mounted next to the
 * detail, so Arrow/Page navigation opens the focused row in the pane. In
 * `off` mode the detail *replaces* the list — following focus would tear the
 * user out of the list, so a row change only moves focus and the message
 * opens on Enter/Space.
 */
import type { ReadingPane } from '$lib/stores/uiLayout.js';

export interface EffectiveReadingPaneContext {
	pane: ReadingPane;
}

export const EFFECTIVE_READING_PANE_CONTEXT_KEY = Symbol('mail.effectiveReadingPane');
