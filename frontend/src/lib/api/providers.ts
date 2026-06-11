/** `/api/v1/accounts/providers` — reads IMAP/SMTP provider templates. */

import { api } from './client.js';
import type { MailProviderResponse } from '$lib/types.js';

export function listProviders(): Promise<MailProviderResponse[]> {
	return api.get<MailProviderResponse[]>('/accounts/providers');
}

export function resolveProviderByEmail(email: string): Promise<MailProviderResponse> {
	return api.get<MailProviderResponse>('/accounts/providers/resolve', {
		params: { email }
	});
}

export function getProvider(id: number): Promise<MailProviderResponse> {
	return api.get<MailProviderResponse>(`/accounts/providers/${id}`);
}
