import { prepareForward, prepareReply } from '$lib/api/mailWrite.js';
import type { MailRequest } from '$lib/types.js';
import type { ComposeAttachment } from '$lib/compose/request.js';
import { isMailHtml, mailHtmlToPlainText } from '$lib/mail/content-sanitizer.js';

export interface ComposePrefill extends Omit<MailRequest, 'attachments'> {
	replacesDraftId: string | null;
	attachments: ComposeAttachment[];
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

export async function loadComposePrefill(params: URLSearchParams): Promise<ComposePrefill | null> {
	const replyId = params.get('reply');
	const forwardId = params.get('forward');
	const draftId = params.get('draft');
	const to = params.get('to');
	const all = params.get('all') === '1';

	if (replyId) {
		return fromMailRequest(await prepareReply(replyId, all));
	}

	if (forwardId) {
		return fromMailRequest(await prepareForward(forwardId));
	}

	if (to) {
		return fromMailRequest({ to, subject: '', body: '' });
	}

	if (draftId) {
		const { getMessageDetail, getMessageContent, downloadAttachment } =
			await import('$lib/api/mailRead.js');
		const [detail, content] = await Promise.all([
			getMessageDetail(draftId),
			getMessageContent(draftId)
		]);

		// Pull the draft's attachments into the composer so an edited send rebuilds
		// the MIME with them intact (an untouched draft is still sent as-is, see
		// ComposeForm.handleSend).
		const attachments: ComposeAttachment[] = await Promise.all(
			(detail.attachments ?? []).map(async (att) => {
				const blob = await downloadAttachment(draftId, att.partPath, att.fileName);
				return {
					localId: crypto.randomUUID(),
					fileName: att.fileName,
					contentType: att.contentType,
					base64Data: await blobToBase64(blob),
					size: blob.size
				};
			})
		);

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
			attachments
		};
	}

	return null;
}
