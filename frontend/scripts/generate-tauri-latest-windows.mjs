import { readFile, readdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from './lib/cli-args.mjs';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const frontendRoot = path.resolve(scriptDir, '..');

const args = parseArgs(process.argv.slice(2));
const bundleDir = path.resolve(args.bundleDir ?? 'src-tauri/target/release/bundle');
const outputPath = path.resolve(args.output ?? 'src-tauri/target/release/latest.json');
const platform = args.platform ?? 'windows-x86_64';
const repository = args.repository ?? process.env.GITHUB_REPOSITORY;
const tag = args.tag ?? process.env.RELEASE_TAG ?? process.env.GITHUB_REF_NAME;

if (!repository) {
	throw new Error('GITHUB_REPOSITORY or --repository is required, for example TheVoxRox/mail.');
}

if (!tag) {
	throw new Error('RELEASE_TAG, GITHUB_REF_NAME, or --tag is required.');
}

const version = args.version ?? (await readAppVersion());
const installer = await findWindowsUpdaterArtifact(bundleDir, version);
const signature = (await readFile(`${installer}.sig`, 'utf8')).trim();
if (!signature) {
	throw new Error(`Updater signature is empty: ${installer}.sig`);
}

const assetName = path.basename(installer);
const latest = {
	version,
	notes: args.notes ?? `Mail ${version}`,
	pub_date: args.pubDate ?? new Date().toISOString(),
	platforms: {
		[platform]: {
			signature,
			url: `https://github.com/${repository}/releases/download/${tag}/${encodeURIComponent(assetName)}`
		}
	}
};

await writeFile(outputPath, `${JSON.stringify(latest, null, '\t')}\n`);
console.log(`Wrote ${outputPath}`);
console.log(`Using ${platform} artifact ${path.relative(frontendRoot, installer)}`);

async function readAppVersion() {
	const tauriConfigPath = path.join(frontendRoot, 'src-tauri', 'tauri.conf.json');
	const tauriConfig = JSON.parse(await readFile(tauriConfigPath, 'utf8'));
	if (!tauriConfig.version) {
		throw new Error(`Missing version in ${tauriConfigPath}`);
	}
	return tauriConfig.version;
}

/**
 * Picks the installer this manifest will point at.
 *
 * Carrying `version` in the name is a **condition**, not a preference. It used
 * to be worth +10 in the score below, which on a clean runner always won — and
 * anywhere else, with a previous release still sitting in `bundle/`, quietly
 * produced a manifest that announced this version while pointing at that build.
 * The signature would still verify (it is read from the chosen artifact's own
 * `.sig`), so neither the empty-signature throw nor the release signature gate
 * catches it: both check the artifact the manifest names, not whether it is the
 * right one. Failing here is the only place that can tell the difference.
 *
 * The remaining score only breaks ties between artifacts that all carry the
 * right version.
 */
async function findWindowsUpdaterArtifact(root, version) {
	const files = await listFiles(root);
	const signed = files.filter((file) => files.includes(`${file}.sig`));

	if (signed.length === 0) {
		throw new Error(
			`No signed Windows updater artifact found in ${root}. Expected a bundle artifact with a sibling .sig file.`
		);
	}

	const matching = signed.filter((file) => nameCarriesVersion(path.basename(file), version));
	if (matching.length === 0) {
		const found = signed.map((file) => path.basename(file)).join(', ');
		throw new Error(
			`No signed Windows updater artifact in ${root} carries version ${version}. ` +
				`Signed artifacts found: ${found}. A stale artifact from an earlier build ` +
				`would otherwise be published under this version.`
		);
	}

	return matching.sort((a, b) => scoreArtifact(b) - scoreArtifact(a))[0];
}

/**
 * Version present as a whole token, so `0.1.0` does not match inside `0.1.01`
 * or `10.1.0`. A `-` or `_` on either side is a boundary, which is what the
 * `voxrox-mail-<version>-windows-x64-setup.exe` shape uses.
 *
 * The two sides are deliberately not symmetric. On the left a `.` has to be
 * excluded as well as a digit, or `0.1.0` matches the tail of `1.0.1.0`. On the
 * right only the digit may be: excluding `.` there rejects a name that puts the
 * extension straight after the version — `voxrox-mail-0.1.0.exe`, or a
 * `<version>.nsis.zip` — and this function is now a hard condition, so a false
 * negative aborts the release rather than merely deranking a candidate.
 */
function nameCarriesVersion(base, version) {
	const escaped = version.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
	return new RegExp(`(^|[^0-9.])${escaped}([^0-9]|$)`, 'i').test(base);
}

function scoreArtifact(file) {
	const base = path.basename(file).toLowerCase();
	let score = 0;
	if (base.startsWith('voxrox-mail-')) score += 10;
	if (base.endsWith('-setup.exe')) score += 8;
	if (base.endsWith('.exe')) score += 4;
	return score;
}

async function listFiles(dir) {
	const entries = await readdir(dir, { withFileTypes: true });
	const files = await Promise.all(
		entries.map(async (entry) => {
			const fullPath = path.join(dir, entry.name);
			return entry.isDirectory() ? listFiles(fullPath) : [fullPath];
		})
	);
	return files.flat();
}
