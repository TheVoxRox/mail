import { execFileSync } from 'node:child_process';
import process from 'node:process';

/*
 * Refuses an index blob whose line endings contradict .gitattributes.
 *
 * git stores a text blob with LF and applies `eol=` on checkout, so a CRLF
 * blob is one no `git add` produced. The way it gets in is a commit made
 * through the GitHub API, which does not apply .gitattributes: dependabot
 * commits that way, weekly. #279 landed `backend/mvnw.cmd` with a CRLF blob
 * under `*.cmd text eol=crlf`, and the fix was `git add --renormalize` (#285).
 *
 * The damage is disproportionate to the cause. Every clone reports the file as
 * modified with a whole-file diff, permanently, and neither `git checkout --`
 * nor `git reset --hard` clears it — the smudge filter rebuilds the working
 * copy from the same bad blob, and the clean filter normalizes it back to
 * something the blob does not match. The only cure is a commit.
 *
 * Sibling of check:nul, and the same class of silent defect: prettier, eslint
 * and the tests all read the file exactly as intended, while git sees
 * something the declaration says it should not be.
 *
 * `git status` is NOT the detection. It missed the `i/mixed` case outright in
 * the experiment that produced this gate — a blob `git add --renormalize`
 * demonstrably rewrites, reported clean by status both fresh from clone and
 * after the stat cache was invalidated. Status compares round-tripped content
 * under a stat cache; `ls-files --eol` reads the blob.
 *
 * Usage:
 *   node scripts/check-eol-normalization.mjs
 */

/*
 * Asked of git rather than derived from cwd, so `npm run` from frontend/ and a
 * hook started at the repo root resolve the same tree.
 */
const repoRoot = execFileSync('git', ['rev-parse', '--show-toplevel'], { encoding: 'utf8' }).trim();

/*
 * `-z` NUL-terminates the records, so a path containing a newline cannot split
 * one in half. Within a record the fields are still space-padded and the path
 * still follows a tab.
 */
const raw = execFileSync('git', ['ls-files', '--eol', '-z'], {
	cwd: repoRoot,
	encoding: 'utf8',
	maxBuffer: 64 * 1024 * 1024
});

/** `i/<eolinfo> w/<eolinfo> attr/<eolattr>` — the part before the tab. */
const HEADER = /^i\/(\S*)\s+w\/(\S*)\s+attr\/(.*)$/;

const offenders = [];
const unparsed = [];
let checked = 0;
let skipped = 0;

for (const record of raw.split('\0')) {
	if (!record) continue;

	/*
	 * The first tab, not the last: an eolattr value never contains one, but a
	 * path legally can, and splitting from the wrong end would mangle it.
	 */
	const tab = record.indexOf('\t');
	const header = tab < 0 ? null : record.slice(0, tab).match(HEADER);
	if (!header) {
		unparsed.push(record);
		continue;
	}

	const [, indexEol, , eolAttr] = header;
	const file = record.slice(tab + 1);
	const attrs = eolAttr.trim().split(/\s+/).filter(Boolean);

	/*
	 * Only paths the repo has actually declared as text get an expectation.
	 * That one condition covers both ways a path can fail to have one:
	 * `attr/-text` is a declared binary, whose bytes are its own business, and
	 * an empty attr means git promises nothing, so a CRLF blob there is a
	 * legitimate choice rather than drift. (This repo's root `* text=auto
	 * eol=lf` means the empty case never arises here — it is handled so a
	 * future subdirectory .gitattributes cannot turn the gate into a liar.)
	 *
	 * Testing for `-text` separately would read as the more careful version
	 * and be dead code: `text` and `-text` are the same attribute, so a path
	 * carrying one never carries the other.
	 *
	 * A bare `eol=lf` needs no special case either: git treats setting eol as
	 * setting text, and `ls-files` reports the resolved form, `text eol=lf`.
	 */
	if (!attrs.some((a) => a === 'text' || a === 'text=auto')) {
		skipped += 1;
		continue;
	}

	/*
	 * `-text` here is git's own verdict that the blob is binary despite the
	 * declaration — content, not attributes. That is check:nul's question, and
	 * answering it twice with two different messages helps nobody. `none` is a
	 * blob with no line ending at all, which contradicts no declaration.
	 */
	if (indexEol === '-text' || indexEol === 'none') {
		skipped += 1;
		continue;
	}

	checked += 1;
	if (indexEol !== 'lf') offenders.push({ file, indexEol, eolAttr: eolAttr.trim() });
}

if (offenders.length > 0) {
	console.error('Index blobs whose line endings contradict .gitattributes:');
	for (const { file, indexEol, eolAttr } of offenders) {
		console.error(`  - ${file} (index holds ${indexEol}, declared ${eolAttr})`);
	}
	console.error('');
	console.error('git normalizes a text blob to LF and applies eol= on checkout, so this blob');
	console.error('is not one `git add` produced — most likely a commit made through the GitHub');
	console.error('API, which does not apply .gitattributes. Every clone will report the file as');
	console.error('modified forever, and `git checkout --` cannot clean it.');
	console.error('');
	console.error('Fix, as its own commit:');
	/*
	 * The remedy is the half of this message that is not obvious, so it has to
	 * survive being pasted. A path with a space would otherwise arrive as two
	 * pathspecs. Single quotes are literal in both sh and PowerShell, and only
	 * the paths that need them get them, so the ordinary case stays readable.
	 */
	const pathspecs = offenders.map(({ file }) => (/\s/.test(file) ? `'${file}'` : file));
	console.error(`  git add --renormalize ${pathspecs.join(' ')}`);
	process.exitCode = 1;
}

/*
 * A record this script cannot read is a file it did not judge, and reporting
 * it as clean would be a guess — the same reason check:nul refuses to skip a
 * file it could not open. Loud here means a git output change gets noticed
 * instead of quietly emptying the gate.
 */
if (unparsed.length > 0) {
	console.error('Unrecognized `git ls-files --eol` records, so these files were not checked:');
	for (const record of unparsed) {
		console.error(`  - ${JSON.stringify(record)}`);
	}
	console.error('');
	console.error('The expected form is `i/<eol> w/<eol> attr/<eolattr>\\t<path>`. If git has');
	console.error('changed it, update the parser — a gate that silently checks nothing is worse');
	console.error('than no gate.');
	process.exitCode = 1;
}

if (offenders.length === 0 && unparsed.length === 0) {
	console.log(
		`EOL check OK: ${checked} declared-text file(s) normalized to LF, ` +
			`${skipped} binary or undeclared and skipped.`
	);
}
