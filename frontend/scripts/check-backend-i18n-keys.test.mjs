import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createGateRepo } from './test-support/gate-repo.mjs';

/*
 * Spring falls back to the suffix-less `messages.properties` when a locale is
 * missing, so that file is not a spare copy — it is what a user gets when
 * negotiation fails. Letting it drift from the base locale means the fallback
 * quietly says something different from the language it is standing in for,
 * which is precisely the kind of divergence nobody notices until a customer
 * reports it.
 */

let repo;

const props = (entries) =>
	Object.entries(entries)
		.map(([k, v]) => `${k}=${v}`)
		.join('\n') + '\n';

function seed({ cs, en, base }) {
	const dir = 'backend/src/main/resources';
	repo.write(`${dir}/messages_cs.properties`, props(cs));
	repo.write(`${dir}/messages_en.properties`, props(en));
	repo.write(`${dir}/messages.properties`, props(base ?? cs));
}

beforeEach(() => {
	repo = createGateRepo();
});

afterEach(() => {
	repo.cleanup();
});

describe('check-backend-i18n-keys', () => {
	it('passes when the locales and the default bundle agree', () => {
		seed({
			cs: { 'error.mail.failed': 'Selhalo', 'ok.sent': 'Odeslano' },
			en: { 'error.mail.failed': 'Failed', 'ok.sent': 'Sent' }
		});

		const result = repo.run('check-backend-i18n-keys.mjs');

		expect(result.status).toBe(0);
	});

	it('fails on a key the other locale lacks', () => {
		seed({
			cs: { 'error.mail.failed': 'Selhalo', 'ok.sent': 'Odeslano' },
			en: { 'error.mail.failed': 'Failed' }
		});

		const result = repo.run('check-backend-i18n-keys.mjs');

		expect(result.status).toBe(1);
		expect(result.output).toContain('ok.sent');
	});

	/*
	 * The default bundle has to match the base locale in values too, not just
	 * in keys — a fallback that carries stale wording is worse than a missing
	 * one, because nothing signals that it is stale.
	 */
	it('fails when the default bundle carries a different value', () => {
		seed({
			cs: { 'ok.sent': 'Odeslano' },
			en: { 'ok.sent': 'Sent' },
			base: { 'ok.sent': 'Odesláno jinak' }
		});

		const result = repo.run('check-backend-i18n-keys.mjs');

		expect(result.status).toBe(1);
		expect(result.output).toContain('ok.sent');
	});

	it('fails when the default bundle is missing a key entirely', () => {
		seed({
			cs: { 'ok.sent': 'Odeslano', 'ok.saved': 'Ulozeno' },
			en: { 'ok.sent': 'Sent', 'ok.saved': 'Saved' },
			base: { 'ok.sent': 'Odeslano' }
		});

		expect(repo.run('check-backend-i18n-keys.mjs').status).toBe(1);
	});

	it('fails when there are no locale bundles at all', () => {
		repo.write('backend/src/main/resources/application.properties', 'x=1\n');

		const result = repo.run('check-backend-i18n-keys.mjs');

		expect(result.status).toBe(1);
		expect(result.output).toContain('No messages_<locale>.properties');
	});
});
