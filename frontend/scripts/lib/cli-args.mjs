/**
 * Shared `--flag value` / `--flag=value` parser for the frontend scripts.
 * Flag names are camelCased (`--some-flag` -> `someFlag`); single-word flags
 * are unaffected. Throws on positional arguments and missing values so a
 * mistyped CI invocation fails loudly instead of running with defaults.
 *
 * check-translation-whitelist.mjs keeps its own parser on purpose: its CLI
 * contract is enum-validated inline-only flags plus --help.
 */

export function parseArgs(argv) {
	const parsed = {};
	for (let i = 0; i < argv.length; i += 1) {
		const item = argv[i];
		if (!item.startsWith('--')) {
			throw new Error(`Unexpected argument: ${item}`);
		}

		const [rawKey, inlineValue] = item.slice(2).split('=', 2);
		const value = inlineValue ?? argv[++i];
		if (!rawKey || value === undefined) {
			throw new Error(`Missing value for argument: ${item}`);
		}
		parsed[toCamelCase(rawKey)] = value;
	}
	return parsed;
}

function toCamelCase(value) {
	return value.replace(/-([a-z])/g, (_, char) => char.toUpperCase());
}
