import type { AttachmentRequest, DraftRequest, MailRequest } from '#lib/types.js';

/**
 * The most attachments a draft save or a send accepts: `@Size(max = 50)` on
 * the attachment list of both MailRequest and DraftRequest. It is not in the
 * client config, so it is written down here; request.test.ts holds it to the
 * `maxItems` the OpenAPI snapshot records for both.
 */
export const MAX_ATTACHMENTS = 50;

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

/**
 * What the compose session compares to tell an edit from no change, on every
 * keystroke. An attachment counts by its `localId`, not its data: an
 * attachment is never changed in place, and its id is minted together with its
 * data wherever one is made (the picker, the prefill), so the id stands for
 * the content. With the data in here, every keystroke serialized each
 * attachment's whole base64 twice and every save left two copies of it alive,
 * which with large attachments made typing visibly lag.
 */
export function draftFingerprint(draft: ComposeDraft): string {
	return JSON.stringify({
		to: draft.to,
		cc: draft.cc,
		bcc: draft.bcc,
		subject: draft.subject,
		body: draft.body,
		inReplyTo: draft.inReplyTo,
		references: draft.references,
		attachments: draft.attachments.map(({ localId, fileName, contentType, size }) => ({
			localId,
			fileName,
			contentType,
			size
		}))
	});
}
