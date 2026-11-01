import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { hashBackendSources } from './lib/sidecar-staleness.mjs';

/*
 * Prints the digest of the backend sources checked out beside this script, for
 * backend/scripts/package-sidecar-windows.ps1 to record inside the image it
 * builds. A process rather than a PowerShell twin of hashBackendSources: the
 * digest packaging records and the one `tauri:dev` recomputes have to agree
 * byte for byte, and one implementation cannot disagree with itself.
 */
const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const { hash } = await hashBackendSources(repoRoot);
console.log(hash);
