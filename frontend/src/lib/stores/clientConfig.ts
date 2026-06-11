import { derived, get, writable, type Readable } from 'svelte/store';
import { getClientConfig } from '$lib/api/clientConfig.js';
import { toError } from '$lib/api/errors.js';
import type { ClientConfigResponse } from '$lib/types.js';

const FALLBACK_CLIENT_CONFIG: ClientConfigResponse = {
	mailDefaultPageSize: 25,
	mailApiMaxPageSize: 200,
	searchQueryMaxLength: 256,
	contactDefaultPageSize: 25,
	contactQueryMaxLength: 100,
	contactAutocompleteDefaultLimit: 6,
	contactAutocompleteMaxLimit: 20,
	attachmentMaxBytes: 10 * 1024 * 1024,
	attachmentTotalMaxBytes: 25 * 1024 * 1024,
	largeAttachmentWarningBytes: 5 * 1024 * 1024
};

type ClientConfigState =
	| { status: 'ready'; source: 'fallback' | 'backend'; config: ClientConfigResponse }
	| { status: 'loading'; config: ClientConfigResponse }
	| { status: 'error'; config: ClientConfigResponse; error: Error };

const clientConfigState = writable<ClientConfigState>({
	status: 'ready',
	source: 'fallback',
	config: FALLBACK_CLIENT_CONFIG
});

export const clientConfig: Readable<ClientConfigResponse> = derived(
	clientConfigState,
	($state) => $state.config
);

export function getClientConfigSnapshot(): ClientConfigResponse {
	return get(clientConfigState).config;
}

export async function loadClientConfig(): Promise<ClientConfigResponse> {
	clientConfigState.set({ status: 'loading', config: getClientConfigSnapshot() });
	try {
		const config = await getClientConfig();
		clientConfigState.set({ status: 'ready', source: 'backend', config });
		return config;
	} catch (err) {
		const error = toError(err);
		/*
		 * The backend is already confirmed ready at this point (boot runs this
		 * after waitForBackendReadiness), so a failure here is unexpected. The
		 * fallback keeps the app usable, but the static values may drift from the
		 * backend's actual limits — log loudly so the degradation is observable
		 * instead of silently masking it.
		 */
		console.warn('[mail] client-config fetch failed, using built-in fallback values', error);
		clientConfigState.set({ status: 'error', config: FALLBACK_CLIENT_CONFIG, error });
		return FALLBACK_CLIENT_CONFIG;
	}
}
