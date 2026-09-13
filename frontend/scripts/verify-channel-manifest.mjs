import { execFileSync } from 'node:child_process';
import process from 'node:process';
import {
	channelFor,
	channelManifestUrl,
	releaseAssetUrl,
	releaseProblems
} from './lib/channel-manifest.mjs';
import { checkManifest, pickInstaller } from './lib/release-status.mjs';
import { wait } from './lib/run.mjs';

/*
 * `node scripts/verify-channel-manifest.mjs --tag=vX.Y.Z [--repo=owner/repo]`
 * (from frontend/) — after a release is published, fetches latest.json through
 * the URL an installation actually polls, and checks it names this version,
 * this installer and the signature attached next to it, and that the installer
 * URL answers.
 *
 * RELEASE_PROCESS step 7 called this "the one part of the chain that a green
 * build does not prove": the signed workflow uploads latest.json but never reads
 * it back through the stable redirect or the beta release, so a publish that
 * left the stable channel on an older release — or on none — looked the same as
 * one that worked. release-channel-check.yml runs it on every published release
 * and on demand.
 *
 * It retries rather than failing on the first miss: the beta manifest is written
 * by beta-channel.yml, which runs on the same event, and GitHub's redirect can
 * take a moment to move. A wrong answer that persists across every attempt is a
 * failure; the problems of the last attempt are what it prints.
 */

const args = new Map(
	process.argv
		.slice(2)
		.filter((arg) => arg.startsWith('--') && arg.includes('='))
		.map((arg) => {
			const [key, ...valueParts] = arg.slice(2).split('=');
			return [key, valueParts.join('=')];
		})
);

function positiveInt(raw, fallback) {
	const value = Number.parseInt(raw ?? '', 10);
	return Number.isFinite(value) && value > 0 ? value : fallback;
}

const tag = args.get('tag');
const repo = args.get('repo') ?? process.env.GITHUB_REPOSITORY;
const attempts = positiveInt(args.get('attempts'), 20);
const intervalMs = positiveInt(args.get('interval-ms'), 30_000);

if (!tag || !repo) {
	console.error('Usage: node scripts/verify-channel-manifest.mjs --tag=vX.Y.Z [--repo=owner/repo]');
	process.exit(2);
}

function fail(message) {
	console.error(`Channel check failed for ${tag}: ${message}`);
	process.exit(1);
}

const version = tag.replace(/^v/, '');
const channel = channelFor(tag);
const release = JSON.parse(
	execFileSync(
		'gh',
		['release', 'view', tag, '--repo', repo, '--json', 'isDraft,isPrerelease,assets'],
		{ encoding: 'utf8' }
	)
);

const upfront = releaseProblems(release, tag);
if (upfront.length > 0) fail(upfront.join('; '));

const installer = pickInstaller(
	release.assets.map((asset) => asset.name),
	version
);
if (!installer) fail(`the release has no installer for ${version}`);

const installerUrl = releaseAssetUrl(repo, tag, installer);
const signatureResponse = await fetch(`${installerUrl}.sig`, { redirect: 'follow' });
if (!signatureResponse.ok) {
	fail(`${installer}.sig answered HTTP ${signatureResponse.status}`);
}
const signature = await signatureResponse.text();
const manifestUrl = channelManifestUrl(repo, channel);

console.log(`Checking the ${channel} channel at ${manifestUrl} for ${version}`);

for (let attempt = 1; attempt <= attempts; attempt++) {
	let problems;
	try {
		const response = await fetch(manifestUrl, { redirect: 'follow', cache: 'no-store' });
		if (!response.ok) {
			problems = [`the manifest answered HTTP ${response.status}`];
		} else {
			problems = checkManifest({
				manifest: await response.json(),
				version,
				installerUrl,
				signature
			});
			if (problems.length === 0) {
				const head = await fetch(installerUrl, { method: 'HEAD', redirect: 'follow' });
				if (!head.ok) problems = [`the installer URL answered HTTP ${head.status}`];
			}
		}
	} catch (error) {
		problems = [error.message];
	}

	if (problems.length === 0) {
		console.log(
			`The ${channel} channel serves ${version}: the manifest names ${installer}, carries its signature, and the installer URL answers.`
		);
		process.exit(0);
	}
	console.log(`Attempt ${attempt} of ${attempts}: ${problems.join('; ')}`);
	if (attempt < attempts) await wait(intervalMs);
}

fail(`the ${channel} channel did not serve it after ${attempts} attempts`);
