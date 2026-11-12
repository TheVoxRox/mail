import { describe, expect, it } from 'vitest';
import { differsOnlyInVersion } from './version-only.mjs';

/*
 * As with comment-only.mjs, the one failure that matters is a yes to a change
 * that was not a version bump, because check:audits then lets it past unread.
 * Most cases below are a bump with something else riding along.
 */

const file = 'tauri.conf.json';
const config = (version, extra = '') =>
	`{\n\t"productName": "VoxRox Mail",\n\t"version": "${version}",\n\t"plugins": {\n\t\t"updater": { "pubkey": "AAA" }${extra}\n\t}\n}\n`;

describe('differsOnlyInVersion', () => {
	it('reads a bump of the top-level version as a version change only', () => {
		expect(differsOnlyInVersion(file, config('0.2.0-beta.4'), config('0.2.0'))).toBe(true);
	});

	it('sees a second change made in the same edit', () => {
		const after = config('0.2.0').replace('"AAA"', '"BBB"');
		expect(differsOnlyInVersion(file, config('0.2.0-beta.4'), after)).toBe(false);
	});

	it('sees a member added beside the bump', () => {
		expect(
			differsOnlyInVersion(file, config('0.2.0-beta.4'), config('0.2.0', ', "endpoints": []'))
		).toBe(false);
	});

	it('sees a whitespace change beside the bump, since the yes is textual', () => {
		const after = config('0.2.0').replace('"productName": ', '"productName":  ');
		expect(differsOnlyInVersion(file, config('0.2.0-beta.4'), after)).toBe(false);
	});

	it('does not count an unchanged version, or a change elsewhere, as a bump', () => {
		expect(differsOnlyInVersion(file, config('0.2.0'), config('0.2.0'))).toBe(false);
		const after = config('0.2.0').replace('"AAA"', '"BBB"');
		expect(differsOnlyInVersion(file, config('0.2.0'), after)).toBe(false);
	});

	it('does not count a nested version as the top-level one', () => {
		const before = '{\n\t"version": "1.0.0",\n\t"plugin": { "version": "2.0.0" }\n}\n';
		const after = '{\n\t"version": "1.0.0",\n\t"plugin": { "version": "2.1.0" }\n}\n';
		expect(differsOnlyInVersion(file, before, after)).toBe(false);
	});

	/*
	 * The top-level member spelled with an escape does not match the pattern, so
	 * a nested member with the same value is the only textual match, and
	 * replacing it produces the new text exactly. The parsed top-level version
	 * did not move, and that is what says no.
	 */
	it('is not fooled when the only textual match is a nested member', () => {
		const before = '{"vers\\u0069on": "1.0.0", "plugin": {"version": "1.0.0"}}';
		const after = '{"vers\\u0069on": "1.0.0", "plugin": {"version": "2.0.0"}}';
		expect(differsOnlyInVersion(file, before, after)).toBe(false);
	});

	it('answers no when the version member appears twice with the old value', () => {
		const before = '{"version": "1.0.0", "plugin": {"version": "1.0.0"}}';
		const after = '{"version": "2.0.0", "plugin": {"version": "1.0.0"}}';
		expect(differsOnlyInVersion(file, before, after)).toBe(false);
	});

	it('answers no for a file that is not JSON, or does not parse', () => {
		expect(differsOnlyInVersion('Cargo.toml', 'version = "1.0.0"\n', 'version = "2.0.0"\n')).toBe(
			false
		);
		expect(differsOnlyInVersion(file, '{"version": "1.0.0",}', '{"version": "2.0.0",}')).toBe(
			false
		);
	});

	it('answers no when the top level is not an object or the version is not a string', () => {
		expect(differsOnlyInVersion(file, '["1.0.0"]', '["2.0.0"]')).toBe(false);
		expect(differsOnlyInVersion(file, '{"version": 1}', '{"version": 2}')).toBe(false);
	});
});
