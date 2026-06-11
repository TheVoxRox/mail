/** `/api/v1/client-config` — backend-driven limits and capability values for the UI. */

import { api } from './client.js';
import type { ClientConfigResponse } from '$lib/types.js';

export function getClientConfig(): Promise<ClientConfigResponse> {
	return api.get<ClientConfigResponse>('/client-config');
}
