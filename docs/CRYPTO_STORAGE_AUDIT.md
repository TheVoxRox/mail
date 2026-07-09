# VoxRox Mail — Crypto & Local Storage Audit

| | |
|---|---|
| **Version** | 1.0 |
| **Date** | 2026-07-09 |
| **Applies to** | VoxRox Mail V0.1.0 |
| **Audited commit** | `d55b753` |
| **Auditor** | Claude (Fable 5) + owner review |
| **Subsystem** | Credential crypto + local filesystem — Boundary 5 of [SECURITY_THREAT_MODEL.md](../SECURITY_THREAT_MODEL.md) |
| **Verdict** | **Security: PASS** (no exploitable finding, no code change). |

Focused verification audit of the boundary **"filesystem ↔ sidecar"**: every
mitigation claimed by the Boundary 5 STRIDE rows was traced to its code path,
plus a data-flow check of the key material from `crypto.bin` to an AES/GCM
operation. Method: static trace only (no dynamic testing); narrower in scope
than the full B3/B4 audits — see [AUDIT_GUIDE.md](AUDIT_GUIDE.md).

## 1. Key material at rest — `crypto.bin` (confirmed)

[StorageContextInitializer](../backend/src/main/java/org/voxrox/mailbackend/core/init/StorageContextInitializer.java)
+ [WindowsDpapiSecretStore](../backend/src/main/java/org/voxrox/mailbackend/core/security/secret/WindowsDpapiSecretStore.java):

- **DPAPI, USER scope** (no `CRYPTPROTECT_LOCAL_MACHINE`) + app-specific
  entropy blob — a `crypto.bin` copied to another user/machine fails
  `CryptUnprotectData` and the boot **fail-stops** with a named recovery
  message (never falls back to plaintext). `CRYPTPROTECT_UI_FORBIDDEN` — no
  interactive prompt path.
- **Native hygiene**: pure Java FFM (no JNI); the plaintext input buffer is
  zeroed after `protect`, the unprotected output buffer is zeroed before
  `LocalFree` after `unprotect`.
- **Protected format** `VOXSEC1\n<base64(DPAPI blob)>`; a legacy plaintext
  `crypto.bin` is migrated in place on load. A `VOXSEC1` header with a
  corrupted payload produces a named recovery error, not a raw base64
  exception. Writes are atomic + private-permissioned.
- **Fingerprint gate**: `crypto.fingerprint` is compared constant-time on
  every start; mismatch hard-stops the app with recovery guidance
  (OPERATIONS.md). The env-override path (`MAIL_CRYPTO_KEY/SALT`) is verified
  constant-time against an existing `crypto.bin` and refuses a silent re-key
  over an existing DB; a half-configured pair (key without salt) refuses to
  boot.

## 2. Credential encryption (confirmed)

[CryptoService](../backend/src/main/java/org/voxrox/mailbackend/core/security/CryptoService.java):

- **AES/GCM-256**, fresh random 12-byte IV per operation, 128-bit tag. The
  algorithm constants are deliberately code (not config) — they are part of
  the on-disk ciphertext format.
- **Per-account key**: PBKDF2WithHmacSHA256, **600,000 iterations** (config
  floor 1,000, default 600k), salt = global salt ‖ accountId, 256-bit output.
  Keys are cached in a bounded LRU.
- **`accountId` bound as AAD** — a ciphertext moved between accounts fails the
  GCM tag, so credentials cannot be swapped across account rows even by direct
  DB edits.
- **Tag mismatch is treated as an attack signal**: `AEADBadTagException`
  escalates to a CRITICAL audit event, distinct from ordinary decrypt errors.
- **Self-test** (PBKDF2 + GCM round trip) runs before first use and fail-stops
  the crypto subsystem if broken; warmed off the boot path so the first OAuth
  callback is not slowed into a duplicate-callback retry.
- **Shutdown hygiene**: the main secret is zeroed in `@PreDestroy`; cached
  `SecretKeySpec`s are dropped, with an honest comment explaining why zeroing
  their copies would be security theater (heap is inside the process boundary
  per the threat model).

## 3. Accepted residuals (documented, not re-litigated)

- **AR-1**: `mail.db` (bodies, contacts) is plaintext at rest — accepted for
  V0.1.0 with BitLocker as the standing mitigation and SQLCipher as the
  upgrade path.
- **Same-user malware** reading `session.json` / DB is out of the adversary
  model (§1 of the threat model); DPAPI protects at-rest secrets, not process
  memory.

## 4. References

- [SECURITY_THREAT_MODEL.md](../SECURITY_THREAT_MODEL.md) — Boundary 5 STRIDE matrix + AR-1.
- [OAUTH_AUDIT.md](OAUTH_AUDIT.md) — Boundary 2 (what flows into this encryption path).
- [backend/OPERATIONS.md](../backend/OPERATIONS.md) — recovery procedures (backup restore, crypto rotation).
- [backend/SECURITY_RELEASE_CHECK.md](../backend/SECURITY_RELEASE_CHECK.md) — per-release security gate.

## 5. Change log

- **1.0** (2026-07-09) — initial focused audit; all Boundary 5 STRIDE
  mitigations verified against `d55b753`.
