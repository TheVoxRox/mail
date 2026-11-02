# Decisions

Standing decisions that shape the code, the release process or the product,
and the reason behind each. This is a **living** document: when a decision
changes, edit its entry in place, keep the original date and add the date of
the change. A decision taken in an issue or a PR is recorded here in the same
PR.

Most entries end with **Revisit when** — the condition that reopens the
question. Until it is met, the question is settled and does not need to be
argued again.

The entries moved here from the retired root `todo.md` on 2026-11-02; its last
state is in git history.

## Standing conventions

- The frontend and the backend are separate artifacts with their own
  versions.
- The frontend version lives in `frontend/package.json`,
  `frontend/src-tauri/tauri.conf.json` and `frontend/src/lib/version.ts`;
  `npm run check:versions` keeps them equal.
- The backend version lives in `backend/pom.xml`; at runtime
  `ApplicationVersion` reads it from Maven's `build-info.properties`.
- Frontend/backend compatibility is not equality of release versions but the
  handshake over `apiVersion` and `minClientVersion`.
- The Tauri desktop bundle carries the frontend and the backend sidecar; after
  a backend change the sidecar is repackaged and copied into the frontend
  (`npm run sidecar:rebuild:windows`).
- Commit messages are Conventional Commits in English, scoped by the part of
  the monorepo: `backend`, `frontend`, `tauri`, `ci`, `docs`, `repo`,
  `release`.
- The canonical changelog is the root `CHANGELOG.md`; module changelogs hold
  only technical and migration detail.

## Product and data

### Microsoft accounts sign in through OAuth only

IMAP with a password is not an alternative: Microsoft turned off basic
authentication for personal accounts (outlook.com, hotmail, live, msn) on
2024-09-16. The code matches — the presets in
[providerPresets.ts](../frontend/src/lib/accounts/providerPresets.ts) use
`flow: 'oauth'` and the seed sets `supports_oauth2`.

### One address book for the whole application, not one per account

**Decided 2026-08-12**, before the first release, because later it would have
been a V2 migration merging duplicates. `contacts` and `contact_labels` have no
`account_id`, and the endpoints are `/api/v1/contacts` and
`/api/v1/contact-labels`. The account stays only in `/contacts/autocomplete`,
where the per-account correspondence history (`correspondent`) is mixed into
the address book.

### `accounts.signature` stays one plain `TEXT` column

**Decided 2026-08-12**, before the first release, because after it a change
means a migration over user data. The original worry — that HTML compose would
need a `signature_html` column or a `signature_format` discriminator — fell
with the shape of the send path: the editor is a plain `textarea` and stays one
(for the screen reader), Markdown is interpreted only on the way out, and the
HTML alternative is derived from it.

A signature is not a separate entity either: the frontend inserts it straight
into the body as `\n\n-- \n{text}`
([signature.ts](../frontend/src/lib/compose/signature.ts)), so it travels the
same path as the rest of the message and its format is already defined: plain
text that may contain Markdown. A second column would introduce state nobody
fills, and a discriminator would tell apart values that behave the same on the
wire. Reinterpreting existing signatures is not a risk either, because a
signature is interpreted again on every send, not once on save.
`MarkdownBodyRendererTest` holds the invariant: a signature block alone does
not turn on the HTML alternative.

### The mail database is not encrypted at rest

**Decided for V0.1.0.** Column encryption collides with FTS5 and with
`ORDER BY` on subject, sender and received time; full-database SQLCipher is
deferred so the audited SQLite core stays stable. The residual risk is offline
access to a powered-off disk (a lost or stolen device, a copied data folder);
credentials stay sealed with DPAPI. The mitigation is BitLocker plus the
profile's ACLs, documented in [PRIVACY.md](../PRIVACY.md),
[END_USER_README.md](../frontend/END_USER_README.md) and as AR-1 in
[SECURITY_THREAT_MODEL.md](../SECURITY_THREAT_MODEL.md). The upgrade path is
SQLCipher through the drop-in `Willena/sqlite-jdbc-crypt` (Apache 2.0), with
the database key sealed by the existing `SecretStore`/DPAPI.

**Revisit when:** the appetite for protecting data at rest changes.

### The desktop shortcut is always created

**Decided 2026-09-07** while revising the release checklist (#418). The
checklist had claimed an optional checkbox that never existed:
[nsis-installer.nsi](../frontend/src-tauri/windows/nsis-installer.nsi) calls
`CreateOrUpdateDesktopShortcut` unconditionally, and the template has neither a
components page nor `MUI_FINISHPAGE_SHOWREADME`, through which the default
Tauri template offers the choice. Rather than add the checkbox, the behaviour
is confirmed: the shortcut is always created, only `/NS` (NoShortcutMode) or
`/UPDATE` on the command line suppress it, and uninstall removes it.
[RELEASE_CHECKLIST.md](../backend/RELEASE_CHECKLIST.md) §3a therefore checks
that the shortcut exists and points at `${MAINBINARYNAME}.exe`, not that it can
be unticked.

**Revisit when:** testers ask for the option.

### The update check runs only at startup

**Decided 2026-08-28.** `checkForUpdateAndPrompt` is called only from the boot
sequence in [bootstrap.ts](../frontend/src/lib/bootstrap.ts), once per
process, after a boot that succeeded and after one that failed; a running app
does not ask again, and the other route is the manual check in Settings →
About. The argument for a periodic check was that with close-to-tray an
installation could run for weeks without asking — but `mail.closeAction`
defaults to `quit` ([uiLayout.ts](../frontend/src/lib/stores/uiLayout.ts)),
so the tray is opt-in and by default every launch is a check. What is left, a
long-running session, did not justify a new timer with everything it needs
(cleanup, guards against racing an open prompt and a running install).

The cost of the decision was a documentation fix, not code: both privacy
policies had said "periodically" and listed the update check among what keeps
running in the tray, where it does not run at all. Fixed in both languages and
on voxrox.org.

**Revisit when:** the default `closeAction` changes, or users turn out to leave
the app running for days.

## Release process

### Everything ships through beta

**Decided 2026-08-28.** Every change ships first as a beta and is promoted to
stable after testing. **A beta is not a parking place for unfinished work** —
it carries finished features that have passed the developers' testing, and it
only means a wider circle of users.

Why it belongs here and not only in the runbook: it **voids the finding "the
beta guard rejects a legitimate stable hotfix"**. In this model the need to
ship a stable patch such as `0.2.1` next to a running beta `0.3.0-beta.1` never
arises, because `0.3.0` is releasable by definition. A candidate older than the
current beta manifest is therefore not a legitimate state, and a red
`beta-channel.yml` is the right signal, not noise.

The cost: a fix reaches stable users only after a beta cycle. Accepted for the
closed beta (~45 testers). The escape route for a fix that cannot wait is in
[OPERATIONS.md](../backend/OPERATIONS.md) "The release model", including that
beta testers then do not get the fix until the next beta.

**Revisit when:** the beta opens to a larger audience, where the delay of a
stable fix stops being acceptable.

### The release after v0.1.0 is 0.2.0: the number follows the content

**Decided 2026-10-20** as `0.1.1`, because everything since v0.1.0 had been
fixes — a patch by SemVer — and a minor was to come with the first new
feature. **Revised 2026-10-26 to `0.2.0`** (maintainer): #797 brought the first
new feature (Ctrl+Shift+A for an attachment, the total attachment size against
the limit, skipping the same file twice) and was wanted in `main` right away.
The release model forbids only a stable patch next to a running beta of a newer
version, and none was running. **The beta step applies here too**
(`0.2.0-beta.N`, then promotion). Its weak spot: the beta channel reaches only
testers who switched to it in Settings → About; the default is `stable`.

### A release candidate is a tag, not a commit on `main`

**Decided 2026-09-09.** Evidence for §0/§1/§2 written against a branch commit
goes stale at the first merge — exactly what happened to the sheet for
`9c51d9d`, several commits behind on the same day. A tag is immutable, so
nothing later can invalidate a ticked item.

**A merge freeze is rejected:** it cannot be enforced and fails silently, while
a tag gives the same for free. One window cannot be closed this way, because
the process itself needs it — §0/§1/§2 run before the version bump and the
changelog cut, and the tag comes after them. That window is kept to minutes.

**A written rule "a docs-only commit does not void §1" is rejected too.** It had
been applied once by hand (a §1 note of 2026-06-26, "code unchanged since
3210e1a — only docs commits since"), so it would promote a human judgement to a
claim nothing recomputes. If a gate against a moving `main` is ever needed, it
should be **computed**: compare the git object ids of the paths §1 rests on, as
`check:audits` does for audits.

**Revisit when:** releases stop going through a tag.

### The overnight run (§8.2) is not a manual gate before publishing

**Decided 2026-09-13.** A manual overnight run before every publish is unusual,
and this one never happened: it delayed publishing by a night for what its
length buys according to [RELEASE_CHECKLIST.md](../backend/RELEASE_CHECKLIST.md)
§8.2 — the machine's daily cycle (sleep, a network change, token expiry), not
the app's timers. The replacement is a deterministic test for each of those and
a scheduled accelerated soak against GreenMail (`soak.yml`); the daily cycle on
real machines is watched by the beta through diagnostic dumps. For v0.1.0 §8.2
did not run and went into §9 as an accepted risk.

**Revisit when:** beta dumps find bugs the overnight run would have caught and
the automation did not.

### The closed beta: Google stays in Testing, without CASA

**Decided 2026-06-25** for v0.1.0: the Google project stays in Testing (at most
100 test users) without CASA verification. **No trigger for starting CASA is
set** (closes #170 finding 2, decided 2026-08-13): the beta stays at its stated
ceiling of ~45 testers, under half the cap, so it does not hit the limit, and a
number fixed in advance would guard a situation that does not occur.

The cap is **100**, not 50 — checked in
[Google's documentation](https://support.google.com/cloud/answer/15549945) on
2026-08-13, since the decision rests on that number. The quota is **consumed by
adding a tester and not returned by removing one**, so rotating people thins
the reserve even without growth — hence the ceiling at half the cap, not at 90.
The second limit of the Testing mode stays known and accepted: a tester's
consent and refresh token expire 7 days after the grant. Publishing the app and
CASA are tracked in #838.

**Revisit when:** the beta ceiling is to rise above ~70 testers. CASA takes
weeks and costs money, so it must not start only when the limit is hit.

### The Windows installer has no Authenticode signature

**Decided 2026-07-18** (closes #170 finding 5): a paid code-signing certificate
is not justified for an open-source project. The repo has no
`WINDOWS_CERTIFICATE_BASE64` secret, and `windows-signed-release.yml` packages
the installer unsigned by design. The updater is covered by an independent
Ed25519 `.sig`; a first install shows SmartScreen's "Unknown publisher" and is
verified by hand through the Sigstore attestation and the `.sha256` (procedure
in [END_USER_README.md](../frontend/END_USER_README.md)). Recorded as AR-4 in
[SECURITY_THREAT_MODEL.md](../SECURITY_THREAT_MODEL.md).

The upgrade path: SignPath Foundation (free for open source, readiness in
[CODE_SIGNING_POLICY.md](../CODE_SIGNING_POLICY.md), the application tracked in
#837), Azure Trusted Signing, or an OV certificate once a legal
entity exists. **The signing workflow picks up only a PFX certificate**
(`WINDOWS_CERTIFICATE_*`). SignPath and Trusted Signing sign through their own
action without a PFX, so the workflow needs two signing steps — the sidecar
before the bundle, the installer after it — and the updater `.sig` must be
computed from the signed installer.

### Dependency manifests are not part of an audit's `Code paths`

**Decided 2026-08-31** while analysing the jsoup 1.23.2 bump (#349). The blind
spot is real: `check:audits` compares the object ids of the files under
`Code paths`, and neither `backend/pom.xml` nor `frontend/package.json` is
among them, so a library bump can change the behaviour of an audited component
while the gate stays green. With #349 it happened literally — jsoup is the
parser under `HtmlSanitizer.java`, which is a code path, while that file itself
did not change.

**Adding the manifests does not work, and that is measured:** in the 90 days
before the decision `backend/pom.xml` changed 20 times and
`frontend/package.json` 32 times, against a ledger cap of 8 entries in 90
days. The ledger would overflow in about five weeks, and the only way out of a
full ledger is re-verifying the whole audit — the gate would produce ceremony,
not attention. **The layer below it was strengthened instead:**
`HtmlSanitizerMalformedInputTest` asserts the security invariant over a corpus
of malformed markup, so a change in the parser's behaviour fails a test with a
diff in the log rather than a gate asking for thought. A cheaper shape, should
it be built after all: an `audit → artifacts` allowlist inside
`frontend/scripts/check-audit-freshness.mjs`, not the whole manifest; for jsoup
that comes to two events a quarter.

**Revisit when:** other audits turn out to rest on a library's behaviour as
directly as the content-rendering audit rests on jsoup. The OAuth audit (on
Spring Security) and the IMAP/SMTP audit (on jakarta.mail) do not: their claims
target our configuration, which tests cover directly.

### No `distributionSha256Sum` in the Maven wrapper

**Decided 2026-08-17** while reviewing #279; **reasoning rewritten
2026-08-19** — the conclusion stands, but two of the three original premises
were false. The one load-bearing argument: the pin would guard a single
artifact (`apache-maven-*-bin.zip`, which CodeQL's autobuild downloads), and
that comes over HTTPS from `repo.maven.apache.org` — **the same origin as every
other dependency of the pom**, none of which is pinned either. It would protect
a sliver of the surface.

What does not hold from the original reasoning: (1) "it is not verified that
dependabot updates the hash on a bump" — it does: dependabot-core's
`wrapper_updater.rb` removes the checksums before running the wrapper and
recomputes them afterwards, in files that already had them. (2) "the value
would have to be computed from our own download" — true (Maven Central
publishes no `.sha256`, re-checked on 2026-08-19), but that is exactly what
dependabot does for you, so the argument against became one for feasibility.
What remains is a one-off cost for the first value. The premise was false
already when written: Maven Wrapper support landed in dependabot-core on
2026-08-11 (dependabot/dependabot-core#14812), six days before the decision.

**Revisit when:** pinning starts systematically, for the whole pom rather than
this one file.
