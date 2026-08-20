import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createGateRepo } from './test-support/gate-repo.mjs';

/*
 * The gate is five regexes, so the tests that earn their keep are the
 * boundaries: the thing it must catch, and the near-identical thing it must
 * not. Two of those boundaries are the reason the rules are written the way
 * they are — a token tint looks like a CSS fraction (`bg-muted/40` vs `w-1/2`)
 * and every rule matches its own documentation unless comments are stripped.
 */

let repo;

beforeEach(() => {
	repo = createGateRepo();
});

afterEach(() => {
	repo.cleanup();
});

const run = () => repo.run('check-design.mjs');

describe('check-design', () => {
	it('passes on a component built from tokens and the scales', () => {
		repo.write(
			'frontend/src/lib/A.svelte',
			'<div class="rounded-md border-border bg-muted/40 text-foreground">Hi</div>\n'
		);

		const result = run();

		expect(result.status).toBe(0);
		expect(result.stdout).toContain('Design OK');
	});

	describe('palette colours', () => {
		it('fails on a Tailwind palette colour, with file and line', () => {
			repo.write(
				'frontend/src/lib/A.svelte',
				['<div>', '  <span class="bg-emerald-500">ok</span>', '</div>', ''].join('\n')
			);

			const result = run();

			expect(result.status).not.toBe(0);
			expect(result.output.replaceAll('\\', '/')).toContain('src/lib/A.svelte:2');
			expect(result.output).toContain('bg-emerald-500');
		});

		it('fails on a hand-written dark-mode counterpart too', () => {
			repo.write('frontend/src/lib/A.svelte', '<p class="dark:text-emerald-300">ok</p>\n');

			expect(run().status).not.toBe(0);
		});

		/* The avatar palette is categorical, and the exception is its path. */
		it('allows palette colours inside the avatar module', () => {
			repo.write(
				'frontend/src/lib/components/ui/avatar/avatar.ts',
				"export const hues = ['bg-rose-200 text-rose-900'];\n"
			);

			expect(run().status).toBe(0);
		});

		it('still rejects the same colours outside it', () => {
			repo.write(
				'frontend/src/lib/components/ContactList.svelte',
				'<span class="bg-rose-200">A</span>\n'
			);

			expect(run().status).not.toBe(0);
		});
	});

	describe('radius scale', () => {
		it('fails on bare rounded, which is Tailwind’s 4px default', () => {
			repo.write('frontend/src/lib/A.svelte', '<div class="rounded bg-muted">x</div>\n');

			const result = run();

			expect(result.status).not.toBe(0);
			expect(result.output).toContain('rounded');
		});

		it('fails on a step the theme does not define', () => {
			repo.write('frontend/src/lib/A.svelte', '<div class="rounded-3xl">x</div>\n');

			expect(run().status).not.toBe(0);
		});

		it.each([
			'rounded-sm',
			'rounded-md',
			'rounded-lg',
			'rounded-xl',
			'rounded-2xl',
			'rounded-full'
		])('accepts %s', (cls) => {
			repo.write('frontend/src/lib/A.svelte', `<div class="${cls}">x</div>\n`);

			expect(run().status).toBe(0);
		});

		it('accepts a side-specific radius', () => {
			repo.write('frontend/src/lib/A.svelte', '<div class="rounded-r-full rounded-t-md">x</div>\n');

			expect(run().status).toBe(0);
		});

		it('accepts an arbitrary value derived from the scale but not a raw one', () => {
			repo.write('frontend/src/lib/A.svelte', '<div class="rounded-[var(--radius-md)]">x</div>\n');
			expect(run().status).toBe(0);

			repo.write('frontend/src/lib/B.svelte', '<div class="rounded-[7px]">x</div>\n');
			expect(run().status).not.toBe(0);
		});
	});

	describe('focus indicator', () => {
		it('fails on an inline focus ring', () => {
			repo.write(
				'frontend/src/lib/A.svelte',
				'<button class="focus-visible:ring-2 focus-visible:ring-ring">x</button>\n'
			);

			const result = run();

			expect(result.status).not.toBe(0);
			expect(result.output).toContain('focus-visible:ring-2');
		});

		it('fails on the plain focus variant, which fires for mouse users too', () => {
			repo.write('frontend/src/lib/A.svelte', '<button class="focus:ring-2">x</button>\n');

			expect(run().status).not.toBe(0);
		});

		it('allows the focus-ring module to spell it out', () => {
			repo.write(
				'frontend/src/lib/components/ui/focus-ring/focus-ring.ts',
				"export const focusRing = 'focus-visible:ring-2 focus-visible:ring-ring';\n"
			);

			expect(run().status).toBe(0);
		});

		it('fails on outline-none, which forced-colors mode cannot fall back from', () => {
			repo.write('frontend/src/lib/A.svelte', '<div class="outline-none">x</div>\n');

			expect(run().status).not.toBe(0);
		});

		it('accepts outline-hidden', () => {
			repo.write('frontend/src/lib/A.svelte', '<div class="outline-hidden">x</div>\n');

			expect(run().status).toBe(0);
		});
	});

	describe('tint ladder', () => {
		it('fails on a tint between the steps', () => {
			repo.write('frontend/src/lib/A.svelte', '<div class="bg-muted/45">x</div>\n');

			const result = run();

			expect(result.status).not.toBe(0);
			expect(result.output).toContain('bg-muted/45');
		});

		it('accepts a tint on the ladder', () => {
			repo.write(
				'frontend/src/lib/A.svelte',
				'<div class="bg-muted/40 text-foreground/80">x</div>\n'
			);

			expect(run().status).toBe(0);
		});

		/* `w-1/2` and `bg-muted/45` are the same shape; only one is a tint. */
		it('leaves width and height fractions alone', () => {
			repo.write('frontend/src/lib/A.svelte', '<div class="w-1/2 h-2/3 basis-1/3">x</div>\n');

			expect(run().status).toBe(0);
		});
	});

	it('does not read its own documentation as a violation', () => {
		repo.write(
			'frontend/src/lib/A.svelte',
			[
				'<!-- Never use bare rounded or outline-none here. -->',
				'<script lang="ts">',
				'\t/* bg-emerald-500 is banned; so is focus-visible:ring-2. */',
				'\t// and bg-muted/45 too',
				'\texport const x = 1;',
				'</script>',
				'<div class="rounded-md">x</div>',
				''
			].join('\n')
		);

		expect(run().status).toBe(0);
	});

	it('does not mistake a protocol for a line comment', () => {
		repo.write(
			'frontend/src/lib/A.svelte',
			'<a href="https://example.test" class="rounded">x</a>\n'
		);

		expect(run().status).not.toBe(0);
	});

	it('scans TypeScript as well as components', () => {
		repo.write('frontend/src/lib/variants.ts', "export const v = 'bg-emerald-500';\n");

		expect(run().status).not.toBe(0);
	});

	it('skips test and e2e files', () => {
		repo.write('frontend/src/lib/a.test.ts', "expect('bg-emerald-500').toBeTruthy();\n");
		repo.write('frontend/src/routes/a.e2e.ts', "await page.locator('.bg-emerald-500').click();\n");

		expect(run().status).toBe(0);
	});

	/*
	 * The one element-scoped rule. Its whole difficulty is that the two
	 * attributes it has to see together are far apart on a real row — eight
	 * lines and a `cn(...)` call — so these tests are mostly about the walk
	 * reaching the class at all, and about it stopping at the right element.
	 */
	describe('row grid columns', () => {
		const row = (classAttr, extra = '') =>
			[
				'<div role="grid" class="grid grid-cols-[2.5rem_auto]">',
				'\t<div',
				'\t\trole="row"',
				'\t\ttabindex="-1"',
				`\t\t${extra}`,
				`\t\tclass=${classAttr}`,
				'\t>',
				'\t\t<span role="gridcell">x</span>',
				'\t</div>',
				'</div>',
				''
			].join('\n');

		it('allows a container that declares the tracks', () => {
			repo.write('frontend/src/lib/A.svelte', row('"col-span-full grid grid-cols-subgrid"'));

			const result = run();

			expect(result.status).toBe(0);
			expect(result.stdout).toContain('Design OK');
		});

		it('fails on a row that declares its own tracks, reporting where the tag opens', () => {
			repo.write('frontend/src/lib/A.svelte', row('"col-span-full grid grid-cols-[2.5rem_auto]"'));

			const result = run();

			expect(result.status).not.toBe(0);
			expect(result.output).toContain('row-grid-columns');
			// Line 2 is `<div`, nine lines above the class it is judged on.
			expect(result.output.replaceAll('\\', '/')).toContain('src/lib/A.svelte:2');
		});

		/*
		 * The reason this is a scanner. `onclick={(e) => …}` puts a `>` inside
		 * the tag, so a walk that stopped at the first one would never reach a
		 * class written below the handler.
		 */
		it('does not let an event handler arrow end the tag early', () => {
			repo.write(
				'frontend/src/lib/A.svelte',
				row('"col-span-full grid grid-cols-[2.5rem_auto]"', 'onclick={(e) => handle(e)}')
			);

			expect(run().status).not.toBe(0);
		});

		it('reads a class built by a helper across several lines', () => {
			repo.write(
				'frontend/src/lib/A.svelte',
				[
					'<div role="row"',
					'\tclass={cn(',
					"\t\t'col-span-full grid grid-cols-[2.5rem_auto]',",
					"\t\tselected ? 'bg-primary/10' : 'hover:bg-muted/40'",
					'\t)}',
					'>',
					'\t<span role="gridcell">x</span>',
					'</div>',
					''
				].join('\n')
			);

			expect(run().status).not.toBe(0);
		});

		it('accepts single quotes around the role', () => {
			repo.write(
				'frontend/src/lib/A.svelte',
				"<div role='row' class='grid grid-cols-[2.5rem_auto]'>x</div>\n"
			);

			expect(run().status).not.toBe(0);
		});

		it('catches a variant-prefixed track list', () => {
			repo.write(
				'frontend/src/lib/A.svelte',
				'<div role="row" class="grid sm:grid-cols-[2.5rem_auto]">x</div>\n'
			);

			expect(run().status).not.toBe(0);
		});

		/*
		 * The boundary that element scoping buys. Both strings are in the file,
		 * on adjacent lines, but on different elements — a line-based rule that
		 * looked for them in one file would fire here.
		 */
		it('does not fire when the row and the tracks are different elements', () => {
			repo.write(
				'frontend/src/lib/A.svelte',
				[
					'<div class="grid grid-cols-[2.5rem_auto]">',
					'\t<div role="row" class="grid-cols-subgrid">x</div>',
					'</div>',
					''
				].join('\n')
			);

			expect(run().status).toBe(0);
		});

		it('ignores a comment that describes the thing it bans', () => {
			repo.write(
				'frontend/src/lib/A.svelte',
				[
					'<!-- A role="row" must not carry grid-cols-[…]; use subgrid. -->',
					'<div role="row" class="grid-cols-subgrid">x</div>',
					''
				].join('\n')
			);

			expect(run().status).toBe(0);
		});

		/* A type parameter reads like a tag, so the rule stays out of .ts. */
		it('does not scan TypeScript for elements', () => {
			repo.write(
				'frontend/src/lib/rows.ts',
				[
					'export function build<T>(role: string) {',
					"\treturn role === 'row' ? 'grid-cols-[2.5rem_auto]' : '';",
					'}',
					''
				].join('\n')
			);

			expect(run().status).toBe(0);
		});
	});

	it('reports every rule that was broken, not just the first', () => {
		repo.write('frontend/src/lib/A.svelte', '<div class="bg-emerald-500">a</div>\n');
		repo.write('frontend/src/lib/B.svelte', '<div class="rounded bg-muted/45">b</div>\n');

		const result = run();

		expect(result.output).toContain('palette-colour');
		expect(result.output).toContain('off-scale-radius');
		expect(result.output).toContain('off-ladder-tint');
	});
});
