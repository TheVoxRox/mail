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

/** The bundles, and the frontend's message files, of which the gate reads only the names. */
function seed({ cs, en, base, frontendLocales = ['cs', 'en'] }) {
	const dir = 'backend/src/main/resources';
	repo.write(`${dir}/messages_cs.properties`, props(cs));
	repo.write(`${dir}/messages_en.properties`, props(en));
	repo.write(`${dir}/messages.properties`, props(base ?? cs));
	for (const locale of frontendLocales) {
		repo.write(`frontend/src/lib/i18n/messages/${locale}.json`, '{}\n');
	}
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

	/*
	 * The client sends its interface language as Accept-Language. An interface
	 * language the backend has no bundle for gets the Czech default bundle's
	 * errors inside an otherwise translated screen, and every bundle check above
	 * passes, because they compare the bundles that exist.
	 */
	it('fails when an interface language has no bundle', () => {
		seed({
			cs: { 'ok.sent': 'Odeslano' },
			en: { 'ok.sent': 'Sent' },
			frontendLocales: ['cs', 'de', 'en']
		});

		const result = repo.run('check-backend-i18n-keys.mjs');

		expect(result.status).toBe(1);
		expect(result.output).toContain('No messages_de.properties');
	});

	it('fails when a bundle has no interface language', () => {
		seed({
			cs: { 'ok.sent': 'Odeslano' },
			en: { 'ok.sent': 'Sent' },
			frontendLocales: ['cs']
		});

		const result = repo.run('check-backend-i18n-keys.mjs');

		expect(result.status).toBe(1);
		expect(result.output).toContain('messages_en.properties has no interface language');
	});

	/*
	 * A bundle made by copying another keeps the source's app.locale, and the
	 * quoted message's date would come out in the source's language. Key and
	 * placeholder parity both pass such a copy.
	 */
	it("fails when a bundle's app.locale names another language", () => {
		seed({
			cs: { 'app.locale': 'cs', 'ok.sent': 'Odeslano' },
			en: { 'app.locale': 'cs', 'ok.sent': 'Sent' }
		});

		const result = repo.run('check-backend-i18n-keys.mjs');

		expect(result.status).toBe(1);
		expect(result.output).toContain('messages_en.properties says app.locale=cs');
	});

	/*
	 * The two sides spell a region differently, so a regional pair could never
	 * satisfy the locale comparison; it is named for what it is instead.
	 */
	it('names a regional locale as unsupported rather than as missing', () => {
		seed({
			cs: { 'ok.sent': 'Odeslano' },
			en: { 'ok.sent': 'Sent' },
			frontendLocales: ['cs', 'en', 'pt-BR']
		});
		repo.write(
			'backend/src/main/resources/messages_pt_BR.properties',
			props({ 'ok.sent': 'Enviado' })
		);

		const result = repo.run('check-backend-i18n-keys.mjs');

		expect(result.status).toBe(1);
		expect(result.output).toContain('regional variants are not supported');
		expect(result.output).toContain('frontend/src/lib/i18n/messages/pt-BR.json');
		expect(result.output).toContain('backend/src/main/resources/messages_pt_BR.properties');
		expect(result.output).not.toContain('No messages_pt-BR.properties');
	});

	it('fails when there are no locale bundles at all', () => {
		repo.write('backend/src/main/resources/application.properties', 'x=1\n');

		const result = repo.run('check-backend-i18n-keys.mjs');

		expect(result.status).toBe(1);
		expect(result.output).toContain('No messages_<locale>.properties');
	});
});
