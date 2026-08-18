import { requireAccountId } from '$lib/routeParams.js';
import type { PageLoad } from './$types.js';

export const load: PageLoad = ({ params, url }) => {
	const page = Number(url.searchParams.get('page') ?? '0');
	/*
	 * The open result is URL state, not component state: the same message has
	 * one address inside the search context (results and query intact behind
	 * it) and another under its own folder in the mail section. Which one you
	 * are on decides where Back goes, so the context owns the address.
	 *
	 * Not validated beyond "non-empty" — an id that no longer resolves is a
	 * stale bookmark, and the detail answers that with its own not-found state
	 * rather than a 400 that would take the results down with it.
	 */
	const message = url.searchParams.get('message')?.trim();
	return {
		accountId: requireAccountId(params.accountId),
		query: url.searchParams.get('q') ?? '',
		page: Number.isFinite(page) ? page : 0,
		message: message ? message : null
	};
};
