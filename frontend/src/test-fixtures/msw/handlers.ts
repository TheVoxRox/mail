import { http, HttpResponse } from 'msw';
import {
	draftToSummary,
	fixtureState,
	forwardFor,
	getFolderMessages,
	listPage,
	removeMessageEverywhere,
	replaceFolderMessages,
	replyFor,
	upsertAccount,
	upsertContact
} from './fixtures.js';
import { openSyncStream } from './sse-bridge.js';
import type {
	AccountCreateRequest,
	AccountUpdateRequest,
	BulkContactCreateRequest,
	BulkContactDeleteRequest,
	ContactCreateRequest,
	ContactEmailRequest,
	ContactMergeRequest,
	ContactPatchRequest,
	ContactResponse,
	ContactUpdateRequest,
	DraftRequest,
	MailFlagType,
	MailRequest,
	MoveRequest
} from '$lib/types.js';

type MockResponse = Response;

function problem(status: number, detail: string, errorCode: string): MockResponse {
	return HttpResponse.json(
		{
			type: `https://example.test/errors/${errorCode.toLowerCase()}`,
			title: detail,
			status,
			detail,
			instance: '/api/v1',
			errorCode,
			timestamp: new Date().toISOString()
		},
		{ status }
	);
}

function accepted(): MockResponse {
	return new HttpResponse(null, { status: 202 });
}

let sendSequence = 0;

/** 202 for the async send endpoints: returns a sendId and records it so the SSE bridge can echo the outcome. */
function acceptedSend(accountId: number): MockResponse {
	const sendId = `e2e-send-${++sendSequence}`;
	fixtureState.lastSendId = sendId;
	fixtureState.lastSendAccountId = accountId;
	return HttpResponse.json({ sendId }, { status: 202 });
}

function noContent(): MockResponse {
	return new HttpResponse(null, { status: 204 });
}

function decodeSegment(value: string | undefined): string {
	return decodeURIComponent(value ?? '');
}

function requireApiKey(request: Request): MockResponse | null {
	const url = new URL(request.url);
	if (request.headers.has('X-API-KEY') || url.searchParams.has('apiKey')) {
		return null;
	}
	return problem(401, 'Chybějící nebo neplatný X-API-KEY.', 'UNAUTHORIZED');
}

function findAccount(id: number) {
	return fixtureState.accounts.find((account) => account.id === id);
}

function validateAccountPayload(
	body: AccountCreateRequest | AccountUpdateRequest,
	method: 'POST' | 'PUT',
	accountId?: number
): MockResponse | null {
	const hasImap = body.imap != null;
	const hasSmtp = body.smtp != null;
	const providerId = body.providerId ?? null;
	const hasProvider = providerId != null;

	if (body.email != null) {
		const normalizedEmail = body.email.trim().toLowerCase();
		const duplicate = fixtureState.accounts.some(
			(account) =>
				account.id !== accountId && account.email.trim().toLowerCase() === normalizedEmail
		);
		if (duplicate) return problem(409, 'Účet s tímto e-mailem už existuje.', 'ACCOUNT_EXISTS');
	}

	if (hasProvider && (hasImap || hasSmtp)) {
		return problem(
			400,
			'Použijte buď providerId, nebo vlastní IMAP/SMTP konfiguraci.',
			'ACCOUNT_XOR'
		);
	}

	if (!hasProvider && !hasImap && !hasSmtp) {
		return problem(
			400,
			'Chybí providerId nebo vlastní IMAP/SMTP konfigurace.',
			'ACCOUNT_CONFIG_REQUIRED'
		);
	}

	if (!hasProvider && hasImap !== hasSmtp) {
		return problem(
			400,
			'Vlastní konfigurace musí obsahovat IMAP i SMTP.',
			'ACCOUNT_CUSTOM_INCOMPLETE'
		);
	}

	if (hasProvider && !fixtureState.providers.some((provider) => provider.id === providerId)) {
		return problem(404, 'Provider nenalezen.', 'PROVIDER_NOT_FOUND');
	}

	return null;
}

let vCardExportDelayMs = 0;
let failNextVCardExport = false;
let readinessDelayMs = 0;
let readinessFailures = 0;
let folderAuthFailure = false;
let mailPageSizeOverride: number | null = null;

export function setVCardExportDelayMs(delayMs: number): void {
	vCardExportDelayMs = Math.max(0, delayMs);
}

export function failNextVCardExportOnce(): void {
	failNextVCardExport = true;
}

export function setReadinessDelayMs(delayMs: number): void {
	readinessDelayMs = Math.max(0, delayMs);
}

export function setReadinessFailures(count: number): void {
	readinessFailures = Math.max(0, count);
}

export function setFolderAuthFailure(enabled: boolean): void {
	folderAuthFailure = enabled;
}

/*
 * Shrinks `mailDefaultPageSize` in the client-config response (driven by the
 * `mail.e2e.mailPageSize` localStorage flag). The fixture set is exactly one
 * default page (25 messages), so pagination behaviour — page announcements,
 * pager buttons — is untestable without a smaller page size.
 */
export function setMailPageSize(size: number | null): void {
	mailPageSizeOverride = size && size > 0 ? size : null;
}

function vCardExportResponse(accountId: number, contacts: ContactResponse[]): MockResponse {
	const lines: string[] = [];
	for (const contact of contacts) {
		const fullName = [contact.name, contact.surname].filter(Boolean).join(' ');
		lines.push('BEGIN:VCARD', 'VERSION:4.0');
		if (fullName) lines.push(`FN:${fullName}`);
		for (const email of contact.emails) {
			const params = email.primary ? ';PREF=1' : '';
			lines.push(`EMAIL${params}:${email.email}`);
		}
		lines.push('END:VCARD');
	}
	return new HttpResponse(lines.join('\r\n'), {
		status: 200,
		headers: {
			'Content-Type': 'text/vcard; charset=utf-8',
			'Content-Disposition': `attachment; filename="contacts-${accountId}.vcf"`
		}
	});
}

function accountRoutes(
	method: string,
	segments: string[],
	request: Request
): Promise<MockResponse> | MockResponse | null {
	if (segments[0] !== 'accounts') return null;

	if (segments.length === 1) {
		if (method === 'GET') return HttpResponse.json(fixtureState.accounts);
		if (method === 'POST') {
			return request.json().then((body) => {
				const requestBody = body as AccountCreateRequest;
				const validationError = validateAccountPayload(requestBody, 'POST');
				return validationError ?? HttpResponse.json(upsertAccount(requestBody), { status: 201 });
			});
		}
	}

	if (segments[1] === 'test-connection' && method === 'POST') {
		return request.json().then((body) => {
			const requestBody = body as AccountCreateRequest;
			const validationError = validateAccountPayload(requestBody, 'POST');
			return (
				validationError ??
				HttpResponse.json({
					imapOk: true,
					smtpOk: true,
					message: 'IMAP i SMTP připojení bylo úspěšně ověřeno.'
				})
			);
		});
	}

	if (segments[1] === 'providers') {
		if (segments[2] === 'resolve') {
			const email = new URL(request.url).searchParams.get('email') ?? '';
			const domain = email.split('@')[1]?.toLowerCase() ?? '';
			const provider =
				fixtureState.providers.find((item) =>
					item.domains
						.split(',')
						.map((value) => value.trim().toLowerCase())
						.includes(domain)
				) ?? null;
			return provider
				? HttpResponse.json(provider)
				: problem(404, 'Provider nenalezen.', 'PROVIDER_NOT_FOUND');
		}
		if (segments[2]) {
			const provider = fixtureState.providers.find((item) => item.id === Number(segments[2]));
			return provider
				? HttpResponse.json(provider)
				: problem(404, 'Provider nenalezen.', 'PROVIDER_NOT_FOUND');
		}
		return HttpResponse.json(fixtureState.providers);
	}

	const accountId = Number(segments[1]);
	if (!Number.isInteger(accountId)) return problem(400, 'Neplatné ID účtu.', 'INVALID_ACCOUNT_ID');

	if (segments.length === 2) {
		const account = findAccount(accountId);
		if (!account && method !== 'POST') return problem(404, 'Účet nenalezen.', 'ACCOUNT_NOT_FOUND');
		if (method === 'GET') return HttpResponse.json(account);
		if (method === 'DELETE') {
			fixtureState.accounts = fixtureState.accounts.filter((item) => item.id !== accountId);
			return noContent();
		}
		if (method === 'PUT') {
			return request.json().then((body) => {
				const requestBody = body as AccountUpdateRequest;
				const validationError = validateAccountPayload(requestBody, 'PUT', accountId);
				return validationError ?? HttpResponse.json(upsertAccount(requestBody, accountId));
			});
		}
	}

	if (segments[2] === 'folders' && method === 'GET') {
		if (folderAuthFailure) {
			return problem(
				401,
				'Autorizace u Google vypršela nebo byla zrušena.',
				'MAIL_AUTHENTICATION_FAILED'
			);
		}
		return HttpResponse.json(fixtureState.foldersByAccount[accountId] ?? []);
	}

	if (segments[2] === 'drafts') {
		if (method === 'GET')
			return HttpResponse.json(
				listPage(fixtureState.draftsByAccount[accountId] ?? [], new URL(request.url))
			);
		if (method === 'POST' && segments[4] === 'send') {
			// POST /accounts/{id}/drafts/{stableId}/send — async draft send (no body).
			// Mirrors verifyDraftForSend: a stableId that is not a draft of this
			// account is rejected up front instead of blindly accepting.
			const stableId = decodeSegment(segments[3]);
			const draft = (fixtureState.draftsByAccount[accountId] ?? []).find(
				(item) => item.stableId === stableId
			);
			if (!draft) {
				return problem(404, 'Koncept nenalezen.', 'MESSAGE_NOT_FOUND');
			}
			// The backend hard-deletes the sent draft only after delivery.
			fixtureState.lastSendSupersedesDraftId = stableId;
			fixtureState.lastSendMailRequest = null;
			return acceptedSend(accountId);
		}
		if (method === 'POST') {
			return request.json().then((body) => {
				const draft = body as DraftRequest;
				if (draft.subject === '__FAIL_DRAFT__') {
					return problem(500, 'Koncept se nepodařilo uložit.', 'DRAFT_SAVE_FAILED');
				}
				const summary = draftToSummary(
					accountId,
					draft,
					new URL(request.url).searchParams.get('replaces')
				);
				// B1 contract: the 202 carries the stableId the draft persists under.
				return HttpResponse.json({ stableId: summary.stableId }, { status: 202 });
			});
		}
	}

	if (segments[2] === 'contacts') {
		return contactRoutes(method, accountId, segments.slice(3), request);
	}

	return null;
}

function contactRoutes(
	method: string,
	accountId: number,
	segments: string[],
	request: Request
): Promise<MockResponse> | MockResponse | null {
	const contacts = fixtureState.contactsByAccount[accountId] ?? [];
	const url = new URL(request.url);

	if (segments.length === 0) {
		if (method === 'GET') {
			const q = (url.searchParams.get('q') ?? '').toLowerCase();
			const label = url.searchParams.get('label');
			const matchedByQuery = q
				? contacts.filter((contact) =>
						[contact.name, contact.surname, ...contact.emails.map((email) => email.email)].some(
							(value) => value?.toLowerCase().includes(q)
						)
					)
				: contacts;
			const filtered = label
				? matchedByQuery.filter((contact) => contact.emails.some((email) => email.label === label))
				: matchedByQuery;
			return HttpResponse.json(listPage(filtered, url));
		}
		if (method === 'POST') {
			return request
				.json()
				.then((body) =>
					HttpResponse.json(upsertContact(accountId, body as ContactCreateRequest), { status: 201 })
				);
		}
	}

	if (segments[0] === 'counts' && method === 'GET') {
		const countFor = (label: string): number =>
			contacts.filter((contact) => contact.emails.some((email) => email.label === label)).length;
		return HttpResponse.json({
			total: contacts.length,
			work: countFor('WORK'),
			home: countFor('HOME'),
			other: countFor('OTHER')
		});
	}

	if (segments[0] === 'bulk') {
		if (method === 'POST') {
			return request.json().then((body) => {
				const requestBody = body as BulkContactCreateRequest;
				const results = requestBody.contacts.map((contact, index) => ({
					index,
					status: 'CREATED' as const,
					contact: upsertContact(accountId, contact)
				}));
				return HttpResponse.json({
					total: requestBody.contacts.length,
					created: results.length,
					failed: 0,
					results
				});
			});
		}
		if (method === 'DELETE') {
			return request.json().then((body) => {
				const requestBody = body as BulkContactDeleteRequest;
				const existingIds = new Set(contacts.map((contact) => contact.id));
				const idsToDelete = new Set(requestBody.ids.filter((id) => existingIds.has(id)));
				fixtureState.contactsByAccount[accountId] = contacts.filter(
					(contact) => !idsToDelete.has(contact.id)
				);
				const results = requestBody.ids.map((id) =>
					existingIds.has(id)
						? { id, status: 'DELETED' as const }
						: {
								id,
								status: 'FAILED' as const,
								errorCode: 'CONTACT_NOT_FOUND',
								errorMessage: 'Kontakt nenalezen.'
							}
				);
				return HttpResponse.json({
					total: requestBody.ids.length,
					deleted: idsToDelete.size,
					failed: requestBody.ids.length - idsToDelete.size,
					results
				});
			});
		}
	}

	if (segments[0] === 'export.vcf' && method === 'GET') {
		if (failNextVCardExport) {
			failNextVCardExport = false;
			return problem(500, 'Export vCard se nepodařil.', 'VCARD_EXPORT_FAILED');
		}
		if (vCardExportDelayMs > 0) {
			return new Promise((resolve) => {
				setTimeout(() => resolve(vCardExportResponse(accountId, contacts)), vCardExportDelayMs);
			});
		}
		return vCardExportResponse(accountId, contacts);
	}

	if (segments[0] === 'autocomplete' && method === 'GET') {
		const q = (url.searchParams.get('q') ?? '').toLowerCase();
		const results = contacts.flatMap((contact) =>
			contact.emails
				.filter((email) => email.email.toLowerCase().includes(q))
				.map((email) => ({
					contactId: contact.id,
					emailId: email.id,
					email: email.email,
					label: email.label,
					primary: email.primary,
					name: contact.name,
					surname: contact.surname
				}))
		);
		return HttpResponse.json(results);
	}

	const contactId = Number(segments[0]);
	const contact = contacts.find((item) => item.id === contactId);
	if (!contact) return problem(404, 'Kontakt nenalezen.', 'CONTACT_NOT_FOUND');

	if (segments.length === 1) {
		if (method === 'GET') return HttpResponse.json(contact);
		if (method === 'DELETE') {
			fixtureState.contactsByAccount[accountId] = contacts.filter((item) => item.id !== contactId);
			return noContent();
		}
		if (method === 'PUT') {
			return request
				.json()
				.then((body) =>
					HttpResponse.json(upsertContact(accountId, body as ContactUpdateRequest, contactId))
				);
		}
		if (method === 'PATCH') {
			return request
				.json()
				.then((body) =>
					HttpResponse.json(upsertContact(accountId, body as ContactPatchRequest, contactId))
				);
		}
	}

	if (segments[1] === 'merge' && method === 'POST') {
		return request.json().then((body) => {
			const requestBody = body as ContactMergeRequest;
			const sourceIds = requestBody.source ?? [];
			const uniqueSourceIds = [...new Set(sourceIds)];
			if (uniqueSourceIds.length !== sourceIds.length) {
				return problem(400, 'Seznam source obsahuje duplicitní ID.', 'VALIDATION_ERROR');
			}
			if (uniqueSourceIds.includes(contactId)) {
				return problem(
					400,
					'Cílový kontakt nesmí být zároveň ve zdrojovém seznamu.',
					'VALIDATION_ERROR'
				);
			}

			const sources = uniqueSourceIds.map((id) => contacts.find((item) => item.id === id));
			if (sources.some((item) => item == null)) {
				return problem(404, 'Kontakt nenalezen.', 'CONTACT_NOT_FOUND');
			}

			const seenEmails = new Set(contact.emails.map((email) => email.email.toLowerCase()));
			const nextEmailId =
				Math.max(0, ...contacts.flatMap((item) => item.emails.map((email) => email.id))) + 1;
			let emailId = nextEmailId;
			const addedEmails = sources.flatMap((source) =>
				source!.emails
					.filter((email) => {
						const key = email.email.toLowerCase();
						if (seenEmails.has(key)) return false;
						seenEmails.add(key);
						return true;
					})
					.map((email) => ({
						...email,
						id: emailId++,
						primary: false
					}))
			);
			if (contact.emails.length + addedEmails.length > 10) {
				return problem(
					400,
					`Po sloučení by kontakt měl ${contact.emails.length + addedEmails.length} e-mailů, maximum je 10.`,
					'VALIDATION_ERROR'
				);
			}

			const mergedContact: ContactResponse = {
				...contact,
				emails: [...contact.emails, ...addedEmails],
				note:
					[contact.note, ...sources.map((source) => source!.note)].filter(Boolean).join('\n\n') ||
					null,
				updatedAt: new Date().toISOString()
			};
			const sourceIdSet = new Set(uniqueSourceIds);
			fixtureState.contactsByAccount[accountId] = contacts
				.filter((item) => !sourceIdSet.has(item.id))
				.map((item) => (item.id === contactId ? mergedContact : item));
			return HttpResponse.json(mergedContact);
		});
	}

	if (segments[1] === 'emails') {
		const emailId = Number(segments[2]);
		if (segments.length === 2 && method === 'POST') {
			return request.json().then((body) => {
				const nextId = Math.max(0, ...contact.emails.map((email) => email.id)) + 1;
				const requestBody = body as ContactEmailRequest;
				const email = {
					id: nextId,
					email: requestBody.email,
					label: requestBody.label ?? null,
					primary: contact.emails.length === 0
				};
				contact.emails = [...contact.emails, email];
				return HttpResponse.json(email, { status: 201 });
			});
		}
		if (segments.length === 3 && method === 'DELETE') {
			contact.emails = contact.emails.filter((email) => email.id !== emailId);
			return noContent();
		}
		if (segments[3] === 'primary' && method === 'PATCH') {
			contact.emails = contact.emails.map((email) => ({ ...email, primary: email.id === emailId }));
			return HttpResponse.json(contact);
		}
	}

	return null;
}

function messageRoutes(
	method: string,
	segments: string[],
	request: Request
): Promise<MockResponse> | MockResponse | null {
	if (segments[0] !== 'messages') return null;
	const url = new URL(request.url);

	if (segments[1] === 'account') {
		const accountId = Number(segments[2]);
		if (segments[3] === 'sync' && method === 'POST') return accepted();
		if (segments[3] === 'send' && method === 'POST')
			return request.json().then((body) => {
				// Recorded so the SSE bridge can mirror the backend contract: delete
				// the superseded draft on send_completed, park a recovery draft on a
				// send_failed with no supersede (B2).
				fixtureState.lastSendMailRequest = body as MailRequest;
				fixtureState.lastSendSupersedesDraftId = url.searchParams.get('supersedesDraftId');
				return acceptedSend(accountId);
			});
		if (segments[3] === 'folder' && method === 'GET') {
			const folderName = url.searchParams.get('folderRef') ?? decodeSegment(segments[4]);
			return HttpResponse.json(listPage(getFolderMessages(accountId, folderName), url));
		}
		if (segments[3] === 'search' && method === 'GET') {
			const q = (url.searchParams.get('q') ?? '').toLowerCase();
			const messages = Object.entries(fixtureState.messagesByFolder)
				.filter(([key]) => key.startsWith(`${accountId}:`))
				.flatMap(([, items]) => items)
				.filter((message) =>
					[message.subject, message.sender].some((value) => value.toLowerCase().includes(q))
				);
			return HttpResponse.json(listPage(messages, url));
		}
	}

	const stableId = decodeSegment(segments[1]);
	const detail = fixtureState.messageDetails[stableId];
	if (!detail) return problem(404, 'Zpráva nenalezena.', 'MESSAGE_NOT_FOUND');

	if (segments.length === 2) {
		if (method === 'GET') return HttpResponse.json(detail);
		if (method === 'DELETE') {
			removeMessageEverywhere(stableId);
			return noContent();
		}
	}

	if (segments[2] === 'flags' && method === 'PATCH') {
		const type = url.searchParams.get('type') as MailFlagType | null;
		const value = url.searchParams.get('value') === 'true';
		if (type) {
			detail[type] = value;
			for (const [key, messages] of Object.entries(fixtureState.messagesByFolder)) {
				const folderName = key.split(':')[1];
				replaceFolderMessages(
					Number(key.split(':')[0]),
					folderName,
					messages.map((message) =>
						message.stableId === stableId ? { ...message, [type]: value } : message
					)
				);
			}
		}
		return noContent();
	}

	if (segments[2] === 'reply' && method === 'GET') {
		return HttpResponse.json(replyFor(stableId, url.searchParams.get('all') === 'true'));
	}
	if (segments[2] === 'forward' && method === 'GET') return HttpResponse.json(forwardFor(stableId));
	if (segments[2] === 'content' && method === 'GET') {
		return HttpResponse.json(
			fixtureState.messageContents[stableId] ?? { content: detail.body ?? '' }
		);
	}
	if (segments[2] === 'attachments' && method === 'GET') {
		const partPath = decodeSegment(segments[3]);
		return HttpResponse.arrayBuffer(
			new TextEncoder().encode(
				fixtureState.attachments[`${stableId}:${partPath}`] ? 'fixture attachment' : ''
			).buffer,
			{
				headers: {
					'Content-Type': detail.attachments[0]?.contentType ?? 'application/octet-stream'
				}
			}
		);
	}
	if (segments[2] === 'move' && method === 'POST') {
		return request.json().then((body) => {
			const { folderRef } = body as MoveRequest;
			const sourceEntry = Object.entries(fixtureState.messagesByFolder).find(([, messages]) =>
				messages.some((message) => message.stableId === stableId)
			);
			if (!sourceEntry) return problem(404, 'Zpráva nenalezena.', 'MESSAGE_NOT_FOUND');

			const [sourceKey, sourceMessages] = sourceEntry;
			const accountId = Number(sourceKey.split(':')[0]);
			const sourceFolder = sourceKey.slice(`${accountId}:`.length);
			const summary = sourceMessages.find((message) => message.stableId === stableId);
			if (!summary) return problem(404, 'Zpráva nenalezena.', 'MESSAGE_NOT_FOUND');

			replaceFolderMessages(
				accountId,
				sourceFolder,
				sourceMessages.filter((message) => message.stableId !== stableId)
			);
			replaceFolderMessages(accountId, folderRef, [
				{ ...summary, folderName: folderRef },
				...getFolderMessages(accountId, folderRef)
			]);
			return accepted();
		});
	}

	return null;
}

function authRoutes(method: string, segments: string[]): MockResponse | null {
	if (
		segments[0] === 'auth' &&
		segments[1] === 'google' &&
		segments[2] === 'success' &&
		method === 'GET'
	) {
		return HttpResponse.json({ ok: true });
	}
	return null;
}

function notificationRoutes(method: string, segments: string[]): MockResponse | null {
	if (segments[0] === 'notifications' && segments[1] === 'stream' && method === 'GET') {
		return openSyncStream();
	}
	return null;
}

function clientConfigRoutes(method: string, segments: string[]): MockResponse | null {
	if (segments[0] !== 'client-config' || method !== 'GET') return null;
	return HttpResponse.json({
		mailDefaultPageSize: mailPageSizeOverride ?? 25,
		mailApiMaxPageSize: 200,
		searchQueryMaxLength: 256,
		contactDefaultPageSize: 25,
		contactQueryMaxLength: 100,
		contactAutocompleteDefaultLimit: 6,
		contactAutocompleteMaxLimit: 20,
		attachmentMaxBytes: 10 * 1024 * 1024,
		attachmentTotalMaxBytes: 25 * 1024 * 1024,
		largeAttachmentWarningBytes: 5 * 1024 * 1024
	});
}

function systemRoutes(
	method: string,
	segments: string[]
): Promise<MockResponse> | MockResponse | null {
	if (segments[0] !== 'system' || segments[1] !== 'readiness' || method !== 'GET') return null;
	if (readinessFailures > 0) {
		readinessFailures -= 1;
		return HttpResponse.json(
			{
				ready: false,
				phase: 'STARTING',
				appName: 'mail',
				appVersion: '0.0.1-SNAPSHOT',
				apiVersion: '1.0.0',
				minClientVersion: '0.0.1',
				dbSchemaVersion: '1',
				reason: 'Backend se ještě připravuje.'
			},
			{ status: 503 }
		);
	}

	const response = HttpResponse.json({
		ready: true,
		phase: 'READY',
		appName: 'mail',
		appVersion: '0.0.1-SNAPSHOT',
		apiVersion: '1.0.0',
		minClientVersion: '0.0.1',
		dbSchemaVersion: '1',
		reason: null
	});

	if (readinessDelayMs > 0) {
		return new Promise((resolve) => {
			setTimeout(() => resolve(response), readinessDelayMs);
		});
	}
	return response;
}

async function routeInternalRequest(request: Request): Promise<MockResponse> {
	const authError = requireApiKey(request);
	if (authError) return authError;

	const url = new URL(request.url);
	const apiIndex = url.pathname.indexOf('/api/internal/');
	const path = apiIndex >= 0 ? url.pathname.slice(apiIndex + '/api/internal/'.length) : '';
	const segments = path.split('/').filter(Boolean);
	const method = request.method.toUpperCase();

	if (segments[0] === 'client-errors' && method === 'POST') {
		await request.json();
		return accepted();
	}
	if (segments[0] === 'client-boot' && method === 'POST') {
		await request.json();
		return accepted();
	}
	if (segments[0] === 'diagnostic-dump' && method === 'GET') {
		return new HttpResponse(new TextEncoder().encode('mock diagnostic dump'), {
			status: 200,
			headers: {
				'Content-Type': 'application/zip',
				'Content-Disposition': 'attachment; filename="mail-diagnostic.zip"'
			}
		});
	}

	return problem(404, `Mock handler nenalezen pro ${method} ${url.pathname}.`, 'MOCK_NOT_FOUND');
}

async function routeApiRequest(request: Request): Promise<MockResponse> {
	const authError = requireApiKey(request);
	if (authError) return authError;

	const url = new URL(request.url);
	const apiIndex = url.pathname.indexOf('/api/v1/');
	const path = apiIndex >= 0 ? url.pathname.slice(apiIndex + '/api/v1/'.length) : '';
	const segments = path.split('/').filter(Boolean);
	const method = request.method.toUpperCase();

	const response =
		clientConfigRoutes(method, segments) ??
		systemRoutes(method, segments) ??
		accountRoutes(method, segments, request) ??
		messageRoutes(method, segments, request) ??
		authRoutes(method, segments) ??
		notificationRoutes(method, segments);

	if (response) return response;
	return problem(404, `Mock handler nenalezen pro ${method} ${url.pathname}.`, 'MOCK_NOT_FOUND');
}

export const handlers = [
	http.all(/\/api\/internal\/.*/, ({ request }) => routeInternalRequest(request)),
	http.all(/\/api\/v1\/.*/, ({ request }) => routeApiRequest(request))
];
