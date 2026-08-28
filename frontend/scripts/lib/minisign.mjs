/**
 * Minisign verification, enough to prove that an updater `.sig` was produced
 * by the private half of the public key a release actually ships.
 *
 * Dependency-free on purpose: this runs inside the release workflow, where
 * pulling a verification library in would widen the supply chain of the one
 * step whose job is to distrust the build.
 *
 * Both encodings a Tauri release deals with are the base64 of a whole
 * minisign *file*, not of its payload: `plugins.updater.pubkey` wraps the
 * `.pub` file and the `.sig` artifact wraps the signature file. The layouts
 * below were read off a real `tauri signer sign` artifact rather than the
 * spec — see the fixture in verify-updater-signature.test.mjs, which is that
 * artifact.
 *
 *   public key file   untrusted comment line
 *                     base64( "Ed" | key id (8) | ed25519 public key (32) )
 *
 *   signature file    untrusted comment line
 *                     base64( algorithm (2) | key id (8) | signature (64) )
 *                     "trusted comment: " line
 *                     base64( global signature (64) )
 *
 * The algorithm field decides what the signature covers: `ED` (what Tauri
 * emits) signs the BLAKE2b-512 hash of the file, legacy `Ed` signs the file
 * bytes. The global signature covers the signature followed by the trusted
 * comment, which is what stops that comment from being editable after the
 * fact.
 */

import { createHash, createPublicKey, verify } from 'node:crypto';

const UNTRUSTED_COMMENT_PREFIX = 'untrusted comment:';
const TRUSTED_COMMENT_PREFIX = 'trusted comment: ';

const ALGORITHM_PREHASHED = 'ED';
const ALGORITHM_LEGACY = 'Ed';

const PUBLIC_KEY_BYTES = 42;
const SIGNATURE_BYTES = 74;
const GLOBAL_SIGNATURE_BYTES = 64;

/** DER prefix that turns a raw 32-byte ed25519 key into an SPKI node accepts. */
const SPKI_ED25519_PREFIX = Buffer.from('302a300506032b6570032100', 'hex');

/**
 * Renders a key id the way minisign writes it into the `.pub` comment, so a
 * mismatch can be matched against the key file by eye: the stored bytes are
 * little-endian, the printed form is not.
 */
function formatKeyId(keyId) {
	return Buffer.from(keyId).reverse().toString('hex').toUpperCase();
}

function decodeFile(value, label) {
	const text = Buffer.from(value.trim(), 'base64').toString('utf8');
	if (!text.includes(UNTRUSTED_COMMENT_PREFIX)) {
		throw new Error(
			`${label} does not look like a base64-wrapped minisign file (no untrusted comment line).`
		);
	}
	return text;
}

function decodePayload(line, expectedBytes, label) {
	const payload = Buffer.from((line ?? '').trim(), 'base64');
	if (payload.length !== expectedBytes) {
		throw new Error(`${label} payload is ${payload.length} bytes, expected ${expectedBytes}.`);
	}
	return payload;
}

export function parsePublicKey(value) {
	const text = decodeFile(value, 'Updater public key');
	const payloadLine = text
		.split('\n')
		.map((line) => line.trim())
		.filter(Boolean)
		.find((line) => !line.startsWith(UNTRUSTED_COMMENT_PREFIX));

	const payload = decodePayload(payloadLine, PUBLIC_KEY_BYTES, 'Updater public key');
	const keyId = payload.subarray(2, 10);

	return {
		keyId,
		keyIdHex: formatKeyId(keyId),
		key: createPublicKey({
			key: Buffer.concat([SPKI_ED25519_PREFIX, payload.subarray(10)]),
			format: 'der',
			type: 'spki'
		})
	};
}

export function parseSignature(value) {
	const lines = decodeFile(value, 'Updater signature').split('\n');
	/*
	 * Anchoring on the trusted comment rather than on fixed line numbers: it
	 * is the only line whose position is load-bearing (the signature is the
	 * line before it, the global signature the line after), and its prefix
	 * cannot be confused with the untrusted comment's, which merely contains
	 * it rather than starting with it.
	 */
	const trustedIndex = lines.findIndex((line) => line.startsWith(TRUSTED_COMMENT_PREFIX));
	if (trustedIndex < 1) {
		throw new Error('Updater signature has no trusted comment line.');
	}

	const payload = decodePayload(lines[trustedIndex - 1], SIGNATURE_BYTES, 'Updater signature');
	const algorithm = payload.subarray(0, 2).toString('latin1');
	if (algorithm !== ALGORITHM_PREHASHED && algorithm !== ALGORITHM_LEGACY) {
		throw new Error(`Unsupported minisign algorithm ${JSON.stringify(algorithm)}.`);
	}

	const keyId = payload.subarray(2, 10);

	return {
		algorithm,
		keyId,
		keyIdHex: formatKeyId(keyId),
		signature: payload.subarray(10),
		trustedComment: lines[trustedIndex].slice(TRUSTED_COMMENT_PREFIX.length),
		globalSignature: decodePayload(
			lines[trustedIndex + 1],
			GLOBAL_SIGNATURE_BYTES,
			'Updater global signature'
		)
	};
}

/**
 * Verifies `data` against a base64-wrapped minisign signature and public key.
 * Throws on any failure, naming which half failed — a key-id mismatch is the
 * failure this exists to catch, so it is reported as itself and not as a bad
 * signature.
 *
 * @returns {{ algorithm: string, keyIdHex: string, trustedComment: string }}
 */
export function verifyMinisignSignature({ publicKey, signature, data }) {
	const pub = parsePublicKey(publicKey);
	const sig = parseSignature(signature);

	if (!pub.keyId.equals(sig.keyId)) {
		throw new Error(
			`Signing key mismatch: the artifact is signed by key ${sig.keyIdHex}, ` +
				`but the shipped config pins key ${pub.keyIdHex}. ` +
				'TAURI_SIGNING_PRIVATE_KEY and TAURI_UPDATER_PUBKEY are not a pair.'
		);
	}

	const signed =
		sig.algorithm === ALGORITHM_PREHASHED ? createHash('blake2b512').update(data).digest() : data;

	if (!verify(null, signed, pub.key, sig.signature)) {
		throw new Error(
			`Signature does not verify against key ${pub.keyIdHex} ` +
				`(algorithm ${sig.algorithm}). The artifact does not match its signature.`
		);
	}

	const globalMessage = Buffer.concat([sig.signature, Buffer.from(sig.trustedComment, 'utf8')]);
	if (!verify(null, globalMessage, pub.key, sig.globalSignature)) {
		throw new Error(
			`Global signature does not verify against key ${pub.keyIdHex}. ` +
				'The trusted comment was altered after signing.'
		);
	}

	return { algorithm: sig.algorithm, keyIdHex: pub.keyIdHex, trustedComment: sig.trustedComment };
}
