/** `/api/v1/accounts/{accountId}/folders` — IMAP folder listing for an account. */

import { api } from './client.js';
import type { FolderResponse } from '$lib/types.js';

export function listFolders(accountId: number): Promise<FolderResponse[]> {
	return api.get<FolderResponse[]>(`/accounts/${accountId}/folders`);
}
