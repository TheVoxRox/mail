import { execFileSync } from 'node:child_process';
import { createHash, generateKeyPairSync, sign } from 'node:crypto';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { verifyMinisignSignature } from './lib/minisign.mjs';

/*
 * The gate protects the one release property that cannot be repaired after
 * shipping: that the key baked into the app is the pair of the key that signed
 * the installer. So the fixture below is not hand-rolled — it is the verbatim
 * output of a real `npx tauri signer generate` + `tauri signer sign` run over
 * REAL_DATA. Constructing a minisign file from the spec and then verifying it
 * with a parser written from the same spec would prove only that the two
 * agreed with each other; this proves the parser reads what Tauri writes,
 * including the choices Tauri makes that the spec leaves open (prehashed ED
 * rather than legacy Ed, and where the trusted comment sits).
 *
 * The matching private key was thrown away with the scratch directory; a
 * public key and a signature are public artifacts by nature.
 */
const REAL_DATA = Buffer.from('fake installer bytes for signature format probe\n');
const REAL_KEY_ID = 'D3CCFB8F9467840D';
const REAL_PUBKEY =
	'dW50cnVzdGVkIGNvbW1lbnQ6IG1pbmlzaWduIHB1YmxpYyBrZXk6IEQzQ0NGQjhGOTQ2Nzg0MEQKUldRTmhHZVVqL3ZNMDZQTG1YK3F5azNOUm5tWXY3QnlVK3RURkNhMlBsdGQ5cFRWTlVrLzE5RGcK';
const REAL_SIGNATURE =
	'dW50cnVzdGVkIGNvbW1lbnQ6IHNpZ25hdHVyZSBmcm9tIHRhdXJpIHNlY3JldCBrZXkKUlVRTmhHZVVqL3ZNMDl5NDdVZUtmZXY1RnpkQnV3aERqYmVkaEM0Z2FKdUxrN0kvODF4UmhpQk5BT3gxWk9KUzQyRnNsRDFsck9ScjZIVDY2bkkrQ0VuVDFKcGt3cmxYaFE0PQp0cnVzdGVkIGNvbW1lbnQ6IHRpbWVzdGFtcDoxNzg3ODYxNzcwCWZpbGU6c2FtcGxlLmJpbgo5Vzl5Ui8zZi9IMU03ekpCWXdaNEV6YnRsbjUycjVtcGhvZVN2endrTEhkRTlFMkVjeDlhWkpubWxLUVFDNzBFUFhIejJ3Ync3eUgvcW1BRVR0dkFBUT09Cg==';

/** The pubkey this repository actually ships, used as "some other key". */
const REPO_PUBKEY =
	'dW50cnVzdGVkIGNvbW1lbnQ6IG1pbmlzaWduIHB1YmxpYyBrZXk6IDYzMzRBRThDOUQ4NDk1RkUKUldUK2xZU2RqSzQwWTFDNkl3NXNNMCtLQ1JzenkzakUwS1VFYU5vWU15cWU5QzE4bVJ6TzRkQzkK';
const REPO_KEY_ID = '6334AE8C9D8495FE';

const scriptPath = path.resolve(
	path.dirname(fileURLToPath(import.meta.url)),
	'verify-updater-signature.mjs'
);

function wrap(text) {
	return Buffer.from(text, 'utf8').toString('base64');
}

/** Rebuilds a base64-wrapped minisign file from its decoded lines. */
function rewrite(signature, mutate) {
	return wrap(mutate(Buffer.from(signature, 'base64').toString('utf8').split('\n')).join('\n'));
}

/**
 * A minisign key pair and signer backed by node's ed25519, used for the cases
 * the real fixture cannot cover: a second key, and the legacy algorithm Tauri
 * does not emit but minisign still defines.
 */
function testKeyPair(keyIdBytes) {
	const { publicKey, privateKey } = generateKeyPairSync('ed25519');
	const keyId = Buffer.from(keyIdBytes);
	const raw = publicKey.export({ format: 'der', type: 'spki' }).subarray(12);

	return {
		keyIdHex: Buffer.from(keyId).reverse().toString('hex').toUpperCase(),
		publicKeyFile: wrap(
			`untrusted comment: test public key\n${Buffer.concat([Buffer.from('Ed', 'latin1'), keyId, raw]).toString('base64')}\n`
		),
		signFile(data, { algorithm = 'ED', trustedComment = 'timestamp:0\tfile:test.bin' } = {}) {
			const signed = algorithm === 'ED' ? createHash('blake2b512').update(data).digest() : data;
			const signature = sign(null, signed, privateKey);
			const payload = Buffer.concat([Buffer.from(algorithm, 'latin1'), keyId, signature]);
			const global = sign(
				null,
				Buffer.concat([signature, Buffer.from(trustedComment, 'utf8')]),
				privateKey
			);
			return wrap(
				`untrusted comment: test signature\n${payload.toString('base64')}\n` +
					`trusted comment: ${trustedComment}\n${global.toString('base64')}\n`
			);
		}
	};
}

describe('verifyMinisignSignature', () => {
	it('accepts a signature produced by the real tauri signer', () => {
		const result = verifyMinisignSignature({
			publicKey: REAL_PUBKEY,
			signature: REAL_SIGNATURE,
			data: REAL_DATA
		});

		// ED, not Ed: Tauri signs the BLAKE2b-512 hash, not the file bytes. The
		// legacy branch below exists because minisign allows both, but this is
		// the one a release actually takes.
		expect(result.algorithm).toBe('ED');
		expect(result.keyIdHex).toBe(REAL_KEY_ID);
		expect(result.trustedComment).toContain('file:sample.bin');
	});

	it('rejects an artifact that changed after signing', () => {
		expect(() =>
			verifyMinisignSignature({
				publicKey: REAL_PUBKEY,
				signature: REAL_SIGNATURE,
				data: Buffer.concat([REAL_DATA, Buffer.from('!')])
			})
		).toThrow(/does not match its signature/);
	});

	it('names both key ids when the pair does not match', () => {
		// The failure the gate exists for: a valid signature, a valid pubkey,
		// and no relationship between them. Reporting it as a bad signature
		// would send the operator looking at the installer instead of at the
		// two secrets.
		expect(() =>
			verifyMinisignSignature({
				publicKey: REPO_PUBKEY,
				signature: REAL_SIGNATURE,
				data: REAL_DATA
			})
		).toThrow(new RegExp(`signed by key ${REAL_KEY_ID}.*pins key ${REPO_KEY_ID}`, 's'));
	});

	it('rejects a trusted comment edited after signing', () => {
		// The main signature still verifies here — it covers the file, not the
		// comment — so this passes unless the global signature is checked too.
		const tampered = rewrite(REAL_SIGNATURE, (lines) =>
			lines.map((line) =>
				line.startsWith('trusted comment: ') ? 'trusted comment: timestamp:0\tfile:evil.exe' : line
			)
		);

		expect(() =>
			verifyMinisignSignature({
				publicKey: REAL_PUBKEY,
				signature: tampered,
				data: REAL_DATA
			})
		).toThrow(/Global signature does not verify/);
	});

	it('accepts the legacy algorithm, which signs the file bytes', () => {
		const keys = testKeyPair([1, 2, 3, 4, 5, 6, 7, 8]);
		const data = Buffer.from('legacy payload');

		const result = verifyMinisignSignature({
			publicKey: keys.publicKeyFile,
			signature: keys.signFile(data, { algorithm: 'Ed' }),
			data
		});

		expect(result.algorithm).toBe('Ed');
	});

	it('refuses input that is not a minisign file', () => {
		expect(() =>
			verifyMinisignSignature({
				publicKey: wrap('not a key at all\n'),
				signature: REAL_SIGNATURE,
				data: REAL_DATA
			})
		).toThrow(/does not look like a base64-wrapped minisign file/);
	});
});

describe('verify-updater-signature.mjs', () => {
	let dir;

	beforeEach(() => {
		dir = mkdtempSync(path.join(os.tmpdir(), 'updater-sig-'));
		mkdirSync(path.join(dir, 'bundle', 'nsis'), { recursive: true });
	});

	afterEach(() => {
		rmSync(dir, { recursive: true, force: true });
	});

	/** Lays down the three artifacts the gate reads, then runs it. */
	function run({
		pubkey = REAL_PUBKEY,
		signature = REAL_SIGNATURE,
		assetName = 'sample.bin',
		updater = {}
	}) {
		writeFileSync(path.join(dir, 'bundle', 'nsis', 'sample.bin'), REAL_DATA);
		writeFileSync(
			path.join(dir, 'config.json'),
			JSON.stringify({ plugins: { updater: { pubkey, ...updater } } })
		);
		writeFileSync(
			path.join(dir, 'latest.json'),
			JSON.stringify({
				version: '9.9.9',
				platforms: {
					'windows-x86_64': {
						signature,
						url: `https://github.com/o/r/releases/download/v9.9.9/${encodeURIComponent(assetName)}`
					}
				}
			})
		);

		return execFileSync(
			process.execPath,
			[
				scriptPath,
				'--config',
				path.join(dir, 'config.json'),
				'--manifest',
				path.join(dir, 'latest.json'),
				'--bundle-dir',
				path.join(dir, 'bundle')
			],
			{ encoding: 'utf8', stdio: 'pipe' }
		);
	}

	it('passes and names the key when the manifest matches the shipped config', () => {
		const output = run({});

		expect(output).toContain(`verifies against key ${REAL_KEY_ID}`);
		expect(output).toContain('Updater signature check OK');
	});

	it('fails the build when the config pins a different key', () => {
		// The scenario in full: a correctly signed installer, a correctly
		// generated manifest, and a TAURI_UPDATER_PUBKEY from the wrong pair.
		expect(() => run({ pubkey: REPO_PUBKEY })).toThrowError(
			new RegExp(`signed by key ${REAL_KEY_ID}`)
		);
	});

	it('fails when the manifest points at a file the build did not produce', () => {
		expect(() => run({ assetName: 'voxrox-mail-0.1.0-windows-x64-setup.exe' })).toThrowError(
			/Manifest names voxrox-mail-0\.1\.0-windows-x64-setup\.exe, but no such file exists/
		);
	});

	it('fails a release whose config allows plain-http updater endpoints', () => {
		// HTTPS-only used to hold because nothing set the env var behind this
		// flag, not because anything checked. A release that fetches its
		// manifest over http can be redirected to a hostile one — the signature
		// still bounds the damage, but nothing should have to rely on that.
		expect(() => run({ updater: { dangerousInsecureTransportProtocol: true } })).toThrowError(
			/dangerousInsecureTransportProtocol/
		);
	});

	it('passes when the flag is present but false', () => {
		// Tauri's own default is false, so a config that spells it out must not
		// be mistaken for one that turns it on.
		expect(run({ updater: { dangerousInsecureTransportProtocol: false } })).toContain(
			'Updater signature check OK'
		);
	});
});
