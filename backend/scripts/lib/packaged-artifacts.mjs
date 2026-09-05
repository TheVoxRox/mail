/**
 * What the shipped backend jar actually carries.
 *
 * The Maven dependency tree and the fat jar are not the same list, and the
 * difference goes both ways:
 *
 *   - `backend/pom.xml` drops springdoc, swagger, the webjars and Jackson 2
 *     from the `repackage` execution, so a dozen artifacts the build resolves
 *     are never distributed;
 *   - the Spring Boot starters are POM aggregators with no jar of their own;
 *   - and `repackage` *adds* `spring-boot-jarmode-tools`, which is in no
 *     dependency scope at all, so a tree-derived inventory cannot see it.
 *
 * A licence inventory or NOTICE built from the tree therefore overstates what
 * is redistributed and — the half that actually matters legally — understates
 * it. Both generators read this module instead.
 *
 * Reading the jar goes through the JDK's own `jar --list`: the backend
 * toolchain already requires a JDK, and Node has no built-in zip reader.
 */

import { execFileSync } from "node:child_process";
import { readdirSync } from "node:fs";
import path from "node:path";

const LIB_PREFIX = "BOOT-INF/lib/";

/**
 * Artifacts that are inside the jar but in no dependency scope, so no tree
 * walk can find them. Kept explicit rather than inferred: an entry here is a
 * claim that someone identified the artifact and its licence by hand.
 *
 * `spring-boot-jarmode-tools` is injected by spring-boot-maven-plugin's
 * `repackage` goal (it backs `java -Djarmode=tools`), and it is redistributed
 * like any other dependency — it was missing from THIRD_PARTY_LICENSES.md for
 * as long as that file was generated from the tree.
 */
const INJECTED_BY_REPACKAGE = [
  {
    groupId: "org.springframework.boot",
    artifactId: "spring-boot-jarmode-tools",
    name: "Spring Boot Jarmode Tools",
    license: "Apache-2.0",
    url: "https://spring.io/projects/spring-boot",
    // Mirrors the publisher the SBOM carries for every other Spring Boot
    // artifact of this version, since this one has no SBOM component at all.
    copyright: "VMware, Inc.",
    repo: "https://github.com/spring-projects/spring-boot",
  },
];

/** Locates the repackaged jar, ignoring the pre-repackage `.original`. */
export function findFatJar(backendDir) {
  const targetDir = path.join(backendDir, "target");
  let candidates;
  try {
    candidates = readdirSync(targetDir);
  } catch {
    candidates = [];
  }
  const jar = candidates
    .filter((name) => /^mail-backend-.*\.jar$/.test(name))
    .sort()
    .at(-1);
  if (!jar) {
    throw new Error(
      `No repackaged backend jar in ${targetDir}. This inventory describes what ships, ` +
        `not what resolves, so it needs the artifact: run \`mvn -DskipTests package\` in backend/ first.`,
    );
  }
  return path.join(targetDir, jar);
}

/**
 * The BOOT-INF/lib file names of a fat jar, e.g. `HikariCP-7.0.2.jar` or
 * `sqlite-jdbc-3.50.4.0-natives-windows.jar`.
 */
export function listPackagedJarNames(fatJarPath) {
  const listing = execFileSync("jar", ["--list", "--file", fatJarPath], {
    encoding: "utf8",
    maxBuffer: 32 * 1024 * 1024,
  });
  return listing
    .split(/\r?\n/)
    .filter((line) => line.startsWith(LIB_PREFIX) && line.endsWith(".jar"))
    .map((line) => line.slice(LIB_PREFIX.length));
}

/**
 * Tracks which packaged jars a caller has accounted for, so that a jar nobody
 * claims is an error rather than a silent omission — that is the direction a
 * missing licence notice hides in.
 */
export function packagedArtifacts(backendDir) {
  const fatJarPath = findFatJar(backendDir);
  const names = listPackagedJarNames(fatJarPath);
  const claimed = new Set();

  /**
   * File names that belong to `artifactId:version`. More than one when the
   * dependency ships under classifiers — sqlite-jdbc is split into
   * `without-natives` plus `natives-windows`.
   */
  function match(artifactId, version) {
    const exact = `${artifactId}-${version}.jar`;
    const classified = `${artifactId}-${version}-`;
    return names.filter(
      (name) => name === exact || name.startsWith(classified),
    );
  }

  return {
    fatJarPath,
    names,
    /** The jar's own count, not the tree's. */
    get count() {
      return names.length;
    },
    /** True when the artifact is distributed; records the match as accounted for. */
    ships(artifactId, version) {
      const hits = match(artifactId, version);
      for (const hit of hits) claimed.add(hit);
      return hits.length > 0;
    },
    /** Classifiers this artifact ships under, `[]` when it ships unclassified. */
    classifiers(artifactId, version) {
      const prefix = `${artifactId}-${version}-`;
      return match(artifactId, version)
        .filter((name) => name.startsWith(prefix))
        .map((name) => name.slice(prefix.length, -".jar".length));
    },
    injected: INJECTED_BY_REPACKAGE.map((entry) => {
      const prefix = `${entry.artifactId}-`;
      const file = names.find((name) => name.startsWith(prefix));
      return file
        ? { ...entry, version: file.slice(prefix.length, -".jar".length) }
        : null;
    }).filter(Boolean),
    /**
     * Throws unless every packaged jar was claimed. `injected` entries count as
     * claimed only once the caller has actually emitted them.
     */
    assertAllAccountedFor(label) {
      for (const entry of INJECTED_BY_REPACKAGE) {
        for (const name of names) {
          if (name.startsWith(`${entry.artifactId}-`)) claimed.add(name);
        }
      }
      const orphans = names.filter((name) => !claimed.has(name));
      if (orphans.length > 0) {
        throw new Error(
          `${label}: ${orphans.length} jar(s) are inside ${path.basename(fatJarPath)} with no entry ` +
            `describing them:\n  ${orphans.join("\n  ")}\n` +
            `A redistributed artifact with no licence entry is the failure this check exists for. ` +
            `Either the dependency is new and the tree walk missed it, or repackage injected it — ` +
            `in which case add it to INJECTED_BY_REPACKAGE in backend/scripts/lib/packaged-artifacts.mjs ` +
            `with its licence.`,
        );
      }
    },
  };
}
