# VoxRox Mail — Frontend (Tauri)

[![CI](../../actions/workflows/ci.yml/badge.svg)](../../actions/workflows/ci.yml)

Desktop mail client frontend built with SvelteKit 2 (Svelte 5), Tailwind CSS 4, Playwright, and Tauri 2. Three main workspace modes: `Mail`, `Contacts`, `Settings`. Talks to a backend that runs either as a Tauri sidecar (desktop) or separately (browser mode); browser mode can also run fully mocked through MSW.

Repo-wide overview and the full doc map live in the monorepo root [`../README.md`](../README.md). This file is frontend-specific.

## Requirements

- Node.js 26
- npm
- Rust toolchain for Tauri builds
- Running backend for browser mode, or packaged backend sidecar for Tauri mode

## Local Development

Install dependencies:

```sh
npm install
```

Start the frontend in browser mode:

```sh
npm run dev
```

Start the Tauri desktop shell:

```sh
npm run tauri:dev
```

## Backend Handshake

In Tauri mode the frontend starts the backend as a sidecar before reading the
handshake files. In browser mode the backend still has to run separately.

The backend writes readiness and session files to:

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

The payload includes an event id, timestamp, kind, message, stack, source
location, current route, user agent, language, and backend version metadata from
`session.json`. It intentionally does not include the API key. If the endpoint is
not implemented yet and returns `404` or `501`, reporting is disabled until the
next reload so production users do not see secondary failures.

## Tauri Sidecar Build

Build the backend sidecar image first from the backend repository:

```powershell
.\scripts\package-sidecar-windows.ps1
```

Then copy it into the Tauri bundle input and build the desktop app:

```powershell
npm run sidecar:sync:windows
npm run tauri:build
```

For a one-shot Windows release build:

```powershell
npm run tauri:build:with-sidecar
```

The sync step copies the sidecar executable plus its `app/` and `runtime/`
directories into `src-tauri/binaries/`. Those generated artifacts are ignored by
Git; keep only `src-tauri/binaries/.gitkeep`. You can pass custom source and
destination paths to `scripts/sync-backend-sidecar-windows.mjs` if needed.

The Java backend is distributed as a Windows sidecar produced by `jpackage`:
`mail-x86_64-pc-windows-msvc.exe` plus the generated `app/` and `runtime/`
directories. The bundled runtime means end users do not need to install Java,
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
  crypto.bin      deterministic crypto key
  session.json    backend handshake payload
  .ready          backend readiness sentinel
```

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
desktop release path uses the local `%LOCALAPPDATA%\VoxRox\Mail\crypto.bin`
bootstrap.
Use `npm run tauri:dev -- --include-backend-env-crypto` only for an explicit
backend-env crypto test against a matching data directory.

## API Types

When the backend OpenAPI schema changes:

```sh
npm run generate:api
```

By default this uses the backend golden snapshot, so the backend does not need to run and the
production sidecar does not need to expose `/v3/api-docs`.

You can also override the schema URL manually:

```powershell
$session = Get-Content "$env:LOCALAPPDATA\VoxRox\Mail\session.json" | ConvertFrom-Json
$origin = $session.baseUrl -replace '/api$', ''
$env:OPENAPI_URL = "$origin/v3/api-docs"
npm run generate:api:live
Remove-Item Env:\OPENAPI_URL
```

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

The workflow is split into four jobs:

- `lint`: `npm run lint`
- `check`: `npm run check`
- `test-functional`: `npm run test:functional:stable`
- `test-a11y`: `npm run test:a11y:stable`

Playwright jobs install Chromium with system dependencies and upload `test-results/` on failure.

## License

MIT — see [`../LICENSE`](../LICENSE) at the monorepo root. Release-facing
end-user notes live in [`END_USER_README.md`](END_USER_README.md). Third-party
dependency licenses are inventoried in [`THIRD_PARTY_LICENSES.md`](THIRD_PARTY_LICENSES.md)
(npm) and [`src-tauri/THIRD_PARTY_LICENSES.md`](src-tauri/THIRD_PARTY_LICENSES.md)
(Cargo + bundled OpenJDK).
