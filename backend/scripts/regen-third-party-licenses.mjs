#!/usr/bin/env node
/**
 * Regenerates `backend/THIRD_PARTY_LICENSES.md` from the resolved Maven
 * dependency tree (compile + runtime scope only). Uses the
 * `license-maven-plugin`'s `add-third-party` goal to produce a normalized
 * licenses listing, then formats it as markdown.
 *
 * Run before every release; do not commit `pom.xml` dependency-related
 * changes without updating this file.
 *
 * Usage: `node backend/scripts/regen-third-party-licenses.mjs`.
 */

import { execFileSync } from "node:child_process";
import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const here = path.dirname(fileURLToPath(import.meta.url));
const backendDir = path.resolve(here, "..");
const outFile = path.join(backendDir, "THIRD_PARTY_LICENSES.md");
const thirdPartyTxt = path.join(
  backendDir,
  "target",
  "generated-sources",
  "license",
  "THIRD-PARTY.txt",
);

const mvnw = process.platform === "win32" ? "mvnw.cmd" : "mvnw";

execFileSync(
  path.join(backendDir, mvnw),
  [
    "-B",
    "-q",
    "-f",
    path.join(backendDir, "pom.xml"),
    "org.codehaus.mojo:license-maven-plugin:2.7.0:add-third-party",
    "-DexcludedScopes=test,provided",
    "-DexcludedTypes=pom",
  ],
  { cwd: backendDir, stdio: "inherit", shell: process.platform === "win32" },
);

const raw = readFileSync(thirdPartyTxt, "utf8");

// Format:  (License A) (License B) Artifact Name (groupId:artifactId:version - URL)
const entryRe =
  /^\s*((?:\([^)]+\)\s*)+)\s*(.+?)\s*\(([^:]+):([^:]+):([^\s)]+)(?:\s*-\s*([^)]+))?\)$/gm;

/**
 * Canonicalises the license string. The Maven plugin emits raw license
 * names from each POM (e.g. "Apache License, Version 2.0",
 * "The Apache Software License, Version 2.0", "Apache-2.0"), so we
 * normalise them to SPDX identifiers where the mapping is unambiguous.
 */
function canonicalLicense(licenses) {
  const list = licenses
    .split(/\)\s*\(/)
    .map((s) => s.replace(/^[(\s]+|[)\s]+$/g, "").trim())
    .filter(Boolean);
  const mapped = list.map((l) => {
    const low = l.toLowerCase();
    if (/apache.*2\.?0/.test(low) || low === "apache-2.0") return "Apache-2.0";
    if (
      /(eclipse public license|epl).*2\.?0/.test(low) ||
      low.includes("epl-2.0")
    ) {
      return "EPL-2.0";
    }
    if (
      /(eclipse distribution license|edl).*1\.?0/.test(low) ||
      low.includes("edl-1.0")
    ) {
      return "EDL-1.0 (BSD-3-Clause)";
    }
    if (/bsd.*3/.test(low) || low === "bsd-3-clause") return "BSD-3-Clause";
    if (/bsd.*2/.test(low) || low === "bsd-2-clause") return "BSD-2-Clause";
    if (/^(the )?mit( license)?$/.test(low) || /^mit$/.test(low)) return "MIT";
    if (/cc0|creative commons cc0|public domain/.test(low)) return "CC0-1.0";
    if (/lesser general public license|lgpl/.test(low)) return "LGPL";
    if (/general public license/.test(low)) return "GPL";
    return l;
  });
  // If the artefact has both EPL and LGPL/GPL, prefer EPL (Logback ships
  // under EPL-2.0 OR LGPL-2.1; we redistribute under EPL).
  if (
    mapped.includes("EPL-2.0") &&
    (mapped.includes("LGPL") || mapped.includes("GPL"))
  ) {
    return "EPL-2.0";
  }
  // Multiple licenses with no obvious priority — pick the first.
  return mapped[0];
}

/** @type {Map<string, Array<{ name: string, gav: string, url: string, raw: string }>>} */
const byLicense = new Map();
let total = 0;
for (const m of raw.matchAll(entryRe)) {
  const [, rawLicenses, name, groupId, artifactId, version, url = ""] = m;
  const lic = canonicalLicense(rawLicenses);
  const gav = `${groupId.trim()}:${artifactId.trim()}:${version.trim()}`;
  total++;
  if (!byLicense.has(lic)) byLicense.set(lic, []);
  byLicense
    .get(lic)
    .push({ name: name.trim(), gav, url: url.trim(), raw: rawLicenses.trim() });
}

const sortedLicenses = Array.from(byLicense.keys()).sort((a, b) => {
  const ca = byLicense.get(a).length;
  const cb = byLicense.get(b).length;
  if (ca !== cb) return cb - ca;
  return a.localeCompare(b);
});

const summary = sortedLicenses
  .map((lic) => `${byLicense.get(lic).length} ${lic}`)
  .join(", ");

const lines = [
  "# Third-Party Licenses — Backend (Maven)",
  "",
  "VoxRox Mail backend bundles or transitively depends on the following Maven artifacts. All listed entries are compile / runtime scope (test and provided scopes are excluded). Multi-licensed artifacts (e.g., Jakarta EE specs licensed under EPL-2.0 + EDL-1.0 + GPL-2.0 with Classpath Exception) are grouped under the license most relevant for redistribution.",
  "",
  `Counts: ${total} artifacts total. ${summary}.`,
  "",
];

for (const license of sortedLicenses) {
  const pkgs = byLicense
    .get(license)
    .sort((a, b) => a.name.localeCompare(b.name));
  lines.push(`## ${license} (${pkgs.length})`);
  lines.push("");
  for (const { name, gav, url } of pkgs) {
    if (url) {
      lines.push(`- **${name}** \`${gav}\` — [${url}](${url})`);
    } else {
      lines.push(`- **${name}** \`${gav}\``);
    }
  }
  lines.push("");
}

writeFileSync(outFile, lines.join("\n"));
console.log(
  `Wrote ${outFile} — ${total} artifacts, ${sortedLicenses.length} license groups.`,
);
