import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';

const repoRoot = path.join(process.cwd(), '..');

// Human-maintained docs that state stack facts. Changelogs, todo files and
// the release checklist are historical records and intentionally not scanned.
const DOC_FILES = [
	'README.md',
	'CONTRIBUTING.md',
	'SECURITY.md',
	'backend/README.md',
	'frontend/README.md',
	'frontend/END_USER_README.md'
];

async function readText(relativePath) {
	return readFile(path.join(repoRoot, relativePath), 'utf8');
}

function major(version, source) {
	const match = /(\d+)/.exec(version);
	if (!match) {
		throw new Error(`Cannot parse a major version from "${version}" (${source})`);
	}
	return match[1];
}

function depMajor(packageJson, name) {
	const raw = packageJson.dependencies?.[name] ?? packageJson.devDependencies?.[name];
	if (!raw) {
		throw new Error(`frontend/package.json does not declare a dependency on ${name}`);
	}
	return major(raw, `frontend/package.json ${name}`);
}

const [packageJson, pomSource, nvmrc, ...docs] = await Promise.all([
	readText('frontend/package.json').then(JSON.parse),
	readText('backend/pom.xml'),
	readText('.nvmrc'),
	...DOC_FILES.map((file) => readText(file))
]);

function pomValue(pattern, description) {
	const match = pattern.exec(pomSource);
	if (!match) {
		throw new Error(`backend/pom.xml does not contain ${description}`);
	}
	return match[1];
}

const springBootMajor = major(
	pomValue(
		/<artifactId>spring-boot-starter-parent<\/artifactId>\s*<version>([^<]+)<\/version>/,
		'a spring-boot-starter-parent <version>'
	),
	'backend/pom.xml spring-boot-starter-parent'
);
const javaMajor = pomValue(/<java\.version>(\d+)<\/java\.version>/, 'a <java.version> property');
const nodeMajor = major(nvmrc.trim(), '.nvmrc');

// Each pattern matches only "<name> <digits>", so prose without a version
// ("the Tauri updater") is ignored. \bSvelte does not match inside "SvelteKit".
const CLAIMS = [
	{
		pattern: /Spring Boot (\d+)/g,
		expected: springBootMajor,
		source: 'backend/pom.xml spring-boot-starter-parent'
	},
	{
		pattern: /\b(?:Java|JDK) (\d+)/g,
		expected: javaMajor,
		source: 'backend/pom.xml <java.version>'
	},
	{
		pattern: /SvelteKit (\d+)/g,
		expected: depMajor(packageJson, '@sveltejs/kit'),
		source: 'frontend/package.json @sveltejs/kit'
	},
	{
		pattern: /\bSvelte (\d+)/g,
		expected: depMajor(packageJson, 'svelte'),
		source: 'frontend/package.json svelte'
	},
	{
		pattern: /Tailwind CSS (\d+)/g,
		expected: depMajor(packageJson, 'tailwindcss'),
		source: 'frontend/package.json tailwindcss'
	},
	{
		pattern: /Tauri (\d+)/g,
		expected: depMajor(packageJson, '@tauri-apps/api'),
		source: 'frontend/package.json @tauri-apps/api'
	},
	{
		pattern: /Node(?:\.js)? (\d+)/g,
		expected: nodeMajor,
		source: '.nvmrc'
	}
];

// Phrases that always signal a stale doc, independent of any version.
const FORBIDDEN = [
	{ pattern: /\(TBD\)/, reason: 'unresolved (TBD) placeholder' },
	{ pattern: /private development/i, reason: 'the repository is public' }
];

const problems = [];

DOC_FILES.forEach((file, index) => {
	docs[index].split('\n').forEach((line, lineIndex) => {
		for (const claim of CLAIMS) {
			for (const match of line.matchAll(claim.pattern)) {
				if (match[1] !== claim.expected) {
					problems.push(
						`${file}:${lineIndex + 1} says "${match[0]}" but ${claim.source} has major ${claim.expected}`
					);
				}
			}
		}
		for (const rule of FORBIDDEN) {
			const match = rule.pattern.exec(line);
			if (match) {
				problems.push(`${file}:${lineIndex + 1} contains "${match[0]}" — ${rule.reason}`);
			}
		}
	});
});

/*
 * Computed claims: numbers a doc states that this script can recount from the
 * source of truth. The version claims above catch a stale dependency; these
 * catch a stale *description of the code* — the B3 audit said "15
 * controllers" for a month after two more landed, and nothing noticed because
 * the number was prose.
 *
 * A pattern that stops matching is a FAILURE, never a silent pass. Rewording
 * the sentence must force you to look at the number again; the alternative is
 * a check that quietly stops checking, which is worse than no check at all.
 */
const WORD_NUMBERS = [
	'zero',
	'one',
	'two',
	'three',
	'four',
	'five',
	'six',
	'seven',
	'eight',
	'nine',
	'ten',
	'eleven',
	'twelve',
	'thirteen',
	'fourteen',
	'fifteen',
	'sixteen',
	'seventeen',
	'eighteen',
	'nineteen',
	'twenty'
];

function numeral(value) {
	const asNumber = Number(value);
	if (!Number.isNaN(asNumber)) return asNumber;
	const index = WORD_NUMBERS.indexOf(String(value).toLowerCase());
	return index === -1 ? NaN : index;
}

function backendFiles() {
	return execFileSync('git', ['ls-files', 'backend/src/main/java'], {
		cwd: repoRoot,
		encoding: 'utf8'
	})
		.split('\n')
		.filter((f) => f.endsWith('.java'))
		.map((f) => ({ file: f, source: readFileSync(path.join(repoRoot, f), 'utf8') }));
}

// `\b` excludes @RestControllerAdvice on GlobalExceptionHandler, which is not
// a controller — the same trap the audit's own regenerate command documents.
const controllers = backendFiles().filter(({ source }) => /@RestController\b/.test(source));
const counts = {
	total: controllers.length,
	internal: controllers.filter(({ source }) => source.includes('/api/internal')).length,
	validated: controllers.filter(({ source }) => source.includes('@Validated')).length,
	hidden: controllers.filter(({ source }) => source.includes('@Hidden')).length
};
counts.public = counts.total - counts.internal;

const ciSource = await readText('.github/workflows/ci.yml');
// Scoped to the `jobs:` block — the trigger keys under `on:` sit at the same
// indentation and would otherwise be counted as jobs.
const jobsBlock = ciSource.split(/^jobs:$/m)[1];
if (!jobsBlock) {
	throw new Error('.github/workflows/ci.yml has no `jobs:` block');
}
const ciJobs = [...jobsBlock.split(/^[a-z]/m)[0].matchAll(/^ {2}([a-z][a-z0-9_-]*):$/gm)].map(
	(m) => m[1]
);

const COMPUTED = [
	{
		file: 'docs/API_SURFACE_AUDIT.md',
		what: 'controller enumeration in the method statement',
		pattern: /all (\d+) `@RestController`s \((\d+) public \+ the (\d+) `\/api\/internal` ones\)/,
		expected: [counts.total, counts.public, counts.internal]
	},
	{
		file: 'docs/API_SURFACE_AUDIT.md',
		what: '@Validated claim in §2',
		pattern: /(\w+) of the (\d+) controllers are `@Validated`/,
		expected: [counts.validated, counts.total]
	},
	{
		file: 'docs/API_SURFACE_AUDIT.md',
		what: 'regenerate-command comments',
		pattern:
			/# (\d+) controllers[\s\S]*?# (\d+) internal \((\d+) public\)[\s\S]*?# (\d+) @Validated[\s\S]*?# (\d+) hidden/,
		expected: [counts.total, counts.internal, counts.public, counts.validated, counts.hidden]
	},
	{
		file: 'frontend/README.md',
		what: 'CI job count',
		pattern: /workflow is split into (\d+) jobs/,
		expected: [ciJobs.length]
	}
];

for (const claim of COMPUTED) {
	const source = await readText(claim.file);
	const match = claim.pattern.exec(source.replace(/\s+/g, ' '));
	if (!match) {
		problems.push(
			`${claim.file}: cannot find the ${claim.what} — the wording changed. ` +
				`Re-check the number, then update the pattern in check-doc-claims.mjs.`
		);
		continue;
	}
	claim.expected.forEach((value, index) => {
		const stated = numeral(match[index + 1]);
		if (stated !== value) {
			problems.push(
				`${claim.file}: ${claim.what} says "${match[index + 1]}" where the code has ${value}`
			);
		}
	});
}

const readmeSource = await readText('frontend/README.md');
for (const job of ciJobs) {
	if (!readmeSource.includes(`\`${job}\``)) {
		problems.push(`frontend/README.md does not mention the CI job \`${job}\` from ci.yml`);
	}
}

if (problems.length > 0) {
	console.error('Doc claims are stale:');
	for (const problem of problems) {
		console.error(`- ${problem}`);
	}
	process.exitCode = 1;
} else {
	console.log(
		`Doc claims OK: ${DOC_FILES.length} docs match the stack (Spring Boot ${springBootMajor}, ` +
			`Java ${javaMajor}, Node ${nodeMajor}); ${COMPUTED.length} computed claims match the code ` +
			`(${counts.total} controllers, ${ciJobs.length} CI jobs).`
	);
}
