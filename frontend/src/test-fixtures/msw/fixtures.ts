import type {
	AccountCreateRequest,
	AccountResponse,
	AccountUpdateRequest,
	AttachmentResponse,
	ContactCreateRequest,
	ContactEmailResponse,
	ContactPatchRequest,
	ContactResponse,
	ContactUpdateRequest,
	DraftRequest,
	FolderResponse,
	MailContentResponse,
	MailDetailResponse,
	MailProviderResponse,
	MailRequest,
	MailSummaryResponse,
	PagedResponse
} from '$lib/types.js';

export interface E2EFixtureState {
	accounts: AccountResponse[];
	providers: MailProviderResponse[];
	foldersByAccount: Record<number, FolderResponse[]>;
	messagesByFolder: Record<string, MailSummaryResponse[]>;
	messageDetails: Record<string, MailDetailResponse>;
	messageContents: Record<string, MailContentResponse>;
	contactsByAccount: Record<number, ContactResponse[]>;
	draftsByAccount: Record<number, MailSummaryResponse[]>;
	attachments: Record<string, Blob>;
	/** sendId of the most recent async send, so the SSE bridge can echo its outcome. */
	lastSendId: string | null;
	lastSendAccountId: number | null;
	/**
	 * `supersedesDraftId` of the most recent send (or the draft id itself for a
	 * draft-send). Mirrors the backend contract: the SSE bridge deletes it on
	 * `send_completed` and keeps it on `send_failed`.
	 */
	lastSendSupersedesDraftId: string | null;
	/** Body of the most recent `POST .../send`, for the B2 recovery draft on failure. */
	lastSendMailRequest: MailRequest | null;
}

const now = new Date('2026-04-21T08:00:00.000Z');
let draftSequence = 100;

function iso(minutesAgo: number): string {
	return new Date(now.getTime() - minutesAgo * 60_000).toISOString();
}

function folderKey(accountId: number, folderName: string): string {
	return `${accountId}:${folderName}`;
}

function makeSummary(index: number): MailSummaryResponse {
	const stableId = `msg-${index.toString().padStart(2, '0')}`;
	return {
		id: index,
		stableId,
		folderName: 'INBOX',
		subject: index === 1 ? 'Projektové podklady' : `Testovací zpráva ${index}`,
		sender:
			index === 1
				? 'Jana Novak <jana@example.com>'
				: `Odesílatel ${index} <sender${index}@example.com>`,
		recipientsTo: 'me@example.com',
		receivedAt: iso(index * 17),
		seen: index > 3,
		flagged: index === 2,
		answered: index === 3,
		hasAttachments: index === 1
	};
}

function makeDetail(summary: MailSummaryResponse): MailDetailResponse {
	const attachments: AttachmentResponse[] = summary.hasAttachments
		? [
				{
					partPath: '2',
					fileName: 'brief.pdf',
					contentType: 'application/pdf',
					size: 2048
				}
			]
		: [];

	return {
		stableId: summary.stableId,
		folderName: summary.folderName,
		subject: summary.subject,
		sender: summary.sender,
		recipientsTo: 'tester@example.com',
		recipientsCc: '',
		recipientsBcc: '',
		body: `Text zprávy ${summary.subject}`,
		receivedAt: summary.receivedAt,
		seen: summary.seen,
		flagged: summary.flagged,
		answered: summary.answered,
		messageId: `<${summary.stableId}@example.com>`,
		inReplyTo: '',
		references: '',
		hasAttachments: summary.hasAttachments,
		attachments,
		contentError: null
	};
}

function pageOf<T>(content: T[], page: number, size: number): PagedResponse<T> {
	const start = page * size;
	const slice = content.slice(start, start + size);
	const totalPages = Math.max(1, Math.ceil(content.length / size));
	return {
		content: slice,
		page,
		size,
		totalPages,
		totalElements: content.length,
		first: page === 0,
		last: page >= totalPages - 1
	};
}

const inboxMessages = Array.from({ length: 25 }, (_, index) => makeSummary(index + 1));
const archiveMessages: MailSummaryResponse[] = [];
const junkMessages: MailSummaryResponse[] = [];
const sentMessages: MailSummaryResponse[] = [
	{
		...makeSummary(31),
		stableId: 'sent-01',
		folderName: 'SENT',
		subject: 'Odeslaná zpráva',
		seen: true,
		hasAttachments: false
	}
];
const draftMessages: MailSummaryResponse[] = [
	{
		...makeSummary(42),
		stableId: 'draft-42',
		folderName: 'DRAFTS',
		subject: 'Rozepsaný koncept',
		seen: true,
		hasAttachments: false
	},
	// A second draft so keyboard tests can exercise a row change in Drafts
	// (arrows must not open the composer — only Enter/Space does).
	{
		...makeSummary(43),
		stableId: 'draft-43',
		folderName: 'DRAFTS',
		subject: 'Druhý rozepsaný koncept',
		seen: true,
		hasAttachments: false
	}
];
// Two messages so the trash permanent-delete flow can exercise both the
// single-row Delete and the select-all bulk path.
const trashMessages: MailSummaryResponse[] = [
	{
		...makeSummary(51),
		stableId: 'trash-01',
		folderName: 'TRASH',
		// "e-mail", not "zpráva" — the search e2e counts fixture matches for
		// the query "zpráva" and trash rows must stay out of that result set.
		subject: 'Smazaný e-mail 1',
		seen: true,
		hasAttachments: false
	},
	{
		...makeSummary(52),
		stableId: 'trash-02',
		folderName: 'TRASH',
		subject: 'Smazaný e-mail 2',
		seen: true,
		hasAttachments: false
	}
];
const allMessages = [
	...inboxMessages,
	...archiveMessages,
	...junkMessages,
	...sentMessages,
	...draftMessages,
	...trashMessages
];

function createInitialState(): E2EFixtureState {
	return {
		accounts: [
			{
				id: 1,
				accountName: 'Pracovní účet',
				email: 'tester@example.com',
				displayName: 'Tester',
				signature: null,
				signatureAutoInsert: true,
				providerId: 1,
				providerName: 'Example Mail',
				imapHost: 'imap.example.com',
				imapPort: 993,
				imapUseSsl: true,
				smtpHost: 'smtp.example.com',
				smtpPort: 465,
				smtpUseSsl: true,
				username: 'tester@example.com',
				authType: 'PASSWORD',
				oauth2Provider: null,
				active: true,
				requiresReauth: false,
				lastSyncAt: iso(5),
				lastError: null,
				lastErrorCode: null,
				lastErrorArgs: {}
			},
			{
				id: 2,
				accountName: 'Osobní účet',
				email: 'personal@another.test',
				displayName: null,
				signature: null,
				signatureAutoInsert: true,
				providerId: 2,
				providerName: 'Another Mail',
				imapHost: 'imap.another.test',
				imapPort: 993,
				imapUseSsl: true,
				smtpHost: 'smtp.another.test',
				smtpPort: 587,
				smtpUseSsl: true,
				username: 'personal@another.test',
				authType: 'PASSWORD',
				oauth2Provider: null,
				active: true,
				requiresReauth: false,
				lastSyncAt: null,
				lastError: null,
				lastErrorCode: null,
				lastErrorArgs: {}
			}
		],
		providers: [
			{
				id: 1,
				name: 'Example Mail',
				imapHost: 'imap.example.com',
				imapPort: 993,
				imapSsl: true,
				smtpHost: 'smtp.example.com',
				smtpPort: 465,
				smtpSsl: true,
				domains: 'example.com'
			},
			{
				id: 2,
				name: 'Another Mail',
				imapHost: 'imap.another.test',
				imapPort: 993,
				imapSsl: true,
				smtpHost: 'smtp.another.test',
				smtpPort: 587,
				smtpSsl: true,
				domains: 'another.test'
			},
			{
				id: 3,
				name: 'Google',
				imapHost: 'imap.gmail.com',
				imapPort: 993,
				imapSsl: true,
				smtpHost: 'smtp.gmail.com',
				smtpPort: 465,
				smtpSsl: true,
				domains: 'gmail.com',
				supportsOauth2: true,
				oauth2RegistrationId: 'google'
			},
			{
				id: 4,
				name: 'Microsoft',
				imapHost: 'outlook.office365.com',
				imapPort: 993,
				imapSsl: true,
				smtpHost: 'smtp-mail.outlook.com',
				smtpPort: 587,
				smtpSsl: true,
				domains: 'outlook.com,hotmail.com,office365.com',
				supportsOauth2: true,
				oauth2RegistrationId: 'microsoft'
			},
			{
				id: 5,
				name: 'Seznam',
				imapHost: 'imap.seznam.cz',
				imapPort: 993,
				imapSsl: true,
				smtpHost: 'smtp.seznam.cz',
				smtpPort: 465,
				smtpSsl: true,
				domains: 'seznam.cz,email.cz,post.cz'
			}
		],
		foldersByAccount: {
			1: [
				{ displayName: 'Doručené', folderRef: 'INBOX', unreadCount: 3, role: 'INBOX' },
				{ displayName: 'Odeslané', folderRef: 'SENT', unreadCount: 0, role: 'SENT' },
				{ displayName: 'Koncepty', folderRef: 'DRAFTS', unreadCount: 0, role: 'DRAFTS' },
				{ displayName: 'Spam', folderRef: 'JUNK', unreadCount: 0, role: 'JUNK' },
				{ displayName: 'Archiv', folderRef: 'ARCHIVE', unreadCount: 0, role: 'ARCHIVE' },
				{ displayName: 'Koš', folderRef: 'TRASH', unreadCount: 0, role: 'TRASH' }
			],
			2: [{ displayName: 'Doručené', folderRef: 'INBOX', unreadCount: 0, role: 'INBOX' }]
		},
		messagesByFolder: {
			[folderKey(1, 'INBOX')]: inboxMessages.map((message) => ({ ...message })),
			[folderKey(1, 'ARCHIVE')]: archiveMessages.map((message) => ({ ...message })),
			[folderKey(1, 'JUNK')]: junkMessages.map((message) => ({ ...message })),
			[folderKey(1, 'SENT')]: sentMessages.map((message) => ({ ...message })),
			[folderKey(1, 'DRAFTS')]: draftMessages.map((message) => ({ ...message })),
			[folderKey(1, 'TRASH')]: trashMessages.map((message) => ({ ...message })),
			[folderKey(2, 'INBOX')]: []
		},
		messageDetails: Object.fromEntries(
			allMessages.map((message) => [message.stableId, makeDetail(message)])
		),
		messageContents: Object.fromEntries(
			allMessages.map((message) => [
				message.stableId,
				{
					content:
						message.stableId === 'msg-01'
							? `<div><p onclick="window.__xss=1">HTML obsah pro <strong>${message.subject}</strong>.</p><script>window.__xss=1</script><img src="https://tracker.example.test/pixel.png" onerror="window.__xss=1" alt="tracker"><a href="javascript:alert(1)">nebezpečný odkaz</a><a href="https://example.com/safe">bezpečný odkaz</a></div>`
							: message.stableId === 'msg-02'
								? `<div><p>Newsletter pro <strong>${message.subject}</strong>.</p><img data-voxrox-remote-src="https://cdn.example.test/logo.png" alt="logo"></div>`
								: `<p>HTML obsah pro <strong>${message.subject}</strong>.</p>`,
					senderEmail: 'newsletter@example.com',
					remoteImagesAllowedForSender: false
				}
			])
		),
		contactsByAccount: {
			1: [
				{
					id: 1,
					name: 'Jana',
					surname: 'Novak',
					note: 'Projekt',
					createdAt: iso(400),
					updatedAt: iso(30),
					emails: [
						{ id: 1, email: 'jana@example.com', label: 'WORK', primary: true },
						{ id: 2, email: 'jana.home@example.com', label: 'HOME', primary: false }
					]
				}
			],
			2: []
		},
		draftsByAccount: {
			1: draftMessages.map((message) => ({ ...message })),
			2: []
		},
		attachments: {
			'msg-01:2': new Blob(['fixture attachment'], { type: 'application/pdf' })
		},
		lastSendId: null,
		lastSendAccountId: null,
		lastSendSupersedesDraftId: null,
		lastSendMailRequest: null
	};
}

export let fixtureState: E2EFixtureState = createInitialState();

export function resetFixtures(): E2EFixtureState {
	draftSequence = 100;
	fixtureState = createInitialState();
	return fixtureState;
}

export function clearAccounts(): E2EFixtureState {
	fixtureState.accounts = [];
	fixtureState.foldersByAccount = {};
	fixtureState.messagesByFolder = {};
	fixtureState.contactsByAccount = {};
	fixtureState.draftsByAccount = {};
	return fixtureState;
}

export function listPage<T>(items: T[], url: URL): PagedResponse<T> {
	const page = Number(url.searchParams.get('page') ?? '0');
	const size = Number(url.searchParams.get('size') ?? '25');
	return pageOf(items, Number.isFinite(page) ? page : 0, Number.isFinite(size) ? size : 25);
}

export function getFolderMessages(accountId: number, folderName: string): MailSummaryResponse[] {
	return fixtureState.messagesByFolder[folderKey(accountId, folderName)] ?? [];
}

export function replaceFolderMessages(
	accountId: number,
	folderName: string,
	messages: MailSummaryResponse[]
): void {
	fixtureState.messagesByFolder[folderKey(accountId, folderName)] = messages;
}

export function incrementFolderUnreadCount(
	accountId: number,
	folderName: string,
	count: number
): void {
	fixtureState.foldersByAccount[accountId] = (fixtureState.foldersByAccount[accountId] ?? []).map(
		(folder) =>
			folder.folderRef === folderName
				? { ...folder, unreadCount: Math.max(0, folder.unreadCount + count) }
				: folder
	);
}

export function upsertAccount(
	body: AccountCreateRequest | AccountUpdateRequest,
	id = Math.max(0, ...fixtureState.accounts.map((account) => account.id)) + 1
): AccountResponse {
	const existing = fixtureState.accounts.find((account) => account.id === id);
	const providerFromBody =
		body.providerId != null
			? (fixtureState.providers.find((item) => item.id === body.providerId) ?? null)
			: null;

	// Denormalization rules (mirror of the backend):
	//  - body.providerId → copy from the template, providerName = template name
	//  - body.imap/smtp → custom config, providerId=null, providerName="Vlastní"
	//  - neither of them (PATCH) → keep existing
	const hasCustomServer = body.imap != null || body.smtp != null;
	const existingHasProvider = existing?.providerId != null;
	const providerKept =
		!providerFromBody && !hasCustomServer && existingHasProvider
			? (fixtureState.providers.find((item) => item.id === existing!.providerId) ?? null)
			: null;

	let providerId: number | null;
	let providerName: string | null;
	let imapHost: string;
	let imapPort: number;
	let imapUseSsl: boolean;
	let smtpHost: string;
	let smtpPort: number;
	let smtpUseSsl: boolean;

	if (providerFromBody) {
		providerId = providerFromBody.id;
		providerName = providerFromBody.name;
		imapHost = providerFromBody.imapHost;
		imapPort = providerFromBody.imapPort;
		imapUseSsl = providerFromBody.imapSsl;
		smtpHost = providerFromBody.smtpHost;
		smtpPort = providerFromBody.smtpPort;
		smtpUseSsl = providerFromBody.smtpSsl;
	} else if (hasCustomServer) {
		providerId = null;
		providerName = 'Vlastní';
		imapHost = body.imap?.host ?? existing?.imapHost ?? 'imap.example.com';
		imapPort = body.imap?.port ?? existing?.imapPort ?? 993;
		imapUseSsl = body.imap?.useSsl ?? existing?.imapUseSsl ?? true;
		smtpHost = body.smtp?.host ?? existing?.smtpHost ?? 'smtp.example.com';
		smtpPort = body.smtp?.port ?? existing?.smtpPort ?? 465;
		smtpUseSsl = body.smtp?.useSsl ?? existing?.smtpUseSsl ?? true;
	} else if (providerKept) {
		providerId = providerKept.id;
		providerName = providerKept.name;
		imapHost = existing!.imapHost;
		imapPort = existing!.imapPort;
		imapUseSsl = existing!.imapUseSsl;
		smtpHost = existing!.smtpHost;
		smtpPort = existing!.smtpPort;
		smtpUseSsl = existing!.smtpUseSsl;
	} else {
		providerId = existing?.providerId ?? null;
		providerName = existing?.providerName ?? null;
		imapHost = existing?.imapHost ?? 'imap.example.com';
		imapPort = existing?.imapPort ?? 993;
		imapUseSsl = existing?.imapUseSsl ?? true;
		smtpHost = existing?.smtpHost ?? 'smtp.example.com';
		smtpPort = existing?.smtpPort ?? 465;
		smtpUseSsl = existing?.smtpUseSsl ?? true;
	}

	const account: AccountResponse = {
		id,
		accountName: body.accountName ?? existing?.accountName ?? 'Nový účet',
		email: body.email ?? existing?.email ?? 'new@example.com',
		displayName: body.displayName ?? existing?.displayName ?? null,
		signature: ('signature' in body ? body.signature : undefined) ?? existing?.signature ?? null,
		signatureAutoInsert:
			('signatureAutoInsert' in body ? body.signatureAutoInsert : undefined) ??
			existing?.signatureAutoInsert ??
			true,
		providerId,
		providerName,
		imapHost,
		imapPort,
		imapUseSsl,
		smtpHost,
		smtpPort,
		smtpUseSsl,
		username: body.username ?? existing?.username ?? body.email ?? 'new@example.com',
		authType: existing?.authType ?? 'PASSWORD',
		oauth2Provider: existing?.oauth2Provider ?? null,
		active: ('active' in body ? body.active : undefined) ?? existing?.active ?? true,
		requiresReauth: existing?.requiresReauth ?? false,
		lastSyncAt: existing?.lastSyncAt ?? null,
		lastError: existing?.lastError ?? null,
		lastErrorCode: existing?.lastErrorCode ?? null,
		lastErrorArgs: existing?.lastErrorArgs ?? {}
	};

	if (existing) {
		fixtureState.accounts = fixtureState.accounts.map((item) => (item.id === id ? account : item));
	} else {
		fixtureState.accounts.push(account);
		fixtureState.foldersByAccount[id] = [
			{ displayName: 'Doručené', folderRef: 'INBOX', unreadCount: 0, role: 'INBOX' }
		];
		fixtureState.messagesByFolder[folderKey(id, 'INBOX')] = [];
		fixtureState.contactsByAccount[id] = [];
		fixtureState.draftsByAccount[id] = [];
	}

	return account;
}

export function upsertContact(
	accountId: number,
	body: ContactCreateRequest | ContactUpdateRequest | ContactPatchRequest,
	id = Math.max(
		0,
		...(fixtureState.contactsByAccount[accountId] ?? []).map((contact) => contact.id)
	) + 1
): ContactResponse {
	const contacts = fixtureState.contactsByAccount[accountId] ?? [];
	const existing = contacts.find((contact) => contact.id === id);
	const emails: ContactEmailResponse[] =
		body.emails?.map((email, index) => ({
			id: existing?.emails[index]?.id ?? index + 1,
			email: email.email,
			label: email.label ?? null,
			primary: index === 0
		})) ??
		existing?.emails ??
		[];
	const contact: ContactResponse = {
		id,
		name: body.name ?? existing?.name ?? null,
		surname: body.surname ?? existing?.surname ?? null,
		note: body.note ?? existing?.note ?? null,
		emails,
		createdAt: existing?.createdAt ?? iso(0),
		updatedAt: iso(0)
	};

	fixtureState.contactsByAccount[accountId] = existing
		? contacts.map((item) => (item.id === id ? contact : item))
		: [...contacts, contact];
	return contact;
}

export function draftToSummary(
	accountId: number,
	body: DraftRequest,
	replaces?: string | null
): MailSummaryResponse {
	const id = (draftSequence += 1);
	const stableId = `draft-${id}`;
	const summary: MailSummaryResponse = {
		id,
		stableId,
		folderName: 'DRAFTS',
		subject: body.subject || '(bez předmětu)',
		sender:
			fixtureState.accounts.find((account) => account.id === accountId)?.email ??
			'tester@example.com',
		recipientsTo: body.to ?? null,
		receivedAt: iso(0),
		seen: true,
		flagged: false,
		answered: false,
		hasAttachments: Boolean(body.attachments?.length)
	};
	if (replaces) {
		fixtureState.draftsByAccount[accountId] = (
			fixtureState.draftsByAccount[accountId] ?? []
		).filter((draft) => draft.stableId !== replaces);
		replaceFolderMessages(
			accountId,
			'DRAFTS',
			getFolderMessages(accountId, 'DRAFTS').filter((draft) => draft.stableId !== replaces)
		);
		delete fixtureState.messageDetails[replaces];
		delete fixtureState.messageContents[replaces];
	}
	fixtureState.draftsByAccount[accountId] = [
		summary,
		...(fixtureState.draftsByAccount[accountId] ?? [])
	];
	replaceFolderMessages(accountId, 'DRAFTS', [summary, ...getFolderMessages(accountId, 'DRAFTS')]);
	fixtureState.messageDetails[stableId] = {
		...makeDetail(summary),
		recipientsTo: body.to ?? '',
		recipientsCc: body.cc ?? '',
		recipientsBcc: body.bcc ?? '',
		body: body.body ?? '',
		inReplyTo: body.inReplyTo ?? '',
		references: body.references ?? '',
		hasAttachments: Boolean(body.attachments?.length)
	};
	fixtureState.messageContents[stableId] = {
		content: body.body ?? '',
		senderEmail: 'me@example.com',
		remoteImagesAllowedForSender: false
	};
	return summary;
}

/**
 * Removes a message from every fixture index (folders, drafts listing,
 * detail/content). Used by DELETE /messages/{id} and by the SSE bridge when a
 * `send_completed` supersedes a draft.
 */
export function removeMessageEverywhere(stableId: string): void {
	for (const [key, messages] of Object.entries(fixtureState.messagesByFolder)) {
		fixtureState.messagesByFolder[key] = messages.filter(
			(message) => message.stableId !== stableId
		);
	}
	for (const [accountId, drafts] of Object.entries(fixtureState.draftsByAccount)) {
		fixtureState.draftsByAccount[Number(accountId)] = drafts.filter(
			(draft) => draft.stableId !== stableId
		);
	}
	delete fixtureState.messageDetails[stableId];
	delete fixtureState.messageContents[stableId];
}

export function replyFor(stableId: string, all: boolean): MailRequest {
	const detail = fixtureState.messageDetails[stableId];
	return {
		to: all ? `${detail.sender}, ${detail.recipientsTo}` : detail.sender,
		cc: all ? detail.recipientsCc : undefined,
		subject: detail.subject.startsWith('Re:') ? detail.subject : `Re: ${detail.subject}`,
		body: `\n\n--- Původní zpráva ---\n${detail.body ?? ''}`,
		inReplyTo: detail.messageId,
		references: detail.references || detail.messageId
	};
}

export function forwardFor(stableId: string): MailRequest {
	const detail = fixtureState.messageDetails[stableId];
	return {
		to: '',
		subject: detail.subject.startsWith('Fwd:') ? detail.subject : `Fwd: ${detail.subject}`,
		body: `\n\n--- Přeposlaná zpráva ---\n${detail.body ?? ''}`
	};
}
