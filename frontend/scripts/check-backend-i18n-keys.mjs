#!/usr/bin/env node
/**
 * check-backend-i18n-keys.mjs
 *
 * Parity guard for the Spring `messages*.properties` bundles — the backend
 * counterpart to check-i18n-keys.mjs (which guards the frontend cs/en JSON).
 *
 * The frontend message files have had a key-parity + dead-key guard for a
 * while; the backend bundles did not. They are currently in perfect parity,
 * but nothing stopped a key from being added to one locale and forgotten in
 * another, or the default bundle from drifting away from the Czech one.
 *
 * Four checks, the first three against BASE_LOCALE = 'cs':
 *   1. Key parity — every `messages_<locale>.properties` has the exact same
 *      key set as `messages_<BASE_LOCALE>.properties`.
 *   2. Default-bundle identity — `messages.properties` (the suffix-less
 *      fallback bundle, resolved when Accept-Language matches no variant) must
 *      be identical in keys *and* values to `messages_<BASE_LOCALE>.properties`.
 *      The app is Czech-first, so the fallback bundle is intentionally a copy
 *      of the Czech one; this asserts they stay in sync.
 *   3. Placeholder parity — every key's MessageFormat arguments ({0}, {value}…)
 *      match across all locales, so a translation can't silently drop or rename
 *      an interpolated argument.
 *      Beside it, `app.locale` in each bundle names the bundle's own language.
 *   4. Locale parity with the frontend — the bundles cover exactly the locales
 *      of `frontend/src/lib/i18n/messages/*.json`. The client sends its
 *      interface language as Accept-Language, so an interface language with no
 *      bundle here gets the backend's errors and quote headers from the Czech
 *      default bundle, inside an otherwise translated screen; a bundle with no
 *      interface language is never asked for. Locales are bare language codes
 *      on both sides, and a regional file is reported as such.
 *
 * Uses only Node builtins (no npm deps), so CI can run it on the backend job's
 * preinstalled Node, the same way it runs check-translation-whitelist.mjs.
 *
 * Run from anywhere:
 *   node frontend/scripts/check-backend-i18n-keys.mjs
 */

import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(__dirname, '..', '..');
const RESOURCES_DIR = path.join(REPO_ROOT, 'backend', 'src', 'main', 'resources');
const FRONTEND_MESSAGES_DIR = path.join(REPO_ROOT, 'frontend', 'src', 'lib', 'i18n', 'messages');
const BASE_LOCALE = 'cs';
const DEFAULT_BUNDLE = 'messages.properties';
const LOCALE_FILE = /^messages_([a-z]{2})\.properties$/;

/**
 * Parse a .properties file into an ordered key→value map. Mirrors how Spring's
 * PropertyResourceBundle reads it: skip blank lines and `#`/`!` comments, split
 * on the first `=` or `:`, trim the key and the value's leading whitespace.
 * Line continuations are not used in these bundles, so they are not handled.
 */
async function readProperties(file) {
	const text = await readFile(file, 'utf8');
	const entries = new Map();
	for (const raw of text.split(/\r?\n/)) {
		const line = raw.trimStart();
		if (line === '' || line.startsWith('#') || line.startsWith('!')) {
			continue;
		}
		const match = /[=:]/.exec(line);
		if (!match) {
			continue;
		}
		const key = line.slice(0, match.index).trim();
		const value = line.slice(match.index + 1).replace(/^\s+/, '');
		entries.set(key, value);
	}
	return entries;
}

/** MessageFormat argument names in a value, sorted: "{0}…{value}" -> ['0','value']. */
function placeholders(value) {
	return [...value.matchAll(/\{([^}]*)\}/g)].map((m) => m[1].split(',')[0].trim()).sort();
}

const files = await readdir(RESOURCES_DIR);
const localeFiles = files
	.map((file) => ({ file, match: LOCALE_FILE.exec(file) }))
	.filter((entry) => entry.match)
	.map((entry) => ({ locale: entry.match[1], file: entry.file }))
	.sort((a, b) => a.locale.localeCompare(b.locale));

if (localeFiles.length === 0) {
	console.error(`[i18n:backend] No messages_<locale>.properties files in ${RESOURCES_DIR}`);
	process.exit(1);
}
if (!localeFiles.some((entry) => entry.locale === BASE_LOCALE)) {
	console.error(`[i18n:backend] Missing base locale bundle messages_${BASE_LOCALE}.properties.`);
	process.exit(1);
}

const bundles = new Map();
for (const { locale, file } of localeFiles) {
	bundles.set(locale, await readProperties(path.join(RESOURCES_DIR, file)));
}

const baseBundle = bundles.get(BASE_LOCALE);
const baseKeys = new Set(baseBundle.keys());
let failed = false;

// 1. Key parity across locale variants.
for (const { locale } of localeFiles) {
	if (locale === BASE_LOCALE) {
		continue;
	}
	const localeKeys = new Set(bundles.get(locale).keys());
	const missing = [...baseKeys].filter((key) => !localeKeys.has(key)).sort();
	const extra = [...localeKeys].filter((key) => !baseKeys.has(key)).sort();
	if (missing.length === 0 && extra.length === 0) {
		continue;
	}
	failed = true;
	console.error(`[i18n:backend] Key mismatch: ${BASE_LOCALE} <-> ${locale}`);
	for (const key of missing) {
		console.error(`  Missing in ${locale}: ${key}`);
	}
	for (const key of extra) {
		console.error(`  Extra in ${locale}:   ${key}`);
	}
}

// 2. Default bundle (messages.properties) must equal the base-locale bundle.
const defaultBundle = await readProperties(path.join(RESOURCES_DIR, DEFAULT_BUNDLE));
const defaultKeys = new Set(defaultBundle.keys());
const defaultMissing = [...baseKeys].filter((key) => !defaultKeys.has(key)).sort();
const defaultExtra = [...defaultKeys].filter((key) => !baseKeys.has(key)).sort();
const defaultValueDiffs = [...baseKeys]
	.filter((key) => defaultKeys.has(key) && defaultBundle.get(key) !== baseBundle.get(key))
	.sort();
if (defaultMissing.length || defaultExtra.length || defaultValueDiffs.length) {
	failed = true;
	console.error(
		`[i18n:backend] ${DEFAULT_BUNDLE} must be identical to messages_${BASE_LOCALE}.properties ` +
			'(the Czech-first fallback bundle):'
	);
	for (const key of defaultMissing) {
		console.error(`  Missing in ${DEFAULT_BUNDLE}: ${key}`);
	}
	for (const key of defaultExtra) {
		console.error(`  Extra in ${DEFAULT_BUNDLE}:   ${key}`);
	}
	for (const key of defaultValueDiffs) {
		console.error(`  Value differs: ${key}`);
	}
}

// 3. Placeholder parity per key across all locales.
for (const { locale } of localeFiles) {
	if (locale === BASE_LOCALE) {
		continue;
	}
	const bundle = bundles.get(locale);
	for (const key of baseKeys) {
		if (!bundle.has(key)) {
			continue; // already reported as a key mismatch above
		}
		const base = JSON.stringify(placeholders(baseBundle.get(key)));
		const other = JSON.stringify(placeholders(bundle.get(key)));
		if (base !== other) {
			failed = true;
			console.error(
				`[i18n:backend] Placeholder mismatch for ${key}: ` +
					`${BASE_LOCALE}=${base} ${locale}=${other}`
			);
		}
	}
}

// 3b. app.locale names the bundle's own language: what the backend formats
// rather than translates (a quoted message's date) is formatted in it, so a
// copied bundle that kept the source's tag would write its dates in the wrong
// language. Checked once the base bundle carries the key.
if (baseBundle.has('app.locale')) {
	for (const { locale } of localeFiles) {
		const tag = bundles.get(locale).get('app.locale');
		if (tag !== undefined && tag !== locale) {
			failed = true;
			console.error(
				`[i18n:backend] messages_${locale}.properties says app.locale=${tag}; it must name its own language, ${locale}`
			);
		}
	}
}

// 4. The same locales as the frontend's message files.
const backendLocales = new Set(localeFiles.map((entry) => entry.locale));
const frontendLocales = new Set(
	(await readdir(FRONTEND_MESSAGES_DIR))
		.filter((file) => file.endsWith('.json'))
		.map((file) => path.basename(file, '.json'))
);
/*
 * A locale is a bare language code on both sides. The first start reads only
 * the language part of the Windows language, and the two sides spell a region
 * differently (pt-BR.json, messages_pt_BR.properties), so a regional file would
 * fail the comparison below with no name that could satisfy it; it is named for
 * what it is instead (docs/TRANSLATING.md).
 */
const regional = [
	...[...frontendLocales]
		.filter((locale) => !/^[a-z]{2}$/.test(locale))
		.map((locale) => `frontend/src/lib/i18n/messages/${locale}.json`),
	...files
		.filter((file) => /^messages_.+\.properties$/.test(file) && !LOCALE_FILE.test(file))
		.map((file) => `backend/src/main/resources/${file}`)
].sort();
if (regional.length > 0) {
	failed = true;
	console.error(
		'[i18n:backend] A locale is a two-letter language code; regional variants are not supported (docs/TRANSLATING.md):'
	);
	for (const file of regional) {
		console.error(`  ${file}`);
	}
}
const isLanguage = (locale) => /^[a-z]{2}$/.test(locale);
const onlyFrontend = [...frontendLocales]
	.filter((locale) => isLanguage(locale) && !backendLocales.has(locale))
	.sort();
const onlyBackend = [...backendLocales].filter((locale) => !frontendLocales.has(locale)).sort();
if (onlyFrontend.length > 0 || onlyBackend.length > 0) {
	failed = true;
	console.error(
		'[i18n:backend] The bundles must cover the locales of frontend/src/lib/i18n/messages:'
	);
	for (const locale of onlyFrontend) {
		console.error(`  No messages_${locale}.properties for the interface language ${locale}`);
	}
	for (const locale of onlyBackend) {
		console.error(`  messages_${locale}.properties has no interface language (${locale}.json)`);
	}
}

if (failed) {
	process.exit(1);
}

const locales = localeFiles.map((entry) => entry.locale).join(', ');
console.log(
	`[i18n:backend] ${locales} bundles match (${baseKeys.size} keys); ` +
		`${DEFAULT_BUNDLE} == messages_${BASE_LOCALE}.properties; the frontend has the same locales.`
);
