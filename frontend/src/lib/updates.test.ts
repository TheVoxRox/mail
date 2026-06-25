// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { get } from 'svelte/store';

/*
 * updates.ts reads VITE_ENABLE_AUTO_UPDATE_CHECK at module-load time and gates the
 * startup check on import.meta.env.PROD, so each test stubs the env and re-imports
 * a fresh module (freshModule()). The Tauri updater + $app/environment are mocked
 * so the real plugin is never loaded.
 */
const { browserMock, isTauriMock, checkMock } = vi.hoisted(() => ({
	browserMock: { value: true },
	isTauriMock: vi.fn<() => boolean>(),
	checkMock: vi.fn()
}));

vi.mock('$app/environment', () => ({
	get browser() {
		return browserMock.value;
	},
	dev: false,
	building: false,
	version: 'test'
}));
vi.mock('@tauri-apps/api/core', () => ({ isTauri: isTauriMock }));
vi.mock('@tauri-apps/plugin-updater', () => ({ check: checkMock }));

type UpdatesModule = typeof import('./updates.js');

async function freshModule(): Promise<UpdatesModule> {
	vi.resetModules();
	return import('./updates.js');
}

beforeEach(() => {
	browserMock.value = true;
	isTauriMock.mockReturnValue(true);
	checkMock.mockReset();
	vi.stubEnv('VITE_E2E_MOCK', '');
	vi.stubEnv('VITE_ENABLE_AUTO_UPDATE_CHECK', '1');
	vi.stubEnv('PROD', true);
});

afterEach(() => {
	vi.unstubAllEnvs();
	vi.restoreAllMocks();
});

describe('checkForUpdateAndPrompt (startup, background)', () => {
	it('fails silently — no failure dialog, just a console warning', async () => {
		const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
		checkMock.mockRejectedValue(new Error('Could not fetch a valid release JSON from the remote'));

		const mod = await freshModule();
		await mod.checkForUpdateAndPrompt();

		// The whole point of the fix: a transient/no-release startup failure must
		// NOT surface the prominent dialog (it would announce on every cold start).
		expect(get(mod.updateFailureState)).toEqual({ status: 'hidden' });
		expect(get(mod.updatePromptState)).toEqual({ status: 'hidden' });
		// warn called proves the catch branch actually ran (the PROD/flag gate passed).
		expect(warn).toHaveBeenCalledOnce();
	});

	it('still prompts when an update is available', async () => {
		checkMock.mockResolvedValue({
			version: '9.9.9',
			currentVersion: '0.1.0',
			rawJson: {}
		});

		const mod = await freshModule();
		await mod.checkForUpdateAndPrompt();

		expect(get(mod.updatePromptState).status).toBe('available');
		expect(get(mod.updateFailureState)).toEqual({ status: 'hidden' });
	});
});

describe('checkForUpdateManually (user-initiated)', () => {
	it('surfaces the failure dialog when the check fails', async () => {
		checkMock.mockRejectedValue(new Error('network down'));

		const mod = await freshModule();
		const result = await mod.checkForUpdateManually();

		expect(result.status).toBe('failed');
		expect(get(mod.updateFailureState).status).toBe('failed');
	});
});
