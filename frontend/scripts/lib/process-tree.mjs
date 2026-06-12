/**
 * Killing a spawned tool on Windows must take the whole process tree with it
 * (npm shim -> node -> vite/tauri/jvm children), otherwise orphans keep the
 * 4173 port or the WebView2 profile locked. taskkill /T /F is the only
 * built-in that does that; on POSIX a plain SIGTERM to the direct child is
 * enough because our children forward signals.
 */

import { spawn, spawnSync } from 'node:child_process';
import path from 'node:path';
import process from 'node:process';
import { wait } from './run.mjs';

function taskkillPath() {
	return path.join(process.env.SystemRoot ?? 'C:\\Windows', 'System32', 'taskkill.exe');
}

/** Synchronous variant — safe inside SIGINT/SIGTERM handlers and finally blocks. */
export function terminateProcessTreeSync(child) {
	if (!child?.pid || child.killed) return;

	if (process.platform === 'win32') {
		spawnSync(taskkillPath(), ['/PID', String(child.pid), '/T', '/F'], {
			stdio: 'ignore',
			windowsHide: true
		});
		return;
	}

	child.kill('SIGTERM');
}

/** Async variant — waits for the kill to land; on POSIX escalates to SIGKILL. */
export async function terminateProcessTree(child) {
	if (!child?.pid || child.killed) return;

	if (process.platform === 'win32') {
		const killer = spawn(taskkillPath(), ['/PID', String(child.pid), '/T', '/F'], {
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

/** Resolves with { code, signal } once the child has exited (or immediately if it already has). */
export async function waitForExit(child) {
	if (child.exitCode !== null || child.signalCode !== null) {
		return { code: child.exitCode, signal: child.signalCode };
	}

	return new Promise((resolve, reject) => {
		child.once('error', reject);
		child.once('exit', (code, signal) => {
			resolve({ code, signal });
		});
	});
}
