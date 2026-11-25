/**
 * What the boot failure screen says about an error.
 *
 * The screen used to show the error's own text, which outside a backend exit
 * is English written for a developer: "Application did not start within
 * 60 s.", ".ready/session.json not found at C:\… after 150 attempts – is the
 * backend running?", "Client 0.2.1 is too old…". Marked as English it passed
 * 3.1.2, and a screen reader read it out in an English voice to someone it told
 * nothing. Each cause the screen can meet now gets a sentence in the interface
 * language, and the error's own text stays as the detail, for support, behind
 * the screen's "Technical details" disclosure.
 */
import { ApiError } from '#lib/api/client.js';
import { technicalMessage, toErrorMessage } from '#lib/api/errors.js';
import { isSidecarExitError } from '#lib/backend/sidecar.js';

type Translate = (key: string, options?: { values: Record<string, string | number> }) => string;

export interface BootFailureText {
	/** In the interface language. */
	message: string;
	/** The error's own English text, or null when the message already says it. */
	detail: string | null;
}

/**
 * Which of the layout's two failure views asks: a backend that stopped or never
 * started (`sidecar`), or a boot step that failed with the backend up (`boot`).
 * An error the sidecar raised without an exit — the shell could not spawn it —
 * is still the service not starting; one from a boot step that names no cause
 * is unexpected.
 */
export type BootFailureOrigin = 'sidecar' | 'boot';

/*
 * By name rather than `instanceof`, as `isSidecarExitError` does: HMR and
 * `vi.resetModules()` hand a reader a different copy of a class than the
 * module that threw. It also keeps `bootstrap.ts` and its imports out of this
 * module.
 */
const SERVICE_UNAVAILABLE = new Set(['SessionLoadError', 'BackendReadinessError']);

function messageFor(error: Error, translate: Translate, origin: BootFailureOrigin): string {
	if (isSidecarExitError(error)) return translate(error.messageKey, { values: error.values });
	if (error.name === 'BootTimeoutError') return translate('app.bootFailure.timeout');
	if (error.name === 'BackendCompatibilityError') {
		return translate('app.bootFailure.versionMismatch');
	}
	if (SERVICE_UNAVAILABLE.has(error.name)) return translate('app.bootFailure.serviceUnavailable');
	// The backend's localized detail, else its status — toErrorMessage's own
	// rule for an ApiError, which it answers without reporting anything.
	if (error instanceof ApiError) return toErrorMessage(error);
	return translate(
		origin === 'sidecar' ? 'app.bootFailure.serviceUnavailable' : 'common.errorUnexpected'
	);
}

export function bootFailureText(
	error: Error,
	translate: Translate,
	origin: BootFailureOrigin
): BootFailureText {
	const message = messageFor(error, translate, origin);
	/*
	 * A backend exit has no detail to show: its message is the one place the
	 * screen was already written for the user, keeping the code where support
	 * asks for it, and the raw exit pair belongs to the log (describeSidecarExit
	 * in sidecar.ts).
	 */
	if (isSidecarExitError(error)) return { message, detail: null };
	const detail = technicalMessage(error).trim();
	return { message, detail: detail.length > 0 && detail !== message ? detail : null };
}
