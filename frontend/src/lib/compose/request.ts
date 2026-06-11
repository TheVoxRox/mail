import type { AttachmentRequest, DraftRequest, MailRequest } from '$lib/types.js';

export interface ComposeAttachment extends AttachmentRequest {
	localId: string;
	size: number;
}

export interface ComposeDraft {
	to: string;
	cc: string;
	bcc: string;
	subject: string;
	body: string;
	attachments: ComposeAttachment[];
	inReplyTo: string | null;
	references: string | null;
}

function toAttachmentRequest(list: ComposeAttachment[]): AttachmentRequest[] | undefined {
	if (list.length === 0) return undefined;
	return list.map(({ fileName, contentType, base64Data }) => ({
		fileName,
		contentType,
		base64Data
	}));
}

export function buildMailRequest(draft: ComposeDraft): MailRequest {
	return {
		to: draft.to,
		cc: draft.cc || null,
		bcc: draft.bcc || null,
		subject: draft.subject,
		body: draft.body,
		attachments: toAttachmentRequest(draft.attachments),
		inReplyTo: draft.inReplyTo,
		references: draft.references
	};
}

export function buildDraftRequest(draft: ComposeDraft): DraftRequest {
	return {
		to: draft.to || null,
		cc: draft.cc || null,
		bcc: draft.bcc || null,
		subject: draft.subject || null,
		body: draft.body || null,
		attachments: toAttachmentRequest(draft.attachments),
		inReplyTo: draft.inReplyTo,
		references: draft.references
	};
}

export function draftFingerprint(draft: ComposeDraft): string {
	return JSON.stringify({
		to: draft.to,
		cc: draft.cc,
		bcc: draft.bcc,
		subject: draft.subject,
		body: draft.body,
		inReplyTo: draft.inReplyTo,
		references: draft.references,
		attachments: draft.attachments.map(({ fileName, contentType, base64Data, size }) => ({
			fileName,
			contentType,
			base64Data,
			size
		}))
	});
}
