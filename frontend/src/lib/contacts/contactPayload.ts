import { strip, stripBlankLinesToNull, stripToNull } from '#lib/storedText.js';
import type { ContactCreateRequest, EmailLabel } from '#lib/types.js';

/** One address row of the contact form, as the form holds it. */
export interface ContactEmailRow {
	email: string;
	label: EmailLabel | '';
	primary: boolean;
}

/** What the contact form holds when it is saved. */
export interface ContactFormValues {
	name: string;
	surname: string;
	note: string;
	emails: readonly ContactEmailRow[];
	labelIds: number[];
}

/**
 * The note as it is stored (a block: `stripBlankLinesToNull`), as an empty
 * string when there is none. The form measures its length on this, since that
 * is what the limit applies to.
 */
export function storedNote(note: string): string {
	return stripBlankLinesToNull(note) ?? '';
}

/**
 * The request the contact form sends, its text in the form the backend stores
 * (`#lib/storedText.js`): the names stripped, a blank one as null, the note as
 * a block, the addresses stripped and the empty rows left out.
 *
 * The first address in the request becomes primary (backend semantics), so the
 * chosen primary goes to the front. If the primary row was left empty it drops
 * out, and the first remaining address takes its place. A form with no address
 * filled in has failed its own validation before it gets here.
 */
export function toContactPayload(values: ContactFormValues): ContactCreateRequest {
	const entries = values.emails
		.map((row) => ({ email: strip(row.email), label: row.label, primary: row.primary }))
		.filter((entry) => entry.email.length > 0);
	const primary = entries.find((entry) => entry.primary) ?? entries[0];
	const ordered = primary ? [primary, ...entries.filter((entry) => entry !== primary)] : entries;
	return {
		name: stripToNull(values.name),
		surname: stripToNull(values.surname),
		note: stripBlankLinesToNull(values.note),
		emails: ordered.map((entry) => ({
			email: entry.email,
			label: entry.label === '' ? null : entry.label
		})),
		labelIds: values.labelIds
	};
}
