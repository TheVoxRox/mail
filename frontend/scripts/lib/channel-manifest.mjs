import { parseSemver } from './semver.mjs';

/**
 * The pure half of scripts/verify-channel-manifest.mjs: which update channel a
 * release belongs to, where that channel's manifest lives, and what about the
 * release itself already rules out that the channel serves it.
 */

/** A prerelease-suffixed tag ships to beta only; everything else is stable (OPERATIONS "Release channels"). */
export function channelFor(tag) {
	return parseSemver(tag.replace(/^v/, '')).prerelease.length > 0 ? 'beta' : 'stable';
}

/**
 * The manifest URL an installation polls on that channel. Stable is GitHub's
 * `releases/latest` redirect, which skips drafts and prereleases; beta is the
 * moving `beta` release beta-channel.yml maintains. The suite checks both
 * against the values the application actually ships.
 */
export function channelManifestUrl(repo, channel) {
	return channel === 'beta'
		? `https://github.com/${repo}/releases/download/beta/latest.json`
		: `https://github.com/${repo}/releases/latest/download/latest.json`;
}

export function releaseAssetUrl(repo, tag, name) {
	return `https://github.com/${repo}/releases/download/${tag}/${name}`;
}

/**
 * What about the release, as gh reports it, already means no channel will
 * serve it correctly — no amount of waiting fixes either of these.
 */
export function releaseProblems(release, tag) {
	const problems = [];
	if (release.isDraft) {
		problems.push(`${tag} is still a draft, and no channel serves a draft`);
	}
	const beta = channelFor(tag) === 'beta';
	if (release.isPrerelease !== beta) {
		problems.push(
			beta
				? `${tag} carries a prerelease suffix but is not marked prerelease, so the stable channel serves it`
				: `${tag} is marked prerelease, so the stable channel skips it`
		);
	}
	return problems;
}
