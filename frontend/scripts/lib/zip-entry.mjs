/*
 * Reads one entry out of an in-memory ZIP, with no dependency.
 *
 * Why not just parse local file headers: the backend builds the diagnostic
 * dump with Java's `ZipOutputStream` and never sets an entry's size or CRC up
 * front, so every entry is written with the general-purpose bit 3 set and its
 * local header carries zeroes for both sizes -- the real values land in a data
 * descriptor *after* the compressed bytes. Scanning forward from `PK\x03\x04`
 * therefore cannot tell where an entry ends. The central directory at the tail
 * always carries the true sizes and offsets, so that is what this reads.
 *
 * Only what the dump actually uses is supported: stored (0) and deflate (8).
 * Anything else, or a structure that does not parse, throws -- a diagnostic
 * reader that guesses is worse than one that stops.
 */

import { inflateRawSync } from 'node:zlib';

const EOCD_SIGNATURE = 0x06054b50;
const CENTRAL_SIGNATURE = 0x02014b50;
const LOCAL_SIGNATURE = 0x04034b50;
const EOCD_MIN_SIZE = 22;
const MAX_COMMENT_SIZE = 0xffff;

/**
 * @param {Buffer} buffer complete ZIP archive
 * @param {string} name exact entry name, e.g. "client-boot.json"
 * @returns {Buffer} the entry's uncompressed bytes
 */
function readZipEntry(buffer, name) {
	const entries = readCentralDirectory(buffer);
	const entry = entries.find((candidate) => candidate.name === name);
	if (!entry) {
		throw new Error(
			`ZIP entry "${name}" not found. Entries: ${entries.map((e) => e.name).join(', ')}`
		);
	}

	if (buffer.readUInt32LE(entry.localHeaderOffset) !== LOCAL_SIGNATURE) {
		throw new Error(
			`ZIP entry "${name}" has no local header at offset ${entry.localHeaderOffset}.`
		);
	}

	// The local header repeats the name and extra field, and their lengths may
	// differ from the central directory's -- read them from the local header.
	const localNameLength = buffer.readUInt16LE(entry.localHeaderOffset + 26);
	const localExtraLength = buffer.readUInt16LE(entry.localHeaderOffset + 28);
	const dataStart = entry.localHeaderOffset + 30 + localNameLength + localExtraLength;
	const data = buffer.subarray(dataStart, dataStart + entry.compressedSize);

	if (data.length !== entry.compressedSize) {
		throw new Error(
			`ZIP entry "${name}" is truncated: expected ${entry.compressedSize} compressed bytes, got ${data.length}.`
		);
	}

	const inflated = entry.compressionMethod === 0 ? Buffer.from(data) : inflateRawSync(data);
	if (inflated.length !== entry.uncompressedSize) {
		throw new Error(
			`ZIP entry "${name}" inflated to ${inflated.length} bytes, central directory says ${entry.uncompressedSize}.`
		);
	}
	return inflated;
}

/**
 * @param {Buffer} buffer complete ZIP archive
 * @param {string} name exact entry name
 * @returns {unknown} the entry parsed as JSON
 */
export function readZipEntryJson(buffer, name) {
	return JSON.parse(readZipEntry(buffer, name).toString('utf8'));
}

function readCentralDirectory(buffer) {
	const eocdOffset = findEndOfCentralDirectory(buffer);
	const entryCount = buffer.readUInt16LE(eocdOffset + 10);
	let offset = buffer.readUInt32LE(eocdOffset + 16);

	const entries = [];
	for (let index = 0; index < entryCount; index++) {
		if (buffer.readUInt32LE(offset) !== CENTRAL_SIGNATURE) {
			throw new Error(
				`ZIP central directory entry ${index} has a bad signature at offset ${offset}.`
			);
		}
		const compressionMethod = buffer.readUInt16LE(offset + 10);
		const compressedSize = buffer.readUInt32LE(offset + 20);
		const uncompressedSize = buffer.readUInt32LE(offset + 24);
		const nameLength = buffer.readUInt16LE(offset + 28);
		const extraLength = buffer.readUInt16LE(offset + 30);
		const commentLength = buffer.readUInt16LE(offset + 32);
		const localHeaderOffset = buffer.readUInt32LE(offset + 42);
		const name = buffer.toString('utf8', offset + 46, offset + 46 + nameLength);

		if (compressionMethod !== 0 && compressionMethod !== 8) {
			throw new Error(
				`ZIP entry "${name}" uses unsupported compression method ${compressionMethod}.`
			);
		}

		entries.push({
			name,
			compressionMethod,
			compressedSize,
			uncompressedSize,
			localHeaderOffset
		});
		offset += 46 + nameLength + extraLength + commentLength;
	}
	return entries;
}

function findEndOfCentralDirectory(buffer) {
	if (buffer.length < EOCD_MIN_SIZE) {
		throw new Error(
			`Not a ZIP archive: ${buffer.length} bytes is shorter than an end-of-central-directory record.`
		);
	}
	// The record is last, but a trailing comment can push it up to 64 KiB back.
	const earliest = Math.max(0, buffer.length - EOCD_MIN_SIZE - MAX_COMMENT_SIZE);
	for (let offset = buffer.length - EOCD_MIN_SIZE; offset >= earliest; offset--) {
		if (buffer.readUInt32LE(offset) === EOCD_SIGNATURE) {
			return offset;
		}
	}
	throw new Error('Not a ZIP archive: no end-of-central-directory record found.');
}
