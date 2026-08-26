import { describe, expect, it } from 'vitest';
import { codeOnly, withoutAnnotations, withoutComments } from './source-text.mjs';

/*
 * These two functions decide what three gates believe about a file, and the
 * cases below are the ones that were wrong at some point rather than a tour of
 * the syntax: a URL inside a string, SQL inside a Java text block, an
 * annotation standing where a type belongs.
 */

describe('codeOnly', () => {
	it('blanks a line comment but keeps the line', () => {
		expect(codeOnly('a(); // b()\nc();')).toBe('a();       \nc();');
	});

	it('blanks a block comment across lines, keeping the line count', () => {
		const out = codeOnly('a();\n/* b()\n   c() */\nd();');
		expect(out.split('\n')).toHaveLength(4);
		expect(out).not.toContain('b()');
		expect(out).toContain('d();');
	});

	it('blanks string contents, so a name only a string mentions is not code', () => {
		expect(codeOnly('log("countGrouped");')).not.toContain('countGrouped');
	});

	/*
	 * Three quotes read as an empty string followed by an opening one, which
	 * closes at the line break — leaving every line of SQL below it as code.
	 * A @Query holding substr(c.email, …) then declared a method called substr.
	 */
	it('blanks a Java text block whole', () => {
		const out = codeOnly('String q = """\n  SELECT substr(x, 1)\n  """;');
		expect(out).not.toContain('substr');
	});

	it('does not let a quote inside a comment swallow the rest of the file', () => {
		const out = codeOnly("// it's fine\nalive();");
		expect(out).toContain('alive();');
	});

	it('stops an unterminated literal at the line break', () => {
		expect(codeOnly('a("oops\nalive();')).toContain('alive();');
	});

	/*
	 * Cut at the first newline the way a quoted string is, a template literal
	 * leaves every line below its opener reading as code — the same failure the
	 * text-block branch exists for, in the language the other half of the tree
	 * is written in.
	 */
	it('blanks a template literal across its lines', () => {
		const out = codeOnly('const q = `\nSELECT staleName FROM t\nWHERE x = 1`;\nalive();');
		expect(out).not.toContain('staleName');
		expect(out).toContain('alive();');
		expect(out.split('\n')).toHaveLength(4);
	});

	/*
	 * The other half of the same fix, and the direction that costs a false
	 * failure rather than a miss: a helper called only from an interpolation is
	 * still called. Blanking it with the surrounding text made it a name that
	 * had left the codebase, which is what check:rename-residue fails a change
	 * for.
	 */
	it('keeps the code inside an interpolation', () => {
		expect(codeOnly('const label = `size: ${formatSize(bytes)}`;')).toContain('formatSize');
	});

	it('keeps interpolated code that carries braces of its own', () => {
		const out = codeOnly('const l = `${items.map((i) => { return render(i); })} tail ${last()}`;');
		expect(out).toContain('render');
		expect(out).toContain('last');
		expect(out).not.toContain('tail');
	});

	it('blanks a nested template literal but keeps its interpolation', () => {
		const out = codeOnly('const l = `outer ${cond ? `inner ${deep()}` : fallback()}`;');
		expect(out).not.toContain('outer');
		expect(out).not.toContain('inner');
		expect(out).toContain('deep');
		expect(out).toContain('fallback');
	});

	/*
	 * A lone backtick is prose far more often than it is a broken literal — a
	 * Svelte line quoting a command, a fixture with an odd count. Treating it as
	 * an opener would blank the rest of the file, which is the one failure this
	 * scanner must not have.
	 */
	it('leaves a backtick with nothing closing it as an ordinary character', () => {
		expect(codeOnly('render(`unclosed);\nalive();')).toContain('alive();');
	});
});

describe('withoutComments', () => {
	/*
	 * The reason this exists: `'http://example.com'` contains `//`, so a regex
	 * pass deletes the rest of the line. Two test bodies rejecting different
	 * URLs then normalize to the same text and read as copies of each other.
	 */
	it('keeps a URL inside a string intact', () => {
		const source = "expect(reject('http://example.com/api')).toThrow();";
		expect(withoutComments(source)).toBe(source);
	});

	it('still blanks a real comment', () => {
		expect(withoutComments('a(); // note')).toBe('a();        ');
	});

	it('keeps two bodies distinguishable when only their strings differ', () => {
		const first = withoutComments("check('http://a.example/api');");
		const second = withoutComments("check('http://b.example/api');");
		expect(first).not.toBe(second);
	});

	// Duplicate detection compares bodies by content, so a multi-line template
	// literal has to come back byte for byte — text, interpolations and all.
	it('returns a template literal unchanged', () => {
		const source = 'const q = `\n  SELECT ${column} FROM t\n`;';
		expect(withoutComments(source)).toBe(source);
	});
});

describe('withoutAnnotations', () => {
	it('blanks an annotation with arguments', () => {
		expect(withoutAnnotations('@Query("SELECT 1") List<X> find();')).not.toContain('SELECT');
	});

	it('leaves a declaration readable once the annotation is gone', () => {
		expect(withoutAnnotations('public @Nullable String getFoo() {')).toContain('String getFoo()');
	});
});
