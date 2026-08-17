/**
 * The contact avatar palette — the one place in the app allowed to name
 * Tailwind palette colours instead of tokens, and the reason `check:design`
 * carries an exception for this path.
 *
 * These are *categorical* colours: eight hues that only have to be told apart
 * from each other, picked by hashing a contact so the same person keeps the
 * same colour. A semantic token cannot do that job — there is no meaning to
 * attach, and eight token pairs times two themes would be thirty-two lines in
 * `app.css` describing nothing but "a different hue from the last one".
 *
 * Each entry pairs a light fill with a dark text of the same hue, so the pair
 * carries its own contrast and stays fixed in both themes: an avatar is a
 * small coloured disc that should read the same way whichever theme is on.
 */

const AVATAR_PALETTE = [
	'bg-rose-200 text-rose-900',
	'bg-amber-200 text-amber-900',
	'bg-emerald-200 text-emerald-900',
	'bg-sky-200 text-sky-900',
	'bg-violet-200 text-violet-900',
	'bg-pink-200 text-pink-900',
	'bg-teal-200 text-teal-900',
	'bg-indigo-200 text-indigo-900'
] as const;

function hashString(value: string): number {
	let hash = 0;
	for (let i = 0; i < value.length; i++) {
		hash = (hash << 5) - hash + value.charCodeAt(i);
		hash |= 0;
	}
	return Math.abs(hash);
}

/** Stable per-seed avatar colour. The same seed always returns the same pair. */
export function avatarColorClass(seed: string): string {
	return AVATAR_PALETTE[hashString(seed) % AVATAR_PALETTE.length];
}
