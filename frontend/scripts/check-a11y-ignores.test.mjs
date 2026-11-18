import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createGateRepo } from './test-support/gate-repo.mjs';

/*
 * The gate reads svelte-ignore comments the way Svelte does in runes mode, so
 * the cases that matter are the edges of that grammar: where the list of codes
 * ends and the prose begins, and which comments are svelte-ignore at all.
 */

let repo;

beforeEach(() => {
	repo = createGateRepo();
});

afterEach(() => {
	repo.cleanup();
});

const run = () => repo.run('check-a11y-ignores.mjs');
const COMPONENT = 'frontend/src/lib/components/Row.svelte';

function component(...lines) {
	repo.write(COMPONENT, [...lines, ''].join('\n'));
}

describe('check-a11y-ignores', () => {
	it('passes a component that silences nothing', () => {
		component('<button type="button">Hi</button>');

		const result = run();

		expect(result.status).toBe(0);
		expect(result.stdout).toContain('A11y ignores OK');
	});

	it('fails an a11y ignore with no reason, with file and line', () => {
		component(
			'<div role="row">',
			'\t<!-- svelte-ignore a11y_click_events_have_key_events -->',
			'\t<div role="gridcell" onclick={stop}></div>',
			'</div>'
		);

		const result = run();

		expect(result.status).not.toBe(0);
		expect(result.output).toContain(`${COMPONENT}:2`);
		expect(result.output).toContain('a11y_click_events_have_key_events');
	});

	it('passes the reason written after the code', () => {
		component(
			'<!-- svelte-ignore a11y_click_events_have_key_events (the row takes the keys) -->',
			'<div role="gridcell" onclick={stop}></div>'
		);

		expect(run().status).toBe(0);
	});

	/* A comma continues the list, so the second code is still a code. */
	it('fails a list of codes that ends without prose', () => {
		component(
			'<!-- svelte-ignore a11y_no_noninteractive_tabindex, a11y_no_noninteractive_element_interactions -->',
			'<div role="separator" tabindex="0"></div>'
		);

		const result = run();

		expect(result.status).not.toBe(0);
		expect(result.output).toContain(
			'a11y_no_noninteractive_tabindex, a11y_no_noninteractive_element_interactions'
		);
	});

	it('passes a list of codes followed by prose', () => {
		component(
			'<!-- svelte-ignore a11y_no_noninteractive_tabindex, a11y_no_noninteractive_element_interactions (a window splitter) -->',
			'<div role="separator" tabindex="0"></div>'
		);

		expect(run().status).toBe(0);
	});

	it('takes prose on the lines after the code', () => {
		component(
			'<!-- svelte-ignore a11y_no_static_element_interactions',
			'\tthe wrapper only hears events bubbling from the controls inside -->',
			'<div onkeydown={close}></div>'
		);

		expect(run().status).toBe(0);
	});

	it('does not take punctuation alone for a reason', () => {
		component('<!-- svelte-ignore a11y_missing_attribute () -->', '<img src="a.png" />');

		expect(run().status).not.toBe(0);
	});

	it('reads a block comment in the script as well', () => {
		component(
			'<script lang="ts">',
			'\t/* svelte-ignore a11y_autofocus */',
			'\tlet value = $state(0);',
			'</script>'
		);

		expect(run().status).not.toBe(0);
	});

	it('leaves a warning that is not about accessibility alone', () => {
		component(
			'<script lang="ts">',
			'\tlet { initial } = $props();',
			'\t/* svelte-ignore state_referenced_locally */',
			'\tlet value = $state(initial);',
			'</script>'
		);

		expect(run().status).toBe(0);
	});

	it('does not count a comment that only mentions svelte-ignore', () => {
		component(
			'<!-- Never write svelte-ignore a11y_missing_attribute here; give the image an alt. -->',
			'<img src="a.png" alt="" />'
		);

		expect(run().status).toBe(0);
	});

	/*
	 * A URL in an attribute holds `//`. Read as a line comment it would swallow
	 * the rest of the line, the ignore after it included.
	 */
	it('does not let a URL hide an ignore after it on the same line', () => {
		component(
			'<a href="https://example.com">Docs</a> <!-- svelte-ignore a11y_missing_attribute -->',
			'<img src="a.png" />'
		);

		expect(run().status).not.toBe(0);
	});

	it('judges a component before its first commit', () => {
		repo.write('frontend/src/lib/components/Old.svelte', '<p>Old</p>\n');
		repo.commit('existing component');
		component('<!-- svelte-ignore a11y_missing_attribute -->', '<img src="a.png" />');

		expect(run().status).not.toBe(0);
	});
});
