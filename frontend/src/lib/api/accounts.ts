/** `/api/v1/accounts` — CRUD over mail accounts. */

import { api } from './client.js';
import type {
	AccountConnectionTestRequest,
	AccountConnectionTestResponse,
	AccountCreateRequest,
	AccountResponse,
	AccountUpdateRequest
} from '$lib/types.js';

export function listAccounts(): Promise<AccountResponse[]> {
	return api.get<AccountResponse[]>('/accounts');
}

export function getAccount(id: number): Promise<AccountResponse> {
	return api.get<AccountResponse>(`/accounts/${id}`);
}

export function createAccount(body: AccountCreateRequest): Promise<AccountResponse> {
	return api.post<AccountResponse>('/accounts', body);
}

export function testAccountConnection(
	body: AccountConnectionTestRequest
): Promise<AccountConnectionTestResponse> {
	return api.post<AccountConnectionTestResponse>('/accounts/test-connection', body);
}

export function updateAccount(id: number, body: AccountUpdateRequest): Promise<AccountResponse> {
	return api.put<AccountResponse>(`/accounts/${id}`, body);
}

export function deleteAccount(id: number): Promise<void> {
	return api.delete<void>(`/accounts/${id}`);
}
