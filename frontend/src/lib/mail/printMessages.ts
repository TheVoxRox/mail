/**
 * Printing messages: the open one, and the ticked rows of a mail list.
 *
 * The open message prints through the reading pane itself (`printOpenMessage`
 * and the print rules in app.css). A ticked row has nothing on screen to print,
 * so its detail and content are fetched here and handed to MessagePrintSheet,
 * which renders each body through the same sandboxed frame the reading pane
 * uses and starts the print once every frame has measured itself.
 *
 * Fetching the content opens the folder read-only on the server, so printing
 * does not mark anything as read.
 */
import { get, writable } from 'svelte/store';
import { getMessageContent, getMessageDetail } from '$lib/api/mailRead.js';
import { _ } from '$lib/i18n/index.js';
import { selectedMessage, type SelectedMessage } from '$lib/stores/selectedMessage.js';
import { announcePolite, pushToast } from '$lib/stores/toasts.js';
import type { MailContentResponse, MailDetailResponse } from '$lib/types.js';

/**
 * Why the open message cannot be printed, as an i18n key, or null when it can.
 * Until its body is there the reading pane shows a placeholder, and after a
 * failed load an error — either would go onto paper as if it were the mail.
 * A body that is there prints even beside an error: that is the cached copy
 * shown when a refresh failed, and it is the message.
 */
export function openMessagePrintRefusal(message: SelectedMessage | null): string | null {
	if (!message) return 'detail.nothingToPrint';
	if (message.content) return null;
	return message.loading ? 'detail.printStillLoading' : 'detail.printUnavailable';
}

/**
 * Prints the open message. `window.print()` and nothing else, deliberately:
 * the print rules in app.css decide what reaches the paper and
 * MessageContent's `beforeprint` hook gives the body frame its full height, so
 * the toolbar action, the palette entry and Ctrl+P all produce the same sheet
 * and a second way to start a print never becomes a second layout. When the
 * body is not there yet, or could not be loaded, it says so instead: printing
 * nothing in silence would leave a screen reader user unsure the key landed.
 */
export function printOpenMessage(): void {
	if (typeof window === 'undefined') return;
	const refusal = openMessagePrintRefusal(get(selectedMessage));
	if (refusal) {
		pushToast(get(_)(refusal), { tone: 'info' });
		return;
	}
	window.print();
}

export interface PrintItem {
	stableId: string;
	detail: MailDetailResponse;
	content: MailContentResponse;
}

export interface PrintJob {
	id: number;
	/** In the order they go on paper — the order the list shows them in. */
	items: PrintItem[];
}

/**
 * What the mail list in view offers to print: its ticked rows. Null when
 * nothing is ticked. The list owns the selection and how it resolves to
 * messages (a ticked conversation stands for several), so it publishes a
 * ready `print` rather than ids.
 */
export interface PrintableSelection {
	/** The bulk bar's own summary ("3 selected messages"); the palette repeats it. */
	summary: string;
	print: () => Promise<void>;
}

export const printableSelection = writable<PrintableSelection | null>(null);

export const printJob = writable<PrintJob | null>(null);

/** From the first fetch until the print dialog closes. One job at a time. */
export const printInProgress = writable(false);

const FETCH_CONCURRENCY = 4;
let nextJobId = 1;

async function fetchItem(stableId: string): Promise<PrintItem> {
	const [detail, content] = await Promise.all([
		getMessageDetail(stableId),
		getMessageContent(stableId)
	]);
	return { stableId, detail, content };
}

async function fetchAll(ids: readonly string[]): Promise<PrintItem[]> {
	const items: PrintItem[] = new Array(ids.length);
	let cursor = 0;
	// One failure already cancels the job; the other workers stop taking ids
	// rather than fetch bodies nobody will print.
	let failed = false;
	async function worker(): Promise<void> {
		while (!failed && cursor < ids.length) {
			const index = cursor++;
			try {
				items[index] = await fetchItem(ids[index] as string);
			} catch (error) {
				failed = true;
				throw error;
			}
		}
	}
	await Promise.all(Array.from({ length: Math.min(FETCH_CONCURRENCY, ids.length) }, worker));
	return items;
}

/**
 * True while a print job runs, and says so. A second Ctrl+P or palette entry
 * cannot start another job, and refusing it in silence would leave a screen
 * reader user unsure the key landed — the reason Ctrl+P with nothing to print
 * speaks too. Callers that do work before `printMessages` (a conversation list
 * loading thread members) ask first.
 */
export function refusePrintWhileBusy(): boolean {
	if (!get(printInProgress)) return false;
	announcePolite(get(_)('messages.printAlreadyPreparing'));
	return true;
}

/**
 * Fetches the messages and hands them to the print sheet. All or nothing: a
 * print missing one of the ticked messages would look complete on paper, so a
 * single failed fetch cancels the job and says so.
 */
export async function printMessages(stableIds: readonly string[]): Promise<void> {
	if (refusePrintWhileBusy()) return;
	const ids = Array.from(new Set(stableIds));
	if (ids.length === 0) return;
	printInProgress.set(true);
	const t = get(_);
	announcePolite(t('messages.printPreparing', { values: { count: ids.length } }));
	try {
		const items = await fetchAll(ids);
		printJob.set({ id: nextJobId++, items });
	} catch (error) {
		console.warn('[mail] failed to load messages for printing', error);
		pushToast(t('messages.printLoadFailed'), { tone: 'error' });
		printInProgress.set(false);
	}
}

/** The print dialog closed, printed or cancelled: drop the sheet. */
export function finishPrintJob(): void {
	printJob.set(null);
	printInProgress.set(false);
}
