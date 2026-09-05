import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createGateRepo } from './test-support/gate-repo.mjs';

/*
 * The fat-jar exclude list lives in two places in backend/pom.xml because Maven
 * offers nowhere to share it, and a difference between them is invisible until
 * a user starts the packaged sidecar. These cases are the shapes that
 * difference can take: an artifact in one list and not the other, a groupId in
 * one and not the other, and the openapi profile resetting the filters on one
 * execution while leaving the other alone.
 *
 * Order is deliberately varied where it should not matter. The lists are sets;
 * a gate that also enforced their order would fail on a reviewed reordering and
 * teach people to reach for --no-verify.
 */

let repo;

const SWAGGER = [
	'io.swagger.core.v3:swagger-core-jakarta',
	'io.swagger.core.v3:swagger-models-jakarta'
];
const JACKSON = ['com.fasterxml.jackson.core:jackson-databind'];

function excludesBlock(pairs) {
	if (pairs === null) return '';
	const entries = pairs
		.map((pair) => {
			const [groupId, artifactId] = pair.split(':');
			return `\t\t\t\t\t\t<exclude>\n\t\t\t\t\t\t\t<groupId>${groupId}</groupId>\n\t\t\t\t\t\t\t<artifactId>${artifactId}</artifactId>\n\t\t\t\t\t\t</exclude>`;
		})
		.join('\n');
	return `\t\t\t\t\t<excludes>\n${entries}\n\t\t\t\t\t</excludes>\n`;
}

function execution(id, { groupIds, artifacts, reset = false }) {
	const configuration = reset
		? '\t\t\t\t\t<excludeGroupIds combine.self="override"/>\n\t\t\t\t\t<excludes combine.self="override"/>\n'
		: `\t\t\t\t\t<excludeGroupIds>${groupIds.join(',')}</excludeGroupIds>\n${excludesBlock(artifacts)}`;
	return [
		'\t\t\t\t<execution>',
		`\t\t\t\t\t<id>${id}</id>`,
		'\t\t\t\t\t<configuration>',
		configuration.replace(/\n$/, ''),
		'\t\t\t\t\t</configuration>',
		'\t\t\t\t</execution>'
	].join('\n');
}

/**
 * A pom carrying the two executions of the default build plus, unless told
 * otherwise, the openapi profile that resets both.
 */
function seed({
	jar = { groupIds: ['org.springdoc'], artifacts: [...SWAGGER, ...JACKSON] },
	aot = { groupIds: ['org.springdoc'], artifacts: [...SWAGGER, ...JACKSON] },
	profileResets = ['repackage', 'process-aot']
} = {}) {
	const resets = profileResets.map((id) => execution(id, { reset: true })).join('\n');
	const pom = [
		'<project>',
		'\t<build>',
		'\t\t<plugins>',
		'\t\t\t<plugin>',
		'\t\t\t\t<artifactId>spring-boot-maven-plugin</artifactId>',
		'\t\t\t\t<executions>',
		execution('repackage', jar),
		'\t\t\t\t</executions>',
		'\t\t\t</plugin>',
		'\t\t</plugins>',
		'\t</build>',
		'\t<profiles>',
		'\t\t<profile>',
		'\t\t\t<id>aot</id>',
		'\t\t\t<executions>',
		execution('process-aot', aot),
		'\t\t\t</executions>',
		'\t\t</profile>',
		'\t\t<profile>',
		'\t\t\t<id>openapi</id>',
		'\t\t\t<executions>',
		resets,
		'\t\t\t</executions>',
		'\t\t</profile>',
		'\t</profiles>',
		'</project>'
	].join('\n');
	repo.write('backend/pom.xml', pom);
	return pom;
}

beforeEach(() => {
	repo = createGateRepo();
});

afterEach(() => {
	repo.cleanup();
});

describe('check-repackage-excludes', () => {
	it('passes when both executions exclude the same things', () => {
		seed();

		const result = repo.run('check-repackage-excludes.mjs');

		expect(result.status).toBe(0);
		expect(result.stdout).toContain('Repackage excludes OK');
	});

	it('passes when the two lists agree but are written in a different order', () => {
		seed({
			jar: { groupIds: ['org.springdoc'], artifacts: [...SWAGGER, ...JACKSON] },
			aot: { groupIds: ['org.springdoc'], artifacts: [...JACKSON, ...SWAGGER.slice().reverse()] }
		});

		const result = repo.run('check-repackage-excludes.mjs');

		expect(result.status).toBe(0);
	});

	it('fails when process-aot excludes an artifact repackage keeps', () => {
		seed({
			jar: { groupIds: ['org.springdoc'], artifacts: SWAGGER },
			aot: { groupIds: ['org.springdoc'], artifacts: [...SWAGGER, ...JACKSON] }
		});

		const result = repo.run('check-repackage-excludes.mjs');

		expect(result.status).not.toBe(0);
		expect(result.output).toContain('<excludes> differ');
		expect(result.output).toContain('com.fasterxml.jackson.core:jackson-databind');
	});

	it('fails when repackage excludes an artifact process-aot keeps', () => {
		seed({
			jar: { groupIds: ['org.springdoc'], artifacts: [...SWAGGER, ...JACKSON] },
			aot: { groupIds: ['org.springdoc'], artifacts: SWAGGER }
		});

		const result = repo.run('check-repackage-excludes.mjs');

		expect(result.status).not.toBe(0);
		expect(result.output).toContain('only on repackage');
	});

	it('fails when the excluded groupIds differ', () => {
		seed({
			jar: { groupIds: ['org.springdoc'], artifacts: SWAGGER },
			aot: { groupIds: ['org.springdoc', 'io.swagger.core.v3'], artifacts: SWAGGER }
		});

		const result = repo.run('check-repackage-excludes.mjs');

		expect(result.status).not.toBe(0);
		expect(result.output).toContain('excludeGroupIds differ');
	});

	it('fails when the openapi profile resets only one of the two executions', () => {
		seed({ profileResets: ['repackage'] });

		const result = repo.run('check-repackage-excludes.mjs');

		expect(result.status).not.toBe(0);
		expect(result.output).toContain('resets the exclude filters on');
	});

	it('fails when an exclude names a groupId but no artifactId', () => {
		const pom = seed();
		repo.write(
			'backend/pom.xml',
			pom.replace(/\n\t*<artifactId>swagger-core-jakarta<\/artifactId>/, '')
		);

		const result = repo.run('check-repackage-excludes.mjs');

		expect(result.status).not.toBe(0);
		expect(result.output).toContain('missing a groupId or artifactId');
	});

	it('fails when an execution the gate depends on is gone', () => {
		const pom = seed();
		repo.write('backend/pom.xml', pom.replaceAll('<id>process-aot</id>', '<id>renamed</id>'));

		const result = repo.run('check-repackage-excludes.mjs');

		expect(result.status).not.toBe(0);
		expect(result.output).toContain('needs rewriting, not deleting');
	});
});
