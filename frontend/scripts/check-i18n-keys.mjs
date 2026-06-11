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

console.log(`[i18n] ${LOCALES.join(', ')} keys match (${baseKeys.size} keys).`);
