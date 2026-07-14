import type { loadComposePrefill } from '$lib/compose/prefill.js';
import type { ComposeAttachment } from '$lib/compose/request.js';
import type { AccountResponse } from '$lib/types.js';

export type ComposePrefill = Awaited<ReturnType<typeof loadComposePrefill>>;

type ComposePrefillValues = {
	to: string;
	cc: string;
	bcc: string;
	subject: string;
	body: string;
	inReplyTo: string | null;
	references: string | null;
	replacesDraftId: string | null;
	attachments: ComposeAttachment[];
};

type ComposeShortcutHandlers = {
	formElement: HTMLFormElement | null;
	onSend: () => void;
	onSave: () => void;
	onDiscard: () => void;
	onFocusField: (fieldId: 'compose-cc' | 'compose-bcc') => void;
};

export function mapComposePrefill(prefill: ComposePrefill): ComposePrefillValues | null {
	if (!prefill) return null;

	return {
		to: prefill.to ?? '',
		cc: prefill.cc ?? '',
		bcc: prefill.bcc ?? '',
		subject: prefill.subject ?? '',
		body: prefill.body ?? '',
		inReplyTo: prefill.inReplyTo ?? null,
		references: prefill.references ?? null,
		replacesDraftId: prefill.replacesDraftId,
		attachments: prefill.attachments
	};
}

export function shouldFocusComposeBody(searchParams: URLSearchParams): boolean {
	return ['reply', 'forward', 'draft'].some((key) => searchParams.has(key));
}

export function targetHref(url: URL): string {
	return `${url.pathname}${url.search}${url.hash}`;
}

/*
 * Compose-scoped shortcuts (send, save, discard, focus Cc/Bcc). When a
 * shortcut changes here, update the user-facing overview in
 * routes/settings/shortcuts/+page.svelte — it is a hand-maintained mirror.
 */
export function handleComposeShortcuts(
	event: KeyboardEvent,
	handlers: ComposeShortcutHandlers
): void {
	const { formElement, onSend, onSave, onDiscard, onFocusField } = handlers;
	if (!formElement || !(event.target instanceof Node) || !formElement.contains(event.target)) {
		return;
	}

	if (event.key === 'Escape') {
		event.preventDefault();
		event.stopPropagation();
		onDiscard();
		return;
	}

	if (!event.ctrlKey || event.metaKey || event.altKey) return;
	const key = event.key.toLowerCase();

	if (event.shiftKey) {
		if (key === 'c' || key === 'b' || key === 'd') {
			event.preventDefault();
			event.stopPropagation();
			if (key === 'd') {
				onDiscard();
				return;
			}
			onFocusField(key === 'c' ? 'compose-cc' : 'compose-bcc');
		}
		return;
	}

	if (event.key === 'Enter') {
		event.preventDefault();
		event.stopPropagation();
		onSend();
		return;
	}

	if (key === 's') {
		event.preventDefault();
		event.stopPropagation();
		onSave();
	}
}

/**
 * From-account resolution for the composer (F4): when the account list
 * (re)settles, keep a still-valid selection; otherwise prefer the account the
 * user was viewing (active) over the first in the list. The active id may be
 * unset or stale (e.g. the account was just removed) — fall back then.
 */
export function resolveComposeFromAccount(
	current: number | null,
	accounts: AccountResponse[],
	activeId: number | null
): number | null {
	if (current != null && accounts.some((account) => account.id === current)) return current;
	if (activeId != null && accounts.some((account) => account.id === activeId)) return activeId;
	return accounts[0]?.id ?? null;
}
