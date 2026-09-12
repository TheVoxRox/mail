import { execFileSync } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import process from 'node:process';
import { resolveObjects } from './lib/git-objects.mjs';
import {
	SIGNED_WORKFLOW,
	checkManifest,
	compareObjectIds,
	decideNextStep,
	findMissingAssets,
	parseCandidateSheet,
	parseGitHubSlug,
	parseLsRemote,
	parseSha256File,
	pickCiResult,
	pickInstaller,
	pickProvenance,
	pickScan,
	pickSignedRun,
	readGhAnswer,
	readProvenance,
	shortSha
} from './lib/release-status.mjs';

/*
 * `npm run release:status` (from frontend/) — where the release stands, and
 * the one thing to do next.
 *
 * The state of a release is spread over places nobody reads together: the
 * candidate sheet in backend/RELEASE_CHECKLIST.md, the tag on origin, the
 * signed build and the draft on GitHub, and the maintainer's task list. On
 * 2026-09-12 that list was two days behind on three facts at once — a
 * superseded candidate, an issue that had closed, and an empty tag list while
 * the tag and a draft built from it both existed. Each of those is something a
 * machine can read, so this reads them in the order docs/RELEASE_PROCESS.md
 * runs and stops at the first condition that is not met.
 *
 * READ-ONLY by design: it does not fetch, tag, push or dispatch a workflow.
 * Re-tagging deletes a pushed ref, and a status command that could do that is
 * a status command nobody dares to run. The commands it prints are for the
 * operator.
 *
 * It reads the checklist and the version from MAIN, not from the working tree.
 * A branch that is behind main carries an older sheet, and a local version
 * bump names a tag main does not have; either would be judged against origin's
 * tag and draft as if it were the release. A difference is reported as a note.
 *
 * A gh call that fails is recorded as unreadable, never read as absence. A
 * rate limit that turned into "nothing has been built yet" would send the
 * operator off to a signed build that overwrites the draft's assets.
 *
 * The installer is never downloaded. The commit it was built from comes from
 * the build provenance GitHub stores under the digest in the attached .sha256 —
 * a lookup, not a verification of the attestation's signature. Verifying a
 * download is END_USER_README's procedure and stays a step of its own.
 *
 * The output uses words rather than symbols, because it is read with a screen
 * reader.
 */

const repoRoot = path.join(process.cwd(), '..');
const CHECKLIST = 'backend/RELEASE_CHECKLIST.md';
const TAURI_CONFIG = 'frontend/src-tauri/tauri.conf.json';

/** Runs a command without throwing; `stderr` carries the reason when `ok` is false. */
function run(command, args) {
	try {
		const stdout = execFileSync(command, args, {
			cwd: repoRoot,
			encoding: 'utf8',
			stdio: ['ignore', 'pipe', 'pipe'],
			windowsHide: true
		});
		return { ok: true, stdout, stderr: '' };
	} catch (error) {
		return { ok: false, stdout: '', stderr: String(error.stderr || error.message).trim() };
	}
}

const firstLine = (text) => text.split(/\r?\n/)[0] || 'gh failed';

/** What GitHub could not answer, by name. Non-empty means no GitHub-based step is judged. */
const unreadable = [];

/**
 * Runs gh with its arguments as one space-separated string, so each call reads
 * as the command a person would type; every value interpolated into one (repo
 * slug, tag, commit, asset name) is free of spaces. Returns the parsed JSON,
 * `null` when gh said the thing does not exist (`absent` matches its stderr),
 * and `undefined` after recording it as unreadable.
 */
function gh(what, command, absent) {
	const answer = readGhAnswer(run('gh', command.split(' ')), absent);
	if (answer.state !== 'unreadable') return answer.data;
	unreadable.push(`${what} (${answer.reason})`);
	return undefined;
}

const lsRemote = run('git', ['ls-remote', 'origin', 'refs/heads/main', 'refs/tags/*']);
const refs = lsRemote.ok ? parseLsRemote(lsRemote.stdout) : new Map();
const mainCommit = refs.get('refs/heads/main') ?? null;
const mainLocal =
	mainCommit !== null && resolveObjects(repoRoot, [mainCommit]).get(mainCommit)?.type === 'commit';

const atMain = (file) => {
	const result = mainLocal ? run('git', ['show', `${mainCommit}:${file}`]) : null;
	return result?.ok ? result.stdout : null;
};
const checklistAtMain = atMain(CHECKLIST);
const configAtMain = atMain(TAURI_CONFIG);
const { version = null, productName = null } = configAtMain ? JSON.parse(configAtMain) : {};
const tag = version ? `v${version}` : null;
const tagCommit = tag ? (refs.get(`refs/tags/${tag}`) ?? null) : null;

const sheet = checklistAtMain ? parseCandidateSheet(checklistAtMain) : null;
const sheetIds = sheet?.objectIds ?? [];
const specsAt = (commit) =>
	commit ? [commit, ...sheetIds.map((row) => `${commit}:${row.path}`)] : [];
const objects = resolveObjects(repoRoot, [...specsAt(mainCommit), ...specsAt(tagCommit)]);
const idsAt = (commit) =>
	commit && objects.get(commit)?.type === 'commit'
		? new Map(sheetIds.map((row) => [row.path, objects.get(`${commit}:${row.path}`)?.oid ?? null]))
		: null;
const mainIds = idsAt(mainCommit);
const tagIds = idsAt(tagCommit);
const sheetVsMain = compareObjectIds(sheetIds, mainIds);
const sheetVsTag = tagCommit ? compareObjectIds(sheetIds, tagIds) : null;
const tagOnMain =
	tagIds && mainIds ? run('git', ['merge-base', '--is-ancestor', tagCommit, mainCommit]).ok : null;

const localNotes = [];
const lf = (text) => text.replace(/\r\n/g, '\n');
if (
	checklistAtMain !== null &&
	lf(readFileSync(path.join(repoRoot, CHECKLIST), 'utf8')) !== lf(checklistAtMain)
) {
	localNotes.push(
		`Your working tree's ${CHECKLIST} differs from main's. This reads main's, so a tick that is not merged does not count yet.`
	);
}
const localVersion = JSON.parse(readFileSync(path.join(repoRoot, TAURI_CONFIG), 'utf8')).version;
if (version !== null && localVersion !== version) {
	localNotes.push(
		`Your working tree says version ${localVersion}; main says ${version}, and the tag follows main.`
	);
}

const slug = parseGitHubSlug(run('git', ['remote', 'get-url', 'origin']).stdout);
let mainCi = null;
let tagCi = null;
let signedRun = null;
let release = null;
let installer = null;
let missingAssets = [];
let assetProblem = null;
let provenance = null;
let manifestProblems = [];
let vulnScan = null;

if (mainLocal && tag && !slug) {
	unreadable.push('the repository (origin is not a GitHub remote)');
} else if (mainLocal && tag) {
	const scans = gh(
		'the scheduled vulnerability scans',
		`run list --repo ${slug} --workflow vuln-scan.yml --event schedule --limit 5 --json status,conclusion,createdAt,url`
	);
	vulnScan = scans ? pickScan(scans) : null;

	const ciFor = (commit) =>
		gh(
			`CI for ${shortSha(commit)}`,
			`run list --repo ${slug} --workflow ci.yml --commit ${commit} --json event,headBranch,status,conclusion`
		);
	const mainRuns = ciFor(mainCommit);
	mainCi = mainRuns ? pickCiResult(mainRuns) : null;

	if (tagCommit) {
		const tagRuns = tagCommit === mainCommit ? mainRuns : ciFor(tagCommit);
		tagCi = tagRuns ? pickCiResult(tagRuns) : null;
		const signedRuns = gh(
			'the signed builds',
			`run list --repo ${slug} --workflow ${SIGNED_WORKFLOW} --limit 30 --json headSha,headBranch,status,conclusion,url`
		);
		signedRun = signedRuns ? pickSignedRun(signedRuns, tag, tagCommit) : null;
	}

	release =
		gh(
			`the release for ${tag}`,
			`release view ${tag} --repo ${slug} --json isDraft,assets,url`,
			/release not found/i
		) ?? null;
	if (release) {
		const names = release.assets.map((asset) => asset.name);
		installer = pickInstaller(names, version);
		missingAssets = findMissingAssets(names, version);
	}

	if (installer && missingAssets.length === 0) {
		const dir = mkdtempSync(path.join(os.tmpdir(), 'release-status-'));
		try {
			const download = run('gh', [
				...`release download ${tag} --repo ${slug} --pattern latest.json --pattern ${installer}.sig --pattern ${installer}.sha256 --dir`.split(
					' '
				),
				dir
			]);
			const read = (name) => {
				const file = path.join(dir, name);
				return existsSync(file) ? readFileSync(file, 'utf8') : null;
			};
			const sha256Text = read(`${installer}.sha256`);
			const signature = read(`${installer}.sig`);
			const manifestText = read('latest.json');

			if (!download.ok) {
				unreadable.push(`the draft's assets (${firstLine(download.stderr)})`);
			} else if (sha256Text === null || signature === null || manifestText === null) {
				assetProblem = 'latest.json, the .sig or the .sha256 was not in the download';
			} else {
				const sha256 = parseSha256File(sha256Text, installer);
				if (sha256.problem) assetProblem = `the .sha256 ${sha256.problem}`;
				if (sha256.digest) {
					const attestations = gh(
						'the build provenance',
						`api repos/${slug}/attestations/sha256:${sha256.digest}`,
						/HTTP 404/
					);
					provenance = attestations
						? pickProvenance(readProvenance(attestations), tag, tagCommit)
						: null;
				}
				let manifest = null;
				try {
					manifest = JSON.parse(manifestText);
				} catch {
					// reported as a manifest problem below
				}
				manifestProblems =
					manifest === null
						? ['it is not valid JSON']
						: checkManifest({
								manifest,
								version,
								installerUrl: `https://github.com/${slug}/releases/download/${tag}/${installer}`,
								signature
							});
			}
		} finally {
			rmSync(dir, { recursive: true, force: true });
		}
	}
}

const decision = decideNextStep({
	remote: lsRemote.ok && mainCommit !== null,
	mainLocal,
	productName,
	tag,
	tagCommit,
	mainCommit,
	sheet,
	sheetVsMain,
	sheetVsTag,
	tagOnMain,
	localNotes,
	unreadable,
	mainCi,
	tagCi,
	signedRun,
	release,
	missingAssets,
	assetProblem,
	provenance,
	manifestProblems,
	vulnScan
});

const yesNo = (value) => (value === null ? 'unknown' : value ? 'yes' : 'no');

function describeComparison(comparison) {
	if (comparison.state === 'match') return 'match';
	if (comparison.state === 'unknown') return 'unknown, the commit is not in this clone';
	return `differ in ${comparison.differing
		.map((row) => `${row.path} (sheet ${row.sheet}, commit ${row.actual ?? 'missing'})`)
		.join(', ')}`;
}

const out = [
	`Release status: ${productName ?? 'unknown product'} ${version ?? 'unknown version'}${tag ? `, tag ${tag}` : ''}`,
	''
];

if (mainLocal) {
	if (sheet) {
		const ids = (done) =>
			sheet.sections
				.filter((section) => section.done === done)
				.map((section) => section.id)
				.join(', ') || 'none';
		out.push(`Candidate sheet ${sheet.date}, in ${CHECKLIST} on main`);
		out.push(`  Ticked: ${ids(true)}`);
		out.push(`  Open: ${ids(false)}`);
		if (sheet.unparsedRows.length > 0) {
			out.push(`  Table rows that cannot be read: ${sheet.unparsedRows.length}`);
		}
		if (sheetIds.length > 0) {
			out.push(
				`  Object ids against main ${shortSha(mainCommit)}: ${describeComparison(sheetVsMain)}`
			);
			if (sheetVsTag) {
				out.push(
					`  Object ids against ${tag} ${shortSha(tagCommit)}: ${describeComparison(sheetVsTag)}`
				);
			}
		}
	} else {
		out.push('Candidate sheet: none found on main');
	}

	out.push('', 'Tag');
	if (tagCommit) {
		out.push(`  On origin: yes, at ${shortSha(tagCommit)}`);
		out.push(`  On main: ${yesNo(tagOnMain)}`);
	} else {
		out.push('  On origin: no');
	}

	out.push('', 'GitHub');
	if (unreadable.length > 0) out.push(`  Could not be read: ${unreadable.join('; ')}`);
	if (slug && tag) {
		out.push(`  CI on main for ${shortSha(mainCommit)}: ${mainCi ?? 'no run found'}`);
		if (tagCommit && tagCommit !== mainCommit) {
			out.push(
				`  CI on main for the tag commit ${shortSha(tagCommit)}: ${tagCi ?? 'no run found'}`
			);
		}
		if (tagCommit) {
			out.push(
				`  Signed build from the tag: ${
					signedRun ? `${signedRun.conclusion || signedRun.status}, ${signedRun.url}` : 'none'
				}`
			);
		}
		if (release) {
			out.push(`  Release: ${release.isDraft ? 'draft' : 'published'}, ${release.url}`);
			out.push(`  Missing assets: ${missingAssets.join(', ') || 'none'}`);
			if (provenance) {
				out.push(
					`  Installer built from: ${shortSha(provenance.commit)} on ${provenance.ref}, by build provenance lookup`
				);
			}
			if (installer && missingAssets.length === 0 && !assetProblem) {
				out.push(
					`  latest.json: ${manifestProblems.length === 0 ? 'consistent' : manifestProblems.join('; ')}`
				);
			}
		} else {
			out.push('  Release: none');
		}
		const scan = vulnScan?.completed;
		out.push(
			`  Last completed scheduled vulnerability scan: ${
				scan ? `${scan.conclusion}, ${scan.createdAt.slice(0, 10)}` : 'none found'
			}${vulnScan?.running ? '; a newer one is running' : ''}`
		);
	}
	out.push('');
}

out.push('Next step', `  ${decision.summary}`);
for (const command of decision.commands) out.push(`    ${command}`);
for (const note of decision.notes) out.push(`  Note: ${note}`);

console.log(out.join('\n'));
