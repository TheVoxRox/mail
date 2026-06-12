/**
 * Shared child-process helpers for the frontend build/dev scripts.
 *
 * Two entry points with distinct failure semantics:
 *   - run()        resolves with the exit code (signal death counts as 1);
 *                  use when the caller propagates the code itself.
 *   - runOrThrow() rejects on any non-zero exit or signal; use for steps
 *                  that must succeed before the script can continue.
 */

import { spawn } from 'node:child_process';
import process from 'node:process';

export function wait(ms) {
	return new Promise((resolve) => setTimeout(resolve, ms));
}

function spawnAndWait(command, args, label, spawnOptions) {
	return new Promise((resolve, reject) => {
		const child = spawn(command, args, {
			cwd: process.cwd(),
			stdio: 'inherit',
			windowsHide: true,
			...spawnOptions
		});

		child.on('error', (error) => {
			reject(new Error(`${label} failed to start: ${error.message}`));
		});
		child.on('exit', (code, signal) => {
			resolve({ code, signal });
		});
	});
}

export async function run(command, args, options = {}) {
	const { label = command, ...spawnOptions } = options;
	const { code, signal } = await spawnAndWait(command, args, label, spawnOptions);
	return signal ? 1 : (code ?? 1);
}

export async function runOrThrow(command, args, options = {}) {
	const { label = command, ...spawnOptions } = options;
	const { code, signal } = await spawnAndWait(command, args, label, spawnOptions);
	if (signal) {
		throw new Error(`${label} terminated by signal ${signal}`);
	}
	if (code !== 0) {
		throw new Error(`${label} exited with code ${code ?? -1}`);
	}
}
