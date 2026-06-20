/**
 * Types mapped directly onto the Spring Boot backend DTOs.
 *
 * Jackson serializes `LocalDateTime` and `Instant` as ISO-8601 strings and
 * `long`/`Long` as number. All DTOs are Java `record`s; here they are
 * declared as `interface` (easier to extend on the FE side).
 */

import type {
	BulkContactCreateResponse as GeneratedBulkContactCreateResponse,
	BulkContactDeleteResponse as GeneratedBulkContactDeleteResponse,
	ClientConfigResponse as GeneratedClientConfigResponse,
	ContactEmailRequest as GeneratedContactEmailRequest,
	FolderResponse as GeneratedFolderResponse,
	MoveRequest as GeneratedMoveRequest
} from '$lib/api/generated.js';

// ─── Common ────────────────────────────────────────────────────────────────

export interface PagedResponse<T> {
	content: T[];
	page: number;
	size: number;
	totalPages: number;
	totalElements: number;
	first: boolean;
	last: boolean;
}

// ─── Accounts & providers ──────────────────────────────────────────────────

export type AuthType = 'PASSWORD' | 'OAUTH2';

export interface AccountResponse {
	id: number;
	accountName: string;
	email: string;
	displayName: string | null;
	/** Per-account outgoing signature (RFC 3676 `"-- "` block), or `null` when unset. */
	signature: string | null;
	providerId: number | null;
	providerName: string | null;
	imapHost: string;
	imapPort: number;
	imapUseSsl: boolean;
	smtpHost: string;
	smtpPort: number;
	smtpUseSsl: boolean;
	username: string;
	authType: AuthType;
	/** OAuth2 provider registrationId (`"google"`, `"microsoft"`, …) or `null` for PASSWORD accounts. */
	oauth2Provider: string | null;
	active: boolean;
	requiresReauth: boolean;
	/** ISO-8601 LocalDateTime, e.g. `2026-04-17T08:30:00`. */
	lastSyncAt: string | null;
	lastError: string | null;
	lastErrorCode: string | null;
	lastErrorArgs: Record<string, string>;
}

/**
 * Nested block for IMAP/SMTP server configuration on "custom" accounts.
 * The client sends it on create/update when the user does not pick a
 * template. The backend does not allow field-level patches inside the
 * block — always the whole object.
 */
export interface MailServerSettings {
	host: string;
	port: number;
	useSsl: boolean;
}

export interface AccountCreateRequest {
	accountName: string;
	displayName?: string | null;
	email: string;
	/** XOR with `imap`+`smtp` — either a template or custom config. */
	providerId?: number | null;
	imap?: MailServerSettings;
	smtp?: MailServerSettings;
	username: string;
	password: string;
}

export interface AccountUpdateRequest {
	accountName: string;
	email: string;
	displayName?: string | null;
	/** Per-account outgoing signature (RFC 3676 `"-- "` block). Null/blank clears it. */
	signature?: string | null;
	providerId?: number | null;
	imap?: MailServerSettings;
	smtp?: MailServerSettings;
	username: string;
	password?: string | null;
	active: boolean;
}

export interface AccountConnectionTestRequest {
	accountId?: number | null;
	email: string;
	providerId?: number | null;
	imap?: MailServerSettings;
	smtp?: MailServerSettings;
	username: string;
	password?: string | null;
}

export interface AccountConnectionTestResponse {
	imapOk: boolean;
	smtpOk: boolean;
	message: string;
}

export interface MailProviderResponse {
	id: number;
	name: string;
	imapHost: string;
	imapPort: number;
	imapSsl: boolean;
	smtpHost: string;
	smtpPort: number;
	smtpSsl: boolean;
	/** Comma-separated list of domains (e.g. "gmail.com,googlemail.com"). */
	domains: string;
	/** Whether the backend has an OAuth2 implementation for this provider. */
	supportsOauth2?: boolean;
	/**
	 * Spring Security ClientRegistration id (`"google"`, `"microsoft"`, …) or
	 * null/absent for password-only providers. Stable across display renames,
	 * so it — not {@link name} — is the key the wizard pairs presets on.
	 */
	oauth2RegistrationId?: string | null;
}

// ─── Folders ───────────────────────────────────────────────────────────────

/*
 * The hand-written FE model stays stricter than the current OpenAPI response
 * DTO, but enum unions are derived from schema.d.ts so that schema drift
 * fails the check.
 */
export type FolderRole = NonNullable<GeneratedFolderResponse['role']>;

export interface FolderResponse {
	displayName: string;
	/** Internal IMAP folder name – used in paths `/messages/.../folder/{folderRef}`. */
	folderRef: string;
	unreadCount: number;
	role: FolderRole;
}

// ─── Messages: listing / detail / content ──────────────────────────────────

export interface MailSummaryResponse {
	id: number;
	stableId: string;
	folderName: string;
	subject: string;
	sender: string;
	recipientsTo: string | null;
	receivedAt: string;
	seen: boolean;
	flagged: boolean;
	answered: boolean;
	hasAttachments: boolean;
}

export interface AttachmentResponse {
	partPath: string;
	fileName: string;
	contentType: string;
	size: number;
}

export interface MailDetailResponse {
	stableId: string;
	subject: string;
	sender: string;
	recipientsTo: string;
	recipientsCc: string;
	body: string | null;
	receivedAt: string;
	seen: boolean;
	flagged: boolean;
	answered: boolean;
	messageId: string;
	inReplyTo: string;
	references: string;
	hasAttachments: boolean;
	attachments: AttachmentResponse[];
	contentError: string | null;
}

export interface MailContentResponse {
	content: string;
}

export type MailFlagType = 'seen' | 'flagged' | 'answered';

export type MoveRequest = GeneratedMoveRequest;

// ─── Client runtime configuration ─────────────────────────────────────────

export type ClientConfigResponse = GeneratedClientConfigResponse;

// ─── Messages: send / drafts ───────────────────────────────────────────────

export interface AttachmentRequest {
	fileName: string;
	contentType: string;
	base64Data: string;
}

export interface MailRequest {
	to: string;
	cc?: string | null;
	bcc?: string | null;
	subject: string;
	body: string;
	attachments?: AttachmentRequest[];
	inReplyTo?: string | null;
	references?: string | null;
}

export interface DraftRequest {
	to?: string | null;
	cc?: string | null;
	bcc?: string | null;
	subject?: string | null;
	body?: string | null;
	attachments?: AttachmentRequest[];
	inReplyTo?: string | null;
	references?: string | null;
}

// ─── Contacts ──────────────────────────────────────────────────────────────

export type EmailLabel = NonNullable<GeneratedContactEmailRequest['label']>;

export interface ContactEmailRequest {
	email: string;
	label?: EmailLabel | null;
}

export interface ContactEmailResponse {
	id: number;
	email: string;
	label: EmailLabel | null;
	primary: boolean;
}

export interface ContactCreateRequest {
	emails: ContactEmailRequest[];
	name?: string | null;
	surname?: string | null;
	note?: string | null;
}

export interface ContactUpdateRequest {
	emails: ContactEmailRequest[];
	name?: string | null;
	surname?: string | null;
	note?: string | null;
}

export interface ContactPatchRequest {
	emails?: ContactEmailRequest[] | null;
	name?: string | null;
	surname?: string | null;
	note?: string | null;
}

export interface ContactResponse {
	id: number;
	emails: ContactEmailResponse[];
	name: string | null;
	surname: string | null;
	note: string | null;
	createdAt: string;
	updatedAt: string;
}

export interface ContactAutocompleteResponse {
	contactId: number;
	emailId: number;
	email: string;
	label: EmailLabel | null;
	primary: boolean;
	name: string | null;
	surname: string | null;
}

export interface BulkContactCreateRequest {
	contacts: ContactCreateRequest[];
}

export type BulkContactCreateResponse = GeneratedBulkContactCreateResponse;

export interface BulkContactDeleteRequest {
	ids: number[];
}

export type BulkContactDeleteResponse = GeneratedBulkContactDeleteResponse;

export interface ContactMergeRequest {
	source: number[];
}

/** Body of the 202 returned by the async send endpoints. */
export interface SendAcceptedResponse {
	/** Correlation id for tracking the send outcome on the notification stream. */
	sendId: string;
}

// ─── Real-time notifications ───────────────────────────────────────────────

/** Payload of the `sync_completed` event from `/notifications/stream`. */
export interface SyncNotification {
	type: string;
	accountId: number;
	folderName: string;
	newMessagesCount: number;
	/** ISO-8601 Instant, e.g. `2026-04-17T08:30:00Z`. */
	timestamp: string;
}

/**
 * Payload of the `send_completed` / `send_failed` events. Correlated with the
 * original request via {@link SendAcceptedResponse.sendId}; recipient / subject
 * are held by the client and not duplicated here.
 */
export interface SendNotification {
	type: 'send_completed' | 'send_failed';
	sendId: string;
	accountId: number;
	/** Set only for `send_failed`; mirrors the account lastError code. */
	errorCode: string | null;
}

/**
 * Payload of the `thread_updated` event. Emitted when the membership of a
 * conversation changes — a new message landed in the thread, or a
 * late-arriving parent caused two orphan chains to merge. The payload is
 * intentionally minimal: the frontend refetches the affected thread (or
 * the surrounding folder listing) to get the new state. V0.1.0 UI does
 * not yet subscribe to this event — see THREADING_DESIGN.md.
 */
export interface ThreadUpdated {
	type: 'thread_updated';
	threadId: string;
	accountId: number;
}

/** Any event delivered over `/notifications/stream`. */
export type StreamNotification = SyncNotification | SendNotification | ThreadUpdated;
