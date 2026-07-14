import { get } from 'svelte/store';
import { addMessages, init } from 'svelte-i18n';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import csMessages from '$lib/i18n/messages/cs.json';
import { handleStreamNotification, lastSync, registerPendingSend } from './notifications.js';
import { toasts } from './toasts.js';
import type { SendNotification, ThreadUpdated } from '$lib/types.js';

beforeAll(() => {
	addMessages('cs', csMessages);
	void init({ fallbackLocale: 'cs', initialLocale: 'cs' });
});

describe('handleStreamNotification', () => {
	it('ignores thread_updated — leaves lastSync untouched (no V0.1.0 consumer)', () => {
		lastSync.set(null);

		const event: ThreadUpdated = { type: 'thread_updated', threadId: 't1', accountId: 5 };
		handleStreamNotification(event);

		// Previously this fell through to the sync handler and stored a malformed
		// SyncNotification (undefined folder/count), breaking the "last sync" render.
		expect(get(lastSync)).toBeNull();
	});
});

describe('pending send outcome handling', () => {
	beforeEach(() => {
		vi.useFakeTimers();
		toasts.set([]);
	});

	afterEach(() => {
		vi.useRealTimers();
	});

	function completed(sendId: string): SendNotification {
		return {
			type: 'send_completed',
			sendId,
			accountId: 1,
			errorCode: null,
			recoveryDraftStableId: null
		};
	}

	function failed(sendId: string, recoveryDraftStableId: string | null = null): SendNotification {
		return {
			type: 'send_failed',
			sendId,
			accountId: 1,
			errorCode: 'SMTP_SEND_FAILED',
			recoveryDraftStableId
		};
	}

	function toastMessages(): string[] {
		return get(toasts).map((toast) => toast.message);
	}

	it('resolves the pending toast when the outcome follows the registration', () => {
		registerPendingSend('send-a', 'jana@example.com');
		expect(toastMessages()).toEqual(['Odesílá se příjemci jana@example.com…']);

		handleStreamNotification(completed('send-a'));
		expect(toastMessages()).toEqual(['Zpráva odeslána příjemci jana@example.com.']);
	});

	it('parks an outcome that beats the registration and resolves it immediately at register time', () => {
		// SSE can deliver the outcome before the 202 response reaches the client.
		handleStreamNotification(completed('send-b'));
		expect(toastMessages()).toEqual([]);

		registerPendingSend('send-b', 'jana@example.com');
		// No transient "sending…" toast — straight to the outcome.
		expect(toastMessages()).toEqual(['Zpráva odeslána příjemci jana@example.com.']);
	});

	it('expires a parked outcome after its TTL', () => {
		handleStreamNotification(completed('send-c'));
		vi.advanceTimersByTime(30_000);

		registerPendingSend('send-c', 'jana@example.com');
		expect(toastMessages()).toEqual(['Odesílá se příjemci jana@example.com…']);
	});

	it('flips the pending toast to a visible warning when no outcome arrives within 60 s', () => {
		registerPendingSend('send-d', 'jana@example.com');
		vi.advanceTimersByTime(60_000);

		const remaining = get(toasts);
		expect(remaining).toHaveLength(1);
		expect(remaining[0].message).toBe(
			'Výsledek odeslání příjemci jana@example.com se nepodařilo ověřit — zkontrolujte složku Odeslané.'
		);
		expect(remaining[0].tone).toBe('error');

		// A very late outcome after the fallback is parked, not lost silently.
		handleStreamNotification(completed('send-d'));
		expect(get(toasts)).toHaveLength(1);
	});

	it('points the user at Drafts when a failed send parked a recovery draft', () => {
		registerPendingSend('send-e', 'jana@example.com');
		handleStreamNotification(failed('send-e', 'draft-999'));

		expect(toastMessages()).toEqual([
			'Zprávu se nepodařilo odeslat příjemci jana@example.com. Obsah je uložen ve složce Rozepsané.'
		]);
	});

	it('keeps the plain failure message when there is no recovery draft', () => {
		registerPendingSend('send-f', 'jana@example.com');
		handleStreamNotification(failed('send-f'));

		expect(toastMessages()).toEqual(['Zprávu se nepodařilo odeslat příjemci jana@example.com.']);
	});
});
