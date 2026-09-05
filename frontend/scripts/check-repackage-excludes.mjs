/**
 * Keeps the two copies of the fat-jar exclude list in agreement.
 *
 * backend/pom.xml drops springdoc and its transitive closure from the shipped
 * jar, and it has to say so twice: once on the `repackage` execution, which
 * decides what goes *into* the jar, and once on `process-aot`, which decides
 * which `__BeanDefinitions` Spring generates. Maven offers no clean way to
 * share them — a plugin-level <configuration> would also hit
 * `spring-boot:run`, which deliberately keeps the full classpath — so they are
 * duplicated, and until now nothing checked that they matched.
 *
 * Drift is silent in the worst way. If `process-aot` sees an artifact that
 * `repackage` leaves out, AOT writes bean definitions referring to classes the
 * jar does not contain; `mvn clean verify` stays green, because it runs on the
 * full compile classpath, and the sidecar dies at startup on a user's machine
 * under -Dspring.aot.enabled=true. That is the shape of #393, which cost a
 * release-blocking fix, and the packaged-sidecar smoke only catches it once
 * someone has built and booted an artifact.
 *
 * Three things are asserted:
 *   1. `repackage` and `process-aot` exclude the same groupIds.
 *   2. They exclude the same groupId:artifactId pairs.
 *   3. The `openapi` profile, which resets both filters so -Popenapi can build
 *      a jar *with* springdoc, resets them on BOTH executions. Resetting one
 *      alone is the same failure in a third shape: a jar carrying springdoc
 *      whose AOT metadata was generated without it, or the reverse.
 *
 * The pom is read as text rather than parsed as XML, which is what the other
 * gates here do with it, and it is enough: the shapes below are anchored on
 * the execution ids, and this file's own suite covers the drift cases.
 */

import { readFile } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';

const pomPath = path.resolve(process.cwd(), '..', 'backend', 'pom.xml');

/**
 * The <configuration> block of one execution, found by its <id>.
 *
 * An execution's id and its configuration are siblings and the id may come
 * either side of the configuration, so the block is taken from the execution
 * element that contains the id rather than from a fixed offset. Nested
 * <configuration> elements do not occur inside these, so the first closing tag
 * ends the block.
 */
function configurationOf(pom, executionId, from = 0) {
	const idAt = pom.indexOf(`<id>${executionId}</id>`, from);
	if (idAt === -1) return null;
	const opensAt = pom.indexOf('<configuration', idAt);
	if (opensAt === -1) return null;
	// Self-closing only if the slash belongs to the <configuration> tag itself,
	// which is decided before its first '>'. Looking any further would find the
	// slash of a self-closing child -- the openapi profile's resets are exactly
	// that shape -- and report a populated block as empty.
	const tagEndsAt = pom.indexOf('>', opensAt);
	if (tagEndsAt === -1) return null;
	if (pom[tagEndsAt - 1] === '/') {
		return { text: '', endsAt: tagEndsAt };
	}
	const closesAt = pom.indexOf('</configuration>', tagEndsAt);
	if (closesAt === -1) return null;
	return { text: pom.slice(opensAt, closesAt), endsAt: closesAt };
}

/** Every execution with this id, in document order. */
function allConfigurations(pom, executionId) {
	const found = [];
	let cursor = 0;
	for (;;) {
		const idAt = pom.indexOf(`<id>${executionId}</id>`, cursor);
		if (idAt === -1) return found;
		const block = configurationOf(pom, executionId, idAt);
		if (!block) return found;
		found.push(block);
		cursor = block.endsAt + 1;
	}
}

/** groupIds from <excludeGroupIds>, which is a comma-separated list. */
function excludedGroupIds(configuration) {
	const match = /<excludeGroupIds>([^<]*)<\/excludeGroupIds>/.exec(configuration);
	if (!match) return [];
	return match[1]
		.split(',')
		.map((entry) => entry.trim())
		.filter(Boolean)
		.sort();
}

/** groupId:artifactId pairs from the <excludes> block. */
function excludedArtifacts(configuration) {
	const block = /<excludes>([\s\S]*?)<\/excludes>/.exec(configuration);
	if (!block) return [];
	const pairs = [];
	const entry = /<exclude>([\s\S]*?)<\/exclude>/g;
	let found;
	while ((found = entry.exec(block[1])) !== null) {
		const groupId = /<groupId>([^<]*)<\/groupId>/.exec(found[1])?.[1]?.trim();
		const artifactId = /<artifactId>([^<]*)<\/artifactId>/.exec(found[1])?.[1]?.trim();
		if (!groupId || !artifactId) {
			throw new Error(
				`backend/pom.xml: an <exclude> is missing a groupId or artifactId ` +
					`(groupId=${groupId ?? 'missing'}, artifactId=${artifactId ?? 'missing'}). ` +
					`A groupId-only entry silently matches nothing.`
			);
		}
		pairs.push(`${groupId}:${artifactId}`);
	}
	return pairs.sort();
}

/** Whether a configuration deliberately empties both filters. */
function resetsBothFilters(configuration) {
	return (
		/<excludeGroupIds\s+combine\.self="override"\s*\/>/.test(configuration) &&
		/<excludes\s+combine\.self="override"\s*\/>/.test(configuration)
	);
}

function difference(left, right) {
	return left.filter((entry) => !right.includes(entry));
}

const pom = await readFile(pomPath, 'utf8');

const repackages = allConfigurations(pom, 'repackage');
const processAots = allConfigurations(pom, 'process-aot');

if (repackages.length === 0 || processAots.length === 0) {
	throw new Error(
		`backend/pom.xml: expected a <execution> with id repackage and one with id process-aot ` +
			`(found ${repackages.length} and ${processAots.length}). If the build moved away from ` +
			`them, this gate needs rewriting, not deleting — the two lists still have to agree.`
	);
}

// The populated pair is the default build's; the reset pair belongs to the
// `openapi` profile and is checked separately below.
const populated = (blocks) => blocks.filter((block) => !resetsBothFilters(block.text));

const repackage = populated(repackages);
const processAot = populated(processAots);

if (repackage.length !== 1 || processAot.length !== 1) {
	throw new Error(
		`backend/pom.xml: expected exactly one populated exclude list per execution, found ` +
			`${repackage.length} for repackage and ${processAot.length} for process-aot. ` +
			`A second one would mean two answers to "what does the shipped jar leave out".`
	);
}

const jarGroupIds = excludedGroupIds(repackage[0].text);
const aotGroupIds = excludedGroupIds(processAot[0].text);
const jarArtifacts = excludedArtifacts(repackage[0].text);
const aotArtifacts = excludedArtifacts(processAot[0].text);

const problems = [];

const groupsOnlyInJar = difference(jarGroupIds, aotGroupIds);
const groupsOnlyInAot = difference(aotGroupIds, jarGroupIds);
if (groupsOnlyInJar.length > 0 || groupsOnlyInAot.length > 0) {
	problems.push(
		`excludeGroupIds differ:\n` +
			`  only on repackage:   ${groupsOnlyInJar.join(', ') || '(none)'}\n` +
			`  only on process-aot: ${groupsOnlyInAot.join(', ') || '(none)'}`
	);
}

const artifactsOnlyInJar = difference(jarArtifacts, aotArtifacts);
const artifactsOnlyInAot = difference(aotArtifacts, jarArtifacts);
if (artifactsOnlyInJar.length > 0 || artifactsOnlyInAot.length > 0) {
	problems.push(
		`<excludes> differ:\n` +
			`  only on repackage:   ${artifactsOnlyInJar.join(', ') || '(none)'}\n` +
			`  only on process-aot: ${artifactsOnlyInAot.join(', ') || '(none)'}`
	);
}

if (problems.length > 0) {
	throw new Error(
		`backend/pom.xml: the fat-jar exclude list and what AOT sees have drifted.\n\n` +
			`${problems.join('\n\n')}\n\n` +
			`process-aot decides which __BeanDefinitions are generated and repackage decides what ` +
			`the jar contains, so a difference means AOT metadata pointing at classes that were not ` +
			`shipped. mvn clean verify cannot see it (it runs on the full compile classpath) and the ` +
			`sidecar dies at startup under -Dspring.aot.enabled=true. Keep both lists identical.`
	);
}

const resetRepackage = repackages.length - repackage.length;
const resetProcessAot = processAots.length - processAot.length;
if (resetRepackage !== resetProcessAot) {
	throw new Error(
		`backend/pom.xml: the openapi profile resets the exclude filters on ${resetRepackage} ` +
			`repackage execution(s) but ${resetProcessAot} process-aot execution(s). It has to reset ` +
			`both or neither: resetting one alone builds a jar carrying springdoc whose AOT metadata ` +
			`was generated without it, or the reverse — the same startup failure by another route.`
	);
}

console.log(
	`Repackage excludes OK: repackage and process-aot agree on ${jarGroupIds.length} groupId(s) ` +
		`and ${jarArtifacts.length} artifact(s); ${resetRepackage} profile reset(s) on each.`
);
