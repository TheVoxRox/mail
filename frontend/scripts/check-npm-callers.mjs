import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { RECORDS_OF_THE_PAST } from './lib/historical-docs.mjs';

/*
 * Fails an entry in `frontend/package.json` `scripts` that nothing in the
 * repository reaches, and reports *how* every live entry is reached instead of
 * issuing a single verdict.
 *
 * The hole it closes is one direction of a pair. `check:refs` walks prose ->
 * package.json: an `npm run` named in a document must exist. Nothing walked
 * package.json -> anywhere, so an entry whose last caller left stayed green
 * forever. #422 found `tauri:signing-config:windows` that way — by hand, named
 * nowhere but in its own definition since the initial import — and the script
 * it pointed at was dead only transitively.
 *
 * knip cannot see this and never could: it treats every package.json script as
 * an entry point, so a file kept alive by a dead npm entry is unreportable.
 * Measured in #425 by restoring the pre-state and running knip three times,
 * which also disproved the "narrow the entry glob" fix recorded in #424. Since
 * #426 knip does report an unused *file* under `frontend/scripts`; this gate is
 * the half above it, about the npm entry rather than the file, and the two do
 * not overlap.
 *
 * Two verdicts on an unreachable entry, never one, and the order is measured
 * rather than assumed. Of the six entries this repository had with no caller,
 * five were working tools that wanted a line of documentation (#426) and one
 * was residue. A gate that said "delete this" would have been wrong five times
 * out of six, so the report leads with documenting it.
 *
 * Documentation is a full route, not an exception. Most of the entries here
 * are reachable only from prose, and that holds up precisely because
 * `check:refs` guards the other direction: a documented `npm run` cannot rot
 * into naming something that does not exist. A changelog is not documentation
 * for this purpose (see HISTORICAL) — it records that a script once existed,
 * which is the opposite of a reason to keep it.
 *
 * Usage: node scripts/check-npm-callers.mjs
 */

const repoRoot = path.join(process.cwd(), '..');
const PACKAGE_JSON = 'frontend/package.json';

/**
 * Documents that describe the past. A script named here is named *because* it
 * changed or was retired, so counting that as a caller would make every entry
 * immortal from the commit that touched it. Shared with `check:refs`, which
 * skips the same files for the same reason.
 */
const HISTORICAL = new Set(RECORDS_OF_THE_PAST);

/**
 * Entries that legitimately have no caller and no documentation. Each needs a
 * reason, and the gate fails when one becomes reachable or disappears from
 * package.json — an exemption that outlives what it excused is how an
 * allowlist turns into a lie.
 *
 * Note what is *not* here: `test` is reached as `npm test`, and `preview`,
 * `check:translations` and the smoke scripts are reached from CONTRIBUTING.md
 * and RELEASE_CHECKLIST.md. Documenting a tool is the cheaper exit than
 * exempting it, and it is the one that helps the next reader.
 */
const EXEMPT = {
	prepare: 'npm lifecycle hook — npm runs it after `npm ci`, never a caller here',
	'check:watch': 'interactive svelte-check loop, started and stopped by hand',
	'test:unit:watch': 'interactive vitest loop, started and stopped by hand'
};

/** Files whose content is not text. Everything else is read and scanned. */
const BINARY_EXTENSIONS = new Set([
	'.png',
	'.jpg',
	'.jpeg',
	'.gif',
	'.ico',
	'.icns',
	'.webp',
	'.woff',
	'.woff2',
	'.ttf',
	'.otf',
	'.eot',
	'.pdf',
	'.zip',
	'.jar',
	'.exe',
	'.dll'
]);

/*
 * `npm run <name>`, with the flag noise both halves of the command pick up:
 * `npm --prefix frontend run <name>`, `npm run --silent <name>`, `npm run
 * <name> -- --arg`. The name is captured; a `"$@"` (the pre-push hook's
 * forwarded arguments) starts with a quote and matches nothing, which is what
 * the hook rule below is for.
 *
 * The examples are spelled with a placeholder on purpose: `check:refs` reads
 * this comment and would demand that a script called `x` exist. It fires on a
 * tracked file only, so the first run that sees it is the commit adding it.
 */
const NPM_RUN =
	/npm(?:\s+-{1,2}[\w-]+(?:[=\s]\S+)?)*\s+run\s+(?:-{1,2}[\w:-]+(?:=\S+)?\s+)*([a-z][\w:.-]*)/g;

/** The shorthands npm resolves to a script of the same name without `run`. */
const NPM_SHORTHAND = /npm(?:\s+-{1,2}[\w-]+(?:[=\s]\S+)?)*\s+(test|start)\b/g;

/*
 * `.githooks/pre-push` wraps `npm run --silent "$@"` in a `run()` helper and
 * then invokes eight gates as bare names. Without this rule those eight look
 * like nothing calls them — a false finding on the file whose whole job is
 * calling them. Scoped to `.githooks/` because a bare word is only unambiguous
 * where a hook's own helper defines it.
 */
const HOOK_CALL = /^\s*run\s+([a-z][\w:.-]*)/gm;

function git(args) {
	return execFileSync('git', args, {
		cwd: repoRoot,
		encoding: 'utf8',
		maxBuffer: 64 * 1024 * 1024
	});
}

function isTestFile(file) {
	return (
		/\.(?:test|spec)\.(?:mjs|ts|js)$/.test(file) ||
		/\.e2e\.ts$/.test(file) ||
		file.includes('/test-support/') ||
		file.includes('/test-fixtures/')
	);
}

/**
 * Which route a mention in this file represents. `test` and `historical` are
 * routes too — they are collected so the report can say "only a test names
 * this" rather than "nothing does", which argues for a different fix.
 */
function routeOf(file) {
	if (file === PACKAGE_JSON) return 'composite';
	if (isTestFile(file)) return 'test';
	if (HISTORICAL.has(file)) return 'historical';
	if (file.endsWith('.md')) return 'documentation';
	return 'automation';
}

const scripts = JSON.parse(readFileSync(path.join(repoRoot, PACKAGE_JSON), 'utf8')).scripts ?? {};
const names = new Set(Object.keys(scripts));

/** name -> [{ route, where }] */
const references = new Map([...names].map((name) => [name, []]));
/** An invocation naming a script that does not exist. */
const broken = [];

/**
 * @param spelling how the file actually writes the call (`npm run <name>`,
 *   `npm <name>`), so a report of a dead reference quotes text the reader can
 *   grep for.
 */
function record(name, route, where, spelling) {
	if (names.has(name)) {
		references.get(name).push({ route, where });
		return;
	}
	/*
	 * Accused only from files whose dead references nothing else reports.
	 * Markdown is `check:refs`, and a gate's own suite writes fixtures naming
	 * scripts that deliberately do not exist. The overlap that remains is a
	 * comment inside a source root, which `check:refs` reads too — both gates
	 * name it, and one fix clears both.
	 */
	if (route === 'automation' || route === 'composite') broken.push({ name, where, spelling });
}

function scan(text, route, where) {
	for (const match of text.matchAll(NPM_RUN)) record(match[1], route, where, `npm run ${match[1]}`);
	for (const match of text.matchAll(NPM_SHORTHAND)) {
		record(match[1], route, where, `npm ${match[1]}`);
	}
}

for (const [name, value] of Object.entries(scripts)) {
	scan(value, 'composite', `${PACKAGE_JSON} → "${name}"`);
}

/*
 * `-z` rather than a newline split: without it git quotes and octal-escapes any
 * path holding a non-ASCII byte, the read of that name fails, and a caller
 * inside a document with a Czech filename would be invisible — reported as
 * having no caller while the documentation sits there.
 */
let scanned = 0;
for (const file of git(['ls-files', '-z']).split('\0').filter(Boolean)) {
	if (file === PACKAGE_JSON) continue;
	if (BINARY_EXTENSIONS.has(path.extname(file).toLowerCase())) continue;
	let text;
	try {
		text = readFileSync(path.join(repoRoot, file), 'utf8');
	} catch (error) {
		// A path git tracks but this checkout does not carry (sparse, or a
		// submodule) reads as ENOENT and is genuinely nothing to say anything
		// about. Anything else is coverage quietly going missing, so it speaks.
		if (error.code !== 'ENOENT') {
			console.error(`  warning: could not read ${file} (${error.code}) — not scanned`);
		}
		continue;
	}
	if (text.includes('\0')) continue;
	scanned += 1;
	const route = routeOf(file);
	scan(text, route, file);
	if (file.startsWith('.githooks/')) {
		/*
		 * Credit only, never accuse: a bare word after `run` is enough to say
		 * "this entry is called here", and nowhere near enough to say a name is
		 * a dead reference. `run npm ci`, or any other shell function called
		 * `run`, would otherwise fail the gate over a legitimate hook line.
		 */
		for (const match of text.matchAll(HOOK_CALL)) {
			if (names.has(match[1])) record(match[1], route, file, `run ${match[1]}`);
		}
	}
}

/** In precedence order: what keeps an entry alive most strongly comes first. */
const ROUTES = ['automation', 'composite', 'documentation'];
const reachedBy = new Map(ROUTES.map((route) => [route, []]));
const findings = { unreachable: [], testOnly: [] };
const staleExemptions = [];

for (const name of names) {
	const refs = references.get(name);
	const live = refs.filter((ref) => ROUTES.includes(ref.route));

	// `Object.hasOwn`, not `in`: `in` walks the prototype chain, so an entry
	// named `constructor` or `toString` would read as exempt and disappear from
	// both the findings and the counts.
	if (Object.hasOwn(EXEMPT, name)) {
		if (live.length > 0) {
			staleExemptions.push(
				`"${name}" is exempt but now reached from ${live[0].where} — drop it from EXEMPT ` +
					`(was: ${EXEMPT[name]})`
			);
		}
		continue;
	}

	if (live.length > 0) {
		// One route per entry so the totals add up: a machine that runs it, then
		// a script that chains it, then a human reading about it.
		const route = ROUTES.find((candidate) => live.some((ref) => ref.route === candidate));
		reachedBy.get(route).push(name);
		continue;
	}

	if (refs.some((ref) => ref.route === 'test')) {
		findings.testOnly.push({ name, where: refs.find((ref) => ref.route === 'test').where });
		continue;
	}

	findings.unreachable.push({
		name,
		historical: refs.filter((ref) => ref.route === 'historical').map((ref) => ref.where)
	});
}

for (const [name, reason] of Object.entries(EXEMPT)) {
	if (!names.has(name)) {
		staleExemptions.push(
			`"${name}" is exempt but no longer a script in ${PACKAGE_JSON} — drop it from EXEMPT ` +
				`(was: ${reason})`
		);
	}
}

const problems =
	findings.unreachable.length + findings.testOnly.length + broken.length + staleExemptions.length;

if (problems === 0) {
	// Every exemption names a live entry, or one of them would be a finding
	// above and this branch would not run.
	const exempt = Object.keys(EXEMPT);
	console.log(
		`npm script callers OK: ${names.size} entries in ${PACKAGE_JSON}, ${scanned} files scanned — ` +
			ROUTES.map((route) => `${reachedBy.get(route).length} ${route}`).join(', ') +
			`, ${exempt.length} exempt (${exempt.join(', ')}).`
	);
	process.exit(0);
}

// Not "entries nothing reaches": two of the four sections below are about a
// reference that names nothing, and about an exemption that stopped holding.
console.error(`Problems with the npm scripts in ${PACKAGE_JSON}:\n`);

if (findings.unreachable.length > 0) {
	console.error(`  no caller anywhere (${findings.unreachable.length}):`);
	for (const hit of findings.unreachable) {
		const seen =
			hit.historical.length > 0
				? `  (named only in ${hit.historical.join(', ')}, which records the past)`
				: '';
		console.error(`      ${hit.name}${seen}`);
	}
	console.error(
		'    Document it first — five of the six entries this gate was built for turned out\n' +
			'    to be working tools (CONTRIBUTING.md, or backend/RELEASE_CHECKLIST.md for a\n' +
			'    release step). Delete it only once you know it is residue, as #422 was.\n'
	);
}

if (findings.testOnly.length > 0) {
	console.error(`  only a test names these (${findings.testOnly.length}):`);
	for (const hit of findings.testOnly) console.error(`      ${hit.name}  (${hit.where})`);
	console.error(
		'    A fixture or a comment in a test is not a caller. Document the entry where a\n' +
			'    human would look for it, or delete it with the mention.\n'
	);
}

if (broken.length > 0) {
	console.error(`  named but not defined (${broken.length}):`);
	for (const hit of broken) console.error(`      ${hit.spelling}  (${hit.where})`);
	console.error(
		'    This is the direction check:refs covers for prose only. A dead reference in a\n' +
			'    workflow, a hook or a script fails when it runs, not before.\n'
	);
}

if (staleExemptions.length > 0) {
	console.error(`  exemptions that no longer hold (${staleExemptions.length}):`);
	for (const problem of staleExemptions) console.error(`      ${problem}`);
	console.error('');
}

process.exitCode = 1;
