import { execFileSync, spawnSync } from 'node:child_process';
import {
	cpSync,
	existsSync,
	mkdirSync,
	mkdtempSync,
	rmSync,
	symlinkSync,
	writeFileSync
} from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * A throwaway git repository to run a gate script against.
 *
 * The gates are tested as processes, not as functions. That is deliberate:
 * every one of them is a CLI whose contract is an exit code, and most of them
 * ask git questions that only a real repository can answer. Importing an
 * exported function would test a shape none of them currently has, and would
 * mean refactoring thirteen scripts that guard everything else in the repo
 * before a single test existed to catch the refactor going wrong.
 *
 * The fixture mirrors the one assumption every script makes: it is started
 * from `frontend/`, and the repo root is one level up.
 *
 * The script under test is *copied into* the fixture before it runs. The gates
 * are split between two ways of finding the repo — some from `process.cwd()`,
 * some from their own location — and only a copy puts both inside the fixture.
 * Running the original from a fixture cwd would leave the second group reading
 * the real repository and reporting on it, which is a test that passes for the
 * wrong reason.
 */
const scriptsDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const realNodeModules = path.resolve(scriptsDir, '..', 'node_modules');

export function createGateRepo() {
	const root = mkdtempSync(path.join(os.tmpdir(), 'voxrox-gate-'));
	const frontend = path.join(root, 'frontend');
	mkdirSync(frontend, { recursive: true });

	const git = (args) => execFileSync('git', args, { cwd: root, encoding: 'utf8' }).trim();

	git(['init', '--quiet', '--initial-branch=main']);
	git(['config', 'user.email', 'gate-test@example.com']);
	git(['config', 'user.name', 'Gate Test']);
	// Signing would prompt (or fail) on a maintainer machine that has it on.
	git(['config', 'commit.gpgsign', 'false']);
	// Fixtures are written with LF; without this git narrates a CRLF warning
	// per file per commit and buries the actual test output.
	git(['config', 'core.autocrlf', 'false']);

	/*
	 * The copied script and the linked dependencies must never become fixture
	 * content: several gates enumerate tracked files, and a test whose subject
	 * shows up in its own input tells you nothing.
	 *
	 * No trailing slash. That form matches directories only, and the
	 * node_modules link is a directory junction on Windows but a symlink on
	 * Linux — where git sees a trackable file, adds it, and the gate under test
	 * then tries to read a directory. The pattern has to catch both.
	 */
	writeFileSync(
		path.join(root, '.git', 'info', 'exclude'),
		'frontend/scripts\nfrontend/node_modules\n',
		'utf8'
	);

	// One script imports prettier; a link costs nothing for the rest.
	if (existsSync(realNodeModules)) {
		try {
			symlinkSync(realNodeModules, path.join(frontend, 'node_modules'), 'junction');
		} catch {
			// A platform or policy that refuses links only affects the one
			// script that needs a package; the others do not notice.
		}
	}

	const installed = new Set();
	function install(scriptName) {
		if (installed.has(scriptName)) return;
		const target = path.join(frontend, 'scripts');
		mkdirSync(target, { recursive: true });
		cpSync(path.join(scriptsDir, scriptName), path.join(target, scriptName));
		// Shared helpers a gate may import as a sibling.
		if (existsSync(path.join(scriptsDir, 'lib'))) {
			cpSync(path.join(scriptsDir, 'lib'), path.join(target, 'lib'), { recursive: true });
		}
		installed.add(scriptName);
	}

	return {
		root,
		frontend,

		/** Writes a repo-relative file, creating parent directories. */
		write(relPath, content) {
			const abs = path.join(root, relPath);
			mkdirSync(path.dirname(abs), { recursive: true });
			writeFileSync(abs, content, 'utf8');
			return abs;
		},

		/** Writes raw bytes — for the cases where the encoding is the point. */
		writeBytes(relPath, bytes) {
			const abs = path.join(root, relPath);
			mkdirSync(path.dirname(abs), { recursive: true });
			writeFileSync(abs, bytes);
			return abs;
		},

		remove(relPath) {
			rmSync(path.join(root, relPath), { recursive: true, force: true });
		},

		git,

		/** Stages everything and commits; returns the short SHA. */
		commit(message = 'fixture') {
			git(['add', '-A']);
			git(['commit', '--quiet', '--no-verify', '-m', message]);
			return git(['rev-parse', '--short', 'HEAD']);
		},

		/**
		 * Runs a gate script the way `npm run` does — from `frontend/`, as its
		 * own process, so the exit code is the real one.
		 */
		run(scriptName, args = []) {
			install(scriptName);
			const result = spawnSync(
				process.execPath,
				[path.join(frontend, 'scripts', scriptName), ...args],
				{ cwd: frontend, encoding: 'utf8' }
			);
			return {
				status: result.status,
				stdout: result.stdout ?? '',
				stderr: result.stderr ?? '',
				/** Both streams, for assertions that do not care which one carried the message. */
				output: `${result.stdout ?? ''}${result.stderr ?? ''}`
			};
		},

		cleanup() {
			rmSync(root, { recursive: true, force: true });
		}
	};
}
