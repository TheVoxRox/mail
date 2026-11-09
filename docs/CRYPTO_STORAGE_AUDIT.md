# VoxRox Mail — Crypto & Local Storage Audit

|                    |                                                                                                                                                                                                                                                                                                                                                                |
| ------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Version**        | 1.3                                                                                                                                                                                                                                                                                                                                                            |
| **Date**           | 2026-11-09                                                                                                                                                                                                                                                                                                                                                     |
| **Applies to**     | VoxRox Mail V0.2.0-beta.3 (`63159d4`), which under `Code paths` lacks only one javadoc sentence of `AotTrainingExitListener` (#866)                                                                                                                                                                                                                            |
| **Audited commit** | `dc44cd5` (re-verified 2026-11-09, clearing five acknowledgements; 1.1/1.2 anchor: `cad05cb`, re-verified 2026-08-08, recorded pre-squash as `5799e8b`; 1.0 baseline: `d55b753`)                                                                                                                                                                               |
| **Code paths**     | `backend/src/main/java/org/voxrox/mailbackend/core/security/CryptoService.java`, `backend/src/main/java/org/voxrox/mailbackend/core/security/CryptoProperties.java`, `backend/src/main/java/org/voxrox/mailbackend/core/security/secret`, `backend/src/main/java/org/voxrox/mailbackend/core/init`, `backend/src/main/java/org/voxrox/mailbackend/core/backup` |
| **Auditor**        | 1.3 re-verified by Claude Opus 5.5, a session that wrote none of the delta it read (#469, #698, #816, #819, #866); 1.0 to 1.2 Claude (Fable 5) + owner review                                                                                                                                                                                                  |
| **Subsystem**      | Credential crypto + local filesystem — Boundary 5 of [SECURITY_THREAT_MODEL.md](../SECURITY_THREAT_MODEL.md)                                                                                                                                                                                                                                                   |
| **Verdict**        | **Security: PASS** — re-verified against `dc44cd5` (no exploitable finding, no code change). 1.3 corrects three §1 claims that did not hold as written since 1.0; none of them moves the verdict.                                                                                                                                                              |

Focused verification audit of the boundary **"filesystem ↔ sidecar"**: every
mitigation claimed by the Boundary 5 STRIDE rows was traced to its code path,
plus a data-flow check of the key material from `crypto.bin` to an AES/GCM
operation. Method: static trace only (no dynamic testing); narrower in scope
than the full B3/B4 audits — see [AUDIT_GUIDE.md](AUDIT_GUIDE.md).

## 1. Key material at rest — `crypto.bin` (confirmed)

[StorageContextInitializer](../backend/src/main/java/org/voxrox/mailbackend/core/init/StorageContextInitializer.java)
and [WindowsDpapiSecretStore](../backend/src/main/java/org/voxrox/mailbackend/core/security/secret/WindowsDpapiSecretStore.java):

- **DPAPI, USER scope** (no `CRYPTPROTECT_LOCAL_MACHINE`) + app-specific
  entropy blob — a `crypto.bin` copied to another user/machine fails
  `CryptUnprotectData` and the boot **fail-stops** with a named recovery
  message (never falls back to plaintext). `CRYPTPROTECT_UI_FORBIDDEN` — no
  interactive prompt path.

- **Native hygiene**: pure Java FFM (no JNI); the plaintext input buffer is
  zeroed after `protect`, the unprotected output buffer is zeroed before
  `LocalFree` after `unprotect`.
- **Protected format** `VOXSEC1`, a line break, `<base64(DPAPI blob)>`. The
  line break is the platform's (`\r\n` on Windows) and the reader accepts
  either (**corrected in 1.3**: this gave the format as `VOXSEC1\n`). A legacy
  plaintext `crypto.bin` is migrated in place on load. A `VOXSEC1` header with
  a corrupted payload produces a named recovery error, not a raw base64
  exception.
- **Writes** go to a temp file in the same directory and are moved over the
  target atomically. Off Windows the file then gets owner-only permissions; on
  Windows that step is skipped and the file keeps the ACL it inherits from the
  per-user data directory, which is the default-ACL assumption of the threat
  model's Boundary 5 **I** rows (**corrected in 1.3**: this said every write was
  private-permissioned).
- **Fingerprint**: `crypto.fingerprint`, a SHA-256 over a domain tag, the key
  and the salt, is compared constant-time (`MessageDigest.isEqual`) on every
  start. What a mismatch does depends on where the key comes from
  (**corrected in 1.3**: this said a mismatch always hard-stops the app):
  - **Desktop bootstrap**, the release default: `crypto.bin` is the source of
    truth, so a stale or mismatching fingerprint is logged as a WARN, rewritten,
    and the boot continues (OPERATIONS.md; pinned by
    `bootstrapCryptoRefreshesStaleFingerprint`). That is what lets a deleted
    `crypto.bin` be regenerated, the documented recovery at the cost of
    signing every account in again. A tampered `crypto.bin` still fails closed,
    at DPAPI rather than at the fingerprint: a modified blob fails
    `CryptUnprotectData` and the boot aborts.
  - **Env override** (`MAIL_CRYPTO_KEY/SALT`): the pair is verified
    constant-time against an existing `crypto.bin`, and a fingerprint mismatch
    hard-stops the app with recovery guidance, so the override refuses a
    silent re-key over an existing DB. A half-configured pair (key without
    salt) refuses to boot.

## 2. Credential encryption (confirmed)

[CryptoService](../backend/src/main/java/org/voxrox/mailbackend/core/security/CryptoService.java):

- **AES/GCM-256**, fresh random 12-byte IV per operation, 128-bit tag. The
  algorithm constants are deliberately code (not config) — they are part of
  the on-disk ciphertext format.
- **Per-account key**: PBKDF2WithHmacSHA256, **600,000 iterations**, salt =
  global salt ‖ accountId, 256-bit output. Keys are cached in a bounded LRU.
  The iteration count is a constant beside the AES/GCM ones (v1.2): it is part
  of the format, since another count derives another key and every stored
  credential then fails its tag. A package-private constructor takes a lower
  count for unit tests; the bean is built through the `@Autowired` one, which
  passes the constant. `CryptoServiceTest` pins a ciphertext written by the
  production constructor, so a change to any format constant fails the build.
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
- **A replaced `crypto.bin`** (recorded in 1.3). In desktop mode a file put in
  place whole, whether a `VOXSEC1` blob protected for this user or a legacy
  plaintext one, which is then migrated, becomes the key source: the
  fingerprint is rewritten, not enforced. Stored credentials then fail their
  GCM tag, each a CRITICAL audit event, and their accounts are asked to sign
  in again; credentials entered afterwards are sealed under the planted key.
  Writing the file takes write access to the user profile, the same-user
  adversary excluded above, who can already read `mail.db` (AR-1) and replace
  the sidecar (AR-2). Enforcing the fingerprint here would also end the
  deleted-file recovery, so it stays a residual rather than a finding.

## 4. References

- [SECURITY_THREAT_MODEL.md](../SECURITY_THREAT_MODEL.md) — Boundary 5 STRIDE matrix + AR-1.
- [OAUTH_AUDIT.md](OAUTH_AUDIT.md) — Boundary 2 (what flows into this encryption path).
- [backend/OPERATIONS.md](../backend/OPERATIONS.md) — recovery procedures (backup restore, crypto rotation).
- [backend/SECURITY_RELEASE_CHECK.md](../backend/SECURITY_RELEASE_CHECK.md) — per-release security gate.

## 5. Change log

- **1.3** (2026-11-09) — re-verified against `dc44cd5`, clearing the five
  acknowledgements recorded since `cad05cb` (#469, #698, #816, #819, #866)
  before the ledger's 90-day cap, which the oldest of them would have reached
  on 2026-12-10. The delta was read as one change and every file under
  `Code paths` re-read at the anchor, under the default stopping rule of
  AUDIT_GUIDE §5: a false claim blocks the record, so the three below are
  corrected here, and what is limited to the same-user adversary is recorded,
  not chased. Re-run rather than read: `CryptoServiceTest`,
  `StorageContextInitializerTest` and `DatabaseBackupServiceTest` pass with
  nothing skipped; the PBKDF2 count set to 700,000 and the GCM tag to 120 bits
  each fail `pinnedCiphertextStillDecrypts`, so the format pin bites; and the
  real DPAPI store, built from the audited sources and run on Windows, round
  trips a bootstrap and rejects the blob with one bit flipped in the middle or
  in the last byte, naming the likely cause. Three §1 claims did not hold as
  written since 1.0, none of them a code defect: a fingerprint mismatch
  hard-stops the app only on the env-override path, while the desktop default
  rewrites it from `crypto.bin` as OPERATIONS.md documents; writes get
  owner-only permissions only off Windows, where the inherited ACL of the
  per-user directory stands in; and the format's line break is `\r\n` on
  Windows. The replaced-file behaviour that the first of these exposes is a new
  §3 residual. The threat model's Boundary 5 **T** row said the same as the
  first claim and is corrected with it. Verdict unchanged (**PASS**).
- **1.2** (2026-10-11) — the PBKDF2 iteration count moves from the property
  `mail.crypto.pbkdf2-iterations` (floor 1,000, default 600k) to a constant in
  `CryptoService`; §2 says why. Found in a review of what is hard to change
  after the first release: the property invited raising the count as tuning,
  which would have made every installation's credentials undecryptable and
  logged each one as a CRITICAL tag mismatch — the shape of the 210k → 600k
  move this repository once needed a migration service for. The value, the KDF,
  the salt layout, the AAD and the ciphertext layout are unchanged, so nothing
  already encrypted is affected. Verdict unchanged (**PASS**); the drift is
  acknowledged, anchor unchanged at `cad05cb`.
- **1.1** (2026-08-08) — re-verified against `5799e8b` after `check:audits`
  reported two commits of drift, neither of which touches key material or the
  storage guards: #155 rewrites a backup-filename `substring` as a null-safe
  `Optional.map` chain for SpotBugs, and #226 adds the newest applied
  migration's checksum (a non-secret integer) to the `app_started` audit
  event. Every §1–§2 claim re-checked in code and still true — AES/GCM with a
  128-bit tag and 12-byte IV, PBKDF2WithHmacSHA256 at 600k with `accountId`
  bound as AAD, DPAPI USER scope with `CRYPTPROTECT_UI_FORBIDDEN` and no
  `CRYPTPROTECT_LOCAL_MACHINE`, the `VOXSEC1` format, and the constant-time
  `MessageDigest.isEqual` fingerprint gate. Verdict unchanged (**PASS**).
