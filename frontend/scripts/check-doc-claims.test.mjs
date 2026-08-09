import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createGateRepo } from './test-support/gate-repo.mjs';

/*
 * Version numbers in prose rot silently: nobody re-reads the README when they
 * bump a major. This gate re-derives each one from the build files and
 * compares. The property that makes it worth having — and the one tested here
 * — is that a claim pattern which stops matching is itself a failure, so
 * rewording a sentence cannot quietly retire the check that guarded it.
 */

let repo;

const DOC_FILES = [
	'README.md',
	'CONTRIBUTING.md',
	'SECURITY.md',
	'backend/README.md',
	'frontend/README.md',
	'frontend/END_USER_README.md'
];

function seed({ springBoot = '4.0.0', java = '25', node = '26', docText } = {}) {
	repo.write(
		'frontend/package.json',
		JSON.stringify(
			{
				name: 'f',
				devDependencies: {
					'@sveltejs/kit': '^2.0.0',
					svelte: '^5.0.0',
					tailwindcss: '^4.0.0'
				},
				dependencies: { '@tauri-apps/api': '^2.0.0' }
			},
			null,
			'\t'
		)
	);
	repo.write(
		'backend/pom.xml',
		[
			'<project>',
			'  <parent>',
			'    <artifactId>spring-boot-starter-parent</artifactId>',
			`    <version>${springBoot}</version>`,
			'  </parent>',
			'  <properties>',
			`    <java.version>${java}</java.version>`,
			'  </properties>',
			'</project>',
			''
		].join('\n')
	);
	repo.write('.nvmrc', `${node}\n`);

	/*
	 * The computed half of the gate: a CI workflow to count jobs in, one
	 * controller to enumerate, and the audit prose that states both. Without
	 * these the script throws on a missing file rather than reaching a verdict.
	 */
	repo.write(
		'.github/workflows/ci.yml',
		['name: CI', 'on:', '  push:', 'jobs:', '  lint:', '    runs-on: ubuntu-latest', ''].join('\n')
	);
	repo.write(
		'backend/src/main/java/org/voxrox/AController.java',
		'@RestController\n@Validated\nclass AController {}\n'
	);
	repo.write(
		'docs/API_SURFACE_AUDIT.md',
		[
			'Reviewed all 1 `@RestController`s (1 public + the 0 `/api/internal` ones).',
			'',
			'1 of the 1 controllers are `@Validated`.',
			'',
			'    # 1 controllers',
			'    # 0 internal (1 public)',
			'    # 1 @Validated',
			'    # 0 hidden',
			''
		].join('\n')
	);

	const text = docText ?? `Built on Spring Boot ${springBoot[0]}, Java ${java}, Node ${node}.\n`;
	for (const doc of DOC_FILES) repo.write(doc, text);
	repo.write(
		'frontend/README.md',
		`${text}
The workflow is split into 1 jobs, including \`lint\`.
`
	);

	// The controller enumeration is derived from `git ls-files`, so an
	// uncommitted fixture would count zero controllers against prose claiming
	// one — a failure about the fixture rather than about the gate.
	repo.commit('fixture');
}

beforeEach(() => {
	repo = createGateRepo();
});

afterEach(() => {
	repo.cleanup();
});

describe('check-doc-claims', () => {
	it('passes when the prose matches the build files', () => {
		seed();

		const result = repo.run('check-doc-claims.mjs');

		expect(result.status).toBe(0);
		expect(result.stdout).toContain('Doc claims OK');
	});

	it('fails when a stack version in prose has fallen behind', () => {
		seed({ docText: 'Built on Spring Boot 3, Java 25, Node 26.\n' });

		const result = repo.run('check-doc-claims.mjs');

		expect(result.status).toBe(1);
		expect(result.stderr).toContain('Spring Boot');
	});

	it('fails on a stale Java version', () => {
		seed({ docText: 'Built on Spring Boot 4, Java 21, Node 26.\n' });

		const result = repo.run('check-doc-claims.mjs');

		expect(result.status).toBe(1);
		expect(result.stderr).toContain('Java');
	});

	it('follows the build file rather than the prose when the build moves', () => {
		seed({ java: '27', docText: 'Built on Spring Boot 4, Java 27, Node 26.\n' });

		expect(repo.run('check-doc-claims.mjs').status).toBe(0);
	});

	/*
	 * The forbidden phrases are claims that were true once. "private
	 * development" outlived the repository going public, which is exactly the
	 * kind of sentence no version check would ever catch.
	 */
	it('fails on a phrase the repository has outgrown', () => {
		seed({
			docText: 'Built on Spring Boot 4, Java 25, Node 26.\n\nThis is private development.\n'
		});

		const result = repo.run('check-doc-claims.mjs');

		expect(result.status).toBe(1);
		expect(result.stderr).toContain('public');
	});

	it('fails on a leftover (TBD) placeholder', () => {
		seed({ docText: 'Built on Spring Boot 4, Java 25, Node 26.\n\nLicence: (TBD)\n' });

		const result = repo.run('check-doc-claims.mjs');

		expect(result.status).toBe(1);
		expect(result.stderr).toContain('TBD');
	});

	/*
	 * Falling back to a default when a build file cannot be read would make the
	 * gate compare prose against a guess — worse than not running at all,
	 * because it would report agreement.
	 */
	it('fails loudly when a build file cannot be parsed at all', () => {
		seed();
		repo.write('backend/pom.xml', '<project></project>\n');
		repo.commit('break the pom');

		const result = repo.run('check-doc-claims.mjs');

		expect(result.status).not.toBe(0);
		expect(result.output).toContain('backend/pom.xml');
	});
});
