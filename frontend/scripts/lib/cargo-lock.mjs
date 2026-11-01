/*
 * Where Cargo.lock keeps the version of the project itself, for
 * bump-version.mjs, which writes it, and check-version-sync.mjs, which reads
 * it. One pattern for both, so the writer and the gate cannot disagree on
 * which entry they mean.
 */

/** The `[package] name` of a Cargo manifest: the name Cargo.lock lists the project under. */
export function cargoPackageName(manifest) {
	const match = /^\[package\][^[]*?^name\s*=\s*"([^"]+)"/ms.exec(manifest);
	if (!match) {
		throw new Error('src-tauri/Cargo.toml does not contain a [package] name');
	}
	return match[1];
}

/**
 * The project's own `[[package]]` entry in Cargo.lock, its version in the
 * second group. Cargo writes `name` and `version` on the two lines after the
 * header, and a dependency elsewhere in the file can carry the same version
 * string, so the entry is found by name rather than by the first match.
 */
export function cargoLockEntry(name) {
	const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
	return new RegExp(
		`(^\\[\\[package\\]\\]\\r?\\nname = "${escaped}"\\r?\\nversion = ")([^"]+)(")`,
		'm'
	);
}
