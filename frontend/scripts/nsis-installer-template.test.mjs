import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

/*
 * The installer template is compiled by the Tauri bundler, which ships only as
 * a binary, and run on a Windows runner by the two smokes in
 * release-candidate-smoke.yml. What those cannot show before a candidate
 * exists is held here on the template's text: that its own name for the
 * sidecar is the one the bundle installs, and that the sidecar is ended and
 * its folders emptied before the first file is written or deleted. The update
 * smoke (installed-app-update-smoke.mjs) is what proves the effect, once the
 * installed version and the candidate differ.
 */

const here = path.dirname(fileURLToPath(import.meta.url));
const read = (relative) => readFileSync(path.join(here, relative), 'utf8');
const template = read('../src-tauri/windows/nsis-installer.nsi').replaceAll('\r\n', '\n');
const config = JSON.parse(read('../src-tauri/tauri.conf.json'));

function section(name) {
	const start = template.indexOf(`\nSection ${name}\n`);
	expect(start, `Section ${name} is in the template`).toBeGreaterThan(-1);
	return template.slice(start, template.indexOf('\nSectionEnd', start));
}

function expectBefore(body, steps, marker) {
	const end = body.indexOf(marker);
	expect(end, `${marker} is in the section`).toBeGreaterThan(-1);
	for (const step of steps) {
		const at = body.indexOf(step);
		expect(at, `${step} is in the section`).toBeGreaterThan(-1);
		expect(at, `${step} comes before ${marker}`).toBeLessThan(end);
	}
}

describe('nsis-installer.nsi and the sidecar', () => {
	it('names the sidecar launcher after externalBin', () => {
		const [externalBin] = config.bundle.externalBin;

		expect(template).toContain(`!define SIDECARBINARYNAME "${path.posix.basename(externalBin)}"`);
	});

	it('empties exactly the folders the bundle installs the sidecar image into', () => {
		const folders = [...template.matchAll(/RMDir \/r "\$INSTDIR\\([^"]+)"/g)].map((m) => m[1]);

		expect(folders.sort()).toEqual(['app', 'runtime']);
		expect(Object.values(config.bundle.resources)).toEqual(expect.arrayContaining(folders));
	});

	it('ends the sidecar and empties its folders before installing a file', () => {
		expectBefore(
			section('Install'),
			[
				'!insertmacro CheckIfSidecarIsRunning',
				'${If} ${FileExists} "$INSTDIR\\${SIDECARBINARYNAME}.exe"',
				'RMDir /r "$INSTDIR\\app"',
				'RMDir /r "$INSTDIR\\runtime"'
			],
			'File "${MAINBINARYSRCPATH}"'
		);
	});

	it('ends the sidecar before uninstalling a file', () => {
		expectBefore(
			section('Uninstall'),
			['!insertmacro CheckIfSidecarIsRunning'],
			'Delete "$INSTDIR\\${MAINBINARYNAME}.exe"'
		);
	});
});
