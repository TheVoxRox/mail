/**
 * Pure helpers for inserting a per-account signature into the compose body.
 *
 * Phase 1 scope (see todo.md "message signatures"): the signature is appended only to
 * brand-new messages and `mailto:` links — never to replies, forwards or edited
 * drafts (a draft already carries its signature, and reply/forward placement
 * around quoted text is deferred to Phase 2). The block uses the RFC 3676 §4.3
 * separator line `"-- "` so downstream clients recognise it as a signature.
 *
 * Kept free of Svelte/DOM so the ComposeForm wiring stays unit-testable.
 */

export type ComposeKind = 'reply' | 'forward' | 'draft' | 'mailto' | 'new';

/**
 * Classifies a compose route by its query params. Mirrors the branch priority in
 * {@link loadComposePrefill} (`reply` > `forward` > `to` > `draft`) so the kind
 * always matches the prefill that was actually loaded.
 */
export function composeKind(params: URLSearchParams): ComposeKind {
	if (params.has('reply')) return 'reply';
	if (params.has('forward')) return 'forward';
	if (params.has('to')) return 'mailto';
	if (params.has('draft')) return 'draft';
	return 'new';
}

/** Whether the composer manages (inserts / swaps) the signature for this kind. */
export function signatureManagedForKind(kind: ComposeKind): boolean {
	return kind === 'new' || kind === 'mailto';
}

/**
 * The exact block appended for a given signature: a blank line, the RFC 3676
 * `"-- "` separator line, then the (right-trimmed) signature text. Empty / blank
 * signatures produce no block (`''`), which makes append / swap no-ops.
 *
 * The format is deterministic so {@link swapSignature} can detect and replace a
 * previously-appended block verbatim.
 */
export function signatureBlock(signature: string | null | undefined): string {
	const trimmed = (signature ?? '').replace(/[ \t\r\n]+$/u, '');
	return trimmed.length === 0 ? '' : `\n\n-- \n${trimmed}`;
}

/** Appends the signature block to `body`. No-op for an empty signature. */
export function appendSignature(body: string, signature: string | null | undefined): string {
	return body + signatureBlock(signature);
}

/**
 * Swaps the signature when the user changes the From account.
 *
 * - If the previously-applied block is still present verbatim at the end of the
 *   body, it is replaced with the new account's block.
 * - If the previous account had no signature, the new one is appended.
 * - If the user edited or deleted the managed block, the body is left untouched
 *   and management stops (we never resurrect a signature the user removed).
 *
 * Returns the new body together with the signature now considered "applied", so
 * the caller can track it for the next swap.
 */
export function swapSignature(
	body: string,
	appliedSignature: string,
	nextSignature: string | null | undefined
): { body: string; appliedSignature: string } {
	const prevBlock = signatureBlock(appliedSignature);
	const nextBlock = signatureBlock(nextSignature);
	const next = nextSignature ?? '';

	if (prevBlock.length === 0) {
		// Previous account had no signature -> switching adds the new one.
		return { body: body + nextBlock, appliedSignature: next };
	}
	if (body.endsWith(prevBlock)) {
		// Managed block intact -> replace it with the new account's block.
		return {
			body: body.slice(0, body.length - prevBlock.length) + nextBlock,
			appliedSignature: next
		};
	}
	// User edited/removed the block -> leave it alone and keep tracking the old
	// value so a later switch still won't re-add anything.
	return { body, appliedSignature };
}
