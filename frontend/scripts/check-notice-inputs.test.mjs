import path from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createGateRepo } from './test-support/gate-repo.mjs';
import { NOTICE_RECORD, inventoryDigest, renderNoticeRecord } from './lib/notice-inputs.mjs';

/*
 * The bundled NOTICE.txt is rendered from inputs no gate can rebuild — SBOMs,
 * a built jar, `cargo tree`, `node_modules` — so what this gate holds it to is
 * the record the generator writes beside it: a digest per license inventory.
 * The cases below are the incident (an inventory regenerated, the notice not),
 * the edits that must not demand a notice (prose, line endings), the ways the
 * record itself can be wrong, and the hook environment every gate here has to
 * survive.
 *
 * The fixture record is written with the generator's own two functions, which
 * is how the real one is written; what those functions hash is pinned by the
 * cases about prose and licence groups, not by repeating the hash here.
 */

const GATE = 'check-notice-inputs.mjs';

const FRONTEND = 'frontend/THIRD_PARTY_LICENSES.md';
const BACKEND = 'backend/THIRD_PARTY_LICENSES.md';
const TAURI = 'frontend/src-tauri/THIRD_PARTY_LICENSES.md';

function inventory(title, groups) {
	const lines = [`# Third-Party Licenses — ${title}`, '', 'Scope paragraph.', ''];
	const total = groups.reduce((sum, [, entries]) => sum + entries.length, 0);
	lines.push(`Counts: ${total} total.`, '');
	for (const [license, entries] of groups) {
		lines.push(`## ${license} (${entries.length})`, '');
		for (const entry of entries) lines.push(`- ${entry}`);
		lines.push('');
	}
	return lines.join('\n');
}

const INVENTORIES = {
	[FRONTEND]: inventory('Frontend (npm)', [
		['MIT', ['**bits-ui** 2.20.6', '**svelte** 5.0.0']],
		['ISC', ['**semver** 7.7.2']]
	]),
	[BACKEND]: inventory('Backend (Maven)', [['Apache-2.0', ['**jsoup** `org.jsoup:jsoup:1.24.1`']]]),
	[TAURI]: inventory('Tauri Runtime (Cargo crates)', [['MIT OR Apache-2.0', ['**zip** 9.4.1']]])
};

let repo;

/** Writes the three inventories and a record rendered from them, as the generator would. */
function seed(inventories = INVENTORIES) {
	const digests = {};
	for (const [file, text] of Object.entries(inventories)) {
		repo.write(file, text);
		digests[file] = inventoryDigest(text);
	}
	repo.write(NOTICE_RECORD, renderNoticeRecord(digests));
	repo.commit('seed');
}

beforeEach(() => {
	repo = createGateRepo();
});

afterEach(() => {
	repo.cleanup();
});

describe('check-notice-inputs', () => {
	it('passes when the record matches every inventory', () => {
		seed();

		const result = repo.run(GATE);

		expect(result.status).toBe(0);
		expect(result.stdout).toContain('Notice inputs OK');
	});

	/*
	 * The incident: a dependency PR regenerates the inventory its bump needs
	 * and leaves the notice — and so the record — as it was.
	 */
	it('fails when an inventory moved a version and the notice was not rendered again', () => {
		seed();
		repo.write(FRONTEND, INVENTORIES[FRONTEND].replace('bits-ui** 2.20.6', 'bits-ui** 2.20.7'));
		repo.commit('bump bits-ui, regen:licenses only');

		const result = repo.run(GATE);

		expect(result.status).toBe(1);
		expect(result.stderr).toContain(`${FRONTEND}: changed since NOTICE.txt was last rendered`);
		expect(result.stderr).not.toContain(BACKEND);
		expect(result.stderr).toContain('npm run regen:notice');
	});

	it('fails when a component was added to an inventory', () => {
		seed();
		repo.write(
			TAURI,
			INVENTORIES[TAURI].replace('- **zip** 9.4.1', '- **zip** 9.4.1\n- **rand** 0.10.3')
		);
		repo.commit();

		const result = repo.run(GATE);

		expect(result.status).toBe(1);
		expect(result.stderr).toContain(`${TAURI}: changed since`);
	});

	/*
	 * The notice groups components by licence, so a component that changed
	 * licence and nothing else still changes the notice.
	 */
	it('fails when a component moved to another licence group', () => {
		const moved = {
			...INVENTORIES,
			[BACKEND]: inventory('Backend (Maven)', [['MIT', ['**jsoup** `org.jsoup:jsoup:1.24.1`']]])
		};
		seed();
		repo.write(BACKEND, moved[BACKEND]);
		repo.commit();

		const result = repo.run(GATE);

		expect(result.status).toBe(1);
		expect(result.stderr).toContain(`${BACKEND}: changed since`);
	});

	/*
	 * The prose above the first licence heading is not in the notice, and the
	 * backend's carries a count of resolved-but-unshipped artifacts that moves
	 * without the shipped list moving. Demanding a full notice render for that
	 * would be a gate people learn to resent.
	 */
	it('passes when only the prose above the component list changed', () => {
		seed();
		repo.write(
			BACKEND,
			INVENTORIES[BACKEND].replace('Scope paragraph.', 'A reworded scope paragraph.').replace(
				'Counts: 1 total.',
				'Counts: 1 artifact total.'
			)
		);
		repo.commit();

		const result = repo.run(GATE);

		expect(result.status).toBe(0);
	});

	it('passes on a working copy with CRLF line endings', () => {
		seed();
		repo.write(FRONTEND, INVENTORIES[FRONTEND].replace(/\n/g, '\r\n'));

		const result = repo.run(GATE);

		expect(result.status).toBe(0);
	});

	it('fails when there is no record', () => {
		seed();
		repo.remove(NOTICE_RECORD);
		repo.commit();

		const result = repo.run(GATE);

		expect(result.status).toBe(1);
		expect(result.stderr).toContain(`not tracked by git: ${NOTICE_RECORD}`);
	});

	/*
	 * The generator writes the record as a new file the first time, and a file
	 * nobody added passes on the machine that wrote it and fails in CI.
	 */
	it('fails when the record exists but is not tracked', () => {
		seed();
		repo.git(['rm', '--cached', '--quiet', NOTICE_RECORD]);
		repo.git(['commit', '--quiet', '--no-verify', '-m', 'untrack']);

		const result = repo.run(GATE);

		expect(result.status).toBe(1);
		expect(result.stderr).toContain(`not tracked by git: ${NOTICE_RECORD}`);
		expect(result.stderr).toContain('git add');
	});

	it('fails when the record is not valid JSON', () => {
		seed();
		repo.write(NOTICE_RECORD, '{ "inventories": ');
		repo.commit();

		const result = repo.run(GATE);

		expect(result.status).toBe(1);
		expect(result.stderr).toContain(`cannot read ${NOTICE_RECORD}`);
	});

	it('fails when the record lacks an inventory or names one the notice does not cover', () => {
		seed();
		const record = JSON.parse(renderNoticeRecord({}));
		record.inventories = {
			[FRONTEND]: inventoryDigest(INVENTORIES[FRONTEND]),
			[BACKEND]: inventoryDigest(INVENTORIES[BACKEND]),
			'docs/THIRD_PARTY_LICENSES.md': inventoryDigest(INVENTORIES[TAURI])
		};
		repo.write(NOTICE_RECORD, JSON.stringify(record));
		repo.commit();

		const result = repo.run(GATE);

		expect(result.status).toBe(1);
		expect(result.stderr).toContain(`${TAURI}: missing from the record`);
		expect(result.stderr).toContain('docs/THIRD_PARTY_LICENSES.md: in the record, but not one');
	});

	/*
	 * A regen that wrote a heading-less file would otherwise be recorded as the
	 * digest of nothing and pass from then on.
	 */
	it('fails when an inventory has no component list', () => {
		seed();
		repo.write(FRONTEND, '# Third-Party Licenses — Frontend (npm)\n\nCounts: 0 packages total.\n');
		repo.commit();

		const result = repo.run(GATE);

		expect(result.status).toBe(1);
		expect(result.stderr).toContain(`${FRONTEND}: the inventory has no`);
	});

	/*
	 * A hook in a linked worktree exports GIT_DIR, and git then takes the
	 * current directory, frontend/, for the top of the tree. A root asked of
	 * git (`rev-parse --show-toplevel`) would be frontend/, and every file would
	 * be looked for under frontend/frontend/ — this case fails on exactly that.
	 */
	it('reads the files from a hook that exports GIT_DIR, as a linked worktree does', () => {
		seed();

		const result = repo.run(GATE, [], { env: { GIT_DIR: path.join(repo.root, '.git') } });

		expect(result.status).toBe(0);
	});
});
