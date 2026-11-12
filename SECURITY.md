# Security Policy

Thank you for taking the time to disclose a security issue in VoxRox Mail.
We treat every report as a private matter until a fix and a release are
available.

## Supported Versions

VoxRox Mail is in active development. Only the newest build on each
update channel is supported with security fixes:

- **Stable channel** — the latest release published on the GitHub
  Releases page.
- **Beta channel** (opt-in via Settings → About) — the latest beta
  prerelease. Security fixes reach beta installs as the next beta or
  stable release, whichever ships first; every stable publish also
  updates the beta channel manifest, so the beta channel never lags
  behind stable.

Older releases, superseded beta builds and unreleased commits are not
covered.

| Version                               | Supported                                 |
| ------------------------------------- | ----------------------------------------- |
| `latest` (stable channel)             | Yes — current release                     |
| Latest beta prerelease (beta channel) | Yes — until the next beta or stable build |
| Older or superseded builds            | No — please update                        |

## Reporting a Vulnerability

**Please do not open a public GitHub issue for security reports.**

Report it privately, in either of two ways:

- **GitHub private vulnerability reporting** (preferred): the **Report a
  vulnerability** button on the repository's
  [Security tab](https://github.com/TheVoxRox/mail/security/advisories/new).
  The report, the conversation about it and the fix stay together in one
  private draft advisory until it is published.
- **Email** to **info@voxrox.org**, if you have no GitHub account or would
  rather not use it.

Please include:

- A description of the issue and how to reproduce it.
- The affected version (Settings → Help → About in the desktop app, or the
  `appVersion` field in `session.json`).
- Your operating system, mail provider, and whether the issue requires
  network access to a specific server.
- A proof-of-concept if you have one, in any format.

You should receive an acknowledgement **within 7 calendar days**. If you
do not, please follow up through the other channel.

We will work with you on the timeline. Our default disclosure window is
**90 days** from the initial report, extended by mutual agreement if a
fix requires a longer rollout (for example, an OAuth provider scope
change).

## What Counts as a Vulnerability

Examples of issues we treat as security-sensitive:

- Cross-site scripting (XSS) in rendered mail content despite the
  HTML sanitiser in `frontend/src/lib/mail/content-sanitizer.ts`.
- Bypass of the SSRF guard in the Tauri / Spring loopback handshake
  (`frontend/src/lib/api/session.ts` `validateBackendBaseUrl`).
- Bypass of the API-key header check (`backend/src/main/java/.../core/security/ApiKeyFilter.java`).
- Plain-text credentials written anywhere on disk by either the backend
  or the frontend (passwords + OAuth refresh tokens MUST be encrypted at
  rest via `CryptoService`).
- Token leakage in log files (audit logs and `mail.log` should never
  contain plain-text secrets or unmasked email addresses).
- IMAP / SMTP downgrade attacks (forced STARTTLS strip).
- Privilege escalation against the sidecar process.

## What Does Not Count

- Theoretical issues without a working demonstration.
- Reports targeting third-party services we depend on (Gmail, Microsoft
  365, Seznam) — please report those directly to the provider.
- DoS by physically destroying the device the user runs the app on.

## After the Fix

The fix is published as a GitHub security advisory and named in the
release notes, and both credit the reporter unless they prefer to remain
anonymous. For an issue that shipped in a public release we request a CVE
through that advisory; GitHub assigns CVE identifiers as a CVE Numbering
Authority.
