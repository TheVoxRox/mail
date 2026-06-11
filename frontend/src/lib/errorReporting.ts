import { reportClientError } from '$lib/api/clientErrors.js';

let installed = false;

function handleWindowError(event: ErrorEvent): void {
	void reportClientError({
		kind: 'window_error',
		error: event.error,
		message: event.message,
		source: event.filename,
		line: event.lineno,
		column: event.colno
	});
}

function handleUnhandledRejection(event: PromiseRejectionEvent): void {
	void reportClientError({
		kind: 'unhandled_rejection',
		error: event.reason,
		message: event.reason instanceof Error ? event.reason.message : undefined
	});
}

export function initErrorReporting(): () => void {
	if (installed || typeof window === 'undefined') return () => {};
	installed = true;
	window.addEventListener('error', handleWindowError);
	window.addEventListener('unhandledrejection', handleUnhandledRejection);

	return () => {
		window.removeEventListener('error', handleWindowError);
		window.removeEventListener('unhandledrejection', handleUnhandledRejection);
		installed = false;
	};
}
