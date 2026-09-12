/**
 * The pure half of `npm run release:status`: reading the candidate sheet,
 * choosing which CI run, signed build, installer, provenance entry and scan
 * count, checking the update manifest against the attached signature, and
 * turning all of it into one next step. Everything that talks to git or GitHub
 * lives in ../release-status.mjs and only gathers raw answers, so every choice
 * made about them is tested here on plain values instead of on a live release.
 */

/** A dated per-candidate worksheet in backend/RELEASE_CHECKLIST.md. */
const SHEET_HEADING = /^### Candidate (\d{4}-\d{2}-\d{2})\b/;
/** A row of a sheet's object id table: | `path` | `object id prefix` |. */
const OBJECT_ROW = /^\|\s*`([^`]+)`\s*\|\s*`([0-9a-fA-F]{7,40})`\s*\|/;
/** A Markdown table separator row, `| --- | :--: |`. */
const TABLE_SEPARATOR = /^\|(\s*:?-+:?\s*\|)+\s*$/;
/** A section tick, `- [x] **§3a Installer behaviour** — …`; the title is optional, as on §9. */
const SECTION_BOX = /^- \[([ xX])\] \*\*(§\d+[a-z]?)(?:\s+([^*]+?))?\*\*/;

/** The sections a sheet needs before anything is installed — they gate the tag. */
const MACHINE_SECTIONS = ['§0', '§1', '§2'];

export const SIGNED_WORKFLOW = 'windows-signed-release.yml';
const SIGNED_WORKFLOW_PATH = `.github/workflows/${SIGNED_WORKFLOW}`;
const INSTALLER_SUFFIX = '-windows-x64-setup.exe';

export const shortSha = (sha) => (sha ? sha.slice(0, 7) : 'unknown');
const label = (section) => (section.title ? `${section.id} ${section.title}` : section.id);

/**
 * Returns the newest dated candidate sheet, or null when the checklist holds
 * none. Newest by the date in the heading rather than by position. Two sheets
 * of the same day cannot be told apart by date, so a tie goes to the first in
 * document order — the newest while the appendix keeps sheets newest-first, as
 * it does — and `sameDate` says that a tie happened, so it is not silent.
 *
 * A table row that is not a readable object id row is returned in
 * `unparsedRows` rather than skipped: a skipped `backend/src` row would take
 * that path out of the comparison, and the rest could then read as a match.
 */
export function parseCandidateSheet(markdown) {
	const lines = markdown.split(/\r?\n/);
	let start = -1;
	let date = null;
	let sameDate = 0;
	lines.forEach((line, index) => {
		const heading = SHEET_HEADING.exec(line);
		if (!heading) return;
		if (date === null || heading[1] > date) {
			date = heading[1];
			start = index;
			sameDate = 1;
		} else if (heading[1] === date) {
			sameDate += 1;
		}
	});
	if (start === -1) return null;

	const body = lines.slice(start + 1);
	const end = body.findIndex((line) => /^#{1,3} /.test(line));
	const sheetLines = end === -1 ? body : body.slice(0, end);

	const objectIds = [];
	const unparsedRows = [];
	const sections = [];
	sheetLines.forEach((line, index) => {
		if (line.startsWith('|')) {
			// A separator, and the header row right above one, are not data.
			if (TABLE_SEPARATOR.test(line) || TABLE_SEPARATOR.test(sheetLines[index + 1] ?? '')) return;
			const row = OBJECT_ROW.exec(line);
			if (row) objectIds.push({ path: row[1], id: row[2].toLowerCase() });
			else unparsedRows.push(line.trim());
			return;
		}
		const box = SECTION_BOX.exec(line);
		if (box) sections.push({ id: box[2], title: box[3]?.trim() ?? '', done: box[1] !== ' ' });
	});
	return { date, sameDate, objectIds, unparsedRows, sections };
}

/**
 * Compares a sheet's object ids with the ones a commit actually carries.
 * `actual` maps path -> full object id (null for a path the commit lacks), or
 * is null when the commit itself is not in the local clone. A sheet writes
 * prefixes, so a match is a prefix match.
 */
export function compareObjectIds(sheetIds, actual) {
	if (actual === null || sheetIds.length === 0) return { state: 'unknown', differing: [] };
	const differing = sheetIds
		.filter(({ path, id }) => !(actual.get(path) ?? '').startsWith(id))
		.map(({ path, id }) => ({
			path,
			sheet: id,
			actual: actual.get(path)?.slice(0, id.length) ?? null
		}));
	return { state: differing.length === 0 ? 'match' : 'differs', differing };
}

/** Origin's `owner/repo` for a GitHub remote URL, https or ssh; null for any other host. */
export function parseGitHubSlug(url) {
	const match = /github\.com[:/]([^/\s]+\/[^/\s]+?)(?:\.git)?\/?$/.exec((url ?? '').trim());
	return match ? match[1] : null;
}

/**
 * What a finished gh call means: `ok` with parsed JSON, `absent` when gh said
 * the thing does not exist (`absent` matches its stderr), or `unreadable` with
 * a reason. Any other failure is unreadable, never absence: a rate limit read
 * as "there is none" turns into "nothing has been built yet", which sends the
 * operator to a signed build that overwrites the draft's assets.
 */
export function readGhAnswer({ ok, stdout, stderr }, absent) {
	if (!ok) {
		if (absent?.test(stderr)) return { state: 'absent', data: null };
		return { state: 'unreadable', reason: stderr.split(/\r?\n/)[0] || 'gh failed' };
	}
	try {
		return { state: 'ok', data: JSON.parse(stdout) };
	} catch {
		return { state: 'unreadable', reason: 'gh did not print JSON' };
	}
}

/**
 * ref -> commit from `git ls-remote` output. For an annotated tag the plain
 * line carries the tag OBJECT, and only the peeled `^{}` line carries the
 * commit, so the peeled line wins; a lightweight tag has no peeled line.
 */
export function parseLsRemote(text) {
	const refs = new Map();
	const peeled = new Set();
	for (const line of text.split(/\r?\n/)) {
		const [sha, ref] = line.trim().split(/\s+/);
		if (!sha || !ref) continue;
		if (ref.endsWith('^{}')) {
			refs.set(ref.slice(0, -3), sha);
			peeled.add(ref.slice(0, -3));
		} else if (!peeled.has(ref)) {
			refs.set(ref, sha);
		}
	}
	return refs;
}

/**
 * CI for a commit on main: the push run's conclusion, its status while it is
 * still running, or null when main has no run for it. A pull-request run on
 * the same commit is not the one RELEASE_PROCESS step 3 relies on.
 */
export function pickCiResult(runs) {
	const run = runs.find(
		(candidate) => candidate.event === 'push' && candidate.headBranch === 'main'
	);
	if (!run) return null;
	return run.status === 'completed' ? run.conclusion : run.status;
}

/**
 * The newest signed run dispatched FROM the tag. A run from a branch at the
 * same commit is not one: RELEASE_PROCESS step 4 builds from the tag, and a
 * branch run is the draft-before-tag path it exists to close.
 */
export function pickSignedRun(runs, tag, tagCommit) {
	return runs.find((run) => run.headSha === tagCommit && run.headBranch === tag) ?? null;
}

/**
 * The installer for exactly this version. An `endsWith` rather than a
 * contains test, because a search for `0.1.0` must not pick
 * `voxrox-mail-0.1.0-beta.1-windows-x64-setup.exe`.
 */
export function pickInstaller(names, version) {
	return names.find((name) => name.endsWith(`-${version}${INSTALLER_SUFFIX}`)) ?? null;
}

/** The assets a publishable draft needs (RELEASE_PROCESS step 6) that this one lacks. */
export function findMissingAssets(names, version) {
	const installer = pickInstaller(names, version);
	const expected = installer
		? [installer, `${installer}.sig`, `${installer}.sha256`, 'latest.json']
		: [`the ${version} installer (*-${version}${INSTALLER_SUFFIX})`, 'latest.json'];
	return expected.filter((name) => !names.includes(name));
}

/**
 * Checks latest.json against what it has to agree with. The signature is
 * compared with the attached .sig, not verified: the signing-pair gate in the
 * signed workflow verifies it at build time, and what is left to catch here is
 * assets that stopped belonging together, for instance after a partial
 * re-upload.
 */
export function checkManifest({ manifest, version, installerUrl, signature }) {
	const problems = [];
	if (manifest?.version !== version) {
		problems.push(`version is ${JSON.stringify(manifest?.version)}, expected "${version}"`);
	}
	const platform = manifest?.platforms?.['windows-x86_64'];
	if (!platform) return [...problems, 'it has no windows-x86_64 entry'];
	if (platform.url !== installerUrl) {
		problems.push(`the installer URL is ${platform.url}, expected ${installerUrl}`);
	}
	if (platform.signature?.trim() !== signature.trim()) {
		problems.push('its signature is not the attached .sig');
	}
	return problems;
}

/** Reads the `<sha256> *<file>` line the signed workflow uploads next to the installer. */
export function parseSha256File(text, installerName) {
	const match = /^([0-9a-f]{64}) \*?(.+)$/.exec(text.trim());
	if (!match) return { digest: null, problem: 'is not a "<sha256> *<file>" line' };
	if (match[2].trim() !== installerName) {
		return { digest: match[1], problem: `names ${match[2].trim()}, expected ${installerName}` };
	}
	return { digest: match[1], problem: null };
}

/**
 * Extracts commit, ref and workflow from GitHub's attestations response for a
 * digest. This reads the in-toto statement; it does not check the Sigstore
 * signature around it, which is what `gh attestation verify` is for.
 */
export function readProvenance(response) {
	return (response?.attestations ?? []).flatMap((attestation) => {
		try {
			const payload = attestation.bundle.dsseEnvelope.payload;
			const statement = JSON.parse(Buffer.from(payload, 'base64').toString('utf8'));
			const definition = statement.predicate.buildDefinition;
			const source = definition.resolvedDependencies?.find((dep) => dep?.digest?.gitCommit);
			return [
				{
					commit: source?.digest.gitCommit ?? null,
					ref: definition.externalParameters?.workflow?.ref ?? null,
					workflow: definition.externalParameters?.workflow?.path ?? null
				}
			];
		} catch {
			return [];
		}
	});
}

/** The signed workflow's build of a digest, preferring one made from the tag itself. */
export function pickProvenance(builds, tag, tagCommit) {
	const signed = builds.filter((build) => build.workflow === SIGNED_WORKFLOW_PATH);
	return (
		signed.find((build) => build.commit === tagCommit && build.ref === `refs/tags/${tag}`) ??
		signed[0] ??
		null
	);
}

/**
 * The last COMPLETED scheduled scan, and whether a newer one is running. The
 * newest run alone would read a scan still in progress as a result, and hide
 * the one that actually finished.
 */
export function pickScan(runs) {
	return {
		completed: runs.find((run) => run.status === 'completed') ?? null,
		running: runs.length > 0 && runs[0].status !== 'completed'
	};
}

/**
 * Turns the gathered facts into the one next step, in the order
 * docs/RELEASE_PROCESS.md runs: sheet, tag, CI, signed build, assets, manual
 * sections, approval. The first unmet condition wins. Nothing after it is
 * judged, because it would be judged against the wrong build.
 *
 * Checks that git alone can answer come first, so a machine without gh still
 * hears that it needs a fetch or a new sheet. Everything that needs GitHub
 * comes after the `unreadable` check — including any tag suggestion, because a
 * tag goes only on a commit whose CI is green.
 */
export function decideNextStep(facts) {
	const { tag, tagCommit, mainCommit, sheet } = facts;
	const notes = [...facts.localNotes];
	const step = (summary, commands = []) => ({ summary, commands, notes });
	const differingPaths = (comparison) => comparison.differing.map((row) => row.path).join(', ');
	const tagCommands = [
		`git tag -a ${tag} -m "${facts.productName} ${tag}" ${mainCommit}`,
		`git push origin ${tag}`
	];
	const buildCommand = `gh workflow run ${SIGNED_WORKFLOW} --ref ${tag}`;

	if (!facts.remote) {
		return step('origin cannot be reached, so neither main nor the tag can be read.');
	}
	if (!facts.mainLocal) {
		return step(
			`main (${shortSha(mainCommit)}) is not in this clone, so its checklist and version cannot be read.`,
			['git fetch origin']
		);
	}
	if (!sheet) {
		return step(
			'The checklist on main holds no dated candidate sheet. Start one in the appendix of backend/RELEASE_CHECKLIST.md.'
		);
	}
	if (sheet.sameDate > 1) {
		notes.push(
			`${sheet.sameDate} sheets carry the date ${sheet.date}. This reads the first of them, ` +
				'which is the newest only while the sheets are kept newest-first.'
		);
	}
	if (sheet.unparsedRows.length > 0) {
		return step(
			`The ${sheet.date} sheet has table rows that cannot be read as object ids, so their paths ` +
				`would go uncompared: ${sheet.unparsedRows.join(' ')} Write each as | \`path\` | \`object id\` |.`
		);
	}
	if (sheet.objectIds.length === 0) {
		return step(
			`The ${sheet.date} sheet names no object ids, so nothing can be compared with it. ` +
				'Add the object id table the checklist appendix describes.'
		);
	}
	if (facts.release?.isDraft === false) {
		return step(
			`${tag} is already published. What is left is RELEASE_PROCESS step 7: ` +
				'confirm the manifest is reachable through the channel URL.'
		);
	}

	// main is in the clone and the sheet names ids, so sheetVsMain is 'match' or 'differs' here.
	if (tagCommit) {
		if (facts.sheetVsTag.state === 'unknown') {
			return step(`The tag commit ${shortSha(tagCommit)} is not in this clone.`, [
				'git fetch origin --tags'
			]);
		}
		if (facts.sheetVsTag.state === 'differs' && facts.sheetVsMain.state === 'differs') {
			return step(
				`The ${sheet.date} sheet matches neither ${tag} (${shortSha(tagCommit)}) nor main ` +
					`(${shortSha(mainCommit)}). Re-take §1 on main, and §2 if a frontend path moved, ` +
					'and start a new sheet.'
			);
		}
	} else {
		if (facts.release?.isDraft) {
			notes.push(
				`A draft for ${tag} exists without its tag. Publishing it now would create the tag ` +
					'from target_commitish "main", not from the commit its installer was built from.'
			);
		}
		if (facts.sheetVsMain.state === 'differs') {
			return step(
				`main (${shortSha(mainCommit)}) differs from the ${sheet.date} sheet in ` +
					`${differingPaths(facts.sheetVsMain)}. Re-take §1 on main, and §2 if a frontend ` +
					'path moved, and start a new sheet.'
			);
		}
		const unfinished = MACHINE_SECTIONS.filter(
			(id) => !sheet.sections.some((section) => section.id === id && section.done)
		);
		if (unfinished.length > 0) {
			return step(`Finish ${unfinished.join(', ')} on the ${sheet.date} sheet before tagging.`);
		}
	}

	if (facts.unreadable.length > 0) {
		return step(
			`GitHub could not be read, and every step from here depends on it: ${facts.unreadable.join('; ')}. ` +
				'Check `gh auth status` and run this again.'
		);
	}

	const mainNotGreen =
		facts.mainCi === 'success'
			? null
			: `${facts.mainCi === null ? 'No CI run on main was found' : `CI on main is ${facts.mainCi}`} ` +
				`for ${shortSha(mainCommit)}, and RELEASE_PROCESS step 3 tags only a green commit.`;

	if (!tagCommit) {
		if (mainNotGreen) return step(`${mainNotGreen} Wait for it or fix it before tagging.`);
		return step(
			`main (${shortSha(mainCommit)}) matches the ${sheet.date} sheet, §0–§2 are ticked and CI is green. ` +
				'Tag it (RELEASE_PROCESS step 3), then build from the tag (step 4):',
			[...tagCommands, buildCommand]
		);
	}

	if (facts.sheetVsTag.state === 'differs') {
		const stale =
			`${tag} points at ${shortSha(tagCommit)}, which differs from the ${sheet.date} sheet in ` +
			`${differingPaths(facts.sheetVsTag)}; main (${shortSha(mainCommit)}) matches the sheet.`;
		if (mainNotGreen)
			return step(`${stale} ${mainNotGreen} Wait for it or fix it before re-tagging.`);
		notes.push(
			'Do not publish the draft between deleting the tag and pushing the new one: ' +
				'without its tag it falls back to target_commitish "main".',
			'RELEASE_PROCESS step 3 also says to delete the draft release. Its body is the ' +
				'only copy of the release notes, so save it first if you do.'
		);
		return step(`${stale} Re-tag main (RELEASE_PROCESS step 3):`, [
			`git tag -d ${tag}`,
			`git push origin :refs/tags/${tag}`,
			...tagCommands
		]);
	}
	if (facts.sheetVsMain.state === 'differs') {
		notes.push(
			`main has moved past the tag in ${differingPaths(facts.sheetVsMain)}. ` +
				'That does not matter while the tag stands: the candidate is the tag.'
		);
	}

	if (facts.tagOnMain === false) {
		return step(
			`${tag} (${shortSha(tagCommit)}) is not on main. RELEASE_PROCESS step 3 tags only a commit on main.`
		);
	}
	if (facts.tagCi !== 'success') {
		return step(
			facts.tagCi === null
				? `No CI run on main was found for ${shortSha(tagCommit)}.`
				: `CI on main for ${shortSha(tagCommit)} is ${facts.tagCi}. RELEASE_PROCESS step 3 tags only a green commit.`
		);
	}

	const run = facts.signedRun;
	if (!run) {
		return step(
			`Nothing has been built from ${tag} yet. Run Windows Signed Release from the tag (RELEASE_PROCESS step 4):`,
			[buildCommand]
		);
	}
	if (run.status !== 'completed') {
		return step(`Windows Signed Release is still running for ${tag}: ${run.url}`);
	}
	if (run.conclusion !== 'success') {
		return step(
			`The last Windows Signed Release run for ${tag} ended ${run.conclusion}: ${run.url}`
		);
	}
	if (!facts.release) {
		return step(
			`The signed build of ${tag} succeeded, but no release exists for the tag: ${run.url}`
		);
	}
	if (facts.missingAssets.length > 0) {
		return step(
			`The draft is missing ${facts.missingAssets.join(', ')}. Re-run Windows Signed Release from the tag:`,
			[buildCommand]
		);
	}
	if (facts.assetProblem) {
		return step(`The draft's assets are wrong: ${facts.assetProblem}.`);
	}

	const built = facts.provenance;
	const origin = !built
		? 'No build provenance was found for the attached installer, so where it came from is unknown.'
		: built.commit !== tagCommit
			? `The attached installer was built from ${shortSha(built.commit)}, not from ${tag} (${shortSha(tagCommit)}).`
			: built.ref !== `refs/tags/${tag}`
				? `The attached installer was built from ${built.ref}, not from the tag, although at the same commit.`
				: null;
	if (origin) {
		return step(`${origin} Re-run Windows Signed Release from the tag:`, [buildCommand]);
	}
	if (facts.manifestProblems.length > 0) {
		return step(`latest.json in the draft is wrong: ${facts.manifestProblems.join('; ')}.`);
	}

	const scan = facts.vulnScan?.completed;
	if (scan && scan.conclusion !== 'success') {
		notes.push(
			`The last completed scheduled vulnerability scan ended ${scan.conclusion}. ` +
				`RELEASE_PROCESS step 6 needs its cause named before publishing: ${scan.url}`
		);
	}
	const open = sheet.sections.filter((section) => !section.done);
	if (open.length > 0) {
		if (open.length > 1) notes.push(`Open after it: ${open.slice(1).map(label).join(', ')}.`);
		return step(
			`The draft carries the signed build of ${tag}, and ${tag} matches the ${sheet.date} sheet. ` +
				`Next on the sheet: ${label(open[0])}.`
		);
	}
	return step(
		`Every section of the ${sheet.date} sheet is ticked. Check the conditions in ` +
			'RELEASE_PROCESS step 6, record the decision in §9, then publish (step 7).'
	);
}
