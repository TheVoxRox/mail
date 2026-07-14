/**
 * SSE notifications: after each `sync_completed` invalidates the current
 * message list page (if it matches the notified account/folder) and forces
 * a folder refresh for that account (unread counts).
 */

import { get, writable } from 'svelte/store';
import { _ } from 'svelte-i18n';
import { SseClient } from '$lib/api/notifications.js';
import { ensureNotificationPermission, notifyUser } from '$lib/api/nativeNotifications.js';
import { folderLabel } from '$lib/mail/folderLabel.js';
import type {
	FolderResponse,
	SendNotification,
	StreamNotification,
	SyncNotification
} from '$lib/types.js';
import { messagesState, reloadCurrentPage } from './messages.js';
import { refreshFolders } from './folders.js';
import { accountsState } from './accounts.js';
import { dismissToast, pushToast } from './toasts.js';

type NotificationsStatus = 'idle' | 'connecting' | 'open' | 'error';

export const notificationsStatus = writable<NotificationsStatus>('idle');
export const lastSync = writable<SyncNotification | null>(null);

let client: SseClient | null = null;

export function startNotifications(): void {
	if (client) return;
	notificationsStatus.set('connecting');
	void ensureNotificationPermission();
	client = new SseClient({
		onOpen: () => notificationsStatus.set('open'),
		onError: () => notificationsStatus.set('error')
	});
	client.on(handleStreamNotification);
	client.start();
}

export function handleStreamNotification(event: StreamNotification): void {
	if (event.type === 'send_completed' || event.type === 'send_failed') {
		handleSendOutcome(event as SendNotification);
		return;
	}
	if (event.type === 'sync_completed') {
		handleSyncCompleted(event as SyncNotification);
		return;
	}
	/*
	 * Any other whitelisted event (currently only `thread_updated`) has no
	 * V0.1.0 consumer. Ignore it — previously it fell through to
	 * handleSyncCompleted, which set `lastSync` to a payload with an undefined
	 * folder/count (breaking the "last sync" render) and fired a spurious
	 * refreshFolders on every thread-membership change. The V0.2 conversation
	 * UI will subscribe here.
	 */
}

/*
 * Pending sends are tracked outside any component (the compose view unmounts
 * right after the request) so the eventual SSE outcome can resolve the right
 * "sending…" toast. The fallback timer drops the entry if the outcome event
 * never arrives (e.g. SSE was disconnected) so the indicator cannot get stuck.
 */
interface PendingSend {
	toastId: number;
	recipient: string;
	timer: ReturnType<typeof setTimeout>;
}

const PENDING_SEND_FALLBACK_MS = 60_000;
const pendingSends = new Map<string, PendingSend>();

export function registerPendingSend(sendId: string, recipient: string): void {
	const translate = get(_);
	const toastId = pushToast(translate('toast.sendPending', { values: { recipient } }), {
		tone: 'info',
		ttl: 0
	});
	const timer = setTimeout(() => {
		pendingSends.delete(sendId);
		dismissToast(toastId);
	}, PENDING_SEND_FALLBACK_MS);
	pendingSends.set(sendId, { toastId, recipient, timer });
}

function resolvePendingSend(sendId: string): string {
	const pending = pendingSends.get(sendId);
	if (!pending) return '';
	clearTimeout(pending.timer);
	dismissToast(pending.toastId);
	pendingSends.delete(sendId);
	return pending.recipient;
}

function handleSendOutcome(event: SendNotification): void {
	const recipient = resolvePendingSend(event.sendId);
	const translate = get(_);
	if (event.type === 'send_completed') {
		pushToast(translate('toast.sendCompleted', { values: { recipient } }), {
			tone: 'success',
			ttl: 6000
		});
		return;
	}
	const message = translate('toast.sendFailed', { values: { recipient } });
	pushToast(message, { tone: 'error', ttl: 0 });
	notifyUser(message, recipient);
}

function handleSyncCompleted(event: SyncNotification): void {
	lastSync.set(event);

	const state = get(messagesState);
	/*
	 * Also cover the `loading` state: on first entry into a not-yet-synced
	 * folder the sync may finish before the (empty) DB response returns.
	 * Without this, after the original request finishes the page would
	 * still appear empty even though messages are already in the DB. The
	 * token in loadPage ensures the original (stale) response is dropped.
	 */
	if (
		state.status !== 'idle' &&
		state.context.accountId === event.accountId &&
		state.context.folderName === event.folderName
	) {
		void reloadCurrentPage();
	}

	/*
	 * The refreshed list also resolves the folder label for the announcement
	 * below. The SSE event carries only the raw server-side name (folderRef),
	 * and the account-scoped `folders` derived store cannot be used instead:
	 * the event may belong to a non-active account.
	 */
	const refreshedFolders = refreshFolders(event.accountId).catch((): FolderResponse[] => []);

	if (event.newMessagesCount > 0) {
		void announceNewMessages(event, refreshedFolders);
	}
}

async function announceNewMessages(
	event: SyncNotification,
	foldersPromise: Promise<FolderResponse[]>
): Promise<void> {
	const folder = (await foldersPromise).find((f) => f.folderRef === event.folderName);
	const accounts = get(accountsState);
	const accountLabel =
		(accounts.status === 'ready'
			? accounts.accounts.find((a) => a.id === event.accountId)?.email
			: null) ?? `#${event.accountId}`;
	const translate = get(_);
	const folderName = folder ? folderLabel(folder, translate) : event.folderName;
	const message = translate('toast.newMessages', {
		values: {
			count: event.newMessagesCount,
			account: accountLabel,
			folder: folderName
		}
	});
	pushToast(message, { tone: 'success', ttl: 6000 });
	notifyUser(message, `${accountLabel}, ${folderName}`);
}
