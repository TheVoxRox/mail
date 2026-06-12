import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';

const BASE_LOCALE = 'cs';
const MESSAGES_DIR = path.join(process.cwd(), 'src', 'lib', 'i18n', 'messages');

const localeFiles = (await readdir(MESSAGES_DIR))
	.filter((file) => file.endsWith('.json'))
	.sort((a, b) => a.localeCompare(b));
const LOCALES = localeFiles.map((file) => path.basename(file, '.json'));

if (LOCALES.length === 0) {
	console.error(`[i18n] No locale files found in ${MESSAGES_DIR}`);
	process.exit(1);
}

if (!LOCALES.includes(BASE_LOCALE)) {
	console.error(`[i18n] Missing base locale ${BASE_LOCALE}.`);
	process.exit(1);
}

async function readMessages(locale) {
	const file = path.join(MESSAGES_DIR, `${locale}.json`);

	try {
		return JSON.parse(await readFile(file, 'utf8'));
	} catch (error) {
		console.error(`[i18n] Failed to read ${file}`);
		throw error;
	}
}

function flattenKeys(value, prefix = '') {
	if (value && typeof value === 'object' && !Array.isArray(value)) {
		return Object.entries(value).flatMap(([key, child]) =>
			flattenKeys(child, prefix ? `${prefix}.${key}` : key)
		);
	}

	return [prefix];
}

const messages = new Map();

for (const locale of LOCALES) {
	messages.set(locale, await readMessages(locale));
}

const keySets = new Map(
	[...messages].map(([locale, value]) => [locale, new Set(flattenKeys(value))])
);
const baseKeys = keySets.get(BASE_LOCALE);
let failed = false;

for (const locale of LOCALES) {
	if (locale === BASE_LOCALE) {
		continue;
	}

	const localeKeys = keySets.get(locale);
	const missing = [...baseKeys].filter((key) => !localeKeys.has(key)).sort();
	const extra = [...localeKeys].filter((key) => !baseKeys.has(key)).sort();

	if (missing.length === 0 && extra.length === 0) {
		continue;
	}

	failed = true;
	console.error(`[i18n] Key mismatch: ${BASE_LOCALE} <-> ${locale}`);

	if (missing.length > 0) {
		console.error(`  Missing in ${locale}:`);
		for (const key of missing) {
			console.error(`    - ${key}`);
		}
	}

	if (extra.length > 0) {
		console.error(`  Extra in ${locale}:`);
		for (const key of extra) {
			console.error(`    - ${key}`);
		}
	}
}

if (failed) {
	process.exit(1);
}

/*
 * Dead-key check: every base-locale key must be referenced from the app
 * sources, either as a string literal ('toast.sendPending') or via a dynamic
 * template prefix (`folder.${role}` / `palette.group_${id}`). Keys consumed
 * through property access are invisible to the literal scanner and must be
 * listed in USED_INDIRECTLY with a justification.
 */
const SRC_DIR = path.join(process.cwd(), 'src');
const SCAN_EXCLUDE = /test-fixtures|\.test\.|\.e2e\.ts$|[\\/]generated\.ts$|schema\.d\.ts$/;
const LITERAL_KEY = /['"]([a-z][A-Za-z0-9]*(?:\.[A-Za-z0-9_]+)+)['"]/g;
const DYNAMIC_PREFIX = /`([a-z][A-Za-z0-9]*(?:\.[A-Za-z0-9_]+)*[._])\$\{/g;

const USED_INDIRECTLY = new Set([
	// i18n/index.ts builds the locale picker labels via message.app.localeLabel
	// property access, not a string lookup.
	'app.localeLabel'
]);

async function collectSourceFiles(dir) {
	const entries = await readdir(dir, { withFileTypes: true });
	const files = [];
	for (const entry of entries) {
		const full = path.join(dir, entry.name);
		if (entry.isDirectory()) {
			files.push(...(await collectSourceFiles(full)));
		} else if (/\.(ts|svelte)$/.test(entry.name) && !SCAN_EXCLUDE.test(full)) {
			files.push(full);
		}
	}
	return files;
}

const usedLiterals = new Set();
const dynamicPrefixes = new Set();
for (const file of await collectSourceFiles(SRC_DIR)) {
	const content = await readFile(file, 'utf8');
	for (const match of content.matchAll(LITERAL_KEY)) {
		usedLiterals.add(match[1]);
	}
	for (const match of content.matchAll(DYNAMIC_PREFIX)) {
		dynamicPrefixes.add(match[1]);
	}
}

const prefixes = [...dynamicPrefixes];
const unused = [...baseKeys]
	.filter(
		(key) =>
			!usedLiterals.has(key) &&
			!USED_INDIRECTLY.has(key) &&
			!prefixes.some((prefix) => key.startsWith(prefix))
	)
	.sort();

if (unused.length > 0) {
	console.error(`[i18n] Unused message keys in ${BASE_LOCALE}.json (${unused.length}):`);
	for (const key of unused) {
		console.error(`    - ${key}`);
	}
	console.error(
		'[i18n] Remove the key from every locale file, or add it to USED_INDIRECTLY ' +
			'in scripts/check-i18n-keys.mjs with a justification.'
	);
	process.exit(1);
}

console.log(`[i18n] ${LOCALES.join(', ')} keys match (${baseKeys.size} keys, none unused).`);
