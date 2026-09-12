import { describe, expect, it } from 'vitest';
import {
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
	readProvenance
} from './lib/release-status.mjs';

/*
 * The CLI half of release:status runs git and gh and hands their raw answers
 * over; every choice made about those answers — which run counts, which asset
 * is the installer, what the next step is — is made in the lib, so this is
 * where the suite sits. The sheet fixture copies the shape of the real
 * 2026-09-11 sheet in backend/RELEASE_CHECKLIST.md, including the §9 box that
 * has no title.
 */

const OLDER_SHEET = [
	'### Candidate 2026-09-10 — `a2caa27`',
	'',
	'- [x] **§0 Version** — ok.',
	'- [x] **§3 Fresh install** — done.'
];

const NEWER_SHEET = [
	'### Candidate 2026-09-11 — see the object ids below',
	'',
	'| path | object id |',
	'| ---- | --------- |',
	'| `backend/src` | `6eb6fc4973c5` |',
	'| `frontend/src` | `9B3A3EF9972C` |',
	'',
	'- [x] **§0 Version** — ok.',
	'- [x] **§1 Backend build** — ok.',
	'- [X] **§2 Frontend automation** — carried forward.',
	'- [ ] **§3a Installer behaviour** — same.',
	'- [ ] **§9** — release decision.'
];

const CHECKLIST = [
	'# Release checklist',
	'',
	'## Appendix',
	'',
	...OLDER_SHEET,
	'',
	...NEWER_SHEET,
	'',
	'### Candidate build',
	'',
	'- [ ] **§4 Account flows** — belongs to no sheet.'
].join('\n');

const MAIN = `5697424${'a'.repeat(33)}`;
const TAG = `db11430${'b'.repeat(33)}`;
const OTHER = `a2caa27${'c'.repeat(33)}`;

function facts(overrides = {}) {
	return {
		remote: true,
		mainLocal: true,
		productName: 'VoxRox Mail',
		tag: 'v0.1.0',
		tagCommit: TAG,
		mainCommit: MAIN,
		sheet: {
			date: '2026-09-11',
			sameDate: 1,
			objectIds: [{ path: 'backend/src', id: '6eb6fc4973c5' }],
			unparsedRows: [],
			sections: [
				{ id: '§0', title: 'Version', done: true },
				{ id: '§1', title: 'Backend build', done: true },
				{ id: '§2', title: 'Frontend automation', done: true },
				{ id: '§3', title: 'Fresh install', done: false },
				{ id: '§9', title: '', done: false }
			]
		},
		sheetVsMain: { state: 'match', differing: [] },
		sheetVsTag: { state: 'match', differing: [] },
		tagOnMain: true,
		localNotes: [],
		unreadable: [],
		mainCi: 'success',
		tagCi: 'success',
		signedRun: { status: 'completed', conclusion: 'success', url: 'https://example.test/run' },
		release: { isDraft: true },
		missingAssets: [],
		assetProblem: null,
		provenance: { commit: TAG, ref: 'refs/tags/v0.1.0' },
		manifestProblems: [],
		vulnScan: {
			completed: { status: 'completed', conclusion: 'success', url: 'https://example.test/scan' },
			running: false
		},
		...overrides
	};
}

const stale = {
	state: 'differs',
	differing: [{ path: 'backend/src', sheet: '6eb6fc4973c5', actual: '6c59df1e87ea' }]
};

const untagged = { tagCommit: null, sheetVsTag: null, signedRun: null, release: null };

function sheetWith(sheetOverrides) {
	return { ...facts().sheet, ...sheetOverrides };
}

function ticked(done) {
	return sheetWith({
		sections: facts().sheet.sections.map((section) => ({ ...section, done: done(section.id) }))
	});
}

describe('parseCandidateSheet', () => {
	it('picks the newest dated sheet whatever its position, and stops at the next heading', () => {
		const sheet = parseCandidateSheet(CHECKLIST);

		expect(sheet.date).toBe('2026-09-11');
		expect(sheet.sameDate).toBe(1);
		expect(sheet.sections.map((section) => section.id)).toEqual(['§0', '§1', '§2', '§3a', '§9']);
	});

	it('reads the object id table case-insensitively, and ticks including a box without a title', () => {
		const sheet = parseCandidateSheet(CHECKLIST);

		expect(sheet.objectIds).toEqual([
			{ path: 'backend/src', id: '6eb6fc4973c5' },
			{ path: 'frontend/src', id: '9b3a3ef9972c' }
		]);
		expect(sheet.unparsedRows).toEqual([]);
		expect(sheet.sections.at(2)).toEqual({ id: '§2', title: 'Frontend automation', done: true });
		expect(sheet.sections.at(-1)).toEqual({ id: '§9', title: '', done: false });
	});

	it('returns a table row it cannot read instead of dropping its path', () => {
		const sheet = parseCandidateSheet(
			[
				'### Candidate 2026-09-12',
				'',
				'| path | object id |',
				'| :--- | --------: |',
				'| backend/src | 6eb6fc4973c5 |',
				'| `frontend/src` | `9b3a3ef9972c` |'
			].join('\n')
		);

		expect(sheet.objectIds).toEqual([{ path: 'frontend/src', id: '9b3a3ef9972c' }]);
		expect(sheet.unparsedRows).toEqual(['| backend/src | 6eb6fc4973c5 |']);
	});

	it('counts sheets that share the newest date and reads the first of them', () => {
		const sheet = parseCandidateSheet(
			[
				'### Candidate 2026-09-11 — second build',
				'- [x] **§0 Version** — second.',
				'### Candidate 2026-09-11 — first build',
				'- [ ] **§0 Version** — first.'
			].join('\n')
		);

		expect(sheet.sameDate).toBe(2);
		expect(sheet.sections).toEqual([{ id: '§0', title: 'Version', done: true }]);
	});

	it('returns null when the checklist holds no dated sheet', () => {
		expect(parseCandidateSheet('# Release checklist\n\n### Candidate build\n')).toBeNull();
	});
});

describe('compareObjectIds', () => {
	const sheetIds = [
		{ path: 'backend/src', id: '6eb6fc4973c5' },
		{ path: 'frontend/src', id: '9b3a3ef9972c' }
	];

	it('matches on the prefix the sheet writes', () => {
		const actual = new Map([
			['backend/src', `6eb6fc4973c5${'0'.repeat(28)}`],
			['frontend/src', `9b3a3ef9972c${'1'.repeat(28)}`]
		]);

		expect(compareObjectIds(sheetIds, actual)).toEqual({ state: 'match', differing: [] });
	});

	it('names each differing path with both prefixes, and a path the commit lacks', () => {
		const actual = new Map([
			['backend/src', `6c59df1e87ea${'0'.repeat(28)}`],
			['frontend/src', null]
		]);

		expect(compareObjectIds(sheetIds, actual)).toEqual({
			state: 'differs',
			differing: [
				{ path: 'backend/src', sheet: '6eb6fc4973c5', actual: '6c59df1e87ea' },
				{ path: 'frontend/src', sheet: '9b3a3ef9972c', actual: null }
			]
		});
	});

	it('does not call a commit outside the clone, or a sheet without ids, a match', () => {
		expect(compareObjectIds(sheetIds, null).state).toBe('unknown');
		expect(compareObjectIds([], new Map()).state).toBe('unknown');
	});
});

describe('reading what git and gh return', () => {
	it('reads a failed gh call as unreadable unless gh said the thing does not exist', () => {
		const notFound = { ok: false, stdout: '', stderr: 'release not found\n' };
		const rateLimited = {
			ok: false,
			stdout: '',
			stderr: 'HTTP 403: API rate limit exceeded\nmore'
		};

		expect(readGhAnswer(notFound, /release not found/i)).toEqual({ state: 'absent', data: null });
		expect(readGhAnswer(rateLimited, /release not found/i)).toEqual({
			state: 'unreadable',
			reason: 'HTTP 403: API rate limit exceeded'
		});
		expect(readGhAnswer({ ok: true, stdout: '<html>', stderr: '' })).toEqual({
			state: 'unreadable',
			reason: 'gh did not print JSON'
		});
		expect(readGhAnswer({ ok: true, stdout: '[]', stderr: '' })).toEqual({ state: 'ok', data: [] });
	});

	it('takes the repository from origin over https and ssh, and nothing from another host', () => {
		expect(parseGitHubSlug('https://github.com/TheVoxRox/mail.git\n')).toBe('TheVoxRox/mail');
		expect(parseGitHubSlug('git@github.com:TheVoxRox/mail')).toBe('TheVoxRox/mail');
		expect(parseGitHubSlug('https://gitlab.com/TheVoxRox/mail.git')).toBeNull();
	});

	it('resolves an annotated tag to its commit and a lightweight tag to itself', () => {
		const refs = parseLsRemote(
			[
				`${MAIN}\trefs/heads/main`,
				`${'f'.repeat(40)}\trefs/tags/v0.1.0`,
				`${TAG}\trefs/tags/v0.1.0^{}`,
				`${OTHER}\trefs/tags/v0.0.9`
			].join('\n')
		);

		expect(refs.get('refs/heads/main')).toBe(MAIN);
		expect(refs.get('refs/tags/v0.1.0')).toBe(TAG);
		expect(refs.get('refs/tags/v0.0.9')).toBe(OTHER);
	});

	it('takes CI from the push to main, not from a pull request on the same commit', () => {
		const pullRequest = {
			event: 'pull_request',
			headBranch: 'feature',
			status: 'completed',
			conclusion: 'failure'
		};
		const push = { event: 'push', headBranch: 'main', status: 'completed', conclusion: 'success' };

		expect(pickCiResult([pullRequest, push])).toBe('success');
		expect(pickCiResult([{ ...push, status: 'in_progress', conclusion: '' }])).toBe('in_progress');
		expect(pickCiResult([pullRequest])).toBeNull();
	});

	it('does not count a signed run dispatched from a branch at the tag commit', () => {
		const fromBranch = {
			headSha: TAG,
			headBranch: 'main',
			status: 'completed',
			conclusion: 'success'
		};
		const fromTag = { ...fromBranch, headBranch: 'v0.1.0' };

		expect(pickSignedRun([fromBranch], 'v0.1.0', TAG)).toBeNull();
		expect(pickSignedRun([fromBranch, fromTag], 'v0.1.0', TAG)).toBe(fromTag);
	});

	it('picks the installer of exactly this version, not a prerelease that starts with it', () => {
		const names = [
			'voxrox-mail-0.1.0-beta.1-windows-x64-setup.exe',
			'voxrox-mail-0.1.0-windows-x64-setup.exe'
		];

		expect(pickInstaller(names, '0.1.0')).toBe('voxrox-mail-0.1.0-windows-x64-setup.exe');
		expect(pickInstaller(names, '0.1.0-beta.1')).toBe(
			'voxrox-mail-0.1.0-beta.1-windows-x64-setup.exe'
		);
		expect(pickInstaller(names.slice(0, 1), '0.1.0')).toBeNull();
	});

	it('lists the assets a draft lacks, naming the installer by its version when it is absent', () => {
		const installer = 'voxrox-mail-0.1.0-windows-x64-setup.exe';

		expect(findMissingAssets([installer, `${installer}.sha256`, 'latest.json'], '0.1.0')).toEqual([
			`${installer}.sig`
		]);
		expect(findMissingAssets(['latest.json'], '0.1.0')).toEqual([
			'the 0.1.0 installer (*-0.1.0-windows-x64-setup.exe)'
		]);
	});

	it('prefers the build made from the tag and ignores other workflows', () => {
		const workflow = '.github/workflows/windows-signed-release.yml';
		const fromBranch = { commit: TAG, ref: 'refs/heads/main', workflow };
		const fromTag = { commit: TAG, ref: 'refs/tags/v0.1.0', workflow };
		const elsewhere = {
			commit: TAG,
			ref: 'refs/tags/v0.1.0',
			workflow: '.github/workflows/ci.yml'
		};

		expect(pickProvenance([elsewhere, fromBranch, fromTag], 'v0.1.0', TAG)).toBe(fromTag);
		expect(pickProvenance([elsewhere, fromBranch], 'v0.1.0', TAG)).toBe(fromBranch);
		expect(pickProvenance([elsewhere], 'v0.1.0', TAG)).toBeNull();
	});

	it('reports the last completed scan while a newer one is still running', () => {
		const finished = { status: 'completed', conclusion: 'success' };

		expect(pickScan([{ status: 'in_progress', conclusion: '' }, finished])).toEqual({
			completed: finished,
			running: true
		});
		expect(pickScan([])).toEqual({ completed: null, running: false });
	});
});

describe('checkManifest', () => {
	const installerUrl =
		'https://github.com/TheVoxRox/mail/releases/download/v0.1.0/voxrox-mail-0.1.0-windows-x64-setup.exe';
	const manifest = {
		version: '0.1.0',
		platforms: { 'windows-x86_64': { signature: 'c2ln', url: installerUrl } }
	};

	it('accepts a manifest that agrees with the version, the installer and the .sig', () => {
		expect(
			checkManifest({ manifest, version: '0.1.0', installerUrl, signature: 'c2ln\n' })
		).toEqual([]);
	});

	it('reports every disagreement, not only the first', () => {
		const problems = checkManifest({
			manifest: {
				version: '0.0.9',
				platforms: { 'windows-x86_64': { signature: 'b3RoZXI=', url: `${installerUrl}.old` } }
			},
			version: '0.1.0',
			installerUrl,
			signature: 'c2ln'
		});

		expect(problems).toHaveLength(3);
		expect(problems[0]).toContain('"0.0.9"');
		expect(problems[1]).toContain('.old');
		expect(problems[2]).toContain('.sig');
	});

	it('reports a manifest without the Windows platform', () => {
		expect(
			checkManifest({
				manifest: { version: '0.1.0', platforms: {} },
				version: '0.1.0',
				installerUrl,
				signature: 'c2ln'
			})
		).toEqual(['it has no windows-x86_64 entry']);
	});
});

describe('parseSha256File', () => {
	const digest = 'ef2907428c6c654ea428fcc536124b031749e189703093b42b4b0674253a3442';
	const installer = 'voxrox-mail-0.1.0-windows-x64-setup.exe';

	it('reads the line the signed workflow uploads', () => {
		expect(parseSha256File(`${digest} *${installer}\n`, installer)).toEqual({
			digest,
			problem: null
		});
	});

	it('flags a checksum that names another file', () => {
		expect(parseSha256File(`${digest} *old-setup.exe`, installer).problem).toContain(
			'old-setup.exe'
		);
	});

	it('flags a file that is not a checksum line', () => {
		expect(parseSha256File('<html>not found</html>', installer)).toEqual({
			digest: null,
			problem: 'is not a "<sha256> *<file>" line'
		});
	});
});

describe('readProvenance', () => {
	it('reads commit, ref and workflow out of the attested statement', () => {
		const statement = {
			predicate: {
				buildDefinition: {
					externalParameters: {
						workflow: {
							ref: 'refs/tags/v0.1.0',
							path: '.github/workflows/windows-signed-release.yml'
						}
					},
					resolvedDependencies: [{ digest: { gitCommit: TAG } }]
				}
			}
		};
		const payload = Buffer.from(JSON.stringify(statement)).toString('base64');
		const response = {
			attestations: [
				{ bundle: { dsseEnvelope: { payload } } },
				{ bundle: { dsseEnvelope: { payload: '!' } } }
			]
		};

		expect(readProvenance(response)).toEqual([
			{
				commit: TAG,
				ref: 'refs/tags/v0.1.0',
				workflow: '.github/workflows/windows-signed-release.yml'
			}
		]);
		expect(readProvenance(null)).toEqual([]);
	});
});

describe('decideNextStep', () => {
	it('re-tags main when the tag is stale, main matches the sheet and CI on main is green', () => {
		const decision = decideNextStep(facts({ sheetVsTag: stale }));

		expect(decision.summary).toContain('Re-tag main');
		expect(decision.commands).toEqual([
			'git tag -d v0.1.0',
			'git push origin :refs/tags/v0.1.0',
			`git tag -a v0.1.0 -m "VoxRox Mail v0.1.0" ${MAIN}`,
			'git push origin v0.1.0'
		]);
		expect(decision.notes.join(' ')).toContain('target_commitish');
	});

	it('asks for a fetch, not a new §1, when main is not in the clone and the tag is stale', () => {
		const decision = decideNextStep(
			facts({
				mainLocal: false,
				sheet: null,
				sheetVsTag: stale,
				sheetVsMain: { state: 'unknown', differing: [] }
			})
		);

		expect(decision.summary).toContain('not in this clone');
		expect(decision.commands).toEqual(['git fetch origin']);
	});

	it('offers no re-tag when main does not match the sheet either', () => {
		const decision = decideNextStep(facts({ sheetVsTag: stale, sheetVsMain: stale }));

		expect(decision.summary).toContain('matches neither');
		expect(decision.commands).toEqual([]);
	});

	it('suggests no tag and no re-tag on a main whose CI is not green', () => {
		const retag = decideNextStep(facts({ sheetVsTag: stale, mainCi: 'failure' }));
		const tag = decideNextStep(facts({ ...untagged, mainCi: null }));

		expect(retag.summary).toContain('CI on main is failure');
		expect(retag.commands).toEqual([]);
		expect(tag.summary).toContain('No CI run on main was found');
		expect(tag.commands).toEqual([]);
	});

	it('offers the tag and the build once main matches, §0–§2 are ticked and CI is green', () => {
		const decision = decideNextStep(facts(untagged));

		expect(decision.commands).toEqual([
			`git tag -a v0.1.0 -m "VoxRox Mail v0.1.0" ${MAIN}`,
			'git push origin v0.1.0',
			'gh workflow run windows-signed-release.yml --ref v0.1.0'
		]);
	});

	it('holds the tag back while a machine section is open, and warns about a tagless draft', () => {
		const decision = decideNextStep(
			facts({
				...untagged,
				release: { isDraft: true },
				sheet: ticked((id) => id !== '§1' && id !== '§3')
			})
		);

		expect(decision.summary).toBe('Finish §1 on the 2026-09-11 sheet before tagging.');
		expect(decision.commands).toEqual([]);
		expect(decision.notes.join(' ')).toContain('without its tag');
	});

	it('stops on an unreadable table row before comparing anything', () => {
		const decision = decideNextStep(
			facts({ sheet: sheetWith({ unparsedRows: ['| backend/src | 6eb6fc4973c5 |'] }) })
		);

		expect(decision.summary).toContain('cannot be read as object ids');
		expect(decision.commands).toEqual([]);
	});

	it('reads a GitHub failure as unreadable and suggests no build', () => {
		const decision = decideNextStep(
			facts({ unreadable: ['the signed builds (HTTP 403: rate limit exceeded)'], signedRun: null })
		);

		expect(decision.summary).toContain('HTTP 403');
		expect(decision.commands).toEqual([]);
	});

	it('sends the operator back to the build when the draft carries another commit', () => {
		const decision = decideNextStep(
			facts({ provenance: { commit: OTHER, ref: 'refs/tags/v0.1.0' } })
		);

		expect(decision.summary).toContain('built from a2caa27, not from v0.1.0 (db11430)');
		expect(decision.commands).toEqual(['gh workflow run windows-signed-release.yml --ref v0.1.0']);
	});

	it('does not accept an installer built from a branch, even at the tag commit', () => {
		const decision = decideNextStep(facts({ provenance: { commit: TAG, ref: 'refs/heads/main' } }));

		expect(decision.summary).toContain('built from refs/heads/main, not from the tag');
		expect(decision.commands).toEqual(['gh workflow run windows-signed-release.yml --ref v0.1.0']);
	});

	it('names the first open section of a consistent candidate and lists the rest', () => {
		const decision = decideNextStep(facts());

		expect(decision.summary).toContain('Next on the sheet: §3 Fresh install.');
		expect(decision.notes).toEqual(['Open after it: §9.']);
	});

	it('keeps the notes about the working tree and about a same-day sheet', () => {
		const decision = decideNextStep(
			facts({
				localNotes: ['Your working tree says version 0.2.0.'],
				sheet: sheetWith({ sameDate: 2 })
			})
		);

		expect(decision.notes[0]).toBe('Your working tree says version 0.2.0.');
		expect(decision.notes[1]).toContain('2 sheets carry the date 2026-09-11');
	});

	it('carries a red completed vulnerability scan into the notes before publishing', () => {
		const decision = decideNextStep(
			facts({
				sheet: ticked(() => true),
				vulnScan: {
					completed: {
						status: 'completed',
						conclusion: 'failure',
						url: 'https://example.test/scan'
					},
					running: true
				}
			})
		);

		expect(decision.summary).toContain('RELEASE_PROCESS step 6');
		expect(decision.notes.join(' ')).toContain('ended failure');
		expect(decision.notes.join(' ')).toContain('https://example.test/scan');
	});

	it('stops at a release that is already published', () => {
		const decision = decideNextStep(facts({ release: { isDraft: false }, sheetVsTag: stale }));

		expect(decision.summary).toContain('already published');
		expect(decision.commands).toEqual([]);
	});
});
