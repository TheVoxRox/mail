import { prepareForward, prepareReply } from '#lib/api/mailWrite.js';
import type { AttachmentResponse, MailRequest } from '#lib/types.js';
import type { ComposeAttachment } from '#lib/compose/request.js';
import { composeSession, type ComposeSession } from '#lib/compose/session.js';
import { isMailHtml, mailHtmlToPlainText } from '#lib/mail/content-sanitizer.js';

export interface ComposePrefill extends Omit<MailRequest, 'attachments'> {
	replacesDraftId: string | null;
	attachments: ComposeAttachment[];
	/**
	 * The content is the compose session's newest save of the opened draft, not
	 * the server's copy of the revision opened: that save may still be waiting
	 * for the server, so the draft is not sent as-is but rebuilt from the fields.
	 */
	fromNewestSave: boolean;
	/**
	 * The account to compose from, when the content belongs to one: the account
	 * the newest save went to. Null leaves the From account as it is.
	 */
	accountId: number | null;
}

function fromMailRequest(
	request: MailRequest,
	overrides: Partial<ComposePrefill> = {}
): ComposePrefill {
	return {
		to: request.to ?? '',
		cc: request.cc ?? '',
		bcc: request.bcc ?? '',
		subject: request.subject ?? '',
		body: request.body ?? '',
		inReplyTo: request.inReplyTo ?? null,
		references: request.references ?? null,
		replacesDraftId: null,
		attachments: [],
		fromNewestSave: false,
		accountId: null,
		...overrides
	};
}

function blobToBase64(blob: Blob): Promise<string> {
	return new Promise((resolve, reject) => {
		const reader = new FileReader();
		reader.onload = () => {
			const result = reader.result as string;
			const comma = result.indexOf(',');
			resolve(comma >= 0 ? result.slice(comma + 1) : result);
		};
		reader.onerror = () => reject(reader.error ?? new Error('FileReader failed'));
		reader.readAsDataURL(blob);
	});
}

/**
 * Downloads a message's attachments into the composer, which holds every
 * attachment in memory and rebuilds the MIME from them on send. One failed
 * download fails the whole prefill: a draft that silently lost a file, or a
 * forward without the file it was being sent for, is worse than an error the
 * user can retry.
 */
async function loadAttachments(
	stableId: string,
	attachments: AttachmentResponse[]
): Promise<ComposeAttachment[]> {
	const { downloadAttachment } = await import('#lib/api/mailRead.js');
	return Promise.all(
		attachments.map(async (att) => {
			const blob = await downloadAttachment(stableId, att.partPath, att.fileName);
			return {
				localId: crypto.randomUUID(),
				fileName: att.fileName,
				contentType: att.contentType,
				base64Data: await blobToBase64(blob),
				size: blob.size
			};
		})
	);
}

/**
 * What the composer opens with for its URL. A `?draft=` that the compose
 * session's saves have already replaced opens that session's newest save
 * instead (IMAP/SMTP audit B1-26): the Drafts list shows the revision until
 * the newer save is stored, and the user who opens it again expects what they
 * last wrote.
 */
export async function loadComposePrefill(
	params: Pick<URLSearchParams, 'get'>,
	session: Pick<ComposeSession, 'newestSaveOf'> = composeSession
): Promise<ComposePrefill | null> {
	const replyId = params.get('reply');
	const forwardId = params.get('forward');
	const draftId = params.get('draft');
	const to = params.get('to');
	const all = params.get('all') === '1';

	if (replyId) {
		return fromMailRequest(await prepareReply(replyId, all));
	}

	if (forwardId) {
		// The forward carries the original's attachments as its reading pane
		// lists them; the backend's prefill holds only the quoted body. Without
		// this a forward left every attachment behind.
		const { getMessageDetail } = await import('#lib/api/mailRead.js');
		const [request, detail] = await Promise.all([
			prepareForward(forwardId),
			getMessageDetail(forwardId)
		]);
		return fromMailRequest(request, {
			attachments: await loadAttachments(forwardId, detail.attachments ?? [])
		});
	}

	if (to) {
		return fromMailRequest({ to, subject: '', body: '' });
	}

	if (draftId) {
		const newest = await session.newestSaveOf(draftId);
		if (newest) {
			return {
				...newest.draft,
				replacesDraftId: newest.draftStableId,
				fromNewestSave: true,
				accountId: newest.accountId
			};
		}

		const { getMessageDetail, getMessageContent } = await import('#lib/api/mailRead.js');
		const [detail, content] = await Promise.all([
			getMessageDetail(draftId),
			getMessageContent(draftId)
		]);

		// Pull the draft's attachments into the composer so an edited send rebuilds
		// the MIME with them intact (an untouched draft is still sent as-is, see
		// ComposeForm.handleSend).
		const attachments = await loadAttachments(draftId, detail.attachments ?? []);

		// The content endpoint returns sanitized display HTML; the composer is a
		// plain-text editor, so flatten it (same as reply/forward do server-side).
		const rawBody = content.content ?? '';
		const body = isMailHtml(rawBody) ? mailHtmlToPlainText(rawBody) : rawBody;

		return {
			to: detail.recipientsTo ?? '',
			cc: detail.recipientsCc ?? '',
			bcc: detail.recipientsBcc ?? '',
			subject: detail.subject ?? '',
			body,
			inReplyTo: detail.inReplyTo || null,
			references: detail.references || null,
			replacesDraftId: draftId,
			attachments,
			fromNewestSave: false,
			accountId: null
		};
	}

	return null;
}
