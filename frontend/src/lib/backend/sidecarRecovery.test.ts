import { beforeEach, describe, expect, it, vi } from 'vitest';
import { backendSidecarState } from './sidecar.js';
import { watchSidecarAutoRestart } from './sidecarRecovery.js';

describe('watchSidecarAutoRestart', () => {
	beforeEach(() => {
		backendSidecarState.set({ status: 'idle' });
	});

	it('recovers once after an automatic restart (restarting → starting → running)', () => {
		const recover = vi.fn();
		const stop = watchSidecarAutoRestart(recover);

		backendSidecarState.set({ status: 'running', pid: 1 }); // already booted
		backendSidecarState.set({ status: 'restarting', attempt: 1 });
		backendSidecarState.set({ status: 'starting' });
		backendSidecarState.set({ status: 'running', pid: 2 });

		expect(recover).toHaveBeenCalledTimes(1);
		stop();
	});

	it('does not recover on the initial boot (idle → starting → running)', () => {
		const recover = vi.fn();
		const stop = watchSidecarAutoRestart(recover);

		backendSidecarState.set({ status: 'starting' });
		backendSidecarState.set({ status: 'running', pid: 1 });

		expect(recover).not.toHaveBeenCalled();
		stop();
	});

	it('does not recover on the manual restart path (stopped → starting → running)', () => {
		// BootErrorView Restart/Retry re-handshakes itself and never emits
		// 'restarting', so the watcher must stay out of its way.
		const recover = vi.fn();
		const stop = watchSidecarAutoRestart(recover);

		backendSidecarState.set({ status: 'running', pid: 1 });
		backendSidecarState.set({ status: 'stopped' });
		backendSidecarState.set({ status: 'starting' });
		backendSidecarState.set({ status: 'running', pid: 2 });

		expect(recover).not.toHaveBeenCalled();
		stop();
	});

	it('does not recover when restarts are exhausted (restarting → starting → error)', () => {
		const recover = vi.fn();
		const stop = watchSidecarAutoRestart(recover);

		backendSidecarState.set({ status: 'running', pid: 1 });
		backendSidecarState.set({ status: 'restarting', attempt: 3 });
		backendSidecarState.set({ status: 'starting' });
		backendSidecarState.set({ status: 'error', error: new Error('boom') });

		expect(recover).not.toHaveBeenCalled();
		stop();
	});

	it('recovers again on a second independent auto-restart', () => {
		const recover = vi.fn();
		const stop = watchSidecarAutoRestart(recover);

		backendSidecarState.set({ status: 'running', pid: 1 });

		backendSidecarState.set({ status: 'restarting', attempt: 1 });
		backendSidecarState.set({ status: 'running', pid: 2 });

		backendSidecarState.set({ status: 'restarting', attempt: 1 });
		backendSidecarState.set({ status: 'running', pid: 3 });

		expect(recover).toHaveBeenCalledTimes(2);
		stop();
	});

	it('stops reacting after unsubscribe', () => {
		const recover = vi.fn();
		const stop = watchSidecarAutoRestart(recover);
		stop();

		backendSidecarState.set({ status: 'restarting', attempt: 1 });
		backendSidecarState.set({ status: 'running', pid: 2 });

		expect(recover).not.toHaveBeenCalled();
	});
});
