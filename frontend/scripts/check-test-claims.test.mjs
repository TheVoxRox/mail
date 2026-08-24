import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createGateRepo } from './test-support/gate-repo.mjs';

/*
 * Both rules are about a test that no longer says what its name says, so the
 * cases split the same way: bodies that became copies, and tests that stopped
 * running. The quiet cases matter as much — two provider suites are supposed
 * to share bodies, and a skip that states its condition is a fact, not a
 * deferral.
 */

const JAVA = 'backend/src/test/java/org/voxrox/mailbackend/ContactRepositoryIT.java';
const OTHER_JAVA = 'backend/src/test/java/org/voxrox/mailbackend/MicrosoftTokenServiceTest.java';
const TS = 'frontend/src/lib/api/session.test.ts';

let repo;

function javaTests(body, file = JAVA) {
	repo.write(file, `package org.voxrox.mailbackend;\n\nclass Suite {\n${body}\n}\n`);
}

function javaTest(name, body) {
	return `    @Test\n    void ${name}() {\n${body}\n    }\n`;
}

function tsTests(body) {
	repo.write(
		TS,
		`import { describe, expect, it } from 'vitest';\n\ndescribe('suite', () => {\n${body}\n});\n`
	);
}

function tsTest(title, body, modifier = '') {
	return `\tit${modifier}('${title}', () => {\n${body}\n\t});\n`;
}

const run = () => repo.run('check-test-claims.mjs', []);

/** Long enough to clear the boilerplate floor the gate applies. */
const BODY_A =
	'        repository.saveAndFlush(newContact("shared@x.cz", "A"));\n' +
	'        assertThat(repository.findAll()).hasSize(2);';
const BODY_B =
	'        repository.saveAndFlush(newContact("other@x.cz", "B"));\n' +
	'        assertThat(repository.findAll()).hasSize(1);';

beforeEach(() => {
	repo = createGateRepo();
});

afterEach(() => {
	repo.cleanup();
});

describe('check-test-claims', () => {
	it('passes when every body in the file differs', () => {
		javaTests(javaTest('first', BODY_A) + javaTest('second', BODY_B));
		repo.commit('distinct');

		const result = run();

		expect(result.status).toBe(0);
		expect(result.output).toContain('no duplicate bodies');
	});

	it('reports two tests in one file with identical bodies', () => {
		javaTests(
			javaTest('sameEmailOnDifferentContacts', BODY_A) + javaTest('sameEmailOtherAccount', BODY_A)
		);
		repo.commit('duplicate');

		const result = run();

		expect(result.status).toBe(1);
		expect(result.output).toContain('sameEmailOtherAccount');
		expect(result.output).toContain('same body');
	});

	/*
	 * The Google and Microsoft token suites share sixteen bodies on purpose:
	 * one contract, two providers. Comparing across files would report every
	 * one of them, which is how a gate gets switched off.
	 */
	it('allows the same body in two different files', () => {
		javaTests(javaTest('refreshFails', BODY_A));
		javaTests(javaTest('refreshFails', BODY_A), OTHER_JAVA);
		repo.commit('parallel suites');

		const result = run();

		expect(result.status).toBe(0);
	});

	it('ignores bodies too short to be a claim', () => {
		javaTests(javaTest('a', '        service.run();') + javaTest('b', '        service.run();'));
		repo.commit('boilerplate');

		const result = run();

		expect(result.status).toBe(0);
	});

	/*
	 * Comments out, strings in: two bodies rejecting different URLs differ only
	 * inside their literals, and `http://` starts with the comment marker.
	 */
	it('keeps two tests distinct when only the URL in them differs', () => {
		tsTests(
			tsTest(
				'rejects non-loopback hosts',
				"\t\texpect(() => validate('http://example.com:51234/api')).toThrow(SessionLoadError);"
			) +
				tsTest(
					'rejects path other than /api',
					"\t\texpect(() => validate('http://127.0.0.1:51234/nope')).toThrow(SessionLoadError);"
				)
		);
		repo.commit('urls');

		const result = run();

		expect(result.status).toBe(0);
	});

	it('reports an unconditional it.skip', () => {
		tsTests(
			tsTest('runs', "\t\texpect(validate('http://127.0.0.1/api')).toBe(true);") +
				tsTest('is off', "\t\texpect(validate('http://127.0.0.1/api')).toBe(true);", '.skip')
		);
		repo.commit('skip');

		const result = run();

		expect(result.status).toBe(1);
		expect(result.output).toContain('.skip');
		expect(result.output).toContain('switched off');
	});

	it('accepts a skip that carries a reason above it', () => {
		tsTests(
			tsTest('runs', "\t\texpect(validate('http://127.0.0.1/api')).toBe(true);") +
				'\t// test-skip: the fixture needs a signed installer, built only on release.\n' +
				tsTest('is off', "\t\texpect(validate('http://127.0.0.1/api')).toBe(true);", '.skip')
		);
		repo.commit('justified skip');

		const result = run();

		expect(result.status).toBe(0);
	});

	/*
	 * skipIf states its condition in the call — "this machine cannot run me" is
	 * a fact about the environment, not a test someone parked.
	 */
	it('stays quiet on a conditional skipIf', () => {
		tsTests(
			tsTest('runs', "\t\texpect(validate('http://127.0.0.1/api')).toBe(true);") +
				tsTest(
					'conditional',
					"\t\texpect(validate('http://127.0.0.1/api')).toBe(true);",
					'.skipIf(!canSymlink)'
				)
		);
		repo.commit('skipIf');

		const result = run();

		expect(result.status).toBe(0);
	});

	it('reports a bare @Disabled but not one with a reason', () => {
		javaTests(`    @Test\n    @Disabled\n    void parked() {\n${BODY_A}\n    }\n`);
		repo.commit('bare disabled');
		expect(run().status).toBe(1);

		javaTests(
			`    @Test\n    @Disabled("Needs a live IMAP server; covered by the release smoke.")\n    void parked() {\n${BODY_A}\n    }\n`
		);
		repo.commit('justified disabled');
		expect(run().status).toBe(0);
	});

	it('does not count a skip named inside a comment or a string', () => {
		tsTests(
			'\t// Never write it.skip here without a reason.\n' +
				tsTest('runs', "\t\texpect(reason).toBe('it.skip is not allowed');")
		);
		repo.commit('mentions');

		const result = run();

		expect(result.status).toBe(0);
	});
});
