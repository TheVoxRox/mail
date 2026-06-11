import type { loadComposePrefill } from '$lib/compose/prefill.js';
import type { ComposeAttachment, ComposeDraft } from '$lib/compose/request.js';

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

type ComposeAutosaveSchedulerOptions = {
	delayMs: number;
	isBusy: () => boolean;
	onAutosave: () => Promise<unknown> | void;
};

function hasComposeContent(draft: ComposeDraft): boolean {
	return Boolean(
		draft.to || draft.cc || draft.bcc || draft.subject || draft.body || draft.attachments.length > 0
	);
}

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

export function createComposeAutosaveScheduler(options: ComposeAutosaveSchedulerOptions) {
	const { delayMs, isBusy, onAutosave } = options;
	let timer: ReturnType<typeof setTimeout> | null = null;

	function clear(): void {
		if (!timer) return;
		clearTimeout(timer);
		timer = null;
	}

	function schedule(draft: ComposeDraft, prefillDone: boolean): void {
		if (!prefillDone) return;
		clear();
		if (!hasComposeContent(draft)) return;
		timer = setTimeout(() => {
			timer = null;
			if (isBusy()) return;
			void onAutosave();
		}, delayMs);
	}

	return {
		schedule,
		clear
	};
}
