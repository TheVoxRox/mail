/**
 * Persisted sort preference for the contacts list.
 *
 * Sort is a view preference, not a filter: the sidebar view links keep clean
 * URLs (no `sort` param), so the last applied sort lives here and the page
 * load falls back to it when the URL carries none. 'surname' is the backend
 * default and maps to "no explicit sort".
 */

import type { ContactSort } from '$lib/api/contacts.js';
import { persistedStore } from './persisted.js';

const CONTACT_SORT_VALUES = ['surname', 'name', 'recent'] as const satisfies readonly ContactSort[];

export const contactSortPreference = persistedStore<ContactSort>(
	'mail.contactSort',
	CONTACT_SORT_VALUES,
	'surname'
);
