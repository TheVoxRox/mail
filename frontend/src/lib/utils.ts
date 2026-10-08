import { clsx, type ClassValue } from 'clsx';
import { extendTailwindMerge } from 'tailwind-merge';
import { createTV } from 'tailwind-variants';

/**
 * tailwind-merge knows only Tailwind's own font sizes and reads any other
 * `text-*` class as a colour, so a size app.css adds (`--text-caption`) lost to
 * the colour after it: `cn('text-caption text-muted-foreground')` kept only the
 * colour, and the text fell back to the inherited 16px. Every `--text-*` size in
 * app.css belongs in this list; utils.test.ts reads app.css and fails otherwise.
 */
const twMergeConfig = { extend: { theme: { text: ['caption'] } } };

const twMerge = extendTailwindMerge(twMergeConfig);

export function cn(...inputs: ClassValue[]): string {
	return twMerge(clsx(inputs));
}

/** tailwind-variants' `tv`, merging with the same configuration as `cn`. */
export const tv = createTV({ twMergeConfig });

export type WithElementRef<T, U extends HTMLElement = HTMLElement> = T & { ref?: U | null };
