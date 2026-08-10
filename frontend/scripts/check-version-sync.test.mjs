import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createGateRepo } from './test-support/gate-repo.mjs';

/*
 * Five files across three build systems have to agree on one version. The
 * gate's value is not the comparison — it is that each version is extracted
 * from a format where a lookalike sits nearby: the Cargo manifest has
 * dependency versions, the pom has a parent version, and version.ts is
 * TypeScript rather than data. An extractor that grabs the wrong one reports
 * agreement that is not there.
 */

let repo;

function seed(versions = {}) {
	const v = {
		pkg: '1.2.3',
		tauri: '1.2.3',
		clientTs: '1.2.3',
		cargo: '1.2.3',
		pom: '1.2.3',
		...versions
	};
	repo.write('frontend/package.json', JSON.stringify({ name: 'f', version: v.pkg }, null, '\t'));
	repo.write(
		'frontend/src-tauri/tauri.conf.json',
		JSON.stringify({ version: v.tauri }, null, '\t')
	);
	repo.write('frontend/src/lib/version.ts', `export const CLIENT_VERSION = '${v.clientTs}';\n`);
	repo.write(
		'frontend/src-tauri/Cargo.toml',
		[
			'[package]',
			'name = "app"',
			`version = "${v.cargo}"`,
			'',
			'[dependencies]',
			'serde = { version = "9.9.9" }',
			''
		].join('\n')
	);
	repo.write(
		'backend/pom.xml',
		[
			'<project>',
			'  <parent>',
			'    <groupId>org.springframework.boot</groupId>',
			'    <artifactId>spring-boot-starter-parent</artifactId>',
			'    <version>4.0.0</version>',
			'  </parent>',
			'  <groupId>org.voxrox</groupId>',
			'  <artifactId>mail-backend</artifactId>',
			`  <version>${v.pom}</version>`,
			'</project>',
			''
		].join('\n')
	);
}

beforeEach(() => {
	repo = createGateRepo();
});

afterEach(() => {
	repo.cleanup();
});

describe('check-version-sync', () => {
	it('passes when all five agree', () => {
		seed();

		const result = repo.run('check-version-sync.mjs');

		expect(result.status).toBe(0);
		expect(result.stdout).toContain('App version sync OK: 1.2.3');
	});

	it('fails and lists every file when one drifts', () => {
		seed({ cargo: '1.2.4' });

		const result = repo.run('check-version-sync.mjs');

		expect(result.status).toBe(1);
		expect(result.stderr).toContain('not in sync');
		expect(result.stderr).toContain('frontend/src-tauri/Cargo.toml: 1.2.4');
		expect(result.stderr).toContain('frontend/package.json: 1.2.3');
	});

	/*
	 * The two extractions that can plausibly grab a neighbour: Cargo's
	 * [dependencies] versions and the pom's <parent> version. If either were
	 * read instead, the gate would compare the wrong numbers and could pass on
	 * a genuinely desynchronised repo.
	 */
	it('reads the Cargo package version, not a dependency version', () => {
		seed({ cargo: '7.7.7', pkg: '7.7.7', tauri: '7.7.7', clientTs: '7.7.7', pom: '7.7.7' });

		const result = repo.run('check-version-sync.mjs');

		expect(result.status).toBe(0);
		expect(result.stdout).toContain('7.7.7');
	});

	it('reads the pom project version, not the parent version', () => {
		seed({ pom: '9.9.9' });

		const result = repo.run('check-version-sync.mjs');

		// Would have passed had it read the parent's 4.0.0 or matched by accident.
		expect(result.status).toBe(1);
		expect(result.stderr).toContain('backend/pom.xml: 9.9.9');
	});

	it('fails loudly when the Cargo manifest has no package version to read', () => {
		seed();
		repo.write('frontend/src-tauri/Cargo.toml', '[dependencies]\nserde = "1"\n');

		const result = repo.run('check-version-sync.mjs');

		expect(result.status).not.toBe(0);
		expect(result.output).toContain('[package] version');
	});
});
