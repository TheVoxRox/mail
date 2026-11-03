/**
 * The Rust crates the shipped Windows build of the Tauri shell compiles.
 *
 * `cargo metadata` cannot answer this. Its `resolve` graph is the dependency
 * resolver's, not the feature resolver's: an optional dependency stays in it
 * whenever an enabled feature names it weakly (`dep?/feature`), and its
 * feature lists are unified across platforms even under `--filter-platform`.
 * Measured on 2026-11-03, that put 28 crates into the inventory that no build
 * compiles — `serde_with`'s `std` alone names chrono, jiff and both schemars
 * that way, and `reqwest`'s `__rustls-ring` names quinn. `cargo tree` uses the
 * feature resolver the build uses, so it is the source here.
 *
 * Normal edges only: build scripts and dev dependencies do not ship.
 * Procedural macros and what they use stay in. They run inside rustc rather
 * than linking into the binary, but the code they generate is compiled into
 * it. They resolve for the host, so regenerate on Windows, the platform the
 * release is built on.
 *
 * Usage mirrors `packagedArtifacts` in backend/scripts/lib/packaged-artifacts.mjs.
 */

import { execFileSync } from 'node:child_process';

/** The only target VoxRox Mail ships today. */
export const SHIPPED_TARGET = 'x86_64-pc-windows-msvc';

/**
 * Tracks which built crates a caller has accounted for, so that a crate the
 * build compiles but no entry describes is an error rather than a silent
 * omission — that is the direction a missing licence notice hides in.
 */
export function builtCrates(tauriDir) {
	// `--locked` because the inventory describes the committed lockfile;
	// a stale one should fail here, not be rewritten by the listing.
	const listing = execFileSync(
		'cargo',
		[
			'tree',
			'--locked',
			'--target',
			SHIPPED_TARGET,
			'--edges',
			'normal',
			'--prefix',
			'depth',
			'--format',
			'{p}'
		],
		{ cwd: tauriDir, encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 }
	);

	// `1dirs v6.0.0`, `2serde_derive v1.0.229 (proc-macro)`, `3aho-corasick v1.1.5 (*)`.
	// Depth 0 is our own crate.
	const keys = new Set();
	for (const line of listing.split(/\r?\n/)) {
		const match = /^(\d+)(\S+) v(\S+)/.exec(line);
		if (match && match[1] !== '0') keys.add(`${match[2]} ${match[3]}`);
	}
	if (keys.size === 0) throw new Error(`cargo tree printed no dependencies in ${tauriDir}.`);

	const claimed = new Set();
	return {
		/** True when the build compiles `name` at `version`; records it as accounted for. */
		ships(name, version) {
			const key = `${name} ${version}`;
			if (!keys.has(key)) return false;
			claimed.add(key);
			return true;
		},
		/** Throws unless every built crate was claimed by a `ships` call. */
		assertAllAccountedFor(label) {
			const orphans = [...keys].filter((key) => !claimed.has(key)).sort();
			if (orphans.length > 0) {
				throw new Error(
					`${label}: ${orphans.length} crate(s) are compiled for ${SHIPPED_TARGET} with no entry ` +
						`describing them:\n  ${orphans.join('\n  ')}\n` +
						`A built crate with no licence entry is the failure this check exists for.`
				);
			}
		}
	};
}
