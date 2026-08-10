import { mkdirSync, mkdtempSync, rmSync, utimesSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import {
	checkSidecarFreshness,
	describeStaleness,
	hashBackendSources,
	HASH_FILE_NAME,
	recordBackendSourceHash
} from './lib/sidecar-staleness.mjs';

/*
 * The fixture is a directory tree, not a git repo: the check deliberately hashes
 * the working tree, so that an uncommitted edit counts as staleness too.
 */

let root;

function write(relative, contents = 'x') {
	const full = path.join(root, relative);
	mkdirSync(path.dirname(full), { recursive: true });
	writeFileSync(full, contents);
	return full;
}

const JAR = 'frontend/src-tauri/binaries/app/mail-backend-0.1.0.jar';

/** A repo whose sidecar was synced from the sources currently on disk. */
async function syncedFixture() {
	write('backend/src/main/java/org/voxrox/Service.java', 'class Service {}');
	write('backend/pom.xml', '<project/>');
	write(JAR);
	await recordBackendSourceHash(root);
}

beforeEach(() => {
	root = mkdtempSync(path.join(os.tmpdir(), 'voxrox-sidecar-'));
});

afterEach(() => {
	rmSync(root, { recursive: true, force: true });
});

describe('sidecar freshness', () => {
	it('reports a missing sidecar rather than pretending it is fresh', async () => {
		write('backend/src/main/java/Service.java');

		const result = await checkSidecarFreshness(root);

		expect(result.status).toBe('missing');
		expect(describeStaleness(result)).toMatchObject({ fatal: true });
		expect(describeStaleness(result).text).toContain('No packaged sidecar found');
	});

	it('warns without blocking when the sidecar predates the check', async () => {
		write('backend/src/main/java/Service.java');
		write(JAR);

		const result = await checkSidecarFreshness(root);

		// No recorded hash is missing bookkeeping, not proof of staleness — a hard
		// stop here would block every dev run until an unrelated re-sync.
		expect(result.status).toBe('unknown');
		expect(describeStaleness(result).fatal).toBe(false);
	});

	it('accepts a sidecar synced from the sources on disk', async () => {
		await syncedFixture();

		const result = await checkSidecarFreshness(root);

		expect(result.status).toBe('ok');
		expect(describeStaleness(result)).toBeNull();
	});

	it('flags an edited backend source', async () => {
		await syncedFixture();
		write('backend/src/main/java/org/voxrox/Service.java', 'class Service { void added() {} }');

		const result = await checkSidecarFreshness(root);

		expect(result.status).toBe('stale');
		const report = describeStaleness(result);
		expect(report.fatal).toBe(true);
		// The report has to carry the way out, not just the verdict.
		expect(report.text).toContain('package-sidecar-dev-windows.ps1');
		expect(report.text).toContain('sidecar:sync:windows');
		expect(report.text).toContain('MAIL_ALLOW_STALE_SIDECAR=1');
	});

	it('flags a new backend source, not only a changed one', async () => {
		await syncedFixture();
		write('backend/src/main/java/org/voxrox/Extra.java', 'class Extra {}');

		expect((await checkSidecarFreshness(root)).status).toBe('stale');
	});

	it('flags a changed pom', async () => {
		await syncedFixture();
		write('backend/pom.xml', '<project><modelVersion/></project>');

		expect((await checkSidecarFreshness(root)).status).toBe('stale');
	});

	it('survives a checkout that rewrites timestamps without changing content', async () => {
		await syncedFixture();
		// What git does on pull / branch switch: same bytes, new mtime. The mtime
		// version of this check called that stale and cost a rebuild every time.
		const touched = path.join(root, 'backend/src/main/java/org/voxrox/Service.java');
		const later = Date.now() / 1000 + 3600;
		utimesSync(touched, later, later);

		expect((await checkSidecarFreshness(root)).status).toBe('ok');
	});

	it('does not depend on backend/target, which is rebuilt constantly', async () => {
		await syncedFixture();
		write('backend/src/main/target/classes/Service.class', 'anything');

		expect((await checkSidecarFreshness(root)).status).toBe('ok');
	});

	it('changes the digest when a file is renamed, not only when bytes change', async () => {
		write('backend/src/main/java/A.java', 'same bytes');
		const before = await hashBackendSources(root);
		rmSync(path.join(root, 'backend/src/main/java/A.java'));
		write('backend/src/main/java/B.java', 'same bytes');

		expect((await hashBackendSources(root)).hash).not.toBe(before.hash);
	});

	it('writes the digest next to the jar under the documented name', async () => {
		await syncedFixture();

		const recorded = await hashBackendSources(root);
		const written = (
			await import('node:fs/promises').then((fs) =>
				fs.readFile(path.join(root, 'frontend/src-tauri/binaries/app', HASH_FILE_NAME), 'utf8')
			)
		).trim();

		expect(written).toBe(recorded.hash);
	});
});
