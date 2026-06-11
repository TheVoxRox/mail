import type { PageLoad } from './$types.js';
import { error } from '@sveltejs/kit';

export const load: PageLoad = ({ params }) => {
	const id = Number(params.id);
	if (!Number.isInteger(id) || id <= 0) {
		error(400, 'Invalid account ID.');
	}
	return { id };
};
