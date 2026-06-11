import { createWriteStream } from 'node:fs';
import { mkdir } from 'node:fs/promises';
import { spawn } from 'node:child_process';
import path from 'node:path';
import process from 'node:process';

const rootDir = process.cwd();
const targetDir = path.join(rootDir, 'target');
const durationArg = process.argv.find((arg) => arg.startsWith('--duration-ms='));
const durationMs = Number.parseInt(durationArg?.slice('--duration-ms='.length) ?? '20000', 10);
const smokeDurationMs = Number.isFinite(durationMs) && durationMs > 0 ? durationMs : 20000;
const stamp = new Date().toISOString().replace(/[:.]/g, '-');
const stdoutPath = path.join(targetDir, `tauri-startup-smoke-${stamp}.stdout.log`);
const stderrPath = path.join(targetDir, `tauri-startup-smoke-${stamp}.stderr.log`);
const command = process.platform === 'win32' ? (process.env.ComSpec ?? 'cmd.exe') : 'npm';
const commandArgs =
	process.platform === 'win32' ? ['/d', '/s', '/c', 'npm.cmd run tauri:dev'] : ['run', 'tauri:dev'];

function wait(ms) {
	return new Promise((resolve) => setTimeout(resolve, ms));
}

async function waitForExit(child) {
	return new Promise((resolve, reject) => {
		child.once('error', reject);
		child.once('exit', (code, signal) => {
			resolve({ code, signal });
		});
	});
}

async function terminateProcessTree(child) {
	if (!child.pid || child.killed) return;

	if (process.platform === 'win32') {
		const taskkillPath = path.join(
			process.env.SystemRoot ?? 'C:\\Windows',
			'System32',
			'taskkill.exe'
		);
		const killer = spawn(taskkillPath, ['/PID', String(child.pid), '/T', '/F'], {
			stdio: 'ignore',
			windowsHide: true,
			detached: true
		});
		killer.once('error', () => {
			child.kill();
		});
		killer.unref();
		await wait(1000);
		return;
	}

	child.kill('SIGTERM');
	await wait(5000);
	if (child.exitCode === null) {
		child.kill('SIGKILL');
	}
}

await mkdir(targetDir, { recursive: true });

const stdout = createWriteStream(stdoutPath, { flags: 'w' });
const stderr = createWriteStream(stderrPath, { flags: 'w' });

console.log(`Starting Tauri startup smoke for ${smokeDurationMs} ms...`);
console.log(`stdout: ${stdoutPath}`);
console.log(`stderr: ${stderrPath}`);

let child;
try {
	child = spawn(command, commandArgs, {
		cwd: rootDir,
		env: {
			...process.env,
			VITE_ENABLE_AUTO_UPDATE_CHECK: process.env.VITE_ENABLE_AUTO_UPDATE_CHECK ?? '0'
		},
		windowsHide: true
	});
} catch (error) {
	stderr.write(`${error.stack ?? error.message}\n`);
	stdout.end();
	stderr.end();
	console.error(`Tauri startup smoke failed to start: ${error.message}`);
	process.exit(1);
}

child.stdout?.pipe(stdout);
child.stderr?.pipe(stderr);

const exitPromise = waitForExit(child);
const timeout = wait(smokeDurationMs).then(() => ({ timedOut: true }));
let result;
try {
	result = await Promise.race([exitPromise, timeout]);
} catch (error) {
	stdout.end();
	stderr.end();
	console.error(`Tauri startup smoke failed to start: ${error.message}`);
	process.exit(1);
}

if ('timedOut' in result) {
	await terminateProcessTree(child);
	stdout.end();
	stderr.end();
	console.log('Tauri startup smoke kept running for the requested duration and was stopped.');
	process.exitCode = 0;
} else {
	stdout.end();
	stderr.end();
	const exitCode = result.code ?? 1;
	const signalSuffix = result.signal ? `, signal ${result.signal}` : '';
	console.error(`Tauri startup smoke exited early with code ${exitCode}${signalSuffix}.`);
	process.exitCode = exitCode === 0 ? 0 : 1;
}
