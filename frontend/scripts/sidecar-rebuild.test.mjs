import { spawn } from 'node:child_process';
import {
	copyFileSync,
	existsSync,
	mkdirSync,
	mkdtempSync,
	realpathSync,
	rmSync,
	writeFileSync
} from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { waitForExit } from './lib/process-tree.mjs';
import {
	parseRebuildArgs,
	rebuildSidecar,
	rebuildSteps,
	runningFromCheckout
} from './lib/sidecar-rebuild.mjs';
import { HASH_FILE_NAME, hashBackendSources } from './lib/sidecar-staleness.mjs';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');

let root;

function write(relative, contents) {
	const full = path.join(root, relative);
	mkdirSync(path.dirname(full), { recursive: true });
	writeFileSync(full, contents);
	return full;
}

/**
 * The repo as packaging and the sync leave it when both worked: the jar built
 * in backend/target, the same jar in binaries/app, and beside it the digest of
 * the sources on disk. The runner below only records the commands, so this is
 * what they would have left behind.
 */
async function rebuiltFixture({ syncedJar = 'jar bytes' } = {}) {
	write('backend/src/main/java/org/voxrox/Service.java', 'class Service {}');
	write('backend/pom.xml', '<project/>');
	write('backend/target/mail-backend-0.1.0.jar', 'jar bytes');
	write('backend/target/mail-backend-0.1.0.jar.original', 'the jar before repackaging');
	write(
		'frontend/src-tauri/tauri.conf.json',
		JSON.stringify({ bundle: { externalBin: ['binaries/voxrox-mail-backend'] } })
	);
	write('frontend/src-tauri/binaries/voxrox-mail-backend-x86_64-pc-windows-msvc.exe', 'launcher');
	write('frontend/src-tauri/binaries/app/mail-backend-0.1.0.jar', syncedJar);
	const { hash } = await hashBackendSources(root);
	write(`frontend/src-tauri/binaries/app/${HASH_FILE_NAME}`, `${hash}\n`);
}

/** Records which commands ran; `failAt` names the one that exits 1. */
function recorder({ failAt = null, running = [] } = {}) {
	const ran = [];
	const lines = [];
	return {
		ran,
		lines,
		deps: {
			runCommand: async (step) => {
				ran.push(step.id);
				return step.id === failAt ? 1 : 0;
			},
			findRunning: async () => running,
			log: (line) => lines.push(line)
		}
	};
}

beforeEach(() => {
	// The long form: the process test compares paths as Windows reports them.
	root = realpathSync.native(mkdtempSync(path.join(os.tmpdir(), 'voxrox-rebuild-')));
});

afterEach(() => {
	rmSync(root, { recursive: true, force: true, maxRetries: 5 });
});

describe('sidecar rebuild', () => {
	it('packages, syncs, checks the result and runs the smoke, in that order', async () => {
		await rebuiltFixture();
		const { ran, lines, deps } = recorder();

		const freshness = await rebuildSidecar(root, parseRebuildArgs([]), deps);

		expect(ran).toEqual(['package', 'sync', 'smoke']);
		const titles = rebuildSteps(root).map((step) => step.title);
		expect(lines.slice(0, titles.length)).toEqual(
			titles.map((title, index) => `${index + 1}/${titles.length} ${title}`)
		);
		expect(freshness.status).toBe('ok');
	});

	it('stops at the first step that fails', async () => {
		await rebuiltFixture();
		const { ran, deps } = recorder({ failAt: 'package' });

		await expect(rebuildSidecar(root, parseRebuildArgs([]), deps)).rejects.toThrow(
			/Packaging the sidecar image .* failed with exit code 1/
		);
		// A sync after a failed packaging would copy the image it failed to replace.
		expect(ran).toEqual(['package']);
	});

	it('names its own OAuth flag when packaging fails without it', async () => {
		await rebuiltFixture();
		const failure = (args) =>
			rebuildSidecar(root, parseRebuildArgs(args), recorder({ failAt: 'package' }).deps).catch(
				(error) => error.message
			);

		// The dev wrapper's own message names -AllowPlaceholderOAuth, which is
		// not how it is passed through npm.
		expect(await failure([])).toContain('-- --allow-placeholder-oauth');
		const flagged = await failure(['--allow-placeholder-oauth']);
		expect(flagged).toContain('failed with exit code 1');
		expect(flagged).not.toContain('allow-placeholder-oauth');
	});

	it('fails when the jar in binaries/ is not the one packaging built', async () => {
		await rebuiltFixture({ syncedJar: 'the jar of an older build' });
		const { ran, deps } = recorder();

		await expect(rebuildSidecar(root, parseRebuildArgs([]), deps)).rejects.toThrow(
			/not the one packaging just built/
		);
		expect(ran).toEqual(['package', 'sync']);
	});

	it('fails when the freshness check tauri:dev runs would not say ok', async () => {
		await rebuiltFixture();
		// The jars agree; the sources moved on after the digest was taken.
		write('backend/src/main/java/org/voxrox/Service.java', 'class Service { void added() {} }');
		const { ran, deps } = recorder();

		await expect(rebuildSidecar(root, parseRebuildArgs([]), deps)).rejects.toThrow(
			/not built from the backend sources that are checked out/
		);
		expect(ran).toEqual(['package', 'sync']);
	});

	it('refuses to start while something runs out of frontend/src-tauri', async () => {
		await rebuiltFixture();
		const { ran, deps } = recorder({
			running: [
				{
					pid: 4242,
					path: path.join(root, 'frontend/src-tauri/target/debug/voxrox-mail-backend.exe')
				}
			]
		});

		await expect(rebuildSidecar(root, parseRebuildArgs([]), deps)).rejects.toThrow(
			/PID 4242[\s\S]*Stop npm run tauri:dev/
		);
		expect(ran).toEqual([]);
	});

	it('passes -AllowPlaceholderOAuth on when asked, and can leave the smoke out', () => {
		const asked = rebuildSteps(
			root,
			parseRebuildArgs(['--allow-placeholder-oauth', '--skip-smoke'])
		);

		expect(asked.map((step) => step.id)).toEqual(['not-running', 'package', 'sync', 'verify']);
		expect(asked.find((step) => step.id === 'package').args).toEqual(
			expect.arrayContaining(['-SkipTests', '-AllowPlaceholderOAuth'])
		);
		const plain = rebuildSteps(root, parseRebuildArgs([]));
		expect(plain.find((step) => step.id === 'package').args).not.toContain(
			'-AllowPlaceholderOAuth'
		);
	});

	it('refuses an argument it does not know', () => {
		// -SkipTests belongs to the packaging script, which this always passes it.
		expect(() => parseRebuildArgs(['--skip-tests'])).toThrow(/Unknown argument --skip-tests/);
	});

	it('runs scripts that exist in this repo', () => {
		const scripts = rebuildSteps(REPO_ROOT)
			.flatMap((step) => step.args ?? [])
			.filter((arg) => /\.(ps1|mjs)$/.test(arg));

		expect(scripts).toHaveLength(3);
		for (const script of scripts) expect(existsSync(script), script).toBe(true);
	});

	it.skipIf(process.platform !== 'win32')(
		'finds a process running out of src-tauri/binaries',
		async () => {
			// What the sidecar smoke leaves running there; any program will do.
			const exe = path.join(root, 'frontend', 'src-tauri', 'binaries', 'probe.exe');
			mkdirSync(path.dirname(exe), { recursive: true });
			copyFileSync(path.join(process.env.SystemRoot ?? 'C:\\Windows', 'System32', 'PING.EXE'), exe);
			const child = spawn(exe, ['-n', '60', '127.0.0.1'], { stdio: 'ignore', windowsHide: true });
			try {
				expect(runningFromCheckout(root).map((found) => found.pid)).toContain(child.pid);
				// Only what runs out of the checkout asked about.
				expect(runningFromCheckout(`${root}-sibling`)).toEqual([]);
			} finally {
				child.kill();
				await waitForExit(child);
			}
		}
	);
});
