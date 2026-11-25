import { beforeAll, describe, expect, it } from 'vitest';
import { get } from 'svelte/store';
import { _, setLocale } from '#lib/i18n/index.js';
import { ApiError } from '#lib/api/client.js';
import { SessionLoadError } from '#lib/api/session.js';
import { BackendCompatibilityError, BackendReadinessError } from '#lib/api/readiness.js';
import { SidecarExitError } from '#lib/backend/sidecar.js';
import { bootFailureText } from './bootFailureText.js';

/*
 * One case per cause the boot failure screen can meet. Each pins the sentence
 * the user reads and that the developer's text is kept as the detail, not
 * shown in its place.
 */

beforeAll(() => {
	setLocale('en');
});

function textFor(error: Error, origin: 'sidecar' | 'boot' = 'boot') {
	return bootFailureText(error, get(_), origin);
}

/** As bootstrap.ts throws it; built here so the test does not import the boot. */
function bootTimeout(): Error {
	const error = new Error('Application did not start within 60 s.');
	error.name = 'BootTimeoutError';
	return error;
}

describe('bootFailureText', () => {
	it('says a boot that ran out of time was stopped', () => {
		expect(textFor(bootTimeout())).toEqual({
			message:
				'Starting took longer than a minute, so the app stopped it. Try again, or restart the mail service.',
			detail: 'Application did not start within 60 s.'
		});
	});

	it('says the service is not there when its session never appeared', () => {
		const error = new SessionLoadError(
			'.ready/session.json not found at C:\\data\\session.json after 150 attempts – is the backend running?'
		);

		expect(textFor(error)).toEqual({
			message:
				'The mail service did not start or is not responding. Try again, or restart the mail service.',
			detail: error.message
		});
	});

	it('says the same when the service never became ready', () => {
		const error = new BackendReadinessError('Backend readiness unavailable after 5 attempts: 503');

		expect(textFor(error).message).toBe(
			'The mail service did not start or is not responding. Try again, or restart the mail service.'
		);
	});

	it('sends a version mismatch to the download, not to a retry', () => {
		const error = new BackendCompatibilityError(
			'Client 0.2.1 is too old. Backend requires at least 0.3.0. Restart the application or reinstall from GitHub Releases.'
		);

		expect(textFor(error)).toEqual({
			message:
				'The app and the mail service are from different versions. Install the latest version from voxrox.org.',
			detail: error.message
		});
	});

	// The raw exit pair belongs to the log; the message keeps the code support
	// asks for, so there is nothing to add behind the disclosure.
	it('shows a backend exit in its own words, with no detail', () => {
		const error = new SidecarExitError(
			'Backend sidecar exited (code=1, signal=null)',
			'app.backendExit.failed',
			{ code: '1' }
		);

		expect(textFor(error, 'sidecar')).toEqual({
			message:
				'The mail service failed to start (code 1). Try starting the application again, and if that does not help, contact support.',
			detail: null
		});
	});

	it('takes the backend’s localized detail as the message, with nothing to add', () => {
		const error = new ApiError(500, 'Internal Server Error', null, {
			detail: 'The accounts could not be read.'
		});

		expect(textFor(error)).toEqual({ message: 'The accounts could not be read.', detail: null });
	});

	it('names the status of an answer that came without a detail', () => {
		const error = new ApiError(503, 'Service Unavailable', null);

		expect(textFor(error)).toEqual({
			message: 'The action could not be completed (error code 503). Try again.',
			detail: 'API error 503: Service Unavailable'
		});
	});

	it('calls an unnamed boot failure unexpected, and one from the sidecar the service', () => {
		const error = new TypeError('Failed to fetch');

		expect(textFor(error, 'boot')).toEqual({
			message: 'An unexpected error occurred.',
			detail: 'Failed to fetch'
		});
		expect(textFor(error, 'sidecar').message).toBe(
			'The mail service did not start or is not responding. Try again, or restart the mail service.'
		);
	});
});
