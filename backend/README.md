# VoxRox Mail — Backend (sidecar)

Spring Boot 3 mail backend running on Java 25. Runs as a Tauri sidecar on
127.0.0.1 with a random port; the frontend reads the port and an in-memory
API key from `${app.data-dir}/session.json` after the `.ready` sentinel
appears.

Repo-wide overview and the full doc map live in the monorepo root
[`../README.md`](../README.md). This file is backend-specific.

## Stack

- Java 25
- Spring Boot 3 (Web, Data JPA, Security/OAuth2 client, Actuator)
- SQLite (via `org.xerial:sqlite-jdbc`) + Flyway migrations
- JavaMail (`org.eclipse.angus:angus-mail`) for IMAP / SMTP
- Spring AOT (opt-in via Maven `aot` profile) + optional JEP 483 AOT class cache

## Quick start (dev)

```bash
cd backend
cp .env.example .env          # fill in OAuth values; leave MAIL_CRYPTO_* empty
./mvnw -Dmaven.repo.local=.m2repo spring-boot:run
```

The backend listens on a random port and writes `session.json` + `.ready` to
`${user.home}/.voxrox/mail/` by default. Override the data directory with
`--app.data-dir=<path>`. See [`OPERATIONS.md`](OPERATIONS.md) for the full layout.

## Test, package, sidecar

```bash
./mvnw verify                                 # unit + integration tests
./mvnw -Paot package                          # fat jar with Spring AOT
./scripts/package-sidecar-windows.ps1         # jpackage app-image for Tauri
```

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
