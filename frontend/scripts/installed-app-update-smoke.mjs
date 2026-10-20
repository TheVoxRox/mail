import { cpSync, existsSync, readFileSync, rmSync, statSync } from 'node:fs';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { resolveMailDataDir } from './lib/data-dirs.mjs';
import {
	createChecks,
	exitText,
	registeredInstallDir,
	request,
	runToExit,
	sha256,
	sidecarProcesses,
	sidecarsGone,
	startApp as startInstalledApp,
	stopApp as stopInstalledApp,
	treeDigests,
	uninstall
} from './lib/installed-app.mjs';
import {
	EXPECTED_INSTALL_SUBPATH,
	aotCacheProblems,
	formatReport,
	installerVersion,
	treeDifferences
} from './lib/installed-app-smoke.mjs';
import { waitForExit } from './lib/process-tree.mjs';
import { compareSemver } from './lib/semver.mjs';

/*
 * `node scripts/installed-app-update-smoke.mjs --previous=<setup.exe> --installer=<setup.exe>`
 * (from frontend/) — installs the previous release, starts it, gives it data,
 * and runs the candidate's installer over the RUNNING installation with the
 * arguments the updater hands it (`/P /UPDATE`, tauri-plugin-updater's
 * updater_parameters for the passive install mode). Then it checks what an
 * update has to leave: the candidate's files, all of them, in place of the
 * previous version's; the data and the key that reads it; an application
 * that starts on them. .github/workflows/release-candidate-smoke.yml runs it on
 * windows-latest next to installed-app-smoke.mjs.
 *
 * Over a running installation, shell and sidecar both, because that is the
 * harder of the two ways an installer meets one. The updater stops the sidecar
 * itself before it starts the installer (installPromptedUpdate in
 * src/lib/updates.ts), but an installer started by hand does not, and the
 * installer's own CheckIfAppIsRunning ends only the shell: the sidecar goes
 * when its parent-death watchdog notices, while the installer may already be
 * writing runtime\ and app\. Passing here covers the updater's case as well.
 * From outside, the updater's case cannot be staged on its own: a sidecar
 * killed under a running shell is restarted by it.
 *
 * Whether the update laid the candidate down is judged against a fresh install
 * of the same installer in the same place, file by file, which needs no idea
 * of what the installer contains. Missing or differing files mean a file it
 * could not write. Files the fresh install does not have are what the previous
 * version left behind; the uninstaller knows only the files of the version it
 * came with, so it leaves them too, and the uninstall is checked for that.
 *
 * When the previous release is older, it also checks that the previous
 * installer refuses to go back (RELEASE_CHECKLIST §3a, the downgrade block).
 * When it is the same version, as on a pull request before the next release
 * is tagged, the run is a reinstall over a running installation: still the
 * overwrite this smoke is for, but no migration and no downgrade.
 *
 * What it does not prove: the updater itself — the check against the channel's
 * latest.json, the download, its signature, the passage from the webview's
 * prompt to the installer. That takes the published channel (RELEASE_CHECKLIST
 * §3a, the update smoke on the real channel).
 *
 * DESTRUCTIVE by design, like installed-app-smoke.mjs: it refuses to run on a
 * profile that already has the installation or the data directory.
 *
 * The output uses words rather than symbols, because it is read with a screen
 * reader.
 */

const args = new Map(
	process.argv
		.slice(2)
		.filter((arg) => arg.startsWith('--') && arg.includes('='))
		.map((arg) => {
			const [key, ...valueParts] = arg.slice(2).split('=');
			return [key, valueParts.join('=')];
		})
);

function positiveInt(raw, fallback) {
	const value = Number.parseInt(raw ?? '', 10);
	return Number.isFinite(value) && value > 0 ? value : fallback;
}

if (process.platform !== 'win32') {
	throw new Error(
		'installed-app-update-smoke runs only on Windows; the application ships Windows-only.'
	);
}
if (!args.get('previous') || !args.get('installer')) {
	throw new Error(
		'Usage: node scripts/installed-app-update-smoke.mjs --previous=<setup.exe> --installer=<setup.exe>'
	);
}

const previous = path.resolve(args.get('previous'));
const installer = path.resolve(args.get('installer'));
const timeoutMs = positiveInt(args.get('timeout-ms'), 180_000);
const orphanTimeoutMs = positiveInt(args.get('orphan-timeout-ms'), 60_000);
const expectedInstallDir = path.join(process.env.LOCALAPPDATA, EXPECTED_INSTALL_SUBPATH);
const dataDir = resolveMailDataDir();
const targetDir = path.join(process.cwd(), 'target');
const stamp = new Date().toISOString().replace(/[:.]/g, '-');
const reportPath = path.join(targetDir, `installed-app-update-smoke-${stamp}.json`);
const logsCopyDir = path.join(targetDir, `installed-app-update-smoke-${stamp}-logs`);

for (const dir of [expectedInstallDir, dataDir]) {
	if (existsSync(dir)) {
		throw new Error(
			`${dir} already exists. This smoke installs, updates and uninstalls the application, ` +
				'so it runs only on a profile that has never had it: a CI runner, not a working machine.'
		);
	}
}
for (const file of [previous, installer]) {
	if (!existsSync(file)) throw new Error(`Installer not found: ${file}`);
}
const previousVersion = installerVersion(path.basename(previous));
const candidateVersion = installerVersion(path.basename(installer));
if (!previousVersion || !candidateVersion) {
	throw new Error(
		'Both installers must keep the name the build gives them, voxrox-mail-<version>-windows-x64-setup.exe.'
	);
}
const direction = compareSemver(previousVersion, candidateVersion);
if (direction > 0) {
	throw new Error(
		`The previous installer is ${previousVersion}, newer than the candidate ${candidateVersion}.`
	);
}

/** Thrown after a check whose failure leaves nothing meaningful to check next. */
class Stop extends Error {}

const { checks, record } = createChecks();
const startApp = (dir) => startInstalledApp(dir, { dataDir, timeoutMs });
const stopApp = (app, dir) => stopInstalledApp(app, dir, { orphanTimeoutMs });
const frontendLog = path.join(dataDir, 'logs', 'mail-frontend.log');
const cryptoFile = path.join(dataDir, 'crypto.bin');
const SEED_EMAIL = 'update-smoke@example.test';

await mkdir(targetDir, { recursive: true });
console.log(
	direction < 0
		? `Updating ${previousVersion} to ${candidateVersion}`
		: `Reinstalling ${candidateVersion} over itself: the previous release is the same version`
);

let installDir = expectedInstallDir;
let running = null;

try {
	const installCode = await runToExit(previous, ['/S']);
	if (
		!record(
			`the previous release, ${previousVersion}, installs in silent mode`,
			installCode === 0,
			`exit code ${installCode}`
		)
	) {
		throw new Stop();
	}
	installDir = registeredInstallDir() || expectedInstallDir;

	try {
		running = await startApp(installDir);
		record('the previous release starts and reports ready', true, `in ${running.readyMs} ms`);
	} catch (error) {
		record('the previous release starts and reports ready', false, error.message);
		throw new Stop();
	}
	const before = running.readiness;

	const created = await request(`${running.session.baseUrl}/v1/contacts`, running.session.apiKey, {
		method: 'POST',
		json: { emails: [{ email: SEED_EMAIL }], name: 'Update', surname: 'Smoke' }
	}).catch((error) => ({ status: 0, error: error.message }));
	const contactId = created.status === 201 ? JSON.parse(created.body.toString('utf8')).id : null;
	if (
		!record(
			'a contact can be added to the previous release, for the update to keep',
			contactId !== null,
			created.error ?? `HTTP ${created.status}`
		)
	) {
		throw new Stop();
	}
	const cryptoBefore = existsSync(cryptoFile) ? sha256(cryptoFile) : null;

	// The update, over the running shell and sidecar
	const sidecarsBefore = sidecarProcesses(installDir);
	const updateCode = await runToExit(installer, ['/P', '/UPDATE'], false, timeoutMs);
	record(
		`the ${candidateVersion} installer exits 0 with /P /UPDATE over the running installation`,
		updateCode === 0,
		exitText(updateCode)
	);
	const shellExit = await Promise.race([
		waitForExit(running.child).then(() => 'exited'),
		new Promise((resolve) => setTimeout(() => resolve('still running'), orphanTimeoutMs))
	]);
	record(
		'the installer ends the running shell',
		shellExit === 'exited',
		shellExit === 'exited' ? '' : `still running ${orphanTimeoutMs} ms after the installer`
	);
	if (shellExit === 'exited') running = null;
	const survivors = await sidecarsGone(installDir, orphanTimeoutMs);
	record(
		'no sidecar of the previous release outlives the update',
		sidecarsBefore.length > 0 && survivors.length === 0,
		sidecarsBefore.length === 0
			? 'no sidecar was running before the update, so nothing was proven'
			: survivors.length > 0
				? `still running: PIDs ${survivors.join(', ')}`
				: ''
	);
	if (running) {
		await stopApp(running, installDir);
		running = null;
	}
	const updatedTree = treeDigests(installDir);

	// The updated installation
	const logOffset = existsSync(frontendLog) ? statSync(frontendLog).size : 0;
	try {
		running = await startApp(installDir);
		record('the updated installation starts and reports ready', true, `in ${running.readyMs} ms`);
	} catch (error) {
		record('the updated installation starts and reports ready', false, error.message);
		throw new Stop();
	}
	const after = running.readiness;
	console.log(
		`Backend ${before.appVersion} with schema ${before.dbSchemaVersion} before, ` +
			`${after.appVersion} with schema ${after.dbSchemaVersion} after`
	);

	const kept = await request(
		`${running.session.baseUrl}/v1/contacts/${contactId}`,
		running.session.apiKey
	).catch((error) => ({ status: 0, error: error.message }));
	const keptEmails =
		kept.status === 200
			? (JSON.parse(kept.body.toString('utf8')).emails ?? []).map((entry) => entry.email)
			: [];
	record(
		'the contact added before the update is still there',
		keptEmails.includes(SEED_EMAIL),
		kept.error ?? (kept.status === 200 ? `e-mails ${keptEmails.join(', ')}` : `HTTP ${kept.status}`)
	);
	record(
		'the update keeps crypto.bin',
		cryptoBefore !== null && existsSync(cryptoFile) && sha256(cryptoFile) === cryptoBefore
	);
	await stopApp(running, installDir);
	running = null;

	// Only the lines of the updated start: the previous release wrote its own,
	// and 0.1.0's cache never mapped on an installation.
	const aotProblems = existsSync(frontendLog)
		? aotCacheProblems(readFileSync(frontendLog).subarray(logOffset).toString('utf8'))
		: null;
	record(
		'the updated sidecar starts on its AOT cache, with no aot warning in the Tauri log',
		aotProblems?.length === 0,
		aotProblems === null ? `${frontendLog} does not exist` : aotProblems.join('; ')
	);

	// §3a The downgrade block
	if (direction < 0) {
		const downgradeCode = await runToExit(previous, ['/S'], false, timeoutMs);
		const afterDowngrade = treeDigests(installDir);
		const untouched = treeDifferences(afterDowngrade, updatedTree);
		const changed = [...untouched.missing, ...untouched.differing, ...untouched.leftover];
		record(
			`the ${previousVersion} installer refuses to go back over ${candidateVersion}`,
			downgradeCode !== null && downgradeCode !== 0 && changed.length === 0,
			`${exitText(downgradeCode)}${changed.length > 0 ? `; it changed ${changed.join(', ')}` : ''}`
		);
	}

	// Leftovers: what the uninstaller of the updated installation cannot remove
	const uninstallCode = await uninstall(installDir);
	record(
		'the updated installation uninstalls in silent mode',
		uninstallCode === 0,
		`exit code ${uninstallCode}`
	);
	// Run in place (`_?=`) so that it can be waited for, the uninstaller cannot
	// delete itself, as the template's reinstall page also notes.
	const remaining = [...treeDigests(installDir).keys()]
		.filter((name) => name.toLowerCase() !== 'uninstall.exe')
		.sort((a, b) => a.localeCompare(b));
	record(
		'uninstalling the updated installation leaves no file behind',
		remaining.length === 0,
		remaining.length > 0 ? `left ${remaining.join(', ')}` : ''
	);
	rmSync(installDir, { recursive: true, force: true });

	// What a fresh install of the candidate lays down, against the update
	const freshCode = await runToExit(installer, ['/S']);
	if (
		!record(
			`a fresh install of ${candidateVersion} in the same place exits 0`,
			freshCode === 0,
			`exit code ${freshCode}`
		)
	) {
		throw new Stop();
	}
	const { missing, differing, leftover } = treeDifferences(updatedTree, treeDigests(installDir));
	record(
		'the update laid down every file a fresh install does, with the same content',
		missing.length === 0 && differing.length === 0,
		[
			missing.length > 0 ? `missing ${missing.join(', ')}` : '',
			differing.length > 0 ? `different ${differing.join(', ')}` : ''
		]
			.filter(Boolean)
			.join('; ')
	);
	record(
		'the update left no file of the previous release in the installation',
		leftover.length === 0,
		leftover.length > 0 ? `left ${leftover.join(', ')}` : ''
	);
} catch (error) {
	if (!(error instanceof Stop))
		record('the smoke runs to its end', false, error.stack ?? error.message);
} finally {
	if (running) await stopApp(running, installDir);

	if (existsSync(path.join(dataDir, 'logs'))) {
		cpSync(path.join(dataDir, 'logs'), logsCopyDir, { recursive: true });
	}
	await uninstall(installDir);

	await writeFile(
		reportPath,
		`${JSON.stringify(
			{ previous, installer, previousVersion, candidateVersion, installDir, dataDir, checks },
			null,
			2
		)}\n`,
		'utf8'
	);
	console.log('');
	console.log(formatReport(checks, 'Installed app update smoke'));
	console.log(`Report: ${reportPath}`);
}

process.exitCode = checks.length > 0 && checks.every((check) => check.ok) ? 0 : 1;
