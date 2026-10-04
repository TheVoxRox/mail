import { execFileSync, spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { expect, it } from 'vitest';

/*
 * The pre-push hook of a linked worktree exports GIT_DIR, and test:unit runs
 * the gate suites inside it. A fixture that inherited the variable ran its
 * `git init`, `git config` and `git commit` against the repository being
 * pushed: on 2026-09-28 that set core.bare=true and a test identity in the
 * real repository's config. Here the inherited GIT_DIR points at a throwaway
 * repository instead, and the harness has to leave it alone.
 *
 * The test's own git calls need the same care, and did not have it: run from
 * that hook, its `git init` re-initialised the real repository, set
 * core.bare=true in the config every worktree shares, and broke the main
 * checkout (2026-10-04). So they run without the variables that point git at a
 * repository, and the child gets only the GIT_DIR this test means it to have.
 */
const gitEnv = { ...process.env };
for (const name of execFileSync('git', ['rev-parse', '--local-env-vars'], { encoding: 'utf8' })
	.split('\n')
	.filter(Boolean)) {
	delete gitEnv[name];
}

it('a fixture ignores a GIT_DIR the process inherited', () => {
	const outside = mkdtempSync(path.join(os.tmpdir(), 'voxrox-outside-'));
	try {
		execFileSync('git', ['init', '--quiet'], { cwd: outside, env: gitEnv });
		const harness = pathToFileURL(
			path.join(path.dirname(fileURLToPath(import.meta.url)), 'gate-repo.mjs')
		).href;
		const script = [
			`const { createGateRepo } = await import(${JSON.stringify(harness)});`,
			'const repo = createGateRepo();',
			"repo.write('a.txt', 'a\\n');",
			'repo.commit();',
			"console.log(repo.git(['rev-list', '--count', 'HEAD']));",
			'repo.cleanup();'
		].join('\n');

		const child = spawnSync(process.execPath, ['--input-type=module', '-e', script], {
			env: { ...gitEnv, GIT_DIR: path.join(outside, '.git') },
			encoding: 'utf8'
		});

		expect(child.status, child.stderr).toBe(0);
		// The fixture committed into itself...
		expect(child.stdout.trim()).toBe('1');
		// ...and wrote nothing into the repository the variable named.
		const config = execFileSync('git', ['config', '--local', '--list'], {
			cwd: outside,
			env: gitEnv,
			encoding: 'utf8'
		});
		expect(config).toContain('core.bare=false');
		expect(config).not.toContain('gate-test@example.com');
	} finally {
		rmSync(outside, { recursive: true, force: true });
	}
});
