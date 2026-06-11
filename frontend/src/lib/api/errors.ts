/**
 * Normalizes anything thrown (Error | string | other) into an Error
 * instance. Use this when storing in a `error: Error | null` store.
 */
export function toError(err: unknown): Error {
	return err instanceof Error ? err : new Error(String(err));
}

/**
 * Returns a human-readable message for the UI.
 *
 * For {@link ApiError}, `err.message` already carries the backend
 * `problem.detail` which is localized via `Accept-Language` (see
 * `error.*` keys in `messages_cs/en.properties`). For other errors we
 * fall back to `Error.message` or `String(err)`.
 */
export function toErrorMessage(err: unknown): string {
	return err instanceof Error ? err.message : String(err);
}
