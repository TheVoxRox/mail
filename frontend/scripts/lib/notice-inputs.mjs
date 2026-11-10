import { createHash } from 'node:crypto';

/**
 * What the bundled NOTICE.txt was last rendered against, written down so that
 * a gate can tell when it has fallen behind.
 *
 * The notice is not rendered from the three license inventories: it reads the
 * three CycloneDX SBOMs, the built backend jar, `cargo tree` and the installed
 * `node_modules`, none of which is committed. The inventories are the committed
 * account of the same three trees, and a dependency change shows up in them
 * first — regenerating one is the step every dependency PR already takes. So
 * `generate-bundled-notice.mjs` records a digest of each inventory beside the
 * notice it writes, and `check-notice-inputs.mjs` recomputes the digests from
 * the committed inventories. A difference means an inventory moved after the
 * notice was last rendered, which is the drift PRs #872, #873, #875, #876 and
 * #886 left until the v0.2.0-beta.4 cut caught it by hand (#890).
 */

/** The three inventories, repo-relative, in the order the record lists them. */
export const NOTICE_INVENTORIES = [
	'frontend/THIRD_PARTY_LICENSES.md',
	'backend/THIRD_PARTY_LICENSES.md',
	'frontend/src-tauri/THIRD_PARTY_LICENSES.md'
];

/** The record, repo-relative. Beside the notice, outside `resources/`, so it does not ship. */
export const NOTICE_RECORD = 'frontend/src-tauri/notice-inputs.json';

/**
 * SHA-256 of an inventory's component list: everything from its first `## `
 * heading on, which is the licence groups and the components under them.
 *
 * The prose above that heading is left out on purpose. The notice uses none of
 * it, and the backend inventory's scope paragraph carries a count of artifacts
 * the build resolves but does not ship, which moves without the shipped list
 * moving. The `Counts:` line is left out with it: it is derived from the list.
 * Line endings are normalised, since a Windows checkout may hold CRLF where
 * the index holds LF.
 *
 * Throws when there is no heading: an inventory without a component list is a
 * broken regen, and a digest of the empty string would record it as fine.
 */
export function inventoryDigest(text) {
	const normalised = text.replace(/\r\n/g, '\n');
	const firstGroup = normalised.search(/^## /m);
	if (firstGroup === -1) {
		throw new Error('the inventory has no `## ` licence heading, so no component list');
	}
	return createHash('sha256').update(normalised.slice(firstGroup), 'utf8').digest('hex');
}

/** The record's own text: tab-indented with a final newline, as Prettier leaves JSON here. */
export function renderNoticeRecord(digests) {
	const record = {
		$comment:
			'Written by frontend/scripts/generate-bundled-notice.mjs beside NOTICE.txt; ' +
			'npm run check:notice compares it with the committed inventories. Do not edit it by ' +
			'hand: when the check fails, render the notice again (backend/RELEASE_CHECKLIST.md, 8a).',
		inventories: Object.fromEntries(NOTICE_INVENTORIES.map((file) => [file, digests[file]]))
	};
	return `${JSON.stringify(record, null, '\t')}\n`;
}
