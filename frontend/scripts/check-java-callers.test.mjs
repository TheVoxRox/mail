import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createGateRepo } from './test-support/gate-repo.mjs';

/*
 * The three verdicts are the point of this gate, so most cases here pin the
 * boundaries between them: nothing calls it, only a test calls it, only its
 * own file calls it. The rest pin what must stay quiet — the framework entry
 * points and the ambiguity a name-based check cannot resolve.
 */

const MAIN = 'backend/src/main/java/org/voxrox/mailbackend';
const TEST = 'backend/src/test/java/org/voxrox/mailbackend';

let repo;

/** A main-source class with the given body. */
function service(body, name = 'ContactService', annotations = '@Service\n') {
	repo.write(
		`${MAIN}/${name}.java`,
		`package org.voxrox.mailbackend;\n\n${annotations}public class ${name} {\n${body}\n}\n`
	);
}

/*
 * The caller class carries @Service and its entry method @Bean, for the same
 * reason the real tree does not trip over its own beans: a fixture is a whole
 * repository to this gate, so un-annotated scaffolding is itself a type
 * nothing references, and every case would fail on its own props.
 */
function caller(body, name = 'ContactCaller') {
	repo.write(
		`${MAIN}/${name}.java`,
		`package org.voxrox.mailbackend;\n\n@Service\npublic class ${name} {\n    @Bean\n${body}\n}\n`
	);
}

function test(body, name = 'ContactServiceTest') {
	repo.write(
		`${TEST}/${name}.java`,
		`package org.voxrox.mailbackend;\n\npublic class ${name} {\n${body}\n}\n`
	);
}

const run = () => repo.run('check-java-callers.mjs', []);

beforeEach(() => {
	repo = createGateRepo();
});

afterEach(() => {
	repo.cleanup();
});

describe('check-java-callers', () => {
	it('passes when another class calls the method', () => {
		service('    public void countGrouped() {\n    }');
		caller('    void use(ContactService s) {\n        s.countGrouped();\n    }');
		repo.commit('called');

		const result = run();

		expect(result.status).toBe(0);
		expect(result.output).toContain('every one reachable');
	});

	it('reports a method nothing names at all as dead', () => {
		service('    public void countGrouped() {\n    }');
		repo.commit('callerless');

		const result = run();

		expect(result.status).toBe(1);
		expect(result.output).toContain('countGrouped');
		expect(result.output).toContain('delete them');
	});

	it('separates a method only a test names', () => {
		service('    public void countGrouped() {\n    }');
		test('    void counts(ContactService s) {\n        s.countGrouped();\n    }');
		repo.commit('test-only');

		const result = run();

		expect(result.status).toBe(1);
		expect(result.output).toContain('only tests name these');
		expect(result.output).not.toContain('delete them');
	});

	it('separates a visible method only its own file names', () => {
		service(
			'    public void entry() {\n        helper();\n    }\n\n' +
				'    public void helper() {\n    }'
		);
		caller('    void use(ContactService s) {\n        s.entry();\n    }');
		repo.commit('internal');

		const result = run();

		expect(result.status).toBe(1);
		expect(result.output).toContain('helper');
		expect(result.output).toContain('too visible');
	});

	/*
	 * The verdict order the first run got wrong: internal use has to win, or a
	 * helper its own class calls reads as "kept alive by tests" and the advice
	 * flips from "narrow it" to "delete it".
	 */
	it('calls a method both its own file and a test name neither dead nor test-only', () => {
		service(
			'    public void entry() {\n        helper();\n    }\n\n' +
				'    public void helper() {\n    }'
		);
		caller('    void use(ContactService s) {\n        s.entry();\n    }');
		test('    void covers(ContactService s) {\n        s.helper();\n    }');
		repo.commit('internal plus test');

		const result = run();

		expect(result.status).toBe(0);
	});

	it('stays quiet on a private method, which SpotBugs already reports', () => {
		service('    private void helper() {\n    }');
		repo.commit('private');

		const result = run();

		expect(result.status).toBe(0);
	});

	it('stays quiet on framework entry points', () => {
		service(
			'    @Bean\n    public String bean() {\n        return "x";\n    }\n\n' +
				'    @GetMapping("/x")\n    public String endpoint() {\n        return "x";\n    }'
		);
		repo.commit('framework');

		const result = run();

		expect(result.status).toBe(0);
	});

	/*
	 * A multi-line annotation's continuation starts with a class name, not an
	 * `@`, which is how a @Configuration class read as un-annotated and got
	 * reported as dead on the first run over the real tree.
	 */
	it('sees an annotation that wraps across lines', () => {
		service(
			'',
			'PropertiesConfig',
			'@Configuration\n@EnableConfigurationProperties({One.class,\n        Two.class})\n'
		);
		repo.commit('wrapped annotation');

		const result = run();

		expect(result.status).toBe(0);
	});

	/*
	 * `return foo(x);` has a declaration's exact shape once `return` counts as
	 * a type, and the phantom it produces is not harmless: the name it invents
	 * has no caller in main, so anything a test happens to name lands in the
	 * test-only bucket. Pinning it needs a test that names the same word —
	 * without one the phantom is invisible and gets filtered as a private
	 * method, which is why the first version of this case passed either way.
	 */
	it('does not read a call in a return statement as a declaration', () => {
		service('    public String entry() {\n        return renderBadge("x");\n    }');
		caller('    void use(ContactService s) {\n        s.entry();\n    }');
		test(
			'    void covers(ContactService s) {\n        s.entry();\n        renderBadge("y");\n    }'
		);
		repo.commit('return call');

		const result = run();

		expect(result.status).toBe(0);
		expect(result.output).not.toContain('renderBadge');
	});

	/*
	 * A text block holds SQL, and SQL holds calls. Treated as three ordinary
	 * quotes it reads as code, and `substr(c.email, …)` inside a @Query became
	 * a declaration the repository never made.
	 */
	it('does not read SQL inside a text block as code', () => {
		service(
			'    @Query(value = """\n' +
				'            SELECT * FROM c\n' +
				'            WHERE substr(c.email, 1, 3) = :p\n' +
				'            """)\n' +
				'    public void query() {\n    }'
		);
		repo.commit('text block');

		const result = run();

		expect(result.output).not.toContain('substr');
	});

	it('reports overloads once, at the first declaration', () => {
		service(
			'    public void countGrouped() {\n        countGrouped(1);\n    }\n\n' +
				'    public void countGrouped(int limit) {\n    }'
		);
		repo.commit('overloads');

		const result = run();

		const mentions = result.output.split('countGrouped').length - 1;
		expect(mentions).toBe(1);
	});

	it('stays quiet when two classes declare the same name and one is called', () => {
		service('    public void countGrouped() {\n    }', 'First');
		service('    public void countGrouped() {\n    }', 'Second');
		caller('    void use(First f) {\n        f.countGrouped();\n    }');
		repo.commit('ambiguous');

		const result = run();

		expect(result.status).toBe(0);
	});

	it('exempts an entity accessor a test calls, but not one nothing calls', () => {
		service(
			'    public String getName() {\n        return "x";\n    }\n\n' +
				'    public void setDead(String v) {\n    }',
			'ContactEntity',
			'@Entity\n'
		);
		test('    void asserts(ContactEntity e) {\n        e.getName();\n    }');
		repo.commit('entity');

		const result = run();

		expect(result.output).not.toContain('getName');
		expect(result.output).toContain('setDead');
	});

	it('accepts a @callerless line comment with a reason', () => {
		service(
			'    // @callerless Named from logback-spring.xml, never from Java.\n' +
				'    public void countGrouped() {\n    }'
		);
		repo.commit('waived');

		const result = run();

		expect(result.status).toBe(0);
		expect(result.output).toContain('waived by @callerless');
	});

	/*
	 * The line comment is the documented form, because javadoc costs an Error
	 * Prone InvalidBlockTag on every build. The gate itself reads the raw lines
	 * above the declaration and is indifferent to which comment carries the
	 * tag; pinned here so that indifference is a decision on record, and so
	 * that moving the five real waivers out of javadoc is demonstrably a
	 * change of style and not of what waives.
	 */
	it('reads the tag out of javadoc as well', () => {
		service(
			'    /**\n     * @callerless Named from logback-spring.xml, never from Java.\n     */\n' +
				'    public void countGrouped() {\n    }'
		);
		repo.commit('waived in javadoc');

		const result = run();

		expect(result.status).toBe(0);
		expect(result.output).toContain('waived by @callerless');
	});

	it('ignores a bare @callerless with no reason', () => {
		service('    // @callerless\n    public void countGrouped() {\n    }');
		repo.commit('bare tag');

		const result = run();

		expect(result.status).toBe(1);
		expect(result.output).toContain('countGrouped');
	});
});
