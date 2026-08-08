import { execFileSync } from 'node:child_process';
import path from 'node:path';
import process from 'node:process';

/*
 * Fails a change that alters what the product does with the user's data
 * without touching the document that promises what it does with the user's
 * data.
 *
 * The other doc gates compare a claim against the code. This one cannot: no
 * script can read `mailFrame.ts` and decide whether the privacy policy is
 * still true. What it can do is refuse to let the question go unasked. The
 * remote-image opt-in shipped with a security audit describing it in detail
 * and a privacy policy that still said the app only ever talks to your mail
 * server, your OAuth provider and GitHub — nobody was dishonest, the question
 * simply never came up at the moment the code changed.
 *
 * The escape hatch is deliberate and cheap: a `Docs-impact:` trailer in any
 * commit of the range. A gate with no way out gets bypassed wholesale, and a
 * one-line reason in the history is worth more than a green check.
 *
 * Usage: node scripts/check-docs-impact.mjs --base <ref>
 */

const repoRoot = path.join(process.cwd(), '..');

const RULES = [
	{
		name: 'user-visible network egress',
		// Paths where a change can alter what leaves the device, or where it goes.
		triggers: [
			'frontend/src/lib/mail/mailFrame.ts',
			'frontend/src/lib/api/remoteImages.ts',
			'frontend/src/lib/updates.ts',
			'frontend/src-tauri/capabilities',
			'frontend/src-tauri/tauri.conf.json',
			'backend/src/main/java/org/voxrox/mailbackend/feature/mail/service/RemoteImageAllowlistService.java',
			'backend/src/main/java/org/voxrox/mailbackend/feature/auth',
			'backend/src/main/java/org/voxrox/mailbackend/core/diagnostic'
		],
		requires: ['PRIVACY.md', 'PRIVACY.en.md'],
		why:
			'These paths decide what data leaves the device and where it goes. ' +
			'PRIVACY.md enumerates that exhaustively ("only in these cases"), so a ' +
			'change here either belongs in the policy or is a deliberate no-op for it.'
	},
	{
		name: 'locally stored user data',
		triggers: ['backend/src/main/resources/db/migration'],
		requires: ['PRIVACY.md', 'PRIVACY.en.md'],
		why:
			'A schema change can add a new category of personal data. PRIVACY.md ' +
			'describes what mail.db holds, table by table in prose.'
	}
];

function arg(name) {
	const index = process.argv.indexOf(name);
	return index === -1 ? undefined : process.argv[index + 1];
}

const base = arg('--base');
if (!base) {
	console.error('Usage: node scripts/check-docs-impact.mjs --base <ref>');
	process.exit(2);
}

// A first push to a new branch has no "before" commit to diff against.
if (/^0{40}$/.test(base)) {
	console.log('Docs impact skipped: no base commit for this event.');
	process.exit(0);
}

function git(args) {
	return execFileSync('git', args, { cwd: repoRoot, encoding: 'utf8' }).trim();
}

let mergeBase;
try {
	mergeBase = git(['merge-base', base, 'HEAD']);
} catch {
	console.error(
		`Cannot resolve a merge base between "${base}" and HEAD. ` +
			'In CI the checkout needs full history (fetch-depth: 0).'
	);
	process.exit(2);
}

const changed = git(['diff', '--name-only', `${mergeBase}..HEAD`])
	.split('\n')
	.filter(Boolean);

if (changed.length === 0) {
	console.log('Docs impact OK: no files changed in range.');
	process.exit(0);
}

// Any commit in the range may carry the waiver; the reason lands in history.
const trailers = git(['log', '--format=%B', `${mergeBase}..HEAD`]);
const waiver = /^Docs-impact:\s*(.+)$/m.exec(trailers);

const problems = [];
for (const rule of RULES) {
	const hits = changed.filter((file) =>
		rule.triggers.some((trigger) => file === trigger || file.startsWith(`${trigger}/`))
	);
	if (hits.length === 0) continue;

	const documented = rule.requires.filter((doc) => changed.includes(doc));
	if (documented.length === rule.requires.length) continue;

	problems.push(
		`${rule.name}: changed ${hits.map((h) => `\n      ${h}`).join('')}\n` +
			`    but did not touch ${rule.requires.filter((d) => !documented.includes(d)).join(' and ')}.\n` +
			`    ${rule.why}`
	);
}

if (problems.length === 0) {
	console.log(`Docs impact OK: ${changed.length} changed file(s), no rule triggered.`);
} else if (waiver) {
	console.log(`Docs impact waived: "${waiver[1].trim()}"`);
	for (const problem of problems) console.log(`  - ${problem}`);
} else {
	console.error('This change may need a documentation update:');
	for (const problem of problems) console.error(`  - ${problem}`);
	console.error(
		'\nUpdate the document, or record why it does not apply with a commit trailer:\n' +
			'    Docs-impact: none — <reason>\n' +
			'(and remember PRIVACY/SECURITY changes need the voxrox.org pages resynced).'
	);
	process.exitCode = 1;
}
