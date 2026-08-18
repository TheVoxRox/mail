import { requireAccountId, requireFolderName } from '$lib/routeParams.js';
import type { PageLoad } from './$types.js';

export const load: PageLoad = ({ params }) => ({
	accountId: requireAccountId(params.accountId),
	folderName: requireFolderName(params.folderName)
});
