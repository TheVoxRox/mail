import path from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createGateRepo } from './test-support/gate-repo.mjs';

const SCRIPT = 'check-unused-java-imports.mjs';

/** Wraps a class body in the package/import preamble every real file has. */
function javaFile({ imports = [], body = '' }) {
	return ['package org.example;', '', ...imports, '', 'class Sample {', body, '}', ''].join('\n');
}

let repo;

beforeEach(() => {
	repo = createGateRepo();
});

afterEach(() => {
	repo.cleanup();
});

describe('check-unused-java-imports', () => {
	it('passes a file whose every import is referenced in code', () => {
		repo.write(
			'backend/src/main/java/org/example/Sample.java',
			javaFile({
				imports: ['import java.util.List;'],
				body: '    List<String> names;'
			})
		);
		repo.commit();

		const result = repo.run(SCRIPT);

		expect(result.status).toBe(0);
		expect(result.stdout).toContain('every import referenced');
	});

	it('fails and names the file, line and symbol of an unused import', () => {
		repo.write(
			'backend/src/main/java/org/example/Sample.java',
			javaFile({
				imports: ['import java.util.List;', 'import java.util.Optional;'],
				body: '    List<String> names;'
			})
		);
		repo.commit();

		const result = repo.run(SCRIPT);

		expect(result.status).toBe(1);
		expect(result.output).toContain('backend/src/main/java/org/example/Sample.java:4 Optional');
		// The used one must not be dragged in with it.
		expect(result.output).not.toContain('List');
	});

	/*
	 * A hook in a linked worktree runs with GIT_DIR exported, and git then takes
	 * the current directory, frontend/, for the top of the tree. The gate asked
	 * git for the root and read frontend/backend/..., which is not there, so a
	 * push from a worktree stopped here.
	 */
	it('reads the repository from a hook that exports GIT_DIR, as a linked worktree does', () => {
		repo.write(
			'backend/src/main/java/org/example/Sample.java',
			javaFile({ imports: ['import java.util.Optional;'] })
		);
		repo.commit();

		const result = repo.run(SCRIPT, [], { env: { GIT_DIR: path.join(repo.root, '.git') } });

		expect(result.status).toBe(1);
		expect(result.output).toContain('backend/src/main/java/org/example/Sample.java:3 Optional');
	});

	/*
	 * The false-positive guards. Each of these is a shape that a naive
	 * "is the word anywhere in the file" check gets wrong, and getting one
	 * wrong means the gate blocks a correct commit.
	 */

	it('counts a javadoc {@link} as a reference, because the import resolves it', () => {
		repo.write(
			'backend/src/main/java/org/example/Sample.java',
			javaFile({
				imports: ['import java.util.Optional;'],
				body: '    /** See {@link Optional#empty()} for the contract. */\n    void doc() {}'
			})
		);
		repo.commit();

		expect(repo.run(SCRIPT).status).toBe(0);
	});

	it('counts an @throws target as a reference', () => {
		repo.write(
			'backend/src/main/java/org/example/Sample.java',
			javaFile({
				imports: ['import java.io.IOException;'],
				body: '    /**\n     * @throws IOException when it breaks\n     */\n    void io() {}'
			})
		);
		repo.commit();

		expect(repo.run(SCRIPT).status).toBe(0);
	});

	it('counts an annotation usage as a reference', () => {
		repo.write(
			'backend/src/main/java/org/example/Sample.java',
			javaFile({
				imports: ['import java.lang.annotation.Native;'],
				body: '    @Native\n    static final int X = 1;'
			})
		);
		repo.commit();

		expect(repo.run(SCRIPT).status).toBe(0);
	});

	it('counts a static import used as a bare call', () => {
		repo.write(
			'backend/src/main/java/org/example/Sample.java',
			javaFile({
				imports: ['import static java.util.Objects.requireNonNull;'],
				body: '    Object o = requireNonNull(null);'
			})
		);
		repo.commit();

		expect(repo.run(SCRIPT).status).toBe(0);
	});

	it('counts a method reference on the imported type', () => {
		repo.write(
			'backend/src/main/java/org/example/Sample.java',
			javaFile({
				imports: ['import java.util.Objects;'],
				body: '    java.util.function.Predicate<Object> p = Objects::isNull;'
			})
		);
		repo.commit();

		expect(repo.run(SCRIPT).status).toBe(0);
	});

	it('counts a type argument as a reference, also after a qualified call', () => {
		repo.write(
			'backend/src/main/java/org/example/Sample.java',
			javaFile({
				imports: ['import java.util.UUID;', 'import java.time.Instant;'],
				body: [
					'    java.util.Map<String, java.util.List<UUID>> ids;',
					// A dot two characters back is not a qualifier of the name.
					'    Object empty = java.util.Collections.<Instant>emptyList();'
				].join('\n')
			})
		);
		repo.commit();

		expect(repo.run(SCRIPT).status).toBe(0);
	});

	it('counts the imported type qualifying a member of its own', () => {
		repo.write(
			'backend/src/main/java/org/example/Sample.java',
			javaFile({
				imports: ['import java.time.Duration;'],
				body: '    Object d = Duration.ZERO;'
			})
		);
		repo.commit();

		expect(repo.run(SCRIPT).status).toBe(0);
	});

	it('counts an unqualified use even where the same name is also spelled out in full', () => {
		repo.write(
			'backend/src/main/java/org/example/Sample.java',
			javaFile({
				imports: ['import java.util.List;'],
				body: '    java.util.List<String> a;\n    List<String> b;'
			})
		);
		repo.commit();

		expect(repo.run(SCRIPT).status).toBe(0);
	});

	it('counts a type named in the parameters of a javadoc member reference', () => {
		repo.write(
			'backend/src/main/java/org/example/Sample.java',
			javaFile({
				imports: ['import java.time.Duration;'],
				body: '    /** Same as {@link #run(Duration)} with no wait. */\n    void now() {}'
			})
		);
		repo.commit();

		expect(repo.run(SCRIPT).status).toBe(0);
	});

	/*
	 * Each javadoc reference is judged on its own. A reference ending a sentence
	 * with its full stop must not read as the qualifier of the next one.
	 */
	it('does not read one javadoc reference as the qualifier of the next', () => {
		repo.write(
			'backend/src/main/java/org/example/Sample.java',
			javaFile({
				imports: ['import java.io.IOException;', 'import java.util.Optional;'],
				body: [
					'    /**',
					'     * @throws IOException.',
					'     * {@link Optional}',
					'     */',
					'    void io() {}'
				].join('\n')
			})
		);
		repo.commit();

		expect(repo.run(SCRIPT).status).toBe(0);
	});

	it('does not treat a longer word containing the symbol as a reference', () => {
		repo.write(
			'backend/src/main/java/org/example/Sample.java',
			javaFile({
				imports: ['import java.util.List;'],
				body: '    int Listener = 1;'
			})
		);
		repo.commit();

		const result = repo.run(SCRIPT);

		expect(result.status).toBe(1);
		expect(result.output).toContain('List');
	});

	/*
	 * The other direction: things that look like a reference but are not, and
	 * so must still be reported. An import Java does not need is dead weight
	 * whatever prose surrounds it.
	 */

	it('does not accept a mention in prose as a reference', () => {
		repo.write(
			'backend/src/main/java/org/example/Sample.java',
			javaFile({
				imports: ['import java.util.Optional;'],
				body: '    // We used to return an Optional here, but no longer do.\n    void plain() {}'
			})
		);
		repo.commit();

		const result = repo.run(SCRIPT);

		expect(result.status).toBe(1);
		expect(result.output).toContain('Optional');
	});

	it('does not accept an occurrence inside a string literal as a reference', () => {
		repo.write(
			'backend/src/main/java/org/example/Sample.java',
			javaFile({
				imports: ['import java.util.Optional;'],
				body: '    String s = "Optional was here";'
			})
		);
		repo.commit();

		const result = repo.run(SCRIPT);

		expect(result.status).toBe(1);
		expect(result.output).toContain('Optional');
	});

	it('does not accept an occurrence inside a text block as a reference', () => {
		repo.write(
			'backend/src/main/java/org/example/Sample.java',
			javaFile({
				imports: ['import java.util.Optional;'],
				body: '    String s = """\n        Optional\n        """;'
			})
		);
		repo.commit();

		const result = repo.run(SCRIPT);

		expect(result.status).toBe(1);
		expect(result.output).toContain('Optional');
	});

	/*
	 * A qualified name is resolved through its qualifier, never through an
	 * import of its last segment. A dot is a word boundary, so a plain
	 * `\bName\b` read the tail of a fully qualified name as a use; this is the
	 * shape #939 found, in a test that spelled the type out twice and imported
	 * it as well.
	 */
	it('does not accept the tail of a fully qualified name as a reference', () => {
		repo.write(
			'backend/src/main/java/org/example/Sample.java',
			javaFile({
				imports: ['import org.springframework.data.domain.Pageable;'],
				body: '    Object p = any(org.springframework.data.domain.Pageable.class);'
			})
		);
		repo.commit();

		const result = repo.run(SCRIPT);

		expect(result.status).toBe(1);
		expect(result.output).toContain('Sample.java:3 Pageable');
	});

	it('does not accept a qualified name broken across lines as a reference', () => {
		repo.write(
			'backend/src/main/java/org/example/Sample.java',
			javaFile({
				imports: ['import java.util.Optional;'],
				body: '    java.util.\n            Optional<String> o;'
			})
		);
		repo.commit();

		const result = repo.run(SCRIPT);

		expect(result.status).toBe(1);
		expect(result.output).toContain('Sample.java:3 Optional');
	});

	it('does not accept a nested type reached through its outer type as a use of its own import', () => {
		repo.write(
			'backend/src/main/java/org/example/Sample.java',
			javaFile({
				imports: ['import java.util.Map;', 'import java.util.Map.Entry;'],
				body: '    Map.Entry<String, String> e;'
			})
		);
		repo.commit();

		const result = repo.run(SCRIPT);

		expect(result.status).toBe(1);
		expect(result.output).toContain('Sample.java:4 Entry');
		// The outer type is what the code does use.
		expect(result.output).not.toContain('Sample.java:3 Map');
	});

	it('does not accept a qualified call as a use of a static import', () => {
		repo.write(
			'backend/src/main/java/org/example/Sample.java',
			javaFile({
				imports: ['import java.util.Objects;', 'import static java.util.Objects.requireNonNull;'],
				body: '    Object o = Objects.requireNonNull(null);'
			})
		);
		repo.commit();

		const result = repo.run(SCRIPT);

		expect(result.status).toBe(1);
		expect(result.output).toContain('Sample.java:4 requireNonNull');
	});

	it('does not accept a method reference as a use of a static import', () => {
		repo.write(
			'backend/src/main/java/org/example/Sample.java',
			javaFile({
				imports: ['import java.util.Objects;', 'import static java.util.Objects.isNull;'],
				body: '    java.util.function.Predicate<Object> p = Objects::isNull;'
			})
		);
		repo.commit();

		const result = repo.run(SCRIPT);

		expect(result.status).toBe(1);
		expect(result.output).toContain('Sample.java:4 isNull');
	});

	it('does not accept a fully qualified javadoc reference as a reference', () => {
		repo.write(
			'backend/src/main/java/org/example/Sample.java',
			javaFile({
				imports: ['import java.util.Optional;', 'import java.time.Duration;'],
				body: [
					'    /**',
					'     * See {@link java.util.Optional#empty()}.',
					'     * @see java.time.Duration',
					'     */',
					'    void doc() {}'
				].join('\n')
			})
		);
		repo.commit();

		const result = repo.run(SCRIPT);

		expect(result.status).toBe(1);
		expect(result.output).toContain('Sample.java:3 Optional');
		expect(result.output).toContain('Sample.java:4 Duration');
	});

	/*
	 * A `//` inside a string does not open a comment. The first version of this
	 * check was a regex and could not tell the two apart; this is the shape
	 * that catches a relapse.
	 */
	it('does not mistake a // inside a string literal for a comment', () => {
		repo.write(
			'backend/src/main/java/org/example/Sample.java',
			javaFile({
				imports: ['import java.util.List;'],
				body: '    String url = "https://example.com";\n    List<String> names;'
			})
		);
		repo.commit();

		expect(repo.run(SCRIPT).status).toBe(0);
	});

	it('does not mistake a quote inside a comment for a string', () => {
		repo.write(
			'backend/src/main/java/org/example/Sample.java',
			javaFile({
				imports: ['import java.util.List;'],
				body: "    // it's fine\n    List<String> names;"
			})
		);
		repo.commit();

		expect(repo.run(SCRIPT).status).toBe(0);
	});

	/* Imports this check cannot see a single symbol in are left alone. */

	it('ignores a wildcard import', () => {
		repo.write(
			'backend/src/main/java/org/example/Sample.java',
			javaFile({ imports: ['import java.util.*;'], body: '    int x = 1;' })
		);
		repo.commit();

		expect(repo.run(SCRIPT).status).toBe(0);
	});

	it('ignores a JEP 511 module import', () => {
		repo.write(
			'backend/src/main/java/org/example/Sample.java',
			javaFile({ imports: ['import module java.base;'], body: '    int x = 1;' })
		);
		repo.commit();

		expect(repo.run(SCRIPT).status).toBe(0);
	});

	it('reports every offender rather than stopping at the first', () => {
		repo.write(
			'backend/src/main/java/org/example/A.java',
			javaFile({ imports: ['import java.util.Optional;'], body: '    int x = 1;' })
		);
		repo.write(
			'backend/src/main/java/org/example/B.java',
			javaFile({ imports: ['import java.util.List;'], body: '    int x = 1;' })
		);
		repo.commit();

		const result = repo.run(SCRIPT);

		expect(result.status).toBe(1);
		expect(result.output).toContain('A.java:3 Optional');
		expect(result.output).toContain('B.java:3 List');
	});

	it('ignores untracked files', () => {
		repo.write(
			'backend/src/main/java/org/example/Sample.java',
			javaFile({ imports: ['import java.util.List;'], body: '    List<String> names;' })
		);
		repo.commit();
		// Written but never committed, so `git ls-files` does not list it.
		repo.write(
			'backend/src/main/java/org/example/Untracked.java',
			javaFile({ imports: ['import java.util.Optional;'], body: '    int x = 1;' })
		);

		expect(repo.run(SCRIPT).status).toBe(0);
	});
});
