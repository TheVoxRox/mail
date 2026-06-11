import { readFile, readdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

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

async function findWindowsUpdaterArtifact(root, version) {
	const files = await listFiles(root);
	const signedCandidates = files.filter((file) => files.includes(`${file}.sig`));
	const preferred = signedCandidates.sort(
		(a, b) => scoreArtifact(b, version) - scoreArtifact(a, version)
	);

	if (preferred.length === 0) {
		throw new Error(
			`No signed Windows updater artifact found in ${root}. Expected a bundle artifact with a sibling .sig file.`
		);
	}

	return preferred[0];
}

function scoreArtifact(file, version) {
	const base = path.basename(file).toLowerCase();
	let score = 0;
	if (base.includes(version.toLowerCase())) score += 10;
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

function parseArgs(argv) {
	const parsed = {};
	for (let i = 0; i < argv.length; i += 1) {
		const item = argv[i];
		if (!item.startsWith('--')) {
			throw new Error(`Unexpected argument: ${item}`);
		}

		const [rawKey, inlineValue] = item.slice(2).split('=', 2);
		const value = inlineValue ?? argv[++i];
		if (!rawKey || value === undefined) {
			throw new Error(`Missing value for argument: ${item}`);
		}
		parsed[toCamelCase(rawKey)] = value;
	}
	return parsed;
}

function toCamelCase(value) {
	return value.replace(/-([a-z])/g, (_, char) => char.toUpperCase());
}
