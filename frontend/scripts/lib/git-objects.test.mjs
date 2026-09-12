import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createGateRepo } from '../test-support/gate-repo.mjs';
import { resolveObjects } from './git-objects.mjs';

/*
 * resolveObjects moved here from check-audit-freshness.mjs so release-status
 * could share it. The gate's own suite still covers it through the gate; this
 * pins the contract both callers now rely on directly: one answer per input,
 * matched back by position, and `missing` returned as data rather than thrown.
 */
describe('resolveObjects', () => {
	let repo;

	beforeEach(() => {
		repo = createGateRepo();
	});

	afterEach(() => {
		repo.cleanup();
	});

	it('resolves a commit, a tree and a blob to the ids git itself reports', () => {
		repo.write('backend/src/App.java', 'class App {}\n');
		repo.commit();
		const head = repo.git(['rev-parse', 'HEAD']);
		const tree = `${head}:backend/src`;
		const blob = `${head}:backend/src/App.java`;

		const resolved = resolveObjects(repo.root, [head, tree, blob]);

		expect(resolved.get(head)).toEqual({ oid: head, type: 'commit' });
		expect(resolved.get(tree)).toEqual({ oid: repo.git(['rev-parse', tree]), type: 'tree' });
		expect(resolved.get(blob)).toEqual({ oid: repo.git(['rev-parse', blob]), type: 'blob' });
	});

	it('keeps each answer in position around missing objects and a path with a space', () => {
		repo.write('docs/with space.md', 'spaced\n');
		repo.write('docs/plain.md', 'plain\n');
		repo.commit();
		const head = repo.git(['rev-parse', 'HEAD']);
		const spaced = `${head}:docs/with space.md`;
		const neverFetched = '0'.repeat(40);
		const plain = `${head}:docs/plain.md`;
		const deleted = `${head}:docs/gone.md`;

		const resolved = resolveObjects(repo.root, [spaced, neverFetched, plain, deleted]);

		expect(resolved.get(spaced)?.oid).toBe(repo.git(['rev-parse', spaced]));
		expect(resolved.get(neverFetched)).toBeNull();
		expect(resolved.get(plain)?.oid).toBe(repo.git(['rev-parse', plain]));
		expect(resolved.get(deleted)).toBeNull();
	});

	it('answers a repeated revspec once, and an empty list without starting git', () => {
		repo.write('README.md', 'fixture\n');
		repo.commit();
		const head = repo.git(['rev-parse', 'HEAD']);

		expect([...resolveObjects(repo.root, [head, head]).keys()]).toEqual([head]);
		// A cwd that does not exist would make any spawn throw, so this proves none happened.
		expect(resolveObjects(`${repo.root}-does-not-exist`, [])).toEqual(new Map());
	});
});
