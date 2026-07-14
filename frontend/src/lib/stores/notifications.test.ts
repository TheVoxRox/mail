import { get } from 'svelte/store';
import { describe, expect, it } from 'vitest';
import { handleStreamNotification, lastSync } from './notifications.js';
import type { ThreadUpdated } from '$lib/types.js';

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
