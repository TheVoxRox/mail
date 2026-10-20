import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createGateRepo } from '../test-support/gate-repo.mjs';
import { readBlobs, resolveObjects } from './git-objects.mjs';

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

describe('readBlobs', () => {
	let repo;

	beforeEach(() => {
		repo = createGateRepo();
	});

	afterEach(() => {
		repo.cleanup();
	});

	it('cuts each answer by bytes, so a multi-byte character does not shift the next one', () => {
		// "§" and "—" are two and three bytes; a size applied to the decoded
		// string would end the first answer inside the second.
		repo.write('backend/RELEASE_CHECKLIST.md', '- [x] **§3a** — carried\n');
		repo.write('docs/plain.md', 'plain\n');
		repo.commit();
		const head = repo.git(['rev-parse', 'HEAD']);
		const checklist = `${head}:backend/RELEASE_CHECKLIST.md`;
		const plain = `${head}:docs/plain.md`;

		const contents = readBlobs(repo.root, [checklist, plain]);

		expect(contents.get(checklist)).toBe('- [x] **§3a** — carried\n');
		expect(contents.get(plain)).toBe('plain\n');
	});

	it('answers a missing object and a tree with null, keeping the rest in position', () => {
		repo.write('backend/src/App.java', 'class App {}\n');
		repo.commit();
		const head = repo.git(['rev-parse', 'HEAD']);
		const gone = `${head}:docs/gone.md`;
		const tree = `${head}:backend/src`;
		const blob = `${head}:backend/src/App.java`;

		const contents = readBlobs(repo.root, [gone, tree, blob]);

		expect(contents.get(gone)).toBeNull();
		expect(contents.get(tree)).toBeNull();
		expect(contents.get(blob)).toBe('class App {}\n');
	});
});
