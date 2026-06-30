import { readFileSync } from 'node:fs';
import { describe, expect, it, vi } from 'vitest';
import { setNativeWindowTitle, type NativeWindowTitleDeps } from './windowTitle.js';

function deps(over: Partial<NativeWindowTitleDeps> = {}): {
	deps: NativeWindowTitleDeps;
	setTitle: ReturnType<typeof vi.fn>;
} {
	const setTitle = vi.fn<(t: string) => Promise<void>>().mockResolvedValue(undefined);
	return {
		setTitle,
		deps: {
			isTauri: () => true,
			getCurrentWindow: () => ({ setTitle }),
			...over
		}
	};
}

describe('setNativeWindowTitle', () => {
	it('does not touch the window when not running under Tauri', () => {
		const { deps: d, setTitle } = deps({ isTauri: () => false });
		setNativeWindowTitle('VoxRox Mail', d);
		expect(setTitle).not.toHaveBeenCalled();
	});

	it('sets the native window title under Tauri', () => {
		const { deps: d, setTitle } = deps();
		setNativeWindowTitle('VoxRox Mail (loading)', d);
		expect(setTitle).toHaveBeenCalledWith('VoxRox Mail (loading)');
	});

	it('swallows a rejected setTitle (no native window) instead of throwing', () => {
		const setTitle = vi
			.fn<(t: string) => Promise<void>>()
			.mockRejectedValue(new Error('no window'));
		const d: NativeWindowTitleDeps = {
			isTauri: () => true,
			getCurrentWindow: () => ({ setTitle })
		};
		expect(() => setNativeWindowTitle('VoxRox Mail', d)).not.toThrow();
	});
});

/*
 * Runtime guard: setTitle() is gated by Tauri's permission system. The
 * core:window:default set only grants the title *getter*, so without an
 * explicit core:window:allow-set-title the real (non-mocked) IPC call is
 * denied and rejects — which setNativeWindowTitle deliberately swallows,
 * leaving the window stuck on the Rust-set loading title. The unit tests
 * above mock setTitle and never hit that gate, so we pin the capability here.
 */
describe('window setTitle capability', () => {
	it('grants core:window:allow-set-title so the loading title can be replaced', () => {
		const capability = JSON.parse(
			readFileSync(new URL('../../src-tauri/capabilities/default.json', import.meta.url), 'utf8')
		) as { permissions: unknown[] };
		expect(capability.permissions).toContain('core:window:allow-set-title');
	});
});
