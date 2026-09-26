# VoxRox Mail — Frontend (Tauri)

[![CI](../../actions/workflows/ci.yml/badge.svg)](../../actions/workflows/ci.yml)

Desktop mail client frontend built with SvelteKit 2 (Svelte 5), Tailwind CSS 4, Playwright, and Tauri 2. Three main workspace modes: `Mail`, `Contacts`, `Settings`. Talks to a backend that runs as a Tauri sidecar; outside Tauri (browser mode) it runs only against the MSW mocks.

Repo-wide overview and the full doc map live in the monorepo root [`../README.md`](../README.md). This file is frontend-specific.

## Requirements

- Node.js 26
- npm
- Rust toolchain for Tauri builds
- Packaged backend sidecar for Tauri mode; browser mode needs no backend

## Local Development

Install dependencies:

```sh
npm install
```

Start the frontend in browser mode, against the MSW mocks — the fixtures the
Playwright suites use:

```sh
npm run dev -- --mode e2e
```

A bare `npm run dev` stops at boot with
`Cannot read properties of undefined (reading 'invoke')`: finding the backend
goes through Tauri's file-system API, which a plain browser does not have.

Start the Tauri desktop shell (needs the packaged sidecar, see
[Tauri Sidecar Build](#tauri-sidecar-build)):

```sh
npm run tauri:dev
```

## Backend Handshake

In Tauri mode the frontend starts the backend as a sidecar before reading the
handshake files. Browser mode has no handshake: the MSW mocks hand the app a
fixed session.

The backend writes readiness and session files to (`Mail.dev` for dev runs):

```text
%LOCALAPPDATA%\VoxRox\Mail\session.json
%LOCALAPPDATA%\VoxRox\Mail\.ready
```

The frontend waits for `.ready` and then reads `session.json` to discover:

- backend `baseUrl`
- dynamic backend `port`
- `X-API-KEY`

Do not hard-code `localhost:8080`.

## Error Reporting

The release baseline uses a first-party backend endpoint instead of a SaaS SDK.
The frontend installs a global browser error reporter during app startup and
captures:

- `window.error`
- `unhandledrejection`

Reports are posted to:

```text
POST /api/internal/client-errors
```

**The backend does not implement this endpoint yet** — `clientErrors.ts` is the
client half of a contract whose server half is still unwritten, so in practice
the first report of a session gets `404`, reporting disables itself until the
next reload, and nothing is recorded anywhere. Keep that in mind before relying
on it while debugging: if you need the data today, read the console.

The payload includes an event id, timestamp, kind, message, stack, source
location, current route, user agent, language, and backend version metadata from
`session.json`. It intentionally does not include the API key. The `404`/`501`
self-disable is what keeps production users from seeing secondary failures.

## Tauri Sidecar Build

Build the backend sidecar image first, from `backend/`:

```powershell
.\package-sidecar-dev-windows.ps1 -SkipTests
```

It bakes the OAuth client ids from `backend/.env` into the launcher and then
runs `scripts/package-sidecar-windows.ps1`, which on its own expects those ids
in the environment, as CI provides them.

Then copy it into the Tauri bundle input and build the desktop app:

```powershell
npm run sidecar:sync:windows
npm run tauri:build
```

The same two steps in one command (it syncs the sidecar, it does not package
it):

```powershell
npm run tauri:build:with-sidecar
```

The sync step copies the sidecar executable plus its `app/` and `runtime/`
directories into `src-tauri/binaries/`. Those generated artifacts are ignored by
Git; keep only `src-tauri/binaries/.gitkeep`. You can pass custom source and
destination paths to `scripts/sync-backend-sidecar-windows.mjs` if needed.

The Java backend is distributed as a Windows sidecar produced by `jpackage`:
`voxrox-mail-backend-x86_64-pc-windows-msvc.exe` plus the generated `app/` and
`runtime/` directories; the installer carries it as `voxrox-mail-backend.exe`
next to the desktop shell `voxrox-mail.exe`. The bundled runtime means end users do not need to install Java,
JDK, or JRE separately.

Windows builds produce a single NSIS installer:

```text
src-tauri/target/release/bundle/nsis/voxrox-mail-<version>-windows-x64-setup.exe
```

The app name shown to users is `VoxRox Mail`. The installer is per-user
(`installMode: currentUser`, no elevation), so the program binaries install
under `%LOCALAPPDATA%\Programs\VoxRox\Mail`. All runtime data is kept separate,
consolidated under one vendor folder:

```
%LOCALAPPDATA%\VoxRox\Mail\
  webview\        WebView2 user data (cookies, localStorage, IndexedDB)
  logs\           mail-frontend.log + backend logs + audit.log
  db\             SQLite (accounts, messages, drafts)
  attachments\
  crypto.bin      local master key, generated on first start
  session.json    backend handshake payload
  .ready          backend readiness sentinel
```

The backend's part of this folder is described in full in
[`../backend/OPERATIONS.md`](../backend/OPERATIONS.md).

Tauri's bundle identifier remains `org.voxrox.mail` for app identity, code
signing, and updater stability — but data location is decoupled from it. The
WebView2 user data folder and frontend log directory are set explicitly in
`src-tauri/src/lib.rs` via `WebviewWindowBuilder::data_directory()` and the
log plugin's `TargetKind::Folder`, so nothing lands under
`%LOCALAPPDATA%\org.voxrox.mail`.

Dev runs use a parallel root `%LOCALAPPDATA%\VoxRox\Mail.dev` (selected by
`MAIL_DATA_SUFFIX=.dev` in `scripts/tauri-dev-with-env.mjs`), keeping any
production install untouched.

## Windows Code Signing

See [`docs/WINDOWS_SIGNING.md`](docs/WINDOWS_SIGNING.md) for the full setup:
required GitHub secrets, optional repo variables, local signed-build commands,
Tauri updater key generation, and release artifact layout.

## Useful Commands

```sh
npm run check
npm run lint
npm run build
npm run tauri:build
npm run test:functional:stable
npm run test:performance:stable
npm run test:a11y:stable
```

Desktop sidecar crypto: `tauri:dev` reads `backend/.env` for OAuth/dev values,
but it does not pass `MAIL_CRYPTO_KEY` or `MAIL_CRYPTO_SALT` by default. The
sidecar uses the local `crypto.bin` bootstrap instead, in
`%LOCALAPPDATA%\VoxRox\Mail.dev` for a dev run.
Use `npm run tauri:dev -- --include-backend-env-crypto` only for an explicit
backend-env crypto test against a matching data directory.

## API Types

When the backend OpenAPI schema changes, refresh the golden snapshot in the
backend first, then generate the types from it:

```sh
cd ../backend && mvn -Dopenapi.snapshot.update=true test -Dtest=OpenApiSnapshotTest
cd ../frontend && npm run generate:api
```

The snapshot is also what `check:api` compares `schema.d.ts` against, so the
backend does not need to run. There is no live variant: the packaged sidecar
leaves springdoc out of the jar and serves no `/v3/api-docs`.

## Tests

Main frontend journeys run against the SvelteKit preview server:

```sh
npm run test:functional:stable
```

Accessibility suite:

```sh
npm run test:a11y:stable
```

Initial-load performance baseline:

```sh
npm run test:performance:stable
```

The performance test records shell-ready time, inbox-ready time, navigation timing,
resource bytes, and resource count as a Playwright JSON attachment. Budgets are
intentionally broad so CI catches meaningful startup regressions without turning
local machine variance into noise.

The committed `static/mockServiceWorker.js` is intentional so the MSW runtime and worker stay in sync in CI and local development.

## CI

GitHub Actions runs the `CI` workflow on pushes to `main` and on pull requests.

The workflow is split into 9 jobs; five of them are this module's:

- `lint`: `npm run lint` + `npm audit --audit-level=high` + `npm run knip` +
  `npm run knip:production`
- `check`: `npm run check` (includes `check:api` schema-drift)
- `test-unit`: `npm run test:unit:coverage`
- `test-functional`: `npm run test:functional:stable`
- `test-a11y`: `npm run test:a11y:stable`

The rest cover the other modules and the release artifacts: `backend`
(`mvn verify`, translation + i18n lints), `tauri` (`cargo check`, `cargo
clippy -D warnings`, `cargo audit`), `backend-build` and `frontend-build`.

<!-- The job count and every id above are checked against ci.yml by check:docs. -->

Playwright jobs install Chromium with system dependencies and upload `test-results/` on failure.

## License

MIT — see [`../LICENSE`](../LICENSE) at the monorepo root. Release-facing
end-user notes live in [`END_USER_README.md`](END_USER_README.md). Third-party
dependency licenses are inventoried in [`THIRD_PARTY_LICENSES.md`](THIRD_PARTY_LICENSES.md)
(npm) and [`src-tauri/THIRD_PARTY_LICENSES.md`](src-tauri/THIRD_PARTY_LICENSES.md)
(Cargo + bundled OpenJDK).
