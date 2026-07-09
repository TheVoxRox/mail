# VoxRox Mail — Subsystem Audit Guide

How per-subsystem security audits are produced in this repo. Distilled from
the B3/B4/B6 audits and the 2026-07-09 truing pass that found (and fixed) the
failure modes this guide exists to prevent.

## 1. Scope and tiers

Every audit is scoped to **one trust boundary** of
[SECURITY_THREAT_MODEL.md](../SECURITY_THREAT_MODEL.md) and comes in one of
two declared tiers:

- **Full audit** — enumerates the whole attack surface of the boundary
  (B3/B4/B6 style: every controller/consumer/sink, data-flow of the riskiest
  paths, empirical checks where feasible).
- **Focused audit** — verifies every mitigation claimed by the boundary's
  STRIDE rows plus one end-to-end data-flow (B2/B5 style). Cheaper; the tier
  must be stated so nobody mistakes it for full coverage.

## 2. Document header (required rows)

```
| **Version**         | semver-ish; bump on ANY content change + change-log entry |
| **Date**            | date of this version |
| **Applies to**      | product version |
| **Audited commit**  | the exact SHA the claims were verified against |
| **Auditor**         | who traced the code + who reviewed |
| **Subsystem**       | boundary N of the threat model |
| **Verdict**         | PASS / findings summary |
```

The **audited commit** row exists because "Applies to V0.1.0" is not a code
state — the B4 audit was invalidated by a fix that landed the *same day*
(F2). A SHA makes drift visible instead of silent.

## 3. Method statement (required)

State what was done **and what was not**: static trace vs. dynamic testing,
which paths got data-flow analysis, which enumeration anchors the completeness
claim. Two hard rules:

1. **Enumerations must be reproducible** — include the list itself or the
   command that regenerates it (e.g. `rg -l '@RestController' backend/src`).
   The "all 12 controllers" error (there were 15) happened because the number
   was neither listed nor regenerable.
2. **Static-only is a limitation, name it.** Regression tests count as
   dynamic cover only for the specific claims they exercise.

## 4. Findings lifecycle

Each finding gets: ID → severity **with rationale tied to the threat model's
adversary/scope decisions** → fix (or explicit acceptance) → regression test →
residual risk → upgrade path. See A1 in
[API_SURFACE_AUDIT.md](API_SURFACE_AUDIT.md) for the reference shape.
Informational notes are kept separate from findings.

## 5. Verification pass (required before recording a verdict)

A **different session/agent than the author** re-checks every factual claim
against the audited commit. The 2026-07-09 pass caught three false claims that
the original audits shipped with. Then apply the **propagation rule**: an
audit claim usually lives in 3+ places — grep the key phrase across
`docs/*AUDIT*.md`, `SECURITY_THREAT_MODEL.md` and
`backend/SECURITY_RELEASE_CHECK.md` and correct every occurrence in one
commit. Record the verdict in the threat model change log and a summary
section in SECURITY_RELEASE_CHECK.

## 6. Coverage checklist (ASVS-derived, right-sized for this app)

Not every item applies to every boundary — mark N/A explicitly rather than
skipping silently.

**Authentication & access (ASVS V1/V4)**
- [ ] Single auth chokepoint identified; comparison constant-time
- [ ] Default-deny at the end of the chain; allow-list enumerated and minimal
- [ ] Per-resource authorization enforced, or the single-user IDOR acceptance restated

**Input handling (V5)**
- [ ] Every entrypoint enumerated (reproducibly); validation on each
- [ ] Payloads bounded — including *pre*-deserialization (Content-Length vs. bean validation)
- [ ] Enum/format params reject unknown values

**Output & errors (V7)**
- [ ] No exception message/stack/SQL reaches a client; prod/dev config split verified
- [ ] Security events audited; PII masked in both log streams

**Crypto & transport (V6/V9)**
- [ ] Algorithms + parameters named (mode, key length, KDF iterations, IV/tag)
- [ ] Key storage + compromise/rotation story stated (even if procedural)
- [ ] TLS enforced; downgrade/cleartext paths blocked fail-closed

**Files & storage (V12)**
- [ ] No attacker-controlled value reaches a filesystem path (traversal)
- [ ] Temp-file lifecycle (creation scope, cleanup, crash sweep)
- [ ] Sensitive files: atomic writes, private permissions

**Config & supply chain (V10/V14)**
- [ ] Build fails closed on missing secrets/keys; placeholder traps checked
- [ ] Signing/provenance chain traced end to end (for release channels)

**Content rendering (app-specific)**
- [ ] Sanitizer chokepoint has no unprotected consumer (grep the import graph)
- [ ] DOM sink sweep: `{@html}`, `innerHTML`, `insertAdjacentHTML`, `outerHTML`, `document.write` = 0
- [ ] Layering verified independently (sanitizer / CSP / sandbox each hold alone)

**Cross-cutting**
- [ ] CSRF posture stated wherever cookies/sessions exist (even if "disabled because X")
- [ ] DoS bounds on caches, queues, recursion (MAX_DEPTH-style caps)

## 7. Current audit map

| Boundary | Audit | Tier |
|---|---|---|
| B1 external mail server | TLS-hardening PRs (#25/#26) + IMAP sync/write review 2026-06-06 | informal |
| B2 OAuth handshake | [OAUTH_AUDIT.md](OAUTH_AUDIT.md) | focused |
| B3 sidecar HTTP API | [API_SURFACE_AUDIT.md](API_SURFACE_AUDIT.md) | full |
| B4 WebView ↔ SPA | [CONTENT_RENDERING_AUDIT.md](CONTENT_RENDERING_AUDIT.md) | full |
| B5 crypto + filesystem | [CRYPTO_STORAGE_AUDIT.md](CRYPTO_STORAGE_AUDIT.md) | focused |
| B6 Tauri updater | [UPDATER_AUDIT.md](UPDATER_AUDIT.md) | full |

Cross-cutting records (secret scan, log hygiene, cargo audit) live in
[backend/SECURITY_RELEASE_CHECK.md](../backend/SECURITY_RELEASE_CHECK.md);
the API contract audit in
[backend/docs/OPENAPI_AUDIT.md](../backend/docs/OPENAPI_AUDIT.md).
