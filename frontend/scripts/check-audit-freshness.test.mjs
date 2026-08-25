import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createGateRepo } from './test-support/gate-repo.mjs';

/*
 * The security gate with the most moving parts, and the one whose failure is
 * quietest: a stale audit looks exactly like a fresh one until someone reads
 * the code. The cases below are the ways it has actually been wrong or could
 * be — a scope claim that stopped matching anything, an acknowledgement that
 * outlived its reason, and above all an anchor that does not survive the
 * squash merge this repo does on every PR.
 */

/** An ISO date `n` days before today, floored the same way the gate floors. */
const daysAgo = (n) => new Date(Date.now() - n * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);

/**
 * `count` acknowledgement records, the oldest dated `oldestDaysAgo` and the
 * rest today — so a test moves exactly one of the two caps at a time.
 */
function acknowledgements(count, oldestDaysAgo = 0) {
	return Array.from({ length: count }, (_, index) => ({
		date: index === 0 ? daysAgo(oldestDaysAgo) : daysAgo(0),
		note: `Reviewed change ${index + 1}: it cannot move the verdict.`
	}));
}

/** A minimal audit document with the two header rows the gate reads. */
function auditDoc({ auditedCommit, codePaths }) {
	const paths = codePaths.map((p) => `\`${p}\``).join(', ');
	return [
		'# Fixture Audit',
		'',
		'| Row                | Value                |',
		'| ------------------ | -------------------- |',
		`| **Audited commit** | \`${auditedCommit}\` |`,
		`| **Code paths**     | ${paths} |`,
		''
	].join('\n');
}

let repo;

beforeEach(() => {
	repo = createGateRepo();
});

afterEach(() => {
	repo.cleanup();
});

/** Sets up one audit over `src/audited`, returns the audited commit's SHA. */
function seedAudit(codePaths = ['src/audited']) {
	repo.write('src/audited/service.ts', 'export const v = 1;\n');
	repo.write('src/elsewhere/other.ts', 'export const v = 1;\n');
	repo.write('docs/FIXTURE_AUDIT.md', auditDoc({ auditedCommit: 'PLACEHOLDER', codePaths }));
	const sha = repo.commit('seed');
	// The audit can only name a commit that already exists, so the SHA goes in
	// afterwards — exactly how a real audit is written.
	repo.write('docs/FIXTURE_AUDIT.md', auditDoc({ auditedCommit: sha, codePaths }));
	repo.commit('anchor the audit');
	return sha;
}

/**
 * Drifts the audited path and returns the object id the gate asks to have
 * recorded, so a ledger test can get straight to the ledger it is about.
 */
function driftAndResolveOid() {
	repo.write('src/audited/service.ts', 'export const v = 2;\n');
	repo.commit('touch the audited path');
	return /"src\/audited": "([0-9a-f]{40})"/.exec(repo.run('check-audit-freshness.mjs').stderr)[1];
}

/** Replaces the ledger with one entry for the fixture audit, and commits it. */
function writeLedger(entry) {
	repo.write(
		'docs/audit-freshness.json',
		JSON.stringify({ 'docs/FIXTURE_AUDIT.md': entry }, null, '\t')
	);
	repo.commit('record the acknowledgement');
}

describe('check-audit-freshness', () => {
	it('passes when nothing under the audited paths has moved', () => {
		seedAudit();

		const result = repo.run('check-audit-freshness.mjs');

		expect(result.status).toBe(0);
		expect(result.stdout).toContain('no unreviewed drift');
	});

	it('ignores changes outside the audited paths', () => {
		seedAudit();
		repo.write('src/elsewhere/other.ts', 'export const v = 2;\n');
		repo.commit('unrelated change');

		expect(repo.run('check-audit-freshness.mjs').status).toBe(0);
	});

	it('fails when an audited path changes, and prints the id to record', () => {
		seedAudit();
		repo.write('src/audited/service.ts', 'export const v = 2;\n');
		repo.commit('touch the audited path');

		const result = repo.run('check-audit-freshness.mjs');

		expect(result.status).toBe(1);
		expect(result.stderr).toContain('src/audited');
		expect(result.stderr).toContain('Current object ids:');
		// A value nobody can derive by hand is a value people will guess.
		expect(result.stderr).toMatch(/"src\/audited": "[0-9a-f]{40}"/);
	});

	it('goes quiet again once the new object id is acknowledged', () => {
		seedAudit();
		repo.write('src/audited/service.ts', 'export const v = 2;\n');
		repo.commit('touch the audited path');

		const oid = /"src\/audited": "([0-9a-f]{40})"/.exec(
			repo.run('check-audit-freshness.mjs').stderr
		)[1];
		repo.write(
			'docs/audit-freshness.json',
			JSON.stringify(
				{
					'docs/FIXTURE_AUDIT.md': {
						reviewedTrees: { 'src/audited': oid },
						acknowledgements: [
							{ date: daysAgo(0), note: 'Reviewed: the change cannot move the verdict.' }
						]
					}
				},
				null,
				'\t'
			)
		);
		repo.commit('acknowledge');

		const result = repo.run('check-audit-freshness.mjs');

		expect(result.status).toBe(0);
		expect(result.stdout).toContain('acknowledged drift');
		// The passing run says how much is being carried. The failure this gate
		// grew to catch was invisible for seventeen days because green said
		// nothing.
		expect(result.stdout).toContain('1/8 acks');
	});

	/*
	 * The reason the anchor is a git object id at all. A squash merge produces a
	 * commit that never existed on the branch, so an acknowledgement recorded
	 * during the PR would name a SHA that stops resolving the moment it lands —
	 * which cost three follow-up commits before this was changed. Content is the
	 * same on both sides of the squash, so the acknowledgement survives.
	 */
	it('keeps an acknowledgement valid across a squash merge', () => {
		seedAudit();
		const mainTip = repo.git(['rev-parse', 'HEAD']);

		// Work happens on a branch and is acknowledged there, as in a real PR.
		repo.git(['checkout', '--quiet', '-b', 'feature']);
		repo.write('src/audited/service.ts', 'export const v = 2;\n');
		repo.commit('feature work');
		const oid = /"src\/audited": "([0-9a-f]{40})"/.exec(
			repo.run('check-audit-freshness.mjs').stderr
		)[1];
		repo.write(
			'docs/audit-freshness.json',
			JSON.stringify({
				'docs/FIXTURE_AUDIT.md': {
					reviewedTrees: { 'src/audited': oid },
					acknowledgements: acknowledgements(1)
				}
			})
		);
		repo.commit('acknowledge on the branch');
		expect(repo.run('check-audit-freshness.mjs').status).toBe(0);

		// Squash-merge it: one new commit, none of the branch's SHAs survive.
		repo.git(['checkout', '--quiet', 'main']);
		repo.git(['reset', '--quiet', '--hard', mainTip]);
		repo.git(['merge', '--squash', 'feature']);
		repo.commit('feature work (#1)');
		repo.git(['branch', '-D', 'feature']);

		const result = repo.run('check-audit-freshness.mjs');

		expect(result.status).toBe(0);
		expect(result.stdout).toContain('no unreviewed drift');
	});

	/*
	 * Every object id comes from one batched `git cat-file --batch-check`, whose
	 * results are matched back to their inputs by position. Two audits drifting
	 * differently is what catches a mis-aligned zip: cross the results and the
	 * untouched audit inherits the drifted one's verdict, which is a false alarm
	 * on one side and a silently stale audit on the other.
	 */
	it('keeps two audits apart when only one of them has drifted', () => {
		repo.write('src/first/service.ts', 'export const v = 1;\n');
		repo.write('src/second/service.ts', 'export const v = 1;\n');
		for (const [doc, dir] of [
			['docs/FIRST_AUDIT.md', 'src/first'],
			['docs/SECOND_AUDIT.md', 'src/second']
		]) {
			repo.write(doc, auditDoc({ auditedCommit: 'PLACEHOLDER', codePaths: [dir] }));
		}
		const sha = repo.commit('seed');
		repo.write('docs/FIRST_AUDIT.md', auditDoc({ auditedCommit: sha, codePaths: ['src/first'] }));
		repo.write('docs/SECOND_AUDIT.md', auditDoc({ auditedCommit: sha, codePaths: ['src/second'] }));
		repo.commit('anchor both audits');

		repo.write('src/second/service.ts', 'export const v = 2;\n');
		repo.commit('touch only what the second audit claims');

		const result = repo.run('check-audit-freshness.mjs');

		expect(result.status).toBe(1);
		expect(result.stderr).toContain('docs/SECOND_AUDIT.md');
		expect(result.stderr).not.toContain('docs/FIRST_AUDIT.md');
	});

	/*
	 * `--batch-check` echoes input it cannot resolve back verbatim, so a path
	 * with a space produces a line whose field layout is indistinguishable from
	 * a resolved one. That is why results are matched by position and a resolved
	 * line is recognised by its exact `<40 hex> <type> <size>` shape, never by
	 * parsing the echo.
	 */
	it('handles a Code paths entry containing a space', () => {
		repo.write('src/with space/service.ts', 'export const v = 1;\n');
		repo.write(
			'docs/FIXTURE_AUDIT.md',
			auditDoc({ auditedCommit: 'PLACEHOLDER', codePaths: ['src/with space'] })
		);
		const sha = repo.commit('seed');
		repo.write(
			'docs/FIXTURE_AUDIT.md',
			auditDoc({ auditedCommit: sha, codePaths: ['src/with space'] })
		);
		repo.commit('anchor the audit');

		expect(repo.run('check-audit-freshness.mjs').status).toBe(0);

		repo.write('src/with space/service.ts', 'export const v = 2;\n');
		repo.commit('touch the audited path');

		const result = repo.run('check-audit-freshness.mjs');

		expect(result.status).toBe(1);
		expect(result.stderr).toMatch(/"src\/with space": "[0-9a-f]{40}"/);
	});

	it('rejects a Code paths entry that no longer exists', () => {
		seedAudit(['src/renamed-away']);

		const result = repo.run('check-audit-freshness.mjs');

		expect(result.status).toBe(1);
		expect(result.stderr).toContain('does not exist');
	});

	it('rejects a glob in Code paths, which would silently match nothing', () => {
		repo.write('src/audited/service.ts', 'export const v = 1;\n');
		repo.write('docs/FIXTURE_AUDIT.md', auditDoc({ auditedCommit: 'HEAD', codePaths: ['src/*'] }));
		repo.commit('seed');

		const result = repo.run('check-audit-freshness.mjs');

		expect(result.status).toBe(1);
		expect(result.stderr).toContain('uses a glob');
	});

	it('rejects an audit with no Code paths row at all', () => {
		repo.write('src/audited/service.ts', 'export const v = 1;\n');
		repo.write('docs/FIXTURE_AUDIT.md', '# Fixture Audit\n\n| **Audited commit** | `HEAD` |\n');
		repo.commit('seed');

		const result = repo.run('check-audit-freshness.mjs');

		expect(result.status).toBe(1);
		expect(result.stderr).toContain('no `Code paths` header row');
	});

	/*
	 * An exemption that outlives its reason is the failure this file exists to
	 * prevent, so the ledger is held to the same rule as the audit: a path it
	 * names has to still be in scope.
	 */
	it('rejects a ledger entry naming a path the audit no longer claims', () => {
		seedAudit();
		repo.write(
			'docs/audit-freshness.json',
			JSON.stringify({
				'docs/FIXTURE_AUDIT.md': {
					reviewedTrees: { 'src/audited': 'x', 'src/dropped-from-scope': 'y' },
					acknowledgements: acknowledgements(1)
				}
			})
		);
		repo.commit('stale ledger');

		const result = repo.run('check-audit-freshness.mjs');

		expect(result.status).toBe(1);
		expect(result.stderr).toContain('src/dropped-from-scope');
		expect(result.stderr).toContain('drop it from the ledger');
	});

	it('fails when there are no audit documents to check', () => {
		repo.write('README.md', '# nothing here\n');
		repo.commit();

		const result = repo.run('check-audit-freshness.mjs');

		expect(result.status).toBe(1);
		expect(result.stderr).toContain('No docs/*_AUDIT.md found');
	});

	/*
	 * A pre-squash SHA in the audit header used to be fatal. It no longer is
	 * when the ledger covers every path, because the verdict does not depend on
	 * history resolving — and a gate that fails on history it does not use is a
	 * gate people learn to bypass.
	 */
	it('tolerates an unresolvable Audited commit when the ledger covers every path', () => {
		seedAudit();
		const oid = repo.git(['rev-parse', 'HEAD:src/audited']);
		repo.write(
			'docs/FIXTURE_AUDIT.md',
			auditDoc({ auditedCommit: 'deadbee', codePaths: ['src/audited'] })
		);
		repo.write(
			'docs/audit-freshness.json',
			JSON.stringify({
				'docs/FIXTURE_AUDIT.md': {
					reviewedTrees: { 'src/audited': oid },
					acknowledgements: acknowledgements(1)
				}
			})
		);
		repo.commit('unresolvable anchor, covered ledger');

		const result = repo.run('check-audit-freshness.mjs');

		expect(result.status).toBe(0);
		expect(result.stdout).toContain('NOTICE');
	});

	it('still fails on an unresolvable Audited commit with nothing to fall back on', () => {
		seedAudit();
		repo.write(
			'docs/FIXTURE_AUDIT.md',
			auditDoc({ auditedCommit: 'deadbee', codePaths: ['src/audited'] })
		);
		repo.commit('unresolvable anchor, empty ledger');

		const result = repo.run('check-audit-freshness.mjs');

		expect(result.status).toBe(1);
		expect(result.stderr).toContain('not a commit in this repository');
	});

	/*
	 * The caps. Acknowledging drift was the cheaper of the two honest routes
	 * and it had no end: the real ledger reached seven run-together reviews in
	 * one string in seventeen days, and the audit behind them was never read
	 * again. Each judgement below is fine on its own — it is their union that
	 * nobody has ever reviewed, which is what the count cap is measuring, and
	 * what the age cap measures for an entry that grows slowly instead.
	 */
	describe('caps on a run of acknowledgements', () => {
		it('allows eight standing acknowledgements and refuses the ninth', () => {
			seedAudit();
			const oid = driftAndResolveOid();

			writeLedger({ reviewedTrees: { 'src/audited': oid }, acknowledgements: acknowledgements(8) });
			expect(repo.run('check-audit-freshness.mjs').status).toBe(0);

			writeLedger({ reviewedTrees: { 'src/audited': oid }, acknowledgements: acknowledgements(9) });
			const result = repo.run('check-audit-freshness.mjs');

			expect(result.status).toBe(1);
			expect(result.stderr).toContain('9 acknowledgements stand (cap 8)');
			expect(result.stderr).toContain('re-verify the audit against HEAD');
		});

		it('allows a ninety-day-old acknowledgement and refuses one a day older', () => {
			seedAudit();
			const oid = driftAndResolveOid();

			writeLedger({
				reviewedTrees: { 'src/audited': oid },
				acknowledgements: acknowledgements(1, 90)
			});
			expect(repo.run('check-audit-freshness.mjs').status).toBe(0);

			writeLedger({
				reviewedTrees: { 'src/audited': oid },
				acknowledgements: acknowledgements(1, 91)
			});
			const result = repo.run('check-audit-freshness.mjs');

			expect(result.status).toBe(1);
			expect(result.stderr).toContain('the oldest is 91 days old (cap 90)');
		});

		/*
		 * A capped entry must not also be offered the way out it has just lost.
		 * Printing "Current object ids:" underneath the cap would name the
		 * weaker action second, and the weaker action is the one that is one
		 * paste away.
		 */
		it('stops offering another acknowledgement once a cap is hit', () => {
			seedAudit();
			const oid = driftAndResolveOid();
			writeLedger({ reviewedTrees: { 'src/audited': oid }, acknowledgements: acknowledgements(9) });
			repo.write('src/audited/service.ts', 'export const v = 3;\n');
			repo.commit('drift again on top of a capped entry');

			const result = repo.run('check-audit-freshness.mjs');

			expect(result.status).toBe(1);
			expect(result.stderr).toContain('outrun its cap');
			expect(result.stderr).not.toContain('Current object ids:');
		});

		it('warns on a passing run while the count cap is still two away', () => {
			seedAudit();
			const oid = driftAndResolveOid();
			writeLedger({ reviewedTrees: { 'src/audited': oid }, acknowledgements: acknowledgements(6) });

			const result = repo.run('check-audit-freshness.mjs');

			expect(result.status).toBe(0);
			expect(result.stdout).toContain('NOTICE');
			expect(result.stdout).toContain('6/8 acknowledgements');
		});

		it('warns on a passing run while the age cap is still a fortnight away', () => {
			seedAudit();
			const oid = driftAndResolveOid();
			writeLedger({
				reviewedTrees: { 'src/audited': oid },
				acknowledgements: acknowledgements(1, 76)
			});

			const result = repo.run('check-audit-freshness.mjs');

			expect(result.status).toBe(0);
			expect(result.stdout).toContain('oldest 76/90 days');
		});
	});

	/*
	 * Shape validation. Every cap above is measured off these fields, so a
	 * record the gate cannot read is a record that cannot age or be counted —
	 * which is the old unbounded behaviour reintroduced by accident.
	 */
	describe('acknowledgement record shape', () => {
		it('rejects the pre-migration note string rather than reading it as nothing', () => {
			seedAudit();
			const oid = driftAndResolveOid();
			writeLedger({ reviewedTrees: { 'src/audited': oid }, note: 'Reviewed, at some point.' });

			const result = repo.run('check-audit-freshness.mjs');

			expect(result.status).toBe(1);
			expect(result.stderr).toContain('still carries the single `note` string');
		});

		it('rejects recorded object ids that carry no acknowledgement at all', () => {
			seedAudit();
			const oid = driftAndResolveOid();
			writeLedger({ reviewedTrees: { 'src/audited': oid } });

			const result = repo.run('check-audit-freshness.mjs');

			expect(result.status).toBe(1);
			expect(result.stderr).toContain('no "acknowledgements"');
		});

		it('rejects an undated acknowledgement, which could never age out', () => {
			seedAudit();
			const oid = driftAndResolveOid();
			writeLedger({
				reviewedTrees: { 'src/audited': oid },
				acknowledgements: [{ note: 'Reviewed: it cannot move the verdict.' }]
			});

			const result = repo.run('check-audit-freshness.mjs');

			expect(result.status).toBe(1);
			expect(result.stderr).toContain('no "date" in YYYY-MM-DD form');
		});

		it('rejects an acknowledgement whose note is blank', () => {
			seedAudit();
			const oid = driftAndResolveOid();
			writeLedger({
				reviewedTrees: { 'src/audited': oid },
				acknowledgements: [{ date: daysAgo(0), note: '   ' }]
			});

			const result = repo.run('check-audit-freshness.mjs');

			expect(result.status).toBe(1);
			expect(result.stderr).toContain('the note is the evidence');
		});

		/*
		 * The age cap reads acknowledgements[0]. Newest-first would make the
		 * oldest record invisible to it, so a run could stand indefinitely.
		 */
		it('rejects records that are not oldest-first', () => {
			seedAudit();
			const oid = driftAndResolveOid();
			writeLedger({
				reviewedTrees: { 'src/audited': oid },
				acknowledgements: [
					{ date: daysAgo(0), note: 'Newest, written at the top.' },
					{ date: daysAgo(30), note: 'Oldest, pushed down out of the caps reach.' }
				]
			});

			const result = repo.run('check-audit-freshness.mjs');

			expect(result.status).toBe(1);
			expect(result.stderr).toContain('records go oldest first');
		});

		it('rejects an acknowledgement dated in the future', () => {
			seedAudit();
			const oid = driftAndResolveOid();
			writeLedger({
				reviewedTrees: { 'src/audited': oid },
				acknowledgements: [{ date: daysAgo(-30), note: 'Reviewed next month, apparently.' }]
			});

			const result = repo.run('check-audit-freshness.mjs');

			expect(result.status).toBe(1);
			expect(result.stderr).toContain('is in the future');
		});
	});
});
