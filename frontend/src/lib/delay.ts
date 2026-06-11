/**
 * Resolves after `ms` OR as soon as `signal` aborts — it NEVER rejects.
 *
 * Callers that must surface an abort as an error re-check `signal.aborted`
 * after the await (the polling loops in `loadSession` and
 * `waitForBackendReadiness` both do this and throw their own typed error).
 * Rejecting here instead would be a footgun under `Promise.race`: nothing
 * awaits the losing promise, so a later abort would surface as an unhandled
 * rejection — reported by the global errorReporting handler as a spurious
 * boot error.
 *
 * Cleanup runs on both exits: the abort path clears the timer, and the timer
 * path removes the abort listener so listeners do not accumulate on a shared
 * signal across hundreds of polling iterations.
 */
export function delayWithAbort(ms: number, signal?: AbortSignal): Promise<void> {
	return new Promise<void>((resolve) => {
		if (signal?.aborted) {
			resolve();
			return;
		}
		const onAbort = () => {
			clearTimeout(timer);
			resolve();
		};
		const timer = setTimeout(() => {
			signal?.removeEventListener('abort', onAbort);
			resolve();
		}, ms);
		signal?.addEventListener('abort', onAbort, { once: true });
	});
}
