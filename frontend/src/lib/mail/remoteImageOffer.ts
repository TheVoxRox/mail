/**
 * The open message's offer to load its blocked remote images, as the banner in
 * MessageContent publishes it while it is on screen.
 *
 * The banner is a silent region ahead of the body, and deliberately opening a
 * message puts the reading cursor past it, in the body. Two things outside the
 * banner therefore have to be able to reach it: the command palette, which
 * loads the images, and Ctrl+Shift+W, Outlook's key for the InfoBar, which
 * moves focus to the offer. Neither can read the component's own state, so the
 * component hands them the two actions for as long as the banner exists and
 * takes them back when it goes — images loaded, another message opened, the
 * plain-text view chosen.
 *
 * `null` means there is nothing to load: no message open, no remote image in
 * it, or the images already showing.
 */
import { writable } from 'svelte/store';

export type RemoteImageOffer = {
	stableId: string;
	/** Loads the images of this message only, exactly as the banner's first button does. */
	load: () => void;
	/** Moves focus to the banner's first button. */
	focus: () => void;
};

const offer = writable<RemoteImageOffer | null>(null);

export const remoteImageOffer = {
	subscribe: offer.subscribe
};

/**
 * Publishes the offer and returns the function that withdraws it. Withdrawing
 * leaves a newer offer alone: when one message replaces another, the new
 * banner may publish before the old one's cleanup runs.
 */
export function publishRemoteImageOffer(next: RemoteImageOffer): () => void {
	offer.set(next);
	return () => offer.update((current) => (current === next ? null : current));
}
