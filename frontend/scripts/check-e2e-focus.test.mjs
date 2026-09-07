import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createGateRepo } from './test-support/gate-repo.mjs';

/*
 * The gate exists because one shape kept coming back — a test takes focus, the
 * app moves it a frame later, the key lands somewhere else — so the cases below
 * are that shape and the ways out of it, plus the ways a text-level rule could
 * plausibly get it wrong: focus in a different test, focus the test did not
 * perform itself, and a key sent through the locator rather than the keyboard.
 */

let repo;

/** A spec file body wrapped in the shape `git ls-files *.e2e.ts` will find. */
function spec(body) {
	return `import { expect, test } from '@playwright/test';\n\ntest.describe('Sada', () => {\n${body}\n});\n`;
}

beforeEach(() => {
	repo = createGateRepo();
});

afterEach(() => {
	repo.cleanup();
});

describe('check-e2e-focus', () => {
	it('fails on focus followed by a keyboard press', () => {
		repo.write(
			'frontend/src/routes/thing.e2e.ts',
			spec(
				`	test('otevře menu', async ({ page }) => {
		await trigger.focus();
		await page.keyboard.press('Enter');
	});`
			)
		);
		repo.commit();

		const result = repo.run('check-e2e-focus.mjs');

		expect(result.status).toBe(1);
		expect(result.output).toContain('thing.e2e.ts:6');
		expect(result.output).toContain('focus was taken at line 5');
	});

	it('passes when waitForFocus stands between them', () => {
		repo.write(
			'frontend/src/routes/thing.e2e.ts',
			spec(
				`	test('otevře menu', async ({ page }) => {
		await trigger.focus();
		await waitForFocus(trigger);
		await page.keyboard.press('Enter');
	});`
			)
		);
		repo.commit();

		expect(repo.run('check-e2e-focus.mjs').status).toBe(0);
	});

	it('accepts an expect(...).toBeFocused() as the same check', () => {
		repo.write(
			'frontend/src/routes/thing.e2e.ts',
			spec(
				`	test('otevře menu', async ({ page }) => {
		await trigger.focus();
		await expect(trigger).toBeFocused();
		await page.keyboard.press('Enter');
	});`
			)
		);
		repo.commit();

		expect(repo.run('check-e2e-focus.mjs').status).toBe(0);
	});

	it('passes when the key goes through the locator, which focuses it itself', () => {
		repo.write(
			'frontend/src/routes/thing.e2e.ts',
			spec(
				`	test('otevře menu', async ({ page }) => {
		await trigger.press('Enter');
	});`
			)
		);
		repo.commit();

		expect(repo.run('check-e2e-focus.mjs').status).toBe(0);
	});

	// The rule is per test, not per file: a key in the *next* test cannot be
	// racing a focus taken in the previous one, and reporting it would send the
	// reader to an unrelated place.
	it('does not carry a focus across a test boundary', () => {
		repo.write(
			'frontend/src/routes/thing.e2e.ts',
			spec(
				`	test('vezme fokus', async () => {
		await trigger.focus();
	});

	test('pošle klávesu', async ({ page }) => {
		await page.keyboard.press('Enter');
	});`
			)
		);
		repo.commit();

		expect(repo.run('check-e2e-focus.mjs').status).toBe(0);
	});

	// Focus that arrives from a click, from Tab or from the app is a different
	// question, and one this gate deliberately does not try to answer.
	it('ignores a key press the test never took focus for', () => {
		repo.write(
			'frontend/src/routes/thing.e2e.ts',
			spec(
				`	test('pošle klávesu', async ({ page }) => {
		await row.click();
		await page.keyboard.press('ArrowDown');
	});`
			)
		);
		repo.commit();

		expect(repo.run('check-e2e-focus.mjs').status).toBe(0);
	});

	// In-page code inside page.evaluate is the browser's business, not a
	// statement the gate can reason about — and every real one is un-awaited.
	it('ignores a focus call inside page.evaluate', () => {
		repo.write(
			'frontend/src/routes/thing.e2e.ts',
			spec(
				`	test('pošle klávesu', async ({ page }) => {
		await page.evaluate(() => {
			document.querySelector('input').focus();
		});
		await page.keyboard.press('ArrowDown');
	});`
			)
		);
		repo.commit();

		expect(repo.run('check-e2e-focus.mjs').status).toBe(0);
	});

	it('reports the last focus before the key, not the first', () => {
		repo.write(
			'frontend/src/routes/thing.e2e.ts',
			spec(
				`	test('dvakrát', async ({ page }) => {
		await first.focus();
		await second.focus();
		await page.keyboard.press('Enter');
	});`
			)
		);
		repo.commit();

		const result = repo.run('check-e2e-focus.mjs');

		expect(result.status).toBe(1);
		expect(result.output).toContain('focus was taken at line 6');
		expect(result.output).toContain('await second.focus();');
	});

	it('excuses a pair carrying focus-settled with a reason', () => {
		repo.write(
			'frontend/src/routes/thing.e2e.ts',
			spec(
				`	test('pošle klávesu', async ({ page }) => {
		await trigger.focus(); // focus-settled: the key is meant for whoever holds it
		await page.keyboard.press('Escape');
	});`
			)
		);
		repo.commit();

		expect(repo.run('check-e2e-focus.mjs').status).toBe(0);
	});

	it('does not excuse a bare focus-settled without a reason', () => {
		repo.write(
			'frontend/src/routes/thing.e2e.ts',
			spec(
				`	test('pošle klávesu', async ({ page }) => {
		await trigger.focus(); // focus-settled:
		await page.keyboard.press('Escape');
	});`
			)
		);
		repo.commit();

		expect(repo.run('check-e2e-focus.mjs').status).toBe(1);
	});

	// keyboard.type and keyboard.down reach the focused element exactly as press
	// does; a rule that only knew press would be worked around by accident.
	it('covers keyboard.type as well as keyboard.press', () => {
		repo.write(
			'frontend/src/routes/thing.e2e.ts',
			spec(
				`	test('píše', async ({ page }) => {
		await field.focus();
		await page.keyboard.type('ahoj');
	});`
			)
		);
		repo.commit();

		expect(repo.run('check-e2e-focus.mjs').status).toBe(1);
	});

	it('only reads tracked spec files', () => {
		repo.write(
			'frontend/src/routes/thing.e2e.ts',
			spec(
				`	test('v pořádku', async ({ page }) => {
		await trigger.press('Enter');
	});`
			)
		);
		repo.commit();
		// Written but never committed, and a helper rather than a spec besides.
		repo.write(
			'frontend/src/routes/untracked-helper.ts',
			`await trigger.focus();\nawait page.keyboard.press('Enter');\n`
		);

		const result = repo.run('check-e2e-focus.mjs');

		expect(result.status).toBe(0);
		expect(result.stdout).toContain('1 spec file(s)');
	});
});
