# VoxRox Mail

Desktop email client for Windows, built as a monorepo: Spring Boot backend (sidecar) + SvelteKit/Tauri desktop UI. Targets single-user, local DB, no SaaS.

Project website: [voxrox.org](https://voxrox.org) (Czech/English) — includes the [support](https://voxrox.org/support/) and [privacy policy](https://voxrox.org/privacy/) pages.

The backend runs as a local Tauri sidecar bound to 127.0.0.1 on a random port. The frontend discovers the port and an in-memory API key via a handshake file (`session.json`) the backend writes on startup. Single ship artifact, single user, all data on disk under `%LOCALAPPDATA%\VoxRox\Mail`.

## Stack

- **Backend:** Java 25, Spring Boot 4, SQLite, IMAP/SMTP, OAuth2 (Google, Microsoft)
- **Frontend:** SvelteKit 2, Svelte 5, Tailwind CSS 4, Tauri 2
- **Tests:** JUnit + Spring Test (backend), Playwright + MSW (frontend)

## Repository layout

```text
backend/    Spring Boot mail backend (runs as a Tauri sidecar)
frontend/   SvelteKit + Tauri desktop UI
LICENSE     MIT
```

## Quick start

Requires Node.js 26, JDK 25, Rust toolchain (for Tauri build), Windows.

### 1. Backend (sidecar)

```bash
cd backend
cp .env.example .env       # fill in OAuth values; leave MAIL_CRYPTO_* empty for desktop bootstrap
./mvnw -Dmaven.repo.local=.m2repo spring-boot:run
```

### 2. Frontend (Tauri dev)

```bash
cd frontend
npm install
npm run sidecar:sync:windows   # copies the backend artifact into src-tauri/binaries/
npm run tauri:dev
```

In desktop mode Tauri does not forward `MAIL_CRYPTO_KEY`/`MAIL_CRYPTO_SALT`
from `backend/.env`; the backend creates and uses
`${user.home}/.voxrox/mail/crypto.bin` for local data. Use the explicit env
crypto override only outside the regular desktop flow.

For a browser-only dev run (MSW mocks, no backend):

```bash
cd frontend
npm run dev
```

## Documentation

Module-level entry points:

- [`backend/README.md`](backend/README.md) — backend stack, quick start, operations TOC
- [`frontend/README.md`](frontend/README.md) — frontend stack, scripts, tests
- [`frontend/END_USER_README.md`](frontend/END_USER_README.md) — release notes for end users (Czech)

Operations and release:

- [`backend/OPERATIONS.md`](backend/OPERATIONS.md) — operations runbook (data directory, logs, recovery)
- [`backend/RELEASE_CHECKLIST.md`](backend/RELEASE_CHECKLIST.md) — release checklist
- [`backend/SECURITY_RELEASE_CHECK.md`](backend/SECURITY_RELEASE_CHECK.md) — pre-release security review
- [`backend/PERFORMANCE_BASELINE.md`](backend/PERFORMANCE_BASELINE.md) — cold-start and runtime baselines
- [`frontend/docs/WINDOWS_SIGNING.md`](frontend/docs/WINDOWS_SIGNING.md) — Windows code signing + Tauri updater setup

Contributing and security:

- [`CONTRIBUTING.md`](CONTRIBUTING.md) — contributor guide: local setup, quality gates, commit conventions
- [`SECURITY.md`](SECURITY.md) — security policy: supported versions, how to report vulnerabilities
- [`SECURITY_THREAT_MODEL.md`](SECURITY_THREAT_MODEL.md) — security threat model

History:

- [`CHANGELOG.md`](CHANGELOG.md) — release-level history of the entire monorepo
- [`backend/CHANGELOG.md`](backend/CHANGELOG.md) — backend technical changes and migration notes

Legal / compliance:

- [`PRIVACY.md`](PRIVACY.md) / [`PRIVACY.en.md`](PRIVACY.en.md) — privacy policy (draft; published at [voxrox.org/privacy](https://voxrox.org/privacy/))
- [`backend/THIRD_PARTY_LICENSES.md`](backend/THIRD_PARTY_LICENSES.md) — Maven third-party license inventory
- [`frontend/THIRD_PARTY_LICENSES.md`](frontend/THIRD_PARTY_LICENSES.md) — npm third-party license inventory
- [`frontend/src-tauri/THIRD_PARTY_LICENSES.md`](frontend/src-tauri/THIRD_PARTY_LICENSES.md) — Cargo + bundled OpenJDK license inventory

Live task tracking:

- [`todo.md`](todo.md) — current open work (release gate items, audit follow-ups, manual smoke checklist)

## License

MIT — see [LICENSE](LICENSE).
