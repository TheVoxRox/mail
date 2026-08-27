/*
 * `$app/state` under vitest. The real module is runes-backed and only resolves
 * inside a SvelteKit build; unit tests need a plain object with the same shape,
 * since nothing under test navigates.
 */
export const page = {
	url: new URL('http://localhost/'),
	params: {},
	route: { id: null },
	status: 200,
	error: null,
	data: {},
	form: undefined,
	state: {}
};

export const navigating = {
	from: null,
	to: null,
	type: null,
	willUnload: null,
	delta: null,
	complete: null
};

export const updated = { current: false, check: async () => false };
