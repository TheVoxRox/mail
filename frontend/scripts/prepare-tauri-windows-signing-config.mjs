import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';

const outputPath = path.resolve(process.argv[2] ?? 'src-tauri/tauri.signing.conf.json');
const certificateThumbprint = process.env.WINDOWS_CERTIFICATE_THUMBPRINT?.trim();
const digestAlgorithm = process.env.WINDOWS_DIGEST_ALGORITHM?.trim() || 'sha256';
const timestampUrl = process.env.WINDOWS_TIMESTAMP_URL?.trim() || 'http://timestamp.digicert.com';

if (!certificateThumbprint) {
	throw new Error('WINDOWS_CERTIFICATE_THUMBPRINT is required.');
}

const config = {
	bundle: {
		windows: {
			certificateThumbprint,
			digestAlgorithm,
			timestampUrl
		}
	}
};

await mkdir(path.dirname(outputPath), { recursive: true });
await writeFile(outputPath, `${JSON.stringify(config, null, '\t')}\n`);

console.log(`Wrote Windows signing config to ${outputPath}`);
