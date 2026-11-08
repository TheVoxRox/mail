import { describe, expect, it } from 'vitest';
import { codeWithoutComments, differsOnlyInComments } from './comment-only.mjs';

/*
 * The one failure this library must not have is calling a code change a
 * comment, because check:audits then lets it past without anyone reading it.
 * So most cases below are a change that LOOKS like a comment edit: text
 * shaped like a comment inside a literal, a `//` that is not a comment's
 * opener, a comment that javac reads as code. The passing cases are there to
 * prove the library is not simply answering "no" to everything.
 */

describe('Java', () => {
	const file = 'Service.java';

	it('reads a javadoc rewrite and a moved line comment as comments only', () => {
		const before = '/** Old text. */\nclass A {\n  int x = 1; // why\n}\n';
		const after =
			'/**\n * New text,\n * over two lines.\n */\nclass A {\n  // why, moved\n  int x = 1;\n}\n';
		expect(differsOnlyInComments(file, before, after)).toBe(true);
	});

	it('sees a code change made in the same edit as a comment change', () => {
		const before = '/** Old. */\nclass A { int x = 1; }\n';
		const after = '/** New. */\nclass A { int x = 2; }\n';
		expect(differsOnlyInComments(file, before, after)).toBe(false);
	});

	it('sees a change to comment-shaped text inside a string literal', () => {
		expect(differsOnlyInComments(file, 'String s = "// a";', 'String s = "// b";')).toBe(false);
	});

	it('sees a change inside a text block, whose content is a string', () => {
		const before = 'String q = """\n  SELECT 1 -- /* a */\n  """;';
		const after = 'String q = """\n  SELECT 1 -- /* b */\n  """;';
		expect(differsOnlyInComments(file, before, after)).toBe(false);
	});

	/*
	 * Read without the escape, the first \""" closes the block and the second
	 * opens another that the real closer ends, so every delimiter pairs up and
	 * the `// x` between them reads as a comment.
	 */
	it('keeps an escaped quote inside a text block from ending it', () => {
		const before = 'String q = """\n  \\""" // x\n  \\"""\n  """;';
		const after = 'String q = """\n  \\""" // y\n  \\"""\n  """;';
		expect(differsOnlyInComments(file, before, after)).toBe(false);
	});

	it('does not read a slash inside a char literal as a comment', () => {
		expect(
			differsOnlyInComments(file, "char c = '/'; int a = 1;", "char c = '/'; int a = 2;")
		).toBe(false);
	});

	/*
	 * javac decodes \u000a before it lexes, so the "comment" below ends at the
	 * escape and what follows it is code. A lexer that does not decode would
	 * call this edit a comment change.
	 */
	it('refuses a file whose unicode escape javac decodes into a line break', () => {
		const before = 'class A { // \\u000a int x = 1;\n}';
		const after = 'class A { // \\u000a int x = 2;\n}';
		expect(codeWithoutComments(file, before)).toBeNull();
		expect(differsOnlyInComments(file, before, after)).toBe(false);
	});

	it('still judges a file whose unicode escapes are ordinary characters', () => {
		expect(codeWithoutComments(file, 'String s = "\\u00e9"; // x')).toBe('String s = "\\u00e9";');
	});

	it('refuses an unterminated block comment rather than guessing where it ends', () => {
		expect(codeWithoutComments(file, 'int a; /* never closed')).toBeNull();
	});

	it('keeps tokens that only whitespace separates apart', () => {
		expect(differsOnlyInComments(file, 'int a = b - -c;', 'int a = b --c;')).toBe(false);
	});
});

describe('TypeScript and JavaScript', () => {
	it('reads a doc comment and a trailing comment edit as comments only', () => {
		const before = '/** Old. */\nexport const a = 1; // old\n';
		const after = '/**\n * New.\n */\nexport const a = 1; // new\n';
		expect(differsOnlyInComments('a.ts', before, after)).toBe(true);
	});

	/*
	 * The case a scanner gets wrong: the `//` inside this regex literal reads
	 * as a line comment, which would hide everything after it on the line.
	 */
	it('sees a code change after a regex literal that contains //', () => {
		const before = 'const re = /^https?:\\/\\//; run();';
		const after = 'const re = /^https?:\\/\\//; evil();';
		expect(differsOnlyInComments('a.ts', before, after)).toBe(false);
	});

	it('sees a change to comment-shaped text inside a template literal', () => {
		const before = 'export const s = `/* a */ ${x} // b`;';
		const after = 'export const s = `/* a */ ${x} // c`;';
		expect(differsOnlyInComments('a.mjs', before, after)).toBe(false);
	});

	it('keeps literals as written, not as their values', () => {
		expect(differsOnlyInComments('a.ts', 'const n = 0x10;', 'const n = 16;')).toBe(false);
	});

	it('sees a semicolon that changes how the next line parses', () => {
		expect(differsOnlyInComments('a.js', 'a\n(b)', 'a;\n(b)')).toBe(false);
	});

	it('refuses a file that does not parse', () => {
		expect(codeWithoutComments('a.ts', 'const x = {')).toBeNull();
	});

	it('refuses a file carrying a compiler directive in a comment', () => {
		expect(codeWithoutComments('a.ts', '/// <reference types="node" />\nlet a;')).toBeNull();
	});
});

describe('.properties', () => {
	const file = 'application.properties';

	it('reads edited and added comment lines and blank lines as comments only', () => {
		const before = '# Old.\nmail.a=1\n\n! old\nmail.b=2\n';
		const after = '# New,\n# over two lines.\nmail.a=1\nmail.b=2\n';
		expect(differsOnlyInComments(file, before, after)).toBe(true);
	});

	it('sees a value change', () => {
		expect(differsOnlyInComments(file, '# c\nmail.a=1\n', '# c\nmail.a=2\n')).toBe(false);
	});

	it('reads a #-line continuing a value as part of the value', () => {
		const before = 'mail.a=one, \\\n  # two\n';
		const after = 'mail.a=one, \\\n  # three\n';
		expect(differsOnlyInComments(file, before, after)).toBe(false);
	});

	it('treats an even run of backslashes as a value ending, not a continuation', () => {
		expect(differsOnlyInComments(file, 'mail.a=x\\\\\n# a\n', 'mail.a=x\\\\\n# b\n')).toBe(true);
	});

	/*
	 * Spring Boot splits a properties file into documents at #---, which
	 * decides which keys apply together.
	 */
	it('sees a Spring document separator added', () => {
		expect(differsOnlyInComments(file, 'a=1\nb=2\n', 'a=1\n#---\nb=2\n')).toBe(false);
	});

	it('reads a line led by a no-break space as a key, not as a comment', () => {
		expect(differsOnlyInComments(file, 'a=1\n # x\n', 'a=1\n # y\n')).toBe(false);
	});
});

describe('languages it does not read', () => {
	it.each(['a.svelte', 'lib.rs', 'release.yml', 'auth-finished.html', 'tauri.conf.json'])(
		'compares %s byte for byte',
		(file) => {
			expect(codeWithoutComments(file, '// x\n')).toBeNull();
			expect(differsOnlyInComments(file, '// a\n', '// b\n')).toBe(false);
		}
	);
});
