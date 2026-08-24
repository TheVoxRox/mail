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
});

describe('withoutAnnotations', () => {
	it('blanks an annotation with arguments', () => {
		expect(withoutAnnotations('@Query("SELECT 1") List<X> find();')).not.toContain('SELECT');
	});

	it('leaves a declaration readable once the annotation is gone', () => {
		expect(withoutAnnotations('public @Nullable String getFoo() {')).toContain('String getFoo()');
	});
});
