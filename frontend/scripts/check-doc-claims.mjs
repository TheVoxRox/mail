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

if (problems.length > 0) {
	console.error('Doc claims are stale:');
	for (const problem of problems) {
		console.error(`- ${problem}`);
	}
	process.exitCode = 1;
} else {
	console.log(
		`Doc claims OK: ${DOC_FILES.length} docs match the stack (Spring Boot ${springBootMajor}, Java ${javaMajor}, Node ${nodeMajor}).`
	);
}
