import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { MAX_ATTACHMENTS } from './request.js';

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
