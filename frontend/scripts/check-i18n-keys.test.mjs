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
