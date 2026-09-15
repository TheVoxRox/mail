import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

/*
 * Runtime guard, the same shape as the setTitle one in windowTitle.test.ts.
 * tray.ts (tray menu actions) and updates.ts (download progress) subscribe with
 * listen(), which Tauri gates on core:event:allow-listen; the unsubscribe it
 * returns needs core:event:allow-unlisten. Both suites mock listen, so they
 * never reached that gate: in the real shell the call was denied, both callers
 * swallow the rejection by design, and the tray menu items and the progress bar
 * went dead without an error anyone could see.
 */
describe('event listen capability', () => {
	const capability = JSON.parse(
		readFileSync(new URL('../../src-tauri/capabilities/default.json', import.meta.url), 'utf8')
	) as { permissions: unknown[] };

	it.each(['core:event:allow-listen', 'core:event:allow-unlisten'])('grants %s', (permission) => {
		expect(capability.permissions).toContain(permission);
	});
});
