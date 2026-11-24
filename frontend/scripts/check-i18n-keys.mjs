import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { parse, TYPE } from '@formatjs/icu-messageformat-parser';

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

/** Dotted key -> message, for every leaf of a locale file. */
function flattenEntries(value, prefix = '', entries = new Map()) {
	if (value && typeof value === 'object' && !Array.isArray(value)) {
		for (const [key, child] of Object.entries(value)) {
			flattenEntries(child, prefix ? `${prefix}.${key}` : key, entries);
		}
	} else {
		entries.set(prefix, value);
	}
	return entries;
}

const messages = new Map();

for (const locale of LOCALES) {
	messages.set(locale, flattenEntries(await readMessages(locale)));
}

const keySets = new Map(
	[...messages].map(([locale, entries]) => [locale, new Set(entries.keys())])
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
 * Message check: key parity says every locale has the key, this says the
 * message under it renders. Each message goes through the parser svelte-i18n
 * formats with (intl-messageformat's), with the option svelte-i18n passes
 * (`ignoreTag`, its default), so a syntax error fails here instead of showing
 * as raw text on its screen. Then, against the base locale:
 *
 *  - The same arguments. A translation that drops or misspells {count} renders
 *    without the value, and one that quotes it ('{name}') renders the braces.
 *  - The same select options. They are values the code passes, not words, so
 *    one missing in a translation falls silently to `other`.
 *
 * And per locale, the plural categories of its own rules. A plural that uses a
 * category of the language (one, few…) needs every one a whole number selects
 * there, or a count lands in `other` with the wrong form: Czech one/few/other,
 * English one/other, Polish one/few/many. Where two forms read the same (the
 * Czech genitive: "u 3 zpráv", "u 5 zpráv") the branch is written twice, so
 * that a gap means a mistake. A plural of exact matches and `other` alone
 * ({failed, plural, =0 {} other {…}}) is a condition on the number, not a word
 * that inflects, and passes. A category the locale never selects is dead text
 * and fails too.
 */
const ARGUMENT_TYPES = new Set([
	TYPE.argument,
	TYPE.number,
	TYPE.date,
	TYPE.time,
	TYPE.select,
	TYPE.plural
]);

/*
 * The whole numbers a count can take, as far as plural rules tell them apart:
 * 0-1000 reaches every rule keyed on the last digits, and the powers of ten the
 * rules keyed on millions (French and Spanish `many`).
 */
const WHOLE_NUMBERS = [...Array.from({ length: 1001 }, (_, n) => n), 1e4, 1e5, 1e6, 1e7, 1e8, 1e9];

const pluralRules = new Map();

/** Categories a locale's rules can select, and those a whole number reaches. */
function pluralCategories(locale, type) {
	const cacheKey = `${locale}:${type}`;
	if (!pluralRules.has(cacheKey)) {
		const rules = new Intl.PluralRules(locale, { type });
		pluralRules.set(cacheKey, {
			all: new Set(rules.resolvedOptions().pluralCategories),
			whole: new Set(WHOLE_NUMBERS.map((n) => rules.select(n)))
		});
	}
	return pluralRules.get(cacheKey);
}

/** Calls `visit` for every element, inside select and plural branches too. */
function walk(elements, visit) {
	for (const element of elements) {
		visit(element);
		if (element.type === TYPE.select || element.type === TYPE.plural) {
			for (const option of Object.values(element.options)) {
				walk(option.value, visit);
			}
		}
	}
}

const ordered = (values) => [...values].sort((a, b) => a.localeCompare(b));
const CATEGORY_ORDER = ['zero', 'one', 'two', 'few', 'many', 'other'];
const categoryOrdered = (values) =>
	[...values].sort((a, b) => CATEGORY_ORDER.indexOf(a) - CATEGORY_ORDER.indexOf(b));
const listed = (values) => (values.length === 0 ? '(none)' : values.join(', '));

/**
 * The arguments and select options of one message, plus the problems it has on
 * its own. A message that does not parse has nothing else to report.
 */
function inspectMessage(locale, key, message) {
	if (typeof message !== 'string') {
		return { problems: [`${locale} ${key}: not a string message`] };
	}
	let elements;
	try {
		elements = parse(message, { ignoreTag: true });
	} catch (error) {
		const offset = error.location?.start?.offset;
		const at = offset === undefined ? '' : ` at offset ${offset}`;
		return { problems: [`${locale} ${key}: ${error.message}${at}`] };
	}

	const problems = [];
	const args = new Set();
	const selects = new Map();
	walk(elements, (element) => {
		if (ARGUMENT_TYPES.has(element.type)) {
			args.add(element.value);
		}
		if (element.type === TYPE.select) {
			const options = selects.get(element.value) ?? new Set();
			for (const option of Object.keys(element.options)) options.add(option);
			selects.set(element.value, options);
		}
		if (element.type !== TYPE.plural) {
			return;
		}
		const { all, whole } = pluralCategories(locale, element.pluralType);
		const categories = Object.keys(element.options).filter((option) => !option.startsWith('='));
		const unknown = categories.filter((category) => !all.has(category));
		if (unknown.length > 0) {
			problems.push(
				`${locale} ${key}: {${element.value}, plural} has ${listed(unknown)}, which ` +
					`${locale} never selects (it has ${listed(categoryOrdered(all))})`
			);
		}
		const inflects = categories.some((category) => category !== 'other');
		const missing = categoryOrdered(whole).filter((category) => !categories.includes(category));
		if (inflects && missing.length > 0) {
			problems.push(
				`${locale} ${key}: {${element.value}, plural} lacks ${listed(missing)} ` +
					`(${locale} needs ${listed(categoryOrdered(whole))} for whole numbers)`
			);
		}
	});
	return { problems, args: ordered(args), selects };
}

const messageProblems = [];
for (const locale of LOCALES) {
	if (Intl.PluralRules.supportedLocalesOf([locale]).length === 0) {
		messageProblems.push(`${locale}: Node's ICU has no plural rules for this locale code`);
	}
}

const inspected = new Map(
	LOCALES.map((locale) => [
		locale,
		new Map(
			[...messages.get(locale)].map(([key, message]) => [key, inspectMessage(locale, key, message)])
		)
	])
);

const baseInspected = inspected.get(BASE_LOCALE);
for (const locale of LOCALES) {
	for (const [key, result] of inspected.get(locale)) {
		messageProblems.push(...result.problems);
		const base = baseInspected.get(key);
		if (locale === BASE_LOCALE || !result.args || !base.args) {
			continue;
		}
		if (result.args.join() !== base.args.join()) {
			messageProblems.push(
				`${locale} ${key}: arguments ${listed(result.args)}, ` +
					`${BASE_LOCALE} has ${listed(base.args)}`
			);
		}
		for (const [name, baseOptions] of base.selects) {
			const options = ordered(result.selects.get(name) ?? []);
			if (options.join() !== ordered(baseOptions).join()) {
				messageProblems.push(
					`${locale} ${key}: {${name}, select} options ${listed(options)}, ` +
						`${BASE_LOCALE} has ${listed(ordered(baseOptions))}`
				);
			}
		}
	}
}

if (messageProblems.length > 0) {
	console.error(`[i18n] Message problems (${messageProblems.length}):`);
	for (const problem of messageProblems) {
		console.error(`    - ${problem}`);
	}
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

console.log(
	`[i18n] ${LOCALES.join(', ')} keys match (${baseKeys.size} keys, none unused); ` +
		'every message parses, with the base arguments and its own plural forms.'
);
