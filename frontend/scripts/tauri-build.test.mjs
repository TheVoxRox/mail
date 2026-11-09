import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { HASH_FILE_NAME, hashBackendSources } from './lib/sidecar-staleness.mjs';
import { createGateRepo } from './test-support/gate-repo.mjs';

/*
 * `npm run tauri:build` checks the sidecar before it builds an installer
 * around it, with the policy `npm run tauri:dev` uses. These run the real
 * script as a process inside a throwaway repo, the way the gate suites do, so
 * the exit code and the output are the ones a person sees.
 *
 * The real `tauri build` never runs. The fixture has no link to the real
 * node_modules, and the Tauri CLI the script starts — found by the same path
 * as in a real checkout, node_modules/@tauri-apps/cli/tauri.js — is a
 * stand-in that writes down the arguments it was given and exits 0. That file
 * is the seam: the script carries no switch for tests.
 *
 * Packaging itself (Maven, jpackage) is out of reach of a test; what it
 * leaves in backend/target/sidecar is laid out by hand, as in the "digest
 * travels with the image" suite of sidecar-staleness.test.mjs, and the real
 * sync script copies it into binaries/.
 */

const SERVICE = 'backend/src/main/java/org/voxrox/Service.java';
const IMAGE = 'backend/target/sidecar/x86_64-pc-windows-msvc';
const LAUNCHER = 'voxrox-mail-backend-x86_64-pc-windows-msvc.exe';
const CLI_DIR = 'frontend/node_modules/@tauri-apps/cli';
const RECORDED_ARGS = `${CLI_DIR}/invoked.json`;

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');

let repo;

beforeEach(() => {
	repo = createGateRepo({ linkDependencies: false });
	// Written through a link to the real node_modules, the stand-in would
	// replace the real Tauri CLI. Refuse rather than find out.
	if (existsSync(path.join(repo.frontend, 'node_modules'))) {
		throw new Error('The fixture links the real node_modules; the stand-in would overwrite it.');
	}
	repo.write(`${CLI_DIR}/package.json`, JSON.stringify({ type: 'commonjs' }));
	repo.write(
		`${CLI_DIR}/tauri.js`,
		[
			"const { writeFileSync } = require('node:fs');",
			"const path = require('node:path');",
			"writeFileSync(path.join(__dirname, 'invoked.json'), JSON.stringify(process.argv.slice(2)));"
		].join('\n')
	);

	repo.write(SERVICE, 'class Service {}');
	repo.write('backend/pom.xml', '<project/>');
	repo.write(
		'frontend/src-tauri/tauri.conf.json',
		JSON.stringify({
			productName: 'VoxRox Mail',
			version: '0.0.0',
			bundle: { externalBin: ['binaries/voxrox-mail-backend'] }
		})
	);
	repo.write('frontend/src-tauri/binaries/.gitkeep', '');
});

afterEach(() => {
	repo.cleanup();
});

/**
 * What backend/scripts/package-sidecar-windows.ps1 leaves in target/sidecar:
 * the launcher, app/ and runtime/, and the digest of the sources as they stood
 * before the build, beside the jar — unless the image predates that.
 */
async function packageImage({ recordDigest = true } = {}) {
	repo.write(`${IMAGE}/${LAUNCHER}`, 'launcher');
	repo.write(`${IMAGE}/app/mail-backend-0.1.0.jar`, 'jar');
	repo.write(`${IMAGE}/app/voxrox-mail-backend-x86_64-pc-windows-msvc.cfg`, '[Application]');
	repo.write(`${IMAGE}/runtime/release`, 'JAVA_VERSION="25"');
	if (!recordDigest) return;
	const { hash } = await hashBackendSources(repo.root);
	repo.write(`${IMAGE}/app/${HASH_FILE_NAME}`, `${hash}\n`);
}

/** `npm run sidecar:sync:windows`, the first half of `tauri:build:with-sidecar`. */
function sync(args = []) {
	const result = repo.run('sync-backend-sidecar-windows.mjs', args);
	expect(result.status, result.output).toBe(0);
}

/** An image packaged from the sources on disk, synced into binaries/. */
async function freshSidecar() {
	await packageImage();
	sync();
}

/**
 * `npm run tauri:build -- <args>`. The opt-out is always set one way or the
 * other, so a MAIL_ALLOW_STALE_SIDECAR left in the shell running the tests
 * cannot decide a result.
 */
function build(args = [], { allowStale = false } = {}) {
	return repo.run('tauri-build.mjs', args, {
		env: { MAIL_ALLOW_STALE_SIDECAR: allowStale ? '1' : '' }
	});
}

/** The arguments the Tauri CLI was started with, or null when it was not started. */
function tauriInvokedWith() {
	const file = path.join(repo.root, RECORDED_ARGS);
	return existsSync(file) ? JSON.parse(readFileSync(file, 'utf8')) : null;
}

describe('npm run tauri:build checks the sidecar first', () => {
	it('stops on a sidecar packaged before a backend change, before tauri build starts', async () => {
		await freshSidecar();
		repo.write(SERVICE, 'class Service { void added() {} }');

		const result = build();

		expect(result.status).toBe(1);
		expect(result.stderr).toContain(
			'[tauri-build] The packaged sidecar was not built from the backend sources that are checked out.'
		);
		// The way out travels with the verdict.
		expect(result.stderr).toContain('npm run sidecar:rebuild:windows');
		expect(tauriInvokedWith()).toBeNull();
	});

	it('builds over it with MAIL_ALLOW_STALE_SIDECAR=1, and says so before and after', async () => {
		await freshSidecar();
		repo.write(SERVICE, 'class Service { void added() {} }');

		const result = build([], { allowStale: true });

		expect(result.status, result.output).toBe(0);
		expect(tauriInvokedWith()).toEqual(['build']);
		const report = result.stderr.indexOf('was not built from the backend sources');
		const optOut = result.stderr.indexOf(
			'WARNING: MAIL_ALLOW_STALE_SIDECAR=1 is set — going ahead with the mismatched pair anyway.'
		);
		const reminder = result.stderr.indexOf('Do not hand the installer on');
		expect(report).toBeGreaterThan(-1);
		expect(optOut).toBeGreaterThan(report);
		// After the build, where its output has scrolled the first warning away.
		expect(reminder).toBeGreaterThan(optOut);
	});

	/*
	 * The opt-out waves through a digest mismatch only. Neither of these is a
	 * tradeoff: with no launcher where Tauri looks, `tauri build` fails anyway,
	 * after minutes of work and with a message about a resource path.
	 */
	it.each([
		['no sidecar at all', async () => {}, 'No packaged sidecar found'],
		[
			'a launcher renamed in tauri.conf.json after the sidecar was synced',
			async () => {
				await freshSidecar();
				repo.write(
					'frontend/src-tauri/tauri.conf.json',
					JSON.stringify({
						version: '0.0.0',
						bundle: { externalBin: ['binaries/voxrox-mail-server'] }
					})
				);
			},
			'expects the sidecar launcher voxrox-mail-server-<target triple>'
		]
	])('stops on %s even with MAIL_ALLOW_STALE_SIDECAR=1', async (_name, arrange, message) => {
		await arrange();

		const result = build([], { allowStale: true });

		expect(result.status).toBe(1);
		expect(result.stderr).toContain(message);
		expect(result.stderr).toContain('npm run sidecar:rebuild:windows');
		expect(result.output).not.toContain('going ahead with the mismatched pair');
		expect(tauriInvokedWith()).toBeNull();
	});

	it('warns about a sidecar packaged before packaging recorded a digest, and builds', async () => {
		await packageImage({ recordDigest: false });
		sync();

		const result = build();

		expect(result.status, result.output).toBe(0);
		expect(result.stderr).toContain(`no ${HASH_FILE_NAME} beside the jar`);
		expect(tauriInvokedWith()).toEqual(['build']);
	});

	it('builds over a sidecar packaged from the checked-out backend, arguments untouched', async () => {
		await freshSidecar();

		const result = build(['--config', 'src-tauri/tauri.release.conf.json', '--no-bundle']);

		expect(result.status, result.output).toBe(0);
		expect(result.stdout).toContain('[tauri-build] The sidecar was packaged from the checked-out');
		expect(result.stderr).not.toContain('[tauri-build]');
		expect(tauriInvokedWith()).toEqual([
			'build',
			'--config',
			'src-tauri/tauri.release.conf.json',
			'--no-bundle'
		]);
	});

	/*
	 * One policy behind both entries: on every result that stops a run, a dev
	 * run and a build stop with the same report, word for word but the label.
	 * Two copies of the policy would agree here only until one of them moved.
	 */
	it.each([
		[
			'stale',
			async () => {
				await freshSidecar();
				repo.write(SERVICE, 'class Service { void added() {} }');
			}
		],
		['missing', async () => {}],
		[
			'misnamed',
			async () => {
				await freshSidecar();
				repo.remove(`frontend/src-tauri/binaries/${LAUNCHER}`);
			}
		]
	])('stops on a %s sidecar with the report tauri:dev gives', async (_status, arrange) => {
		await arrange();

		const dev = repo.run('tauri-dev-with-env.mjs', [], { env: { MAIL_ALLOW_STALE_SIDECAR: '' } });
		const built = build();

		expect(dev.status).toBe(1);
		expect(built.status).toBe(1);
		expect(dev.stderr).toContain('npm run sidecar:rebuild:windows');
		expect(built.output.replaceAll('[tauri-build]', '[label]')).toBe(
			dev.output.replaceAll('[tauri-dev]', '[label]')
		);
	});
});

describe('npm run tauri:build after a packaging and a sync', () => {
	it('passes the release workflow: package, sync with its paths, sign, build with its config', async () => {
		const workflow = readFileSync(
			path.join(repoRoot, '.github', 'workflows', 'windows-signed-release.yml'),
			'utf8'
		);
		const packaged = workflow.search(/- name: Package backend sidecar\b/);
		const synced = workflow.search(/- name: Sync backend sidecar\b/);
		const built = workflow.search(/npm run tauri:build\b/);
		// The order this test reproduces, read from the workflow rather than
		// assumed: packaging, then the sync from the same checkout, then the
		// build. And the build gets no opt-out there; it does not need one.
		expect(packaged).toBeGreaterThan(-1);
		expect(synced).toBeGreaterThan(packaged);
		expect(built).toBeGreaterThan(synced);
		expect(workflow).not.toContain('MAIL_ALLOW_STALE_SIDECAR');
		const syncArgs = workflow
			.match(/npm run sidecar:sync:windows -- (.+)/)[1]
			.trim()
			.split(/\s+/)
			// Written for Windows; split so that the paths resolve here too.
			.map((arg) => path.join(...arg.split(/[\\/]/)));
		const buildArgs = workflow
			.match(/npm run tauri:build -- (.+)/)[1]
			.trim()
			.split(/\s+/);

		await packageImage();
		sync(syncArgs);
		// The workflow signs the launcher in binaries/ between the sync and the
		// build. The check reads its name, never its bytes.
		repo.write(`frontend/src-tauri/binaries/${LAUNCHER}`, 'launcher, signed');
		const result = build(buildArgs);

		expect(result.status, result.output).toBe(0);
		expect(result.stderr).not.toContain('[tauri-build]');
		expect(tauriInvokedWith()).toEqual(['build', ...buildArgs]);
	});

	it('passes tauri:build:with-sidecar after its sync of a freshly packaged image', async () => {
		await packageImage();

		sync();
		const result = build();

		expect(result.status, result.output).toBe(0);
		expect(tauriInvokedWith()).toEqual(['build']);
	});

	it('stops tauri:build:with-sidecar when the image it syncs predates a backend change', async () => {
		// The sync copies whatever backend/target/sidecar holds, and only
		// packaging rebuilds that: this pair used to go into an installer.
		await packageImage();
		repo.write(SERVICE, 'class Service { void added() {} }');

		sync();
		const result = build();

		expect(result.status).toBe(1);
		expect(result.stderr).toContain('was not built from the backend sources');
		expect(tauriInvokedWith()).toBeNull();
	});
});
