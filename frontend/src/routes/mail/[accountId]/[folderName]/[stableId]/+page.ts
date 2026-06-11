import { error } from '@sveltejs/kit';
import type { PageLoad } from './$types.js';

export const load: PageLoad = ({ params }) => {
	const accountId = Number(params.accountId);
	if (!Number.isInteger(accountId) || accountId <= 0) {
		error(400, 'Invalid account ID.');
	}
	const folderName = decodeURIComponent(params.folderName);
	if (!folderName) {
		error(400, 'Missing folder name.');
	}
	const stableId = decodeURIComponent(params.stableId);
	if (!stableId) {
		error(400, 'Missing message ID.');
	}
	return { accountId, folderName, stableId };
};
