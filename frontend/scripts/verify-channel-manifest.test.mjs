import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import {
	channelFor,
	channelManifestUrl,
	releaseAssetUrl,
	releaseProblems
} from './lib/channel-manifest.mjs';

/*
 * The URLs are the point of the check, so they are tested against the values
 * the application ships rather than restated: a channel check that polls a URL
 * no installation polls would pass while the real channel stayed empty.
 */

const here = path.dirname(fileURLToPath(import.meta.url));
const read = (relative) => readFileSync(path.join(here, relative), 'utf8');
const REPO = 'TheVoxRox/mail';

describe('channelManifestUrl', () => {
	it('is the stable endpoint in tauri.conf.json', () => {
		const config = JSON.parse(read('../src-tauri/tauri.conf.json'));

		expect([channelManifestUrl(REPO, 'stable')]).toEqual(config.plugins.updater.endpoints);
	});

	it('is the beta endpoint compiled into the shell when no override is set', () => {
		const shell = read('../src-tauri/src/lib.rs');
		const literal = /"(https:\/\/github\.com\/[^"]+\/releases\/download\/beta\/latest\.json)"/.exec(
			shell
		)?.[1];

		expect(literal).toBe(channelManifestUrl(REPO, 'beta'));
	});
});

describe('channelFor', () => {
	it('sends a prerelease-suffixed tag to beta and everything else to stable', () => {
		expect(channelFor('v0.1.0')).toBe('stable');
		expect(channelFor('0.2.0')).toBe('stable');
		expect(channelFor('v0.2.0-beta.1')).toBe('beta');
		expect(channelFor('v0.2.0+build.5')).toBe('stable');
	});

	it('refuses a tag that is not a version', () => {
		expect(() => channelFor('beta')).toThrow('Not a valid semver version');
	});
});

describe('releaseAssetUrl', () => {
	it('is the download URL latest.json names for an asset', () => {
		expect(releaseAssetUrl(REPO, 'v0.1.0', 'voxrox-mail-0.1.0-windows-x64-setup.exe')).toBe(
			'https://github.com/TheVoxRox/mail/releases/download/v0.1.0/voxrox-mail-0.1.0-windows-x64-setup.exe'
		);
	});
});

describe('releaseProblems', () => {
	it('accepts a published stable release and a published prerelease', () => {
		expect(releaseProblems({ isDraft: false, isPrerelease: false }, 'v0.1.0')).toEqual([]);
		expect(releaseProblems({ isDraft: false, isPrerelease: true }, 'v0.2.0-beta.1')).toEqual([]);
	});

	it('refuses a draft', () => {
		expect(releaseProblems({ isDraft: true, isPrerelease: false }, 'v0.1.0')).toEqual([
			'v0.1.0 is still a draft, and no channel serves a draft'
		]);
	});

	it('names both ways the prerelease flag can disagree with the tag', () => {
		expect(releaseProblems({ isDraft: false, isPrerelease: true }, 'v0.1.0')[0]).toContain(
			'the stable channel skips it'
		);
		expect(releaseProblems({ isDraft: false, isPrerelease: false }, 'v0.2.0-beta.1')[0]).toContain(
			'the stable channel serves it'
		);
	});
});
