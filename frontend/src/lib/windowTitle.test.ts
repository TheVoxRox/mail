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
