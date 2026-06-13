# Security release check

Stav z 2026-04-30 pro aktuální backend artefakt a frontend dependency strom.

Aktualizace 2026-05-08: zopakovaný lokální pre-release secret audit po Tauri
release buildu.

## Ověřeno

- [x] Backend build prošel: `mvn package`
- [x] Testy prošly: `Tests run: 568, Failures: 0, Errors: 0, Skipped: 0`
- [x] SBOM vygenerován přes CycloneDX Maven plugin:
  - `target/bom.xml`
  - `target/bom.json`
- [x] SBOM obsahuje 155 komponent.
- [x] Maven dependency tree se sestaví bez konfliktů: `mvn dependency:tree`.
- [x] Výsledný JAR neobsahuje `application-it.properties`.
- [x] Výsledný JAR neobsahuje `.env`.
- [x] Výsledný JAR neobsahuje `session.json`.
- [x] Výsledný JAR neobsahuje `crypto.bin`.
- [x] Rychlý binary scan výsledného JARu nenašel:
  - `MAIL_CRYPTO_KEY=`
  - `MAIL_CRYPTO_SALT=`
  - `test-api-key`
  - `dev-api-key`
  - `dummy-secret`
  - `google-client-secret`
  - `internal_api_key`
- [x] `.env.example` nechává `MAIL_CRYPTO_KEY` a `MAIL_CRYPTO_SALT` prázdné.
- [x] Produkční `application.properties` odkazuje na env proměnné, neobsahuje konkrétní OAuth secret.
- [x] Aktuální Tauri sidecar app JAR ani launcher config neobsahují rychlé secret patterny ani `session.json` / `crypto.bin`.
- [x] Aktuální lokální `backend/.env` je ignorovaný gitem a obsahuje vyplněné jen lokální proměnné; hodnoty se nevypisují do logu auditu.
- [x] Root `.gitignore` doplněn o globální pojistky pro `.env`, lokální logy/DB a signing key/cert soubory (`*.key`, `*.pem`, `*.p12`, `*.pfx`), s explicitní výjimkou pro šablony/test env.
- [x] Tauri desktop režim defaultně nepředává `MAIL_CRYPTO_KEY`/`MAIL_CRYPTO_SALT` z `backend/.env`; release/dev sidecar používá lokální `crypto.bin` bootstrap, explicitní env crypto je opt-in.
- [x] Desktop bootstrap režim opraví stale `crypto.fingerprint` podle `crypto.bin`. Interní handshake API klíč je in-memory only (generovaný při každém startu sidecaru, zapsaný do `session.json`, nikdy persistovaný v DB), takže ho rotace crypto materiálu netýká. Nečitelná uživatelská encrypted credentials se nepřepisují, účet se označí k novému přihlášení.
- [x] Přímý scan aktuálních release artefaktů proti vyplněným hodnotám z `backend/.env` nenašel žádnou shodu:
  - `backend/target/mail-backend-0.1.0.jar`
  - `frontend/src-tauri/target/release/app.exe`
  - `frontend/src-tauri/target/release/mail.exe`
  - `frontend/src-tauri/target/release/bundle/nsis/Mail_0.1.0_x64-setup.exe`
  - `frontend/src-tauri/target/release/bundle/msi/Mail_0.1.0_x64_en-US.msi`
- [x] Přímý scan textových souborů, které by `git ls-files --others --exclude-standard` dovolil přidat, nenašel žádnou vyplněnou hodnotu z `backend/.env`.
- [x] Frontend `npm audit --json` po lockfile hardeningu hlásí 0 zranitelností.
- [x] Frontend dependency hardening:
  - `@sveltejs/kit -> cookie` override na `0.7.2` kvůli GHSA-pxg6-pf52-xh8x.
  - `postcss` override na `8.5.12` kvůli GHSA-qx2v-qp2m-jg93.
  - `svelte-i18n -> esbuild` override na `0.28.0` kvůli GHSA-67mh-4wv8-2f99.
- [x] Frontend ověření po hardeningu: `npm run check`, `npm run build`, `npm ls svelte-i18n esbuild postcss cookie`.

## Otevřené před releasem

- [ ] Spustit vulnerability scan nad `target/bom.json` nebo přímo Maven dependency tree v nástroji s aktuální CVE databází.
  - 2026-05-08: OWASP Dependency-Check lokálně nedoběhl kvůli NVD API 429 bez API key; SBOM `target/bom.json` byl znovu vygenerován.
- [ ] Rozhodnout, jestli se OWASP Dependency-Check zapojí do CI buildu. Doporučení: zapojit spíš v CI/release pipeline s NVD API key, ne jako povinný lokální build krok.
- [x] Po aktuálním lokálním installer/sidecar balení zopakovat secret scan nad distribuovaným bundlem, nejen nad backend JARem a aktuálním `src-tauri/binaries/app`.
- [ ] Před prvním GitHub pushem rotovat lokální Google OAuth secret nalezený v ignorovaném `backend/.env`, protože už byl použit v lokálním vývoji.

## Log hygiene audit (2026-06-13)

Statická kontrola obou log streamů (`mail.log` + `audit.log` backendu,
frontend `tauri-plugin-log`) na únik credentials, OAuth tokenů, těl/předmětů
zpráv a nemaskovaného PII.

- [x] Produkční log level `INFO` (`src/main/resources/application.properties`); dev profil s `DEBUG`/`show-sql` se dle hlavičky `application-dev.properties` do release JARu nedostává.
- [x] Žádný JavaMail debug flag (`session.setDebug` / `mail.debug`) — IMAP/SMTP protokol včetně AUTH řádků a těl se nedumpuje.
- [x] Hesla, OAuth access/refresh tokeny ani interní API klíč se nelogují; token-refresh (`OAuth2TokenService`) loguje jen maskovaný email, expiraci a scope.
- [x] Těla ani předměty zpráv se nelogují — jediný výskyt slova „Body" (`MailContentService` ~ř. 56) loguje `uid`+`folder`, ne obsah.
- [x] Všech 7 míst logujících email jde přes `LogMasker.maskEmail()` / `lazyEmail()`; `AuditLog` má explicitní pravidla (žádné tokeny, emaily jen maskované) a píše do odděleného `audit.log`.
- [x] Žádný Lombok `@Data` / `@ToString` v codebase (0 výskytů) → žádný auto-toString únik přes entity.
- [x] Diagnostika neobsahuje obsah ani nemaskované PII: `ClientBootDiagnosticsRequest` = jen timingy, `DiagnosticDumpService` = maskovaný email + počty zpráv.
- [x] Frontend nemá console→soubor můstek (žádný `attachConsole`); `errorReporting` / `clientErrors.ts` posílá jen na loopback s `X-API-KEY`, payload truncovaný na 4000 znaků, žádný externí egress.
- [x] **Opraveno:** `AccountEntity.toString()` a `AccountCredentialEntity.toString()` nově maskují email/username přes `LogMasker` (dřív nemaskované — latentní únik, kdyby entita vyletěla v Hibernate výjimce či budoucím log statementu; žádný aktuální caller nenalezen). Ověřeno `mvn spotless:apply compile`.

Reziduum / poznámky:

- Backendový sink `/internal/client-errors` zatím **neexistuje** (frontend se self-disabluje na HTTP 404); až vznikne, musí logovat bounded a bez echo PII/stacku do `audit.log`.
- `LogMasker.maskEmail` zachovává plnou doménu (`j***k@seznam.cz`) — vědomý kompromis pro debug. `AccountEntity.toString` ponechává `displayName`/`accountName` (uživatelské labely, ne unikátní identifikátor).

## cargo audit — Rust/Tauri závislosti (2026-06-13)

`cargo audit` (RustSec advisory DB) nad `frontend/src-tauri/Cargo.lock`
(597 crate dependencies).

- [x] **0 vulnerabilities** (exit 0). 20 nálezů jsou jen `warning` typu `unmaintained` (17) a `unsound` (3), žádný typu `vulnerability`.
- [x] 11 z 20 je Linux-only GTK3/glib stack (`atk*`, `gdk*`, `gtk*`, `glib`) — tranzitivní deps webkit2gtk přes Tauri. V0.1.0 je Windows-only, tyto crate se do release binárky nekompilují.
- [x] Zbytek je tranzitivní: `proc-macro-error` (build-time proc-macro, ne v runtime binárce), `fxhash`, `unic-*` (Unicode), `rand` 0.7.3/0.8.5 (unsound jen při custom global loggeru volajícím `rand::rng()` během init — pattern, který appka nepoužívá).
- [x] Přijaté warnings zaznamenány v `frontend/src-tauri/.cargo/audit.toml` (ignore per advisory-ID s odůvodněním) → scheduled report pak ukáže jen NOVÉ advisories. Ignore per-ID nezakryje budoucí `vulnerability` ve stejné crate (má jiné ID).
- [x] **Zapojeno do CI** (doplňuje stávající Trivy SBOM scan, který Rust kryl jen obecnou DB, HIGH/CRITICAL a report-only):
  - `ci.yml` job `tauri` — blokující gate per-PR (padá na `vulnerability`).
  - `vuln-scan.yml` job `cargo-audit-tauri` — scheduled JSON report (artifact) + gate.
  - cargo-audit pinnut na `0.22.2` a cachovaný (vzor jako `cargo-cyclonedx`).

## Použité příkazy

```powershell
mvn -Dmaven.repo.local=C:\dev\java\mail-backend\.m2repo -Dapp.data-dir=C:\dev\java\mail-backend\target\test-data package
mvn -Dmaven.repo.local=C:\dev\java\mail-backend\.m2repo -Dapp.data-dir=C:\dev\java\mail-backend\target\test-data -DskipTests cyclonedx:makeAggregateBom
mvn -Dmaven.repo.local=.m2repo -Dapp.data-dir=target/test-data -DskipTests org.owasp:dependency-check-maven:check
mvn -Dmaven.repo.local=C:\dev\java\mail-backend\.m2repo dependency:tree
jar tf target\mail-backend-0.1.0.jar
npm audit --json
npm run check
npm run build
npm ls svelte-i18n esbuild postcss cookie
```
