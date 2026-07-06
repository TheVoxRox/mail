# Privacy Policy — VoxRox Mail

_Version: 2026-06-24 (draft, pre-first-release). This version is preliminary
and has not yet been reviewed by a lawyer — see "Open items" at the bottom._

_Česká verze: [PRIVACY.md](PRIVACY.md)._

VoxRox Mail is a desktop e-mail client for Windows. The application runs
locally on your computer. Your e-mails, contacts, account credentials and
logs stay on your device and are never sent to any VoxRox server or other
third party — except those you choose yourself by adding an e-mail account
(your e-mail provider, and optionally Google or Microsoft for OAuth login),
and except the check for available updates on GitHub (see "What data leaves
the device").

VoxRox does not operate a backend server for this application, does not store
your data in the cloud, and does not collect telemetry, analytics or crash
reports against an external endpoint.

## Who is the data controller

Under the GDPR you are the **data controller** for everything stored by the
application — all data lives locally on your device under your operating
system account. VoxRox only supplies software that processes data according
to your instructions and has no access to your data.

If an **organization** deploys the application to its employees (company
machines, company e-mail accounts), the **organization** is the data
controller for the personal data involved, not VoxRox. Even then the
application sends no data to VoxRox — the organization is responsible for
configuration, backups and informing users under its own data processing
policies.

## What data the application stores locally

All persistent data lives in the standard Windows data directory:

```text
%LOCALAPPDATA%\VoxRox\Mail\
```

Contents:

| File / folder | What it contains |
|---|---|
| `crypto.bin` | Local encryption key for credentials (created on first start). |
| `crypto.fingerprint` | Fingerprint of the key, used to detect swap or corruption of `crypto.bin`. |
| `session.json` | Port and internal API key of the currently running backend (rewritten on every start). |
| `db/mail.db` | SQLite database: accounts, contacts, message headers and bodies, sync state. |
| `db/mail.db.backup-pre-v*` | DB snapshot taken before every schema migration (3 most recent kept). |
| `attachments/` | Local copies of attachments downloaded from the server. |
| `logs/mail.log` | Application log (rotates, max 7 files × 10 MB, total ~100 MB). |
| `logs/audit.log` | Security / audit log (retention 365 days, max ~500 MB). |
| `tmp/` | Temporary files (cleared automatically). |

### What is encrypted

- **IMAP/SMTP passwords** and **OAuth tokens** (Google, Microsoft) are
  encrypted with AES-GCM using a key derived from `crypto.bin`. Without the
  `crypto.bin` stored in your profile they cannot be decrypted.
- **`crypto.bin` itself** is protected only by Windows file system
  permissions on your user profile. Recommendation: do not back it up to
  public cloud storage without additional encryption.

### What is not encrypted

- E-mail headers and bodies, contacts, message subjects, senders,
  attachments, log files and synchronization metadata sit in the SQLite
  database and on disk in plain form. If you need protection against
  physical disk access, use full-disk encryption (BitLocker etc.).

## What data leaves the device

The application initiates network traffic only in these cases:

1. **Connection to your e-mail server (IMAP and SMTP)** — fetching mail,
   sending, folder synchronization. Where exactly the application connects
   depends on the account you add (Gmail, Outlook, Seznam, custom server).
2. **OAuth login (Google, Microsoft)** — if you choose this login method
   instead of an app password. The application opens your default browser
   on the provider's login page, receives an access token + refresh token,
   and stores them locally (encrypted, see above). It periodically obtains
   a new access token from the provider using the refresh token.
3. **Application updates** (Tauri updater) — the application periodically
   connects to
   `https://github.com/TheVoxRox/mail/releases/latest/download/latest.json`
   to check whether a newer signed release exists, and downloads the signed
   installer if so. During this request GitHub (the release host) temporarily
   sees your **IP address** and the version you are querying in its server
   logs — just like any other download from the web. No other data is sent
   during the update check.

The application **does not send** your e-mails, contacts or activity to any
VoxRox server or third-party analytics platform.

### Diagnostic reports from the client

The frontend is prepared to send technical errors (JavaScript exceptions,
failed API calls) to the **local** backend endpoint
`POST /api/internal/client-errors`, which runs on 127.0.0.1 on your computer.
This endpoint is not implemented yet — on the first failed attempt (HTTP 404)
the frontend disables reporting. Once it exists, it will still be a loopback
write to your local logs only; the reports **never leave your device** and are
not sent to VoxRox or any third party.

If you contact support and provide a manually exported diagnostic bundle
(`GET /api/internal/diagnostic-dump`), that bundle contains only masked
e-mail addresses, account configuration (host, port, SSL), synchronization
status and runtime metrics. It **does not contain** credentials, OAuth
tokens, message bodies or subjects.

## Third parties

When you add an account, the following providers come into play:

- **Your e-mail provider** (Gmail, Outlook, Seznam, your own server, ...) —
  governed by their own data processing terms.
- **Google** — if you use Gmail with OAuth ([https://policies.google.com/privacy](https://policies.google.com/privacy)).
- **Microsoft** — if you use Outlook / Hotmail / Live with OAuth ([https://privacy.microsoft.com/](https://privacy.microsoft.com/)).
- **GitHub** — updates are distributed via GitHub Releases, so the application
  periodically checks for a new version against GitHub (see "What data leaves
  the device" above). GitHub sees your IP address and the queried version
  ([https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement)).

VoxRox has no data-sharing agreement with any of these providers regarding
your data. Communication happens directly between your computer and the
respective server.

## Your rights and controls

- **Remove an account from the application** — delete the account in
  Settings. This cascade-deletes the account record, credentials, sync
  state, messages and contacts from the local database.
- **Revoke OAuth access** at the provider:
  - Google: [https://myaccount.google.com/permissions](https://myaccount.google.com/permissions) → find "VoxRox
    Mail" → Remove access.
  - Microsoft: [https://account.microsoft.com/privacy/app-access](https://account.microsoft.com/privacy/app-access)
    (personal accounts) or [https://myapps.microsoft.com](https://myapps.microsoft.com) (work / school
    accounts) → find the app by Client ID → "Remove permission". Microsoft
    does not implement the standard revoke endpoint (RFC 7009), so the
    revocation has to happen on their side.
- **Complete data removal** — uninstall the application and then **manually
  delete** the `%LOCALAPPDATA%\VoxRox\Mail\` directory. The installer
  deliberately does not delete the data directory (so reinstall does not
  lose your mail).
- **Data export** — contacts can be exported to vCard via Settings >
  Contacts. Full database export is not available from the UI in the 0.x
  releases; advanced users may copy `db/mail.db` directly and open it in
  any SQLite browser (e.g. DB Browser for SQLite).
- **Access to logs** — files under `logs/` are plain text readable in any
  editor.

## Your responsibility and backups

Because all data stays on your device and VoxRox has no access to it, **you are
responsible for backing it up and securing it** (or the organization that
deployed the application):

- **Backups** — VoxRox runs no cloud backup. To protect yourself against data
  loss, disk failure or accidental deletion, back up the entire
  `%LOCALAPPDATA%\VoxRox\Mail\` directory (safe procedure in
  [backend/OPERATIONS.md](backend/OPERATIONS.md)). Downloaded mail also remains
  on your provider's server, but local drafts, contacts and settings may not.
- **Device security** — address on-disk sensitivity with a locked Windows
  account and full-disk encryption (BitLocker), see "What is not encrypted"
  above.
- **`crypto.bin`** — without this file your credentials cannot be recovered.
  Do not back it up to unencrypted cloud storage.

The software is provided "AS IS", without any warranty, to the extent
permitted by the MIT License (see [LICENSE](LICENSE)). VoxRox is not liable for
data loss, e-mail service outages or damages arising from use of the
application.

## Children

The application is not specifically aimed at children under 16, but does
not restrict their use either — features depend entirely on the accounts
you add yourself. Recommendation for parents: confirm that your chosen
e-mail provider permits use by a child.

## Security disclosure

If you discover a vulnerability or security issue in the application,
**please do not report it publicly in the issue tracker.** Email
**info@voxrox.org** (the same contact as for ordinary support).

## Changes to this policy

This version is preliminary. Before the first public release and on every
material change (e.g. introducing telemetry, changing the list of third
parties) this document is updated. The current version is always available
in the project repository and at [voxrox.org/en/privacy](https://voxrox.org/en/privacy/).

## Open items before the first release

These items will be filled in before this document is finalized:

- [x] Support contact — **info@voxrox.org** (unified with the voxrox.org site 2026-06-21).
- [x] Security disclosure contact (responsible disclosure) — **info@voxrox.org**.
- [x] Specific Tauri updater endpoint URL — `https://github.com/TheVoxRox/mail/releases/latest/download/latest.json` ([tauri.conf.json](frontend/src-tauri/tauri.conf.json)).
- [x] English translation of this document (this file).
- [ ] Legal review of the whole document (GDPR). The "data controller"
      phrasing for organizational deployment and the "Your responsibility and
      backups" section are drafted, but need confirmation by a lawyer.

---

_VoxRox Mail is open-source software under the MIT License (see [LICENSE](LICENSE)).
Third-party dependencies are listed in the `THIRD_PARTY_LICENSES.md` files
of each module ([backend](backend/THIRD_PARTY_LICENSES.md), [frontend](frontend/THIRD_PARTY_LICENSES.md),
[Tauri runtime](frontend/src-tauri/THIRD_PARTY_LICENSES.md))._
