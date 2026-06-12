import { createWriteStream, existsSync } from 'node:fs';
import { mkdir, readFile, rename, rm, stat, writeFile } from 'node:fs/promises';
import { spawn } from 'node:child_process';
import http from 'node:http';
import path from 'node:path';
import process from 'node:process';
import { resolveMailDataDir } from './lib/data-dirs.mjs';
import { envForDesktopSidecar, loadBackendEnv } from './lib/dotenv.mjs';
import { terminateProcessTree, waitForExit } from './lib/process-tree.mjs';
import { wait } from './lib/run.mjs';

const rootDir = process.cwd();
const targetDir = path.join(rootDir, 'target');
const appDataDir = resolveMailDataDir();
const sessionPath = path.join(appDataDir, 'session.json');
const readyPath = path.join(appDataDir, '.ready');
const stamp = new Date().toISOString().replace(/[:.]/g, '-');
const reportPath = path.join(targetDir, `tauri-release-startup-${stamp}.json`);
const stdoutPath = path.join(targetDir, `tauri-release-startup-${stamp}.stdout.log`);
const stderrPath = path.join(targetDir, `tauri-release-startup-${stamp}.stderr.log`);

const args = new Map(
	process.argv
		.slice(2)
		.filter((arg) => arg.startsWith('--') && arg.includes('='))
		.map((arg) => {
			const [key, ...valueParts] = arg.slice(2).split('=');
			return [key, valueParts.join('=')];
		})
);

const runs = positiveInt(args.get('runs'), 2);
const timeoutMs = positiveInt(args.get('timeout-ms'), 60_000);
const settleMs = positiveInt(args.get('settle-ms'), 2_000);
const exePath = path.resolve(args.get('exe') ?? resolveDefaultReleaseExe());
const includeBackendEnvCrypto = process.argv.includes('--include-backend-env-crypto');
const backendEnv = envForDesktopSidecar(
	await loadBackendEnv('Release startup smoke needs the same backend env as tauri:dev.'),
	includeBackendEnvCrypto
);
const isolateAppData = process.argv.includes('--isolate-app-data');

function positiveInt(raw, fallback) {
	const value = Number.parseInt(raw ?? '', 10);
	return Number.isFinite(value) && value > 0 ? value : fallback;
}

function resolveDefaultReleaseExe() {
	const candidates = [
		path.join(rootDir, 'src-tauri', 'target', 'release', 'app.exe'),
		path.join(rootDir, 'src-tauri', 'target', 'release', 'mail.exe'),
		path.join(rootDir, 'src-tauri', 'target', 'release', 'Mail.exe')
	];
	return candidates.find((candidate) => existsSync(candidate)) ?? candidates[0];
}

async function exists(filePath) {
	try {
		await stat(filePath);
		return true;
	} catch {
		return false;
	}
}

async function removeRuntimeSentinels() {
	await mkdir(appDataDir, { recursive: true });
	await rm(readyPath, { force: true });
	await rm(sessionPath, { force: true });
}

async function setupAppDataIsolation() {
	if (!isolateAppData) {
		return async () => {};
	}

	const backupPath = path.join(path.dirname(appDataDir), `mail.startup-smoke-backup-${stamp}`);
	const hadOriginal = await exists(appDataDir);

	if (hadOriginal) {
		await rename(appDataDir, backupPath);
	}
	await mkdir(appDataDir, { recursive: true });

	return async () => {
		await rm(appDataDir, { recursive: true, force: true });
		if (hadOriginal) {
			await rename(backupPath, appDataDir);
		}
	};
}

async function readSession() {
	return JSON.parse(await readFile(sessionPath, 'utf8'));
}

async function existingBackendLooksRunning() {
	if (!(await exists(sessionPath))) {
		return false;
	}

	try {
		const session = await readSession();
		const readiness = await getJson(
			`${session.baseUrl}/v1/system/readiness`,
			session.apiKey,
			1_500
		);
		return readiness && typeof readiness === 'object';
	} catch {
		return false;
	}
}

function getJson(url, apiKey, requestTimeoutMs) {
	return new Promise((resolve, reject) => {
		const request = http.get(
			url,
			{
				headers: {
					'X-API-KEY': apiKey
				},
				timeout: requestTimeoutMs
			},
			(response) => {
				let body = '';
				response.setEncoding('utf8');
				response.on('data', (chunk) => {
					body += chunk;
				});
				response.on('end', () => {
					if ((response.statusCode ?? 500) < 200 || (response.statusCode ?? 500) >= 300) {
						reject(new Error(`GET ${url} returned ${response.statusCode}: ${body}`));
						return;
					}
					try {
						resolve(JSON.parse(body));
					} catch (error) {
						reject(error);
					}
				});
			}
		);

		request.on('timeout', () => {
			request.destroy(new Error(`GET ${url} timed out after ${requestTimeoutMs} ms`));
		});
		request.on('error', reject);
	});
}

async function waitForSession(startedAtMs, child) {
	const deadline = Date.now() + timeoutMs;
	while (Date.now() < deadline) {
		if (child.exitCode !== null) {
			throw new Error(`Release app exited before session was ready with code ${child.exitCode}`);
		}

		if ((await exists(readyPath)) && (await exists(sessionPath))) {
			const readyStat = await stat(readyPath);
			const sessionStat = await stat(sessionPath);
			if (readyStat.mtimeMs >= startedAtMs - 500 && sessionStat.mtimeMs >= startedAtMs - 500) {
				return readSession();
			}
		}

		await wait(100);
	}
	throw new Error(`Timed out after ${timeoutMs} ms waiting for ${readyPath} and ${sessionPath}`);
}

async function waitForReadiness(session, child) {
	const deadline = Date.now() + timeoutMs;
	const readinessUrl = `${session.baseUrl}/v1/system/readiness`;
	let lastError = null;

	while (Date.now() < deadline) {
		if (child.exitCode !== null) {
			throw new Error(`Release app exited before readiness with code ${child.exitCode}`);
		}

		try {
			const readiness = await getJson(readinessUrl, session.apiKey, 2_000);
			if (readiness.ready === true) {
				return readiness;
			}
			lastError = new Error(`readiness.ready=${String(readiness.ready)}`);
		} catch (error) {
			lastError = error;
		}

		await wait(200);
	}

	throw new Error(
		`Timed out after ${timeoutMs} ms waiting for readiness: ${lastError?.message ?? 'unknown error'}`
	);
}

async function measureRun(index, stdout, stderr) {
	const label = index === 0 ? 'cold' : 'warm';
	await removeRuntimeSentinels();

	const startedAtMs = Date.now();
	const startedAtIso = new Date(startedAtMs).toISOString();
	const child = spawn(exePath, [], {
		cwd: path.dirname(exePath),
		env: {
			...process.env,
			...backendEnv,
			VITE_ENABLE_AUTO_UPDATE_CHECK: process.env.VITE_ENABLE_AUTO_UPDATE_CHECK ?? '0'
		},
		windowsHide: false
	});

	child.stdout?.pipe(stdout, { end: false });
	child.stderr?.pipe(stderr, { end: false });

	try {
		const session = await waitForSession(startedAtMs, child);
		const sessionReadyMs = Date.now() - startedAtMs;
		const readiness = await waitForReadiness(session, child);
		const readinessMs = Date.now() - startedAtMs;

		await wait(settleMs);
		await terminateProcessTree(child);
		await waitForExit(child).catch(() => null);

		return {
			label,
			startedAt: startedAtIso,
			sessionReadyMs,
			readinessMs,
			appVersion: session.appVersion,
			apiVersion: session.apiVersion,
			dbSchemaVersion: session.dbSchemaVersion,
			readinessPhase: readiness.phase ?? null
		};
	} catch (error) {
		await terminateProcessTree(child).catch(() => null);
		await waitForExit(child).catch(() => null);
		throw error;
	}
}

await mkdir(targetDir, { recursive: true });

if (!(await exists(exePath))) {
	throw new Error(`Release executable not found: ${exePath}. Run npm run tauri:build first.`);
}

if (await existingBackendLooksRunning()) {
	throw new Error(
		`Existing backend appears to be running from ${sessionPath}. Close the app before measuring release startup.`
	);
}

const stdout = createWriteStream(stdoutPath, { flags: 'w' });
const stderr = createWriteStream(stderrPath, { flags: 'w' });

const report = {
	exePath,
	appDataDir,
	backendEnvNames: Object.keys(backendEnv).sort(),
	includeBackendEnvCrypto,
	isolateAppData,
	timeoutMs,
	settleMs,
	runs: []
};

console.log(`Measuring release startup from ${exePath}`);
console.log(`Report: ${reportPath}`);

const restoreAppData = await setupAppDataIsolation();

try {
	for (let index = 0; index < runs; index++) {
		const result = await measureRun(index, stdout, stderr);
		report.runs.push(result);
		console.log(
			`${result.label}: session=${result.sessionReadyMs}ms readiness=${result.readinessMs}ms`
		);
	}
} finally {
	await restoreAppData();
	stdout.end();
	stderr.end();
}

await writeFile(reportPath, `${JSON.stringify(report, null, 2)}\n`, 'utf8');
