import { realpathSync } from 'node:fs';
import { readdir } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { psQuote, pwsh, sha256 } from './installed-app.mjs';
import { checkSidecarFreshness, describeStaleness } from './sidecar-staleness.mjs';

/**
 * One command from a changed backend to a sidecar `npm run tauri:dev` accepts:
 * `npm run sidecar:rebuild:windows`.
 *
 * By hand it took two commands in two directories, and the second also worked
 * without the first. The sync copies whatever image backend/target/sidecar
 * holds, so skipping the packaging, or packaging with `mvn package` as the app
 * itself used to advise, put an old backend in place with nothing said. This
 * runs both in order and then checks the result by content. The jar's mtime
 * proves nothing, since `project.build.outputTimestamp` dates every build
 * 1980-02-01. Neither does the sync's own report: it says the same thing for
 * an old image as for a new one.
 *
 * The release workflow keeps its own steps: its OAuth client ids come from
 * secrets, not from backend/.env.
 */

const FLAGS = {
	'--allow-placeholder-oauth': 'allowPlaceholderOAuth',
	'--skip-smoke': 'skipSmoke'
};

/** `npm run sidecar:rebuild:windows -- <flags>`; anything else is refused. */
export function parseRebuildArgs(argv) {
	const options = { allowPlaceholderOAuth: false, skipSmoke: false };
	for (const arg of argv) {
		const key = FLAGS[arg];
		if (!key) {
			throw new Error(`Unknown argument ${arg}. Known: ${Object.keys(FLAGS).join(', ')}.`);
		}
		options[key] = true;
	}
	return options;
}

/** The long form of a path that exists. 8.3 short names (C:\Users\RUNNER~1) would not match. */
function canonical(dir) {
	try {
		return realpathSync.native(dir);
	} catch {
		return dir;
	}
}

/**
 * Processes running out of this checkout's frontend/src-tauri/binaries or
 * frontend/src-tauri/target, as `{ pid, path }`. `tauri dev` runs the shell and
 * the sidecar from a copy in target/debug, and the sidecar smoke runs the one in
 * binaries/ itself.
 */
export function runningFromCheckout(repoRoot) {
	if (process.platform !== 'win32') return [];
	const prefixes = ['binaries', 'target'].map(
		(dir) => canonical(path.join(repoRoot, 'frontend', 'src-tauri', dir)) + path.sep
	);
	const matches = prefixes
		.map(
			(prefix) =>
				`$_.ExecutablePath.StartsWith(${psQuote(prefix)}, [System.StringComparison]::OrdinalIgnoreCase)`
		)
		.join(' -or ');
	const out = pwsh(
		`@(Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -and (${matches}) } | ` +
			`ForEach-Object { "$($_.ProcessId)|$($_.ExecutablePath)" }) -join "\`n"`
	);
	return out
		.split(/\r?\n/)
		.filter(Boolean)
		.map((line) => {
			const [pid, ...rest] = line.split('|');
			return { pid: Number(pid), path: rest.join('|') };
		});
}

/** The jars under `dir`, by their path relative to it; none when it does not exist. */
async function jarsUnder(dir) {
	let names = [];
	try {
		names = await readdir(dir, { recursive: true });
	} catch {
		// No directory: no jars.
	}
	return names.filter((name) => name.endsWith('.jar')).sort();
}

/**
 * That binaries/ now holds the image packaging just published, byte for byte
 * in every jar under app/ -- the application jar and lib/ -- and that the
 * freshness check `tauri:dev` runs says `ok` about it.
 *
 * The comparison is with the published image rather than with the executable
 * jar in backend/target, because the image carries that jar extracted
 * (package-sidecar-windows.ps1). An image packaging failed to replace is still
 * caught: the digest the freshness check reads was taken before the build it
 * describes.
 */
export async function verifySyncedSidecar(repoRoot) {
	const published = path.join(
		repoRoot,
		'backend',
		'target',
		'sidecar',
		'x86_64-pc-windows-msvc',
		'app'
	);
	const synced = path.join(repoRoot, 'frontend', 'src-tauri', 'binaries', 'app');
	const built = await jarsUnder(published);
	if (built.length === 0) throw new Error(`No jar in ${published}: packaging published no image.`);
	const copied = new Set(await jarsUnder(synced));
	const differing = built.filter(
		(name) =>
			!copied.has(name) || sha256(path.join(published, name)) !== sha256(path.join(synced, name))
	);
	const extra = [...copied].filter((name) => !built.includes(name));
	if (differing.length > 0 || extra.length > 0) {
		throw new Error(
			[
				`The jars in binaries/ are not the ones packaging just built (${published}):`,
				...differing.map((name) => `  differs or missing: ${name}`),
				...extra.map((name) => `  not in the image:   ${name}`)
			].join('\n')
		);
	}
	const freshness = await checkSidecarFreshness(repoRoot);
	if (freshness.status !== 'ok') throw new Error(describeStaleness(freshness).text);
	return freshness;
}

/**
 * The steps in the order they run. A step either runs a command (`command`,
 * `args`, `cwd`) or checks something in-process (`check`).
 */
export function rebuildSteps(repoRoot, { allowPlaceholderOAuth = false, skipSmoke = false } = {}) {
	const backend = path.join(repoRoot, 'backend');
	const frontend = path.join(repoRoot, 'frontend');
	const steps = [
		{
			id: 'not-running',
			title: 'Checking that nothing runs out of frontend/src-tauri',
			check: 'not-running'
		},
		{
			id: 'package',
			title: 'Packaging the sidecar image (mvn clean, -Paot package, jpackage)',
			command: 'pwsh',
			// The dev wrapper bakes in the OAuth client ids from backend/.env.
			args: [
				'-NoProfile',
				'-File',
				path.join(backend, 'package-sidecar-dev-windows.ps1'),
				'-SkipTests',
				...(allowPlaceholderOAuth ? ['-AllowPlaceholderOAuth'] : [])
			],
			cwd: backend
		},
		{
			id: 'sync',
			title: 'Copying it into frontend/src-tauri/binaries',
			command: process.execPath,
			args: [path.join(frontend, 'scripts', 'sync-backend-sidecar-windows.mjs')],
			cwd: frontend
		},
		{
			id: 'verify',
			title: 'Checking that binaries/ holds what was just built',
			check: 'verify'
		}
	];
	if (!skipSmoke) {
		steps.push({
			id: 'smoke',
			title: 'Starting the packaged sidecar headless (tauri:smoke:sidecar)',
			command: process.execPath,
			args: [path.join(frontend, 'scripts', 'tauri-sidecar-smoke.mjs')],
			cwd: frontend
		});
	}
	return steps;
}

function runningReport(processes) {
	return [
		'Something from this checkout is still running out of frontend/src-tauri:',
		...processes.map((p) => `  PID ${p.pid}  ${p.path}`),
		'A sidecar running from binaries/ cannot be replaced, and an app running from target/',
		'would go on with the sidecar it started with. Stop npm run tauri:dev (or end these',
		'processes) and run this again.'
	].join('\n');
}

/**
 * Runs the steps and stops at the first one that fails, by throwing.
 * `runCommand(step)` resolves with the exit code, `findRunning(repoRoot)` with
 * what `runningFromCheckout` returns, and `log` takes one line at a time.
 */
export async function rebuildSidecar(repoRoot, options, { runCommand, findRunning, log }) {
	const steps = rebuildSteps(repoRoot, options);
	let freshness;
	for (const [index, step] of steps.entries()) {
		log(`${index + 1}/${steps.length} ${step.title}`);
		if (step.check === 'not-running') {
			const running = await findRunning(repoRoot);
			if (running.length > 0) throw new Error(runningReport(running));
			continue;
		}
		if (step.check === 'verify') {
			freshness = await verifySyncedSidecar(repoRoot);
			continue;
		}
		const code = await runCommand(step);
		if (code !== 0) {
			const hint =
				step.id === 'package' && !options.allowPlaceholderOAuth
					? '\nIf backend/.env does not define the OAuth client ids, ' +
						'`npm run sidecar:rebuild:windows -- --allow-placeholder-oauth` packages a ' +
						'sidecar whose OAuth login will not work.'
					: '';
			throw new Error(`${step.title} failed with exit code ${code}.${hint}`);
		}
	}
	log(
		`Done: binaries/ holds the sidecar packaged from the backend checked out now ` +
			`(${freshness.recorded.slice(0, 12)}…)` +
			(options.skipSmoke ? ', smoke skipped.' : ', and it passed the smoke.')
	);
	return freshness;
}
