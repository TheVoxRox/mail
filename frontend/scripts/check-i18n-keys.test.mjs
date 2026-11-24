import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createGateRepo } from './test-support/gate-repo.mjs';

/*
 * Two separate guarantees live in this gate and only one of them is obvious.
 * Key parity between cs and en is the obvious half. The other is the dead-key
 * sweep, which has to understand how keys are actually reached in the source:
 * as literals, through a template prefix, or — for the handful that are read
 * as properties — via an explicit allowlist. A sweep that misses the dynamic
 * form deletes keys that are in use.
 */

let repo;

const messages = (obj) => JSON.stringify(obj, null, '\t');

function seedLocales(cs, en) {
	repo.write('frontend/src/lib/i18n/messages/cs.json', messages(cs));
	repo.write('frontend/src/lib/i18n/messages/en.json', messages(en));
}

/** Somewhere for the dead-key sweep to find usages. */
function seedSource(content) {
	repo.write('frontend/src/lib/app.ts', content);
}

beforeEach(() => {
	repo = createGateRepo();
});

afterEach(() => {
	repo.cleanup();
});

describe('check-i18n-keys', () => {
	it('passes when the locales match and every key is used', () => {
		seedLocales({ toast: { sent: 'Odesláno' } }, { toast: { sent: 'Sent' } });
		seedSource("export const m = t('toast.sent');\n");

		const result = repo.run('check-i18n-keys.mjs');

		expect(result.status).toBe(0);
		expect(result.stdout).toContain('keys match');
	});

	it('fails on a key missing from the other locale', () => {
		seedLocales({ toast: { sent: 'Odesláno', failed: 'Selhalo' } }, { toast: { sent: 'Sent' } });
		seedSource("t('toast.sent'); t('toast.failed');\n");

		const result = repo.run('check-i18n-keys.mjs');

		expect(result.status).toBe(1);
		expect(result.stderr).toContain('Missing in en');
		expect(result.stderr).toContain('toast.failed');
	});

	it('fails on a key the base locale does not have', () => {
		seedLocales({ toast: { sent: 'Odesláno' } }, { toast: { sent: 'Sent', extra: 'Extra' } });
		seedSource("t('toast.sent');\n");

		const result = repo.run('check-i18n-keys.mjs');

		expect(result.status).toBe(1);
		expect(result.stderr).toContain('Extra in en');
	});

	it('fails on a base-locale key nothing references', () => {
		seedLocales(
			{ toast: { sent: 'Odesláno', orphan: 'Sirotek' } },
			{ toast: { sent: 'Sent', orphan: 'Orphan' } }
		);
		seedSource("t('toast.sent');\n");

		const result = repo.run('check-i18n-keys.mjs');

		expect(result.status).toBe(1);
		expect(result.stderr).toContain('Unused message keys');
		expect(result.stderr).toContain('toast.orphan');
	});

	/*
	 * The case the sweep exists to survive: keys assembled at runtime. Treating
	 * them as unused would invite deleting a key the app resolves every render.
	 */
	it('counts keys reached through a template prefix as used', () => {
		seedLocales(
			{ folders: { role: { inbox: 'Doručené', sent: 'Odeslané' } } },
			{ folders: { role: { inbox: 'Inbox', sent: 'Sent' } } }
		);
		seedSource('const key = `folders.role.${role}`;\n');

		expect(repo.run('check-i18n-keys.mjs').status).toBe(0);
	});

	it('does not count usages inside tests and fixtures', () => {
		seedLocales({ toast: { sent: 'Odesláno' } }, { toast: { sent: 'Sent' } });
		repo.write('frontend/src/lib/app.test.ts', "t('toast.sent');\n");

		const result = repo.run('check-i18n-keys.mjs');

		expect(result.status).toBe(1);
		expect(result.stderr).toContain('toast.sent');
	});

	it('fails when the base locale is missing entirely', () => {
		repo.write('frontend/src/lib/i18n/messages/en.json', messages({ toast: { sent: 'Sent' } }));
		seedSource("t('toast.sent');\n");

		const result = repo.run('check-i18n-keys.mjs');

		expect(result.status).toBe(1);
		expect(result.stderr).toContain('Missing base locale cs');
	});
});

/*
 * Key parity says the key exists; these say the message renders. Each failure
 * below is one svelte-i18n shows without an error of its own: the raw text of
 * a message that does not parse, a sentence without its value, a count in the
 * wrong form. Nothing else in the repo reads a translation's syntax.
 */
describe('check-i18n-keys messages', () => {
	it('passes plurals that carry each locale its own categories', () => {
		seedLocales(
			{ mail: { count: '{n, plural, one {# zpráva} few {# zprávy} other {# zpráv}}' } },
			{ mail: { count: '{n, plural, one {# message} other {# messages}}' } }
		);
		seedSource("t('mail.count');\n");

		const result = repo.run('check-i18n-keys.mjs');

		expect(result.status).toBe(0);
		expect(result.stdout).toContain('every message parses');
	});

	it('fails on a message that does not parse', () => {
		seedLocales({ toast: { sent: 'Odesláno: {count}' } }, { toast: { sent: 'Sent: {count' } });
		seedSource("t('toast.sent');\n");

		const result = repo.run('check-i18n-keys.mjs');

		expect(result.status).toBe(1);
		expect(result.stderr).toContain('en toast.sent: EXPECT_ARGUMENT_CLOSING_BRACE');
	});

	/*
	 * An apostrophe quotes in ICU, so '{name}' is the literal text "{name}": the
	 * message parses, renders the braces, and never shows the name.
	 */
	it('fails on a translation that loses an argument to quoting', () => {
		seedLocales(
			{ folder: { gone: 'Složka {name} byla smazána.' } },
			{ folder: { gone: "Folder '{name}' was deleted." } }
		);
		seedSource("t('folder.gone');\n");

		const result = repo.run('check-i18n-keys.mjs');

		expect(result.status).toBe(1);
		expect(result.stderr).toContain('en folder.gone: arguments (none), cs has name');
	});

	it('fails on a translation whose select options differ', () => {
		seedLocales(
			{ folder: { role: '{role, select, inbox {Doručené} sent {Odeslané} other {Složka}}' } },
			{ folder: { role: '{role, select, inbox {Inbox} other {Folder}}' } }
		);
		seedSource("t('folder.role');\n");

		const result = repo.run('check-i18n-keys.mjs');

		expect(result.status).toBe(1);
		expect(result.stderr).toContain(
			'en folder.role: {role, select} options inbox, other, cs has inbox, other, sent'
		);
	});

	it('fails on a plural that lacks a category the locale needs', () => {
		seedLocales(
			{ mail: { count: '{n, plural, one {# zpráva} other {# zpráv}}' } },
			{ mail: { count: '{n, plural, one {# message} other {# messages}}' } }
		);
		seedSource("t('mail.count');\n");

		const result = repo.run('check-i18n-keys.mjs');

		expect(result.status).toBe(1);
		expect(result.stderr).toContain(
			'cs mail.count: {n, plural} lacks few (cs needs one, few, other for whole numbers)'
		);
	});

	/*
	 * The bulk-outcome messages use a plural as a condition on the number:
	 * "Selhalo: 3." reads the same for any count, so they carry no category of
	 * the language and must not be held to its rules.
	 */
	it('passes a plural of exact matches and other alone', () => {
		seedLocales(
			{ bulk: { failed: '{failed, plural, =0 {} other { Selhalo: {failed}.}}' } },
			{ bulk: { failed: '{failed, plural, =0 {} other { Failed: {failed}.}}' } }
		);
		seedSource("t('bulk.failed');\n");

		expect(repo.run('check-i18n-keys.mjs').status).toBe(0);
	});

	it('fails on a category the locale never selects', () => {
		seedLocales(
			{ mail: { count: '{n, plural, one {# zpráva} few {# zprávy} other {# zpráv}}' } },
			{ mail: { count: '{n, plural, one {# message} few {# messages} other {# messages}}' } }
		);
		seedSource("t('mail.count');\n");

		const result = repo.run('check-i18n-keys.mjs');

		expect(result.status).toBe(1);
		expect(result.stderr).toContain('en mail.count: {n, plural} has few, which en never selects');
	});

	it('fails on a locale code without plural rules', () => {
		seedLocales({ toast: { sent: 'Odesláno' } }, { toast: { sent: 'Sent' } });
		repo.write('frontend/src/lib/i18n/messages/cz.json', messages({ toast: { sent: 'Odesláno' } }));
		seedSource("t('toast.sent');\n");

		const result = repo.run('check-i18n-keys.mjs');

		expect(result.status).toBe(1);
		expect(result.stderr).toContain("cz: Node's ICU has no plural rules");
	});
});
