import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createGateRepo } from './test-support/gate-repo.mjs';

/*
 * The gate reads a diff, not a tree, so every case here is a *change*: a
 * baseline commit, then a commit that removes or renames something. What is
 * worth pinning is the pair of judgements it makes — a name is orphaned only
 * when it left the code, and a leftover mention is only interesting where a
 * compiler will never look.
 */

const SERVICE = 'backend/src/main/java/org/voxrox/mailbackend/ContactService.java';
const TEST = 'backend/src/test/java/org/voxrox/mailbackend/ContactServiceTest.java';

let repo;

function service(body) {
	return `package org.voxrox.mailbackend;\n\npublic class ContactService {\n${body}\n}\n`;
}

/** Commits a service with one method plus a test that mentions it in a comment. */
function baseline() {
	repo.write(SERVICE, service('    public void countByAccountId() {\n    }'));
	repo.write(
		TEST,
		'package org.voxrox.mailbackend;\n\npublic class ContactServiceTest {\n' +
			'    // Counting goes through countByAccountId.\n' +
			'    public void counts() {\n    }\n}\n'
	);
	repo.commit('baseline');
	return repo.git(['rev-parse', 'HEAD']);
}

beforeEach(() => {
	repo = createGateRepo();
});

afterEach(() => {
	repo.cleanup();
});

describe('check-rename-residue', () => {
	it('passes when the range removes no declaration', () => {
		const base = baseline();
		repo.write('README.md', '# Fixture\n');
		repo.commit('docs only');

		const result = repo.run('check-rename-residue.mjs', ['--base', base]);

		expect(result.status).toBe(0);
		expect(result.output).toContain('removes no declaration');
	});

	it('fails when a renamed method is still named in a comment', () => {
		const base = baseline();
		repo.write(SERVICE, service('    public void countGrouped() {\n    }'));
		repo.commit('rename');

		const result = repo.run('check-rename-residue.mjs', ['--base', base]);

		expect(result.status).toBe(1);
		expect(result.output).toContain('countByAccountId');
		expect(result.output).toContain(`${TEST}:4`);
	});

	it('fails when the leftover is a test name rather than a comment', () => {
		const base = baseline();
		repo.write(SERVICE, service('    public void countGrouped() {\n    }'));
		repo.write(
			TEST,
			'package org.voxrox.mailbackend;\n\npublic class ContactServiceTest {\n' +
				'    @DisplayName("countByAccountId groups the labels")\n' +
				'    public void counts() {\n    }\n}\n'
		);
		repo.commit('rename, DisplayName left behind');

		const result = repo.run('check-rename-residue.mjs', ['--base', base]);

		expect(result.status).toBe(1);
		expect(result.output).toContain('countByAccountId');
	});

	it('passes when the rename updated the mention too', () => {
		const base = baseline();
		repo.write(SERVICE, service('    public void countGrouped() {\n    }'));
		repo.write(
			TEST,
			'package org.voxrox.mailbackend;\n\npublic class ContactServiceTest {\n' +
				'    // Counting goes through countGrouped.\n' +
				'    public void counts() {\n    }\n}\n'
		);
		repo.commit('rename, mention updated');

		const result = repo.run('check-rename-residue.mjs', ['--base', base]);

		expect(result.status).toBe(0);
	});

	it('stays quiet when the declaration only moved to another file', () => {
		const base = baseline();
		repo.write(SERVICE, service('    public void other() {\n    }'));
		repo.write(
			'backend/src/main/java/org/voxrox/mailbackend/ContactRepository.java',
			'package org.voxrox.mailbackend;\n\npublic class ContactRepository {\n' +
				'    public void countByAccountId() {\n    }\n}\n'
		);
		repo.commit('move');

		const result = repo.run('check-rename-residue.mjs', ['--base', base]);

		expect(result.status).toBe(0);
	});

	/*
	 * The reason this gate asks "is the name still in code" instead of matching
	 * declarations: an interface method carries no access modifier, so a
	 * declaration matcher misses it and calls a live symbol orphaned.
	 */
	it('treats an interface declaration as code', () => {
		repo.write(
			'backend/src/main/java/org/voxrox/mailbackend/ContactRepository.java',
			'package org.voxrox.mailbackend;\n\npublic interface ContactRepository {\n' +
				'    List<Object> countByAccountId(Long accountId);\n}\n'
		);
		repo.write(SERVICE, service('    public void countByAccountId() {\n    }'));
		repo.commit('baseline with an interface');
		const base = repo.git(['rev-parse', 'HEAD']);

		repo.write(SERVICE, service('    public void other() {\n    }'));
		repo.commit('drop the class method, keep the interface one');

		const result = repo.run('check-rename-residue.mjs', ['--base', base]);

		expect(result.status).toBe(0);
	});

	/*
	 * Markdown is out of scope in both directions, and the second one is the
	 * one worth pinning: a changelog naming the old symbol must not read as the
	 * symbol still being alive, or every documented rename would suppress the
	 * very residue it documents.
	 */
	it('neither reports Markdown nor lets it hide a leftover', () => {
		const base = baseline();
		repo.write(SERVICE, service('    public void countGrouped() {\n    }'));
		repo.write('CHANGELOG.md', '# Changelog\n\n- countByAccountId renamed to countGrouped.\n');
		repo.commit('rename, recorded in the changelog');

		const result = repo.run('check-rename-residue.mjs', ['--base', base]);

		expect(result.status).toBe(1);
		expect(result.output).toContain(`${TEST}:4`);
		expect(result.output).not.toContain('CHANGELOG.md');
	});

	it('ignores names too short or too plain to navigate by', () => {
		repo.write(SERVICE, service('    public void size() {\n    }'));
		repo.write(
			TEST,
			'package org.voxrox.mailbackend;\n\npublic class ContactServiceTest {\n' +
				'    // The size is what matters.\n    public void counts() {\n    }\n}\n'
		);
		repo.commit('baseline');
		const base = repo.git(['rev-parse', 'HEAD']);

		repo.write(SERVICE, service('    public void other() {\n    }'));
		repo.commit('drop it');

		const result = repo.run('check-rename-residue.mjs', ['--base', base]);

		expect(result.status).toBe(0);
	});

	it('accepts a deliberate mention through the commit trailer', () => {
		const base = baseline();
		repo.write(SERVICE, service('    public void countGrouped() {\n    }'));
		repo.commit('rename\n\nRename-residue: the comment describes the old design on purpose.');

		const result = repo.run('check-rename-residue.mjs', ['--base', base]);

		expect(result.status).toBe(0);
		expect(result.output).toContain('waived');
		expect(result.output).toContain('countByAccountId');
	});

	it('skips the zero base of a first push instead of failing', () => {
		baseline();

		const result = repo.run('check-rename-residue.mjs', ['--base', '0'.repeat(40)]);

		expect(result.status).toBe(0);
		expect(result.output).toContain('skipped');
	});

	it('exits 2 without a --base', () => {
		baseline();

		const result = repo.run('check-rename-residue.mjs', []);

		expect(result.status).toBe(2);
		expect(result.output).toContain('Usage');
	});
});
