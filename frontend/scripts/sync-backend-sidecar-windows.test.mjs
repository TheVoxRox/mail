import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { copyFileSync, existsSync, mkdirSync, readFileSync } from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { waitForExit } from './lib/process-tree.mjs';
import {
	checkSidecarFreshness,
	HASH_FILE_NAME,
	hashBackendSources
} from './lib/sidecar-staleness.mjs';
import { createGateRepo } from './test-support/gate-repo.mjs';

/*
 * `npm run sidecar:sync:windows`, run as a process inside a throwaway repo
 * like the gate suites, since the script finds the repo from its own location.
 * What packaging leaves in backend/target/sidecar is laid out by hand, as in
 * sidecar-staleness.test.mjs; the freshness check `tauri:dev` runs is what
 * judges the result, because that is who reads binaries/ next.
 */

const IMAGE = 'backend/target/sidecar/x86_64-pc-windows-msvc';
const BINARIES = 'frontend/src-tauri/binaries';
const SERVICE = 'backend/src/main/java/org/voxrox/Service.java';
const LAUNCHER = 'voxrox-mail-backend-x86_64-pc-windows-msvc.exe';
const LAUNCHER_CFG = 'voxrox-mail-backend-x86_64-pc-windows-msvc.cfg';

let repo;

beforeEach(() => {
	repo = createGateRepo();
	repo.write(SERVICE, 'class Service {}');
	repo.write('backend/pom.xml', '<project/>');
	repo.write(
		'frontend/src-tauri/tauri.conf.json',
		JSON.stringify({ bundle: { externalBin: ['binaries/voxrox-mail-backend'] } })
	);
	repo.write(`${BINARIES}/.gitkeep`, '');
});

afterEach(() => {
	repo.cleanup();
});

/** What package-sidecar-windows.ps1 publishes, packaged from the sources on disk. */
async function packageImage({ cfg = true } = {}) {
	repo.remove(IMAGE);
	repo.write(`${IMAGE}/${LAUNCHER}`, 'launcher');
	repo.write(`${IMAGE}/app/mail-backend-0.1.0.jar`, 'jar');
	if (cfg) repo.write(`${IMAGE}/app/${LAUNCHER_CFG}`, '[Application]');
	repo.write(`${IMAGE}/runtime/release`, 'JAVA_VERSION="25"');
	const { hash } = await hashBackendSources(repo.root);
	repo.write(`${IMAGE}/app/${HASH_FILE_NAME}`, `${hash}\n`);
	return hash;
}

const sync = () => repo.run('sync-backend-sidecar-windows.mjs');
const inBinaries = (relative) => path.join(repo.root, BINARIES, relative);

describe('npm run sidecar:sync:windows', () => {
	it('puts the image in place with both launcher cfgs, and leaves nothing staged', async () => {
		// What an earlier sync that stopped while assembling app/ left behind.
		repo.write(`${BINARIES}/.app-incoming/left-over.jar`, 'from a sync that stopped');
		await packageImage();

		const result = sync();

		expect(result.status, result.output).toBe(0);
		expect(readFileSync(inBinaries(`app/${LAUNCHER_CFG}`), 'utf8')).toBe('[Application]');
		// The name the launcher looks for once the bundle has dropped the triple.
		expect(readFileSync(inBinaries('app/voxrox-mail-backend.cfg'), 'utf8')).toBe('[Application]');
		expect(existsSync(inBinaries('.app-incoming'))).toBe(false);
		expect(existsSync(inBinaries('app/left-over.jar'))).toBe(false);
		expect((await checkSidecarFreshness(repo.root)).status).toBe('ok');
	});

	it('refuses an image without the launcher cfg, and leaves the synced one alone', async () => {
		const synced = await packageImage();
		expect(sync().status).toBe(0);
		repo.write(SERVICE, 'class Service { void added() {} }');
		await packageImage({ cfg: false });

		const result = sync();

		// A launcher without its cfg stops on an invisible jpackage error dialog.
		expect(result.status).toBe(1);
		expect(result.output).toContain(LAUNCHER_CFG);
		expect(result.output).toContain('npm run sidecar:rebuild:windows');
		// Checked before anything was deleted: binaries/ still holds the image
		// of the earlier sync, which the check now calls stale, and says so.
		const freshness = await checkSidecarFreshness(repo.root);
		expect(freshness.status).toBe('stale');
		expect(freshness.recorded).toBe(synced);
	});

	/*
	 * A file in binaries/runtime that cannot be deleted stops the sync halfway,
	 * as an executable Defender is scanning, or a sidecar left running, does on
	 * a real machine. A running executable is the one lock a test can hold on
	 * every Windows: deleting a mapped image is refused whoever opened it.
	 * findstr reading a stdin that stays open runs until it is killed. Other
	 * platforms delete such a file without complaint, so there is no lock to
	 * take there.
	 */
	it.skipIf(process.platform !== 'win32')(
		'leaves no app/ when it stops in runtime/, so the check calls the sidecar missing',
		async () => {
			await packageImage();
			expect(sync().status).toBe(0);
			repo.write(SERVICE, 'class Service { void added() {} }');
			await packageImage();

			const locked = inBinaries('runtime/bin/locked.exe');
			mkdirSync(path.dirname(locked), { recursive: true });
			copyFileSync(path.join(process.env.SystemRoot, 'System32', 'findstr.exe'), locked);
			const holder = spawn(locked, ['never-typed'], { stdio: ['pipe', 'ignore', 'ignore'] });
			await once(holder, 'spawn');
			try {
				const result = sync();

				// Bounded: the retries give up after about two seconds.
				expect(result.status).not.toBe(0);
				expect(result.output).toMatch(/EPERM|EBUSY|EACCES/);
				// Before, the new app/ was already in place, digest and all, and
				// the check called the new jar beside half a runtime fresh.
				expect((await checkSidecarFreshness(repo.root)).status).toBe('missing');
			} finally {
				holder.kill();
				await waitForExit(holder);
			}
		}
	);
});
