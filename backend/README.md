# VoxRox Mail — Backend (sidecar)

Spring Boot 4 mail backend running on Java 25. Runs as a Tauri sidecar on
127.0.0.1 with a random port; the frontend reads the port and an in-memory
API key from `${app.data-dir}/session.json` after the `.ready` sentinel
appears.

Repo-wide overview and the full doc map live in the monorepo root
[`../README.md`](../README.md). This file is backend-specific.

## Stack

- Java 25
- Spring Boot 4 (Web, Data JPA, Security/OAuth2 client, Actuator)
- SQLite (via `org.xerial:sqlite-jdbc`) + Flyway migrations
- JavaMail (`org.eclipse.angus:angus-mail`) for IMAP / SMTP
- Spring AOT (opt-in via Maven `aot` profile) + optional JEP 483 AOT class cache

## Quick start (dev)

```bash
cd backend
cp .env.example .env   # fill in OAuth values; leave MAIL_CRYPTO_* empty
```

Then `npm run sidecar:rebuild:windows` and `npm run tauri:dev` from `frontend/`.
The first packages the sidecar image with the OAuth client ids from `.env` and
puts it where Tauri looks for it; Tauri then starts the sidecar itself and hands
it the values from `.env`.

The backend listens on a random port and writes `session.json` + `.ready` to
the data directory Tauri passes in `APP_DATA_DIR` — `%LOCALAPPDATA%\VoxRox\Mail`,
`Mail.dev` for dev runs. See [`OPERATIONS.md`](OPERATIONS.md) for the full layout.

A bare `mvn spring-boot:run` still starts a standalone backend, but it reads no
`.env`: OAuth falls back to the non-working `mail-local-*` placeholder client
ids, and the data directory to `${user.home}/.voxrox/mail/` unless
`--app.data-dir=<path>` or `APP_DATA_DIR` says otherwise.

## Test, package, sidecar

```bash
mvn clean verify                              # unit + integration tests + SpotBugs
mvn -Paot package                             # fat jar with Spring AOT
./package-sidecar-dev-windows.ps1             # jpackage app-image for Tauri (bakes OAuth from .env)
```

`clean` is not cosmetic: SpotBugs runs in `verify` and will analyse the
`__BeanDefinitions` classes a previous `-Paot package` left in `target/`.

> The bare `scripts/package-sidecar-windows.ps1` requires real OAuth client ids in
> the environment (CI secrets) and fails otherwise. `package-sidecar-dev-windows.ps1`
> sources them from `.env`; pass `-AllowPlaceholderOAuth` to package a local build
> without a working OAuth login.

Packaging also needs `node` on the PATH: before the build it records the digest
of `src/main` and `pom.xml` inside the image, which `npm run tauri:dev` compares
with the checked-out backend. It runs `mvn -Paot clean package` itself, so a
file deleted from the sources does not ship from an earlier build. Only
packaging rebuilds the image, and `npm run sidecar:rebuild:windows` runs it
together with the copy into the Tauri bundle input; `mvn -Paot package` stops at
the jar.

## Documentation

- [`OPERATIONS.md`](OPERATIONS.md) — runbook (data dir, logs, health, recovery, JVM tuning, AOT cache)
- [`RELEASE_CHECKLIST.md`](RELEASE_CHECKLIST.md) — release-time smoke checklist
- [`SECURITY_RELEASE_CHECK.md`](SECURITY_RELEASE_CHECK.md) — pre-release security review (secrets, SBOM, lock hardening)
- [`PERFORMANCE_BASELINE.md`](PERFORMANCE_BASELINE.md) — cold-start and runtime baselines
- [`CHANGELOG.md`](CHANGELOG.md) — backend technical changes and migration notes
- [`THIRD_PARTY_LICENSES.md`](THIRD_PARTY_LICENSES.md) — Maven dependency license inventory
- [`docs/translation-whitelist.txt`](docs/translation-whitelist.txt) — files exempt from the Czech-text lint

## License

MIT — see [`../LICENSE`](../LICENSE) at the monorepo root.
