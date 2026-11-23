import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createGateRepo } from './test-support/gate-repo.mjs';

const SCRIPT = 'check-migrations.mjs';
const SQL_V1 = 'backend/src/main/resources/db/migration/V1__init.sql';
const JAVA_V2 = 'backend/src/main/java/db/migration/V2__Fold_keys.java';
const JAVA_V3 = 'backend/src/main/java/db/migration/V3__Next.java';

describe('check-migrations', () => {
	let repo;

	beforeEach(() => {
		repo = createGateRepo();
		repo.write(SQL_V1, 'CREATE TABLE a (id INTEGER PRIMARY KEY);\n');
		repo.write(JAVA_V2, 'public class V2__Fold_keys {}\n');
		repo.write('backend/src/main/java/Other.java', 'class Other {}\n');
		repo.commit('first release');
		repo.git(['tag', 'v0.1.0']);
	});

	afterEach(() => repo.cleanup());

	it('passes when every released migration is as its tag has it', () => {
		const result = repo.run(SCRIPT);

		expect(result.status).toBe(0);
		expect(result.stdout).toContain('2 released migration file(s) unchanged across 1 v* tag(s)');
	});

	it('leaves a migration no tag carries free to change, and other code too', () => {
		repo.write(JAVA_V3, 'public class V3__Next {}\n');
		repo.commit('V3 written');
		repo.write(JAVA_V3, 'public class V3__Next { int fixed; }\n');
		repo.write('backend/src/main/java/Other.java', 'class Other { int changed; }\n');
		repo.commit('V3 corrected before release');

		expect(repo.run(SCRIPT).status).toBe(0);
	});

	it('fails on an edit of a released SQL migration and says to add the next one', () => {
		repo.write(SQL_V1, 'CREATE TABLE a (id INTEGER PRIMARY KEY, added TEXT);\n');
		repo.commit('edit V1');

		const result = repo.run(SCRIPT);

		expect(result.status).toBe(1);
		expect(result.stderr).toContain(SQL_V1);
		expect(result.stderr).toContain('v0.1.0');
		expect(result.stderr).toContain('V<n+1>');
		expect(result.stderr).not.toContain(JAVA_V2);
	});

	it('fails on an edit of a released Java migration, whose comments count too', () => {
		repo.write(JAVA_V2, '/** Folds the keys. */\npublic class V2__Fold_keys {}\n');
		repo.commit('document V2');

		const result = repo.run(SCRIPT);

		expect(result.status).toBe(1);
		expect(result.stderr).toContain(JAVA_V2);
	});

	it('fails on a deletion and on a rename', () => {
		repo.remove(SQL_V1);
		repo.remove(JAVA_V2);
		repo.write(
			'backend/src/main/java/db/migration/V2__Renamed.java',
			'public class V2__Fold_keys {}\n'
		);
		repo.commit('delete V1, rename V2');

		const result = repo.run(SCRIPT);

		expect(result.status).toBe(1);
		expect(result.stderr).toContain(SQL_V1);
		expect(result.stderr).toContain(JAVA_V2);
		expect(result.stderr.match(/HEAD does not have it/g)).toHaveLength(2);
	});

	it('checks against every v* tag, not only the newest', () => {
		repo.write(SQL_V1, 'CREATE TABLE a (id INTEGER PRIMARY KEY, added TEXT);\n');
		repo.commit('edit V1 after v0.1.0');
		// An annotated tag, as a release tag may be: it has to be read through to
		// its commit.
		repo.git(['tag', '-a', '-m', 'second release', 'v0.2.0']);

		const result = repo.run(SCRIPT);

		expect(result.status).toBe(1);
		expect(result.stderr).toContain('v0.1.0');
		expect(result.stderr).not.toContain('v0.2.0');
	});

	it('ignores a tag that is not v*, such as the beta channel tag', () => {
		repo.write(SQL_V1, 'CREATE TABLE a (id INTEGER PRIMARY KEY, added TEXT);\n');
		repo.commit('a state only the channel tag points at');
		repo.git(['tag', 'beta']);
		repo.git(['revert', '--no-edit', 'HEAD']);

		expect(repo.run(SCRIPT).status).toBe(0);
	});

	it('ignores a v* tag that is not merged into HEAD', () => {
		repo.git(['checkout', '--quiet', '-b', 'voided-candidate']);
		repo.write(SQL_V1, 'CREATE TABLE a (id INTEGER PRIMARY KEY, added TEXT);\n');
		repo.commit('a candidate that never merged');
		repo.git(['tag', 'v0.2.0']);
		repo.git(['checkout', '--quiet', 'main']);

		const result = repo.run(SCRIPT);

		expect(result.status).toBe(0);
		expect(result.stdout).toContain('across 1 v* tag(s)');
	});

	it('names the fetch that brings a tag a re-cut moved', () => {
		repo.write(JAVA_V2, 'public class V2__Fold_keys { int edited; }\n');
		repo.commit('edit V2');

		expect(repo.run(SCRIPT).stderr).toContain('git fetch --tags --force');
	});

	it('fails closed when no v* tag is reachable, as in a shallow clone', () => {
		repo.git(['tag', '--delete', 'v0.1.0']);

		const result = repo.run(SCRIPT);

		expect(result.status).toBe(1);
		expect(result.stderr).toContain('No v* tag is reachable from HEAD');
		expect(result.stderr).toContain('fetch-depth: 0');
	});
});
