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

- [x] Spustit vulnerability scan nad `target/bom.json` nebo přímo Maven dependency tree v nástroji s aktuální CVE databází.
  - 2026-05-08: OWASP Dependency-Check lokálně nedoběhl kvůli NVD API 429 bez API key; SBOM `target/bom.json` byl znovu vygenerován.
  - Vyřešeno scheduled workflow `vuln-scan.yml` (OWASP Dependency-Check s `NVD_API_KEY` + retry na flaky NVD, Trivy SBOM gate, cargo/npm audit); box odškrtnut při doc-truing pass 2026-07-09.
- [x] Rozhodnout, jestli se OWASP Dependency-Check zapojí do CI buildu. Doporučení: zapojit spíš v CI/release pipeline s NVD API key, ne jako povinný lokální build krok.
  - Rozhodnuto a zapojeno přesně dle doporučení: scheduled `vuln-scan.yml` (po-pá 04:00 UTC) s NVD API key, ne povinný lokální krok; box odškrtnut při doc-truing pass 2026-07-09.
- [x] Po aktuálním lokálním installer/sidecar balení zopakovat secret scan nad distribuovaným bundlem, nejen nad backend JARem a aktuálním `src-tauri/binaries/app`.
- [x] Před prvním GitHub pushem rotovat lokální Google OAuth secret nalezený v ignorovaném `backend/.env`, protože už byl použit v lokálním vývoji.
  - Rotace proběhla — potvrzeno vlastníkem 2026-07-09 při doc-truing pass. Gitleaks scan git historie hlásil 0 úniků, takže šlo o defense-in-depth opatření.

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

## API surface audit — Boundary 3 (2026-07-09)

Per-subsystem audit sidecar REST API (loopback + `X-API-KEY`), plný zápis v
[docs/API_SURFACE_AUDIT.md](../docs/API_SURFACE_AUDIT.md). Verdikt **PASS**.

- [x] Autentizace: `ApiKeyFilter` = constant-time compare (SHA-256 + `MessageDigest.isEqual`), fail-fast 401 + `AuditLog.failure`; klíč per-JVM, in-memory, jen v `session.json`.
- [x] Autorizace: `anyRequest().authenticated()`; `PUBLIC_ENDPOINTS` minimální (OAuth + static auth pages + springdoc vypnutý v prod); `/api/internal/**` (dump, threading, client-boot, actuator health) za klíčem. IDOR vědomě mimo model (single-user, jeden klíč).
- [x] Input validation: 10 z 15 controllerů `@Validated` — všechny, které deklarují constrainované parametry (zbylých 5 nemá bean-validaci co vynucovat: 4 bezparametrické endpointy + client-boot sanitizuje DTO ve service); DTO `@Valid` s per-field constraints; pagination/search/bulk capy.
- [x] Error hygiene: catch-all vrací fixní lokalizovanou hlášku, `include-message/stacktrace=never`; žádný leak zprávy/stacku/SQL.
- [x] Attachment download: `partPath` = MIME index (`Integer.parseInt` per segment), ne FS cesta → žádný path traversal; temp soubor až po DB lookupu, unlink na close; `Content-Disposition` filename sanitizován.
- [x] Diagnostic dump: bez credentials/tokenů/těl/předmětů; email maskovaný, `lastError` jen boolean; `ClientBootDiagnosticsService` allow-list klíčů + cap 512 znaků.
- [x] **Opraveno (A1, Low, defense-in-depth):** JSON `send`/`draft` endpointy neměly cap na délku `body` ani počet příloh (multipart limit se na JSON `@RequestBody` nevztahuje); doplněn `@Size` `body` ≤ 10 MiB + ≤ 50 příloh na `MailRequest`/`DraftRequest`, regresní testy `MailWriteControllerTest.sendBodyTooLong` / `sendTooManyAttachments`. Pre-deserializační agregátní bound = přijaté reziduum (loopback + auth + klient 25 MB).

## Auto-updater audit — Boundary 6 (2026-07-09)

Per-subsystem audit Tauri auto-updateru (GitHub release → signature-verified
install), plný zápis v [docs/UPDATER_AUDIT.md](../docs/UPDATER_AUDIT.md).
Verdikt **PASS**, bez zásahu do kódu.

- [x] Ed25519 verifikace je nativní v Rust pluginu — frontend ([updates.ts](../frontend/src/lib/updates.ts)) s podpisem nikdy nepracuje; grantnuté jen `updater:allow-check` + `updater:allow-download-and-install`.
- [x] Build fail-closed: `buildUpdaterPlugin()` v [lib/tauri-config.mjs](../frontend/scripts/lib/tauri-config.mjs) hodí chybu na prázdném `TAURI_UPDATER_PUBKEY` (žádný placeholder key ≠ analogie OAuth `invalid_client`) a bez ≥1 endpointu; workflow padá bez `TAURI_SIGNING_PRIVATE_KEY`; manifest generator bere jen podepsaný artefakt a padá na prázdném `.sig`.
- [x] Transport HTTPS-only (`dangerousInsecureTransportProtocol` opt-in, nezapnutý), `allowDowngrades:false`; updater fetch je nativní, takže nerozšiřuje WebView `connect-src` (zůstává loopback + `ipc:`).
- [x] Hijacknutý `latest.json` nespustí kód (signature mismatch → install abort); pole `notes`/`version` se nerenderují jako markup ([UpdatePromptDialog.svelte](../frontend/src/lib/components/UpdatePromptDialog.svelte) ukazuje jen i18n-escaped `version`, `notes` vůbec).
- [x] Fail handling: startup check ([bootstrap.ts](../frontend/src/lib/bootstrap.ts)) tiše (console.warn), manual check ([AboutSettings.svelte](../frontend/src/lib/components/settings/AboutSettings.svelte)) s failure UI + fallback na releases; pokryto `updates.test.ts`.
- [x] Defense-in-depth: Sigstore build-provenance attestation + SHA-256 checksum u každého instalátoru.
- [ ] Poznámka (procedurální, ne kód): base `tauri.conf.json` updater blok je dev reference; bare `npm run tauri:build` nevyrobí `.sig`, takže není validní release — guard je jen v RELEASE_CHECKLIST. Volitelné budoucí zpřísnění: CI lint že `dangerousInsecureTransportProtocol` není nikdy `true`.

## OAuth handshake audit — Boundary 2 (2026-07-09)

Focused audit (ověření STRIDE mitigací + data-flow refresh tokenu), plný zápis
v [docs/OAUTH_AUDIT.md](../docs/OAUTH_AUDIT.md). Verdikt **PASS**, bez zásahu
do kódu. Ověřeno proti `d55b753`.

- [x] PKCE (S256) vynuceno pro oba providery přes custom resolver (Spring default kryje jen public klienty; Google je confidential). Microsoft = public client bez secretu (`client-authentication-method=none`).
- [x] State nonce + úzká brána pro benigní duplicate callback (`OAuth2CompletedStateTracker` — jen přesný error code A prokazatelně dokončený state).
- [x] Failure handler: WARN log server-side, redirect jen s URL-encoded error kódem — žádný reflected provider text.
- [x] Scopes hardcoded per provider, redirect URI loopback; refresh token at rest AES/GCM přes `CryptoService` (per-account klíč + AAD), access tokeny jen v bounded in-memory `TokenCache`.
- [x] `invalid_grant` → `requires_reauth` (scheduler stop, wizard); duplicate callback flag nečistí.

## Crypto & storage audit — Boundary 5 (2026-07-09)

Focused audit (ověření STRIDE mitigací + data-flow klíčového materiálu), plný
zápis v [docs/CRYPTO_STORAGE_AUDIT.md](../docs/CRYPTO_STORAGE_AUDIT.md).
Verdikt **PASS**, bez zásahu do kódu. Ověřeno proti `d55b753`.

- [x] `crypto.bin`: DPAPI USER scope + app entropy, `UI_FORBIDDEN`; cizí user/machine → fail-stop s recovery hláškou; FFM implementace zeruje nativní buffery na obou cestách; atomic write + private permissions; in-place migrace legacy plaintext formátu.
- [x] Fingerprint gate: constant-time compare, mismatch → hard stop; env-override pár se ověřuje proti existujícímu `crypto.bin` (žádný tichý re-key), poloviční konfigurace (key bez salt) odmítne boot.
- [x] Šifrování credentials: AES/GCM-256, random 12B IV per operaci, PBKDF2-HmacSHA256 600k iterací, per-account salt, `accountId` jako AAD (ciphertext nelze přehodit mezi účty); AEAD tag mismatch → CRITICAL audit event.
- [x] Self-test fail-stop před prvním použitím; main secret zerován při shutdownu.
- [x] AR-1 (plaintext `mail.db`) potvrzeno jako vědomé reziduum s BitLocker mitigací a SQLCipher upgrade path.

## IMAP/SMTP protocol audit — Boundary 1 (2026-07-09)

Full audit protokolové vrstvy (transport, fetch→parse→persist, hlavičky,
attachment download, SMTP send), plný zápis v
[docs/IMAP_SMTP_AUDIT.md](../docs/IMAP_SMTP_AUDIT.md). Verdikt **PASS** — jeden
Medium DoS nález (B1-1) jako přijaté reziduum AR-3. Ověřeno proti `35a06f3`.

- [x] Hostname verification `ssl.checkserveridentity=true` explicitně na IMAP store, SMTP transportu i credential probe.
- [x] OAuth2 token nikdy v cleartextu — fail-closed na OBOU protokolech: SMTP `requireSslForOAuth2`, IMAP CRITICAL `imap_oauth2_plaintext_blocked`; STARTTLS `required=true`.
- [x] Timeouty reálné (IMAP 30s/60s, SMTP connect 30s), předané jako millis stringy; retry jen na transientní chyby, auth failure short-circuit na refresh.
- [x] Strukturální DoS bounded: MIME depth 20, References walk 50, inline images 2 MiB/image + 8 MiB/zpráva (`readBounded`); malformed BODYSTRUCTURE → envelope-only stub (fail-soft).
- [x] `From` je display-only data (RFC 2047 decode), do DB/FTS jako data, nikdy do body iframe; jediná security-load cesta (remote-image allowlist) je keyed na spoofovatelný From, ovlivní jen načtení obrázků (parametrizované dotazy). UIDVALIDITY cross-check resetuje sync state.
- [x] Attachment download streamovaný na disk (`Files.copy`, konstantní heap) + empty-download integrity check + stale-temp sweep.
- [ ] **B1-1 (Medium, AR-3):** tělo zprávy se načítá `getContent().toString()` bez size capu → nepřátelský server může velkým tělem vyčerpat heap při otevření zprávy. Přijaté reziduum V0.1.0 (self-inflicted local DoS, recoverable, vyžaduje user interakci, žádná perzistence). Doporučený fix: bounded body read + "message too large — download original" placeholder.

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
