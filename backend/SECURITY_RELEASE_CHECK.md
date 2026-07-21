# Security release check

| | |
|---|---|
| **Version** | 2.0 |
| **Date** | 2026-07-20 |
| **Applies to** | VoxRox Mail V0.1.0 |
| **Role** | Průřezový pre-release security gate: index verdiktů per-subsystem auditů + primární záznamy průřezových kontrol (secret/artefakt scan, log hygiene, dependency audity) |

Detailní tvrzení jednotlivých auditů žijí **výhradně** v auditních dokumentech
(`docs/*_AUDIT.md`, proces v [docs/AUDIT_GUIDE.md](../docs/AUDIT_GUIDE.md));
tenhle soubor je neduplikuje. Průřezové kontroly níže (secret scan, log
hygiene, cargo audit) tady naopak mají svůj primární záznam — každá sekce je
datovaný snapshot konkrétního běhu.

## Index verdiktů per-subsystem auditů

| Boundary | Audit | Tier | Verze | Datum | Audited commit | Verdikt |
|---|---|---|---|---|---|---|
| B1 external mail server | [IMAP_SMTP_AUDIT.md](../docs/IMAP_SMTP_AUDIT.md) | full | 1.2 | 2026-07-10 | `35a06f3` | **PASS** — B1-1 (Medium DoS) opraven v kódu 2026-07-10 |
| B2 OAuth handshake | [OAUTH_AUDIT.md](../docs/OAUTH_AUDIT.md) | focused | 1.0 | 2026-07-09 | `d55b753` | **PASS** — bez zásahu do kódu |
| B3 sidecar HTTP API | [API_SURFACE_AUDIT.md](../docs/API_SURFACE_AUDIT.md) | full | 1.3 | 2026-07-09 | `d55b753` | **PASS** — A1 (Low, defense-in-depth) opraven |
| B4 WebView ↔ SPA | [CONTENT_RENDERING_AUDIT.md](../docs/CONTENT_RENDERING_AUDIT.md) | full | 1.3 | 2026-07-10 | `d55b753` (re-verifikováno proti `fc71cb4`) | **PASS** — F1/F2/F3 opraveny |
| B5 crypto + filesystem | [CRYPTO_STORAGE_AUDIT.md](../docs/CRYPTO_STORAGE_AUDIT.md) | focused | 1.0 | 2026-07-09 | `d55b753` | **PASS** — bez zásahu do kódu |
| B6 Tauri updater | [UPDATER_AUDIT.md](../docs/UPDATER_AUDIT.md) | full | 1.2 | 2026-07-11 | `e2b8d8d` | **PASS** — re-verifikováno pro release channels |

Verdikt + odkaz drží také change log
[SECURITY_THREAT_MODEL.md](../SECURITY_THREAT_MODEL.md) §7; při aktualizaci
auditu se mění (a) audit sám, (b) řádek tady, (c) jednořádkový záznam v change
logu threat modelu.

## Secret & artefakt scan — snapshot 2026-04-30 (aktualizace 2026-05-08)

Datovaný záznam pre-release secret/artefakt auditu; konkrétní čísla (počty
testů, komponent SBOM) platí k datu běhu. Zopakovaný lokální secret audit po
Tauri release buildu proběhl 2026-05-08.

### Ověřeno

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

### Původně otevřené položky (všechny uzavřeny)

- [x] Spustit vulnerability scan nad `target/bom.json` nebo přímo Maven dependency tree v nástroji s aktuální CVE databází.
  - 2026-05-08: OWASP Dependency-Check lokálně nedoběhl kvůli NVD API 429 bez API key; SBOM `target/bom.json` byl znovu vygenerován.
  - Vyřešeno scheduled workflow `vuln-scan.yml` (OWASP Dependency-Check s `NVD_API_KEY` + retry na flaky NVD, Trivy SBOM gate, cargo/npm audit); box odškrtnut při doc-truing pass 2026-07-09.
- [x] Rozhodnout, jestli se OWASP Dependency-Check zapojí do CI buildu. Doporučení: zapojit spíš v CI/release pipeline s NVD API key, ne jako povinný lokální build krok.
  - Rozhodnuto a zapojeno přesně dle doporučení: scheduled `vuln-scan.yml` (po-pá 04:00 UTC) s NVD API key, ne povinný lokální krok; box odškrtnut při doc-truing pass 2026-07-09.
- [x] Po aktuálním lokálním installer/sidecar balení zopakovat secret scan nad distribuovaným bundlem, nejen nad backend JARem a aktuálním `src-tauri/binaries/app`.
- [x] Před prvním GitHub pushem rotovat lokální Google OAuth secret nalezený v ignorovaném `backend/.env`, protože už byl použit v lokálním vývoji.
  - Rotace proběhla — potvrzeno vlastníkem 2026-07-09 při doc-truing pass. Gitleaks scan git historie hlásil 0 úniků, takže šlo o defense-in-depth opatření.

### Použité příkazy

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

## Log hygiene re-audit (2026-07-10)

Re-audit obou log streamů proti `2a1c865` (main po B1-1 fixu #139 a
hostile-content harnessu #141). Enumerace (reprodukovatelné):
287 log míst v 62 souborech (`rg -c "log\.(trace|debug|info|warn|error)\("
backend/src/main/java`), 68 AuditLog volání ve 21 souborech
(`rg "AuditLog\.(success|failure|critical)\("`), 68 callerů
`LogMasker.maskEmail/lazyEmail` ve 24 souborech.

- [x] **CWE-117 řešeno strukturálně:** `logback-spring.xml` přepíná `%m`/`%msg`/`%message` na `CrlfSafeMessageConverter` (CR/LF strip) — platí pro všechny appendery včetně `audit.log`, takže attacker-influenced hodnoty (IMAP folder names, URI, exception texty) nemohou forgovat log řádky. Chokepoint, ne per-místo kázeň.
- [x] Prod level INFO (`org.hibernate.SQL=ERROR`), dev DEBUG jen pod dev profilem. Rotace: `mail.log` 10 MB/7 dní/100 MB cap; `audit.log` 10 MB/365 dní/500 MB cap, `additivity=false`. Oba pod `${app.data-dir}/logs` (privátní ACL data adresáře — viz B5 audit).
- [x] Žádný `session.setDebug`/`mail.debug` (0 výskytů) — IMAP/SMTP protokol včetně AUTH a těl se nedumpuje.
- [x] Obsah zpráv se nedostává do logu: search loguje jen **počet znaků** dotazu (`MailReadController`), remote-image allowlist záměrně neloguje sender („allowed for a sender on account {}"), B1-1 oversize cesta loguje jen uid + limit, `MimePartExtractor` loguje jen cid/limity. Entity toString v log statementech: 0 výskytů (multiline grep).
- [x] Audit stream (68 eventů) drží pravidla `AuditLog`: actor = `account=<id>` / `system` / maskovaný email; detail = event kódy, ID a `getClass().getSimpleName()` — žádné tokeny, žádné raw emaily, žádná těla.
- [x] Frontend: `tauri-plugin-log` v prod píše jen do `logs/mail-frontend*` a Rust strana loguje 4 statické cesty (start, data root, log dir, WebView2 dir); **žádný `attachConsole`** → JS konzole se nikam nepersistuje. `clientErrors.ts`: truncace 4000, jen loopback s `X-API-KEY`, self-disable na 404/501; sink `/internal/client-errors` stále neexistuje (reziduum z 2026-06-13 trvá).
- [x] **Opraveno (L1, Low): raw e-mail v interních textech výjimek.** `GlobalExceptionHandler` loguje `ex.getMessage()` na WARN a `ContactBulkService` vrací/loguje per-item `e.getMessage()` — čtyři interní texty nesly nemaskovaný e-mail a obcházely tak masker: `AccountAlreadyExistsException`, `DuplicateContactException`, `ContactService.addEmail` (adresa už na kontaktu) a `ContactService.checkNoDuplicatesWithinAccount` (duplicita v requestu). Interní texty nově maskují přes `LogMasker.maskEmail`; lokalizovaná odpověď klientovi (messageKey + raw argument) beze změny. Vedlejší efekt: bulk-create per-item message nese maskovaný e-mail — položku dál jednoznačně určuje `index` + `errorCode`.

Reziduum / poznámky:

- `db_backup_failed` je jediný audit event s raw `e.getMessage()` (SQLite) — lokální původ (vlastní DB, ne attacker-controlled), CRLF-safe, ponecháno kvůli diagnostice obnovy.
- Jackson „unreadable JSON" neechuje payload do logu — source-in-location je v Jackson 3 default vypnuté; spoléháme na default (bez explicitního testu).
- Upgrade path: maskování e-mailových vzorů přímo v `CrlfSafeMessageConverter` by pravidlo „žádný raw e-mail v logu" vynutilo chokepointem i pro budoucí log místa; dnes je pravidlo drženo per-místo kázní + tímto re-auditem.

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

## Change log

| Version | Date | Summary |
|---|---|---|
| 2.0 | 2026-07-20 | Restrukturalizace (konsolidace dokumentace): pět per-boundary souhrnných sekcí nahrazeno indexem verdiktů — detailní tvrzení žijí jen v `docs/*_AUDIT.md`, čímž se propagace auditního tvrzení zkracuje na 2 místa (audit + threat model). Doplněna verzní hlavička; datované průřezové sekce označeny jako snapshoty konkrétních běhů. Obsahově se žádné tvrzení nemění. |
| — | 2026-04-30 až 2026-07-12 | Append-only éra bez verzní hlavičky (hlavička tvrdila „stav z 2026-04-30", obsah rostl do 2026-07-11) — plná historie v gitu. |
