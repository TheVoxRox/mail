import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import {
	draftFingerprint,
	MAX_ATTACHMENTS,
	type ComposeAttachment,
	type ComposeDraft
} from './request.js';

function makeDraft(attachments: ComposeAttachment[]): ComposeDraft {
	return {
		to: 'a@example.com',
		cc: '',
		bcc: '',
		subject: 'Podklady',
		body: 'Text zpravy',
		attachments,
		inReplyTo: null,
		references: null
	};
}

function attachment(localId: string, base64Data: string): ComposeAttachment {
	return { localId, fileName: 'podklady.pdf', contentType: 'application/pdf', base64Data, size: 3 };
}

/*
 * The compose session takes this on every keystroke, twice, so it must not
 * read an attachment's data: an attachment's id stands for it, minted with
 * the data and never reused for other data.
 */
describe('draftFingerprint', () => {
	it('does not grow with the attachment data', () => {
		const large = attachment('att-1', 'A'.repeat(4 * 1024 * 1024));

		expect(draftFingerprint(makeDraft([large])).length).toBeLessThan(1024);
	});

	it('tells attachments apart by their id, not their data', () => {
		const fingerprint = draftFingerprint(makeDraft([attachment('att-1', 'QUJD')]));

		expect(draftFingerprint(makeDraft([attachment('att-1', 'WFla')]))).toBe(fingerprint);
		expect(draftFingerprint(makeDraft([attachment('att-2', 'QUJD')]))).not.toBe(fingerprint);
	});

	it('changes when an attachment is added or removed', () => {
		const none = draftFingerprint(makeDraft([]));
		const one = draftFingerprint(makeDraft([attachment('att-1', 'QUJD')]));
		const two = draftFingerprint(
			makeDraft([attachment('att-1', 'QUJD'), attachment('att-2', 'QUJD')])
		);

		expect(new Set([none, one, two]).size).toBe(3);
	});
});

/*
 * The picker refuses an attachment past the cap when it is added; past the
 * backend's own cap it would be attached and then fail every autosave and the
 * send. The snapshot is what OpenApiSnapshotTest keeps equal to the DTOs, so
 * a change to either @Size reaches this test through it.
 */
describe('MAX_ATTACHMENTS', () => {
	const apiDocs = JSON.parse(
		readFileSync(
			new URL('../../../../backend/src/test/resources/openapi/api-docs.json', import.meta.url),
			'utf8'
		)
	) as {
		components: { schemas: Record<string, { properties: { attachments: { maxItems?: number } } }> };
	};

	it.each(['MailRequest', 'DraftRequest'])('matches maxItems of %s.attachments', (schema) => {
		expect(apiDocs.components.schemas[schema]?.properties.attachments.maxItems).toBe(
			MAX_ATTACHMENTS
		);
	});
});
