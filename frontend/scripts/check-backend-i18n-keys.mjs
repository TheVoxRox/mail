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
 * Three checks, against BASE_LOCALE = 'cs':
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

if (failed) {
	process.exit(1);
}

const locales = localeFiles.map((entry) => entry.locale).join(', ');
console.log(
	`[i18n:backend] ${locales} bundles match (${baseKeys.size} keys); ` +
		`${DEFAULT_BUNDLE} == messages_${BASE_LOCALE}.properties.`
);
