# TODO - VoxRox Mail

Aktualni pracovni seznam pro monorepo `backend/` + `frontend/`.

**Pravidlo:** jen ZIVE ukoly (`[ ]`) a rozhodnuti. Zadne implementacni deniky, audit reporty ani "HOTOVO" prozy — ty patri do commitu / `CHANGELOG.md` / docs. Tvrzeni o kodu vzdy s `file:line`, at jdou overit.

Hotove reporty do 2026-06-07 jsou zamrazene v [todo-archive.md](todo-archive.md) (puvodni 748-radkovy todo, ponechan jako historie — neudrzuje se).

---

## Rozhodnuti

- Frontend a backend jsou samostatne artefakty s vlastni verzi.
- Frontend verzi drzi `frontend/package.json`, `frontend/src-tauri/tauri.conf.json` a `frontend/src/lib/version.ts`; kontroluje je `npm run check:versions`.
- Backend verzi drzi `backend/pom.xml`; runtime ji cte pres `ApplicationVersion` z Maven `build-info.properties`.
- Kompatibilitu FE/BE neresi rovnost release verzi, ale handshake pres `apiVersion` a `minClientVersion`.
- Tauri desktop bundle nese frontend i backend sidecar; po zmene backendu znovu zabalit sidecar a zkopirovat do frontendu.
- Commit messages: Conventional Commits v anglictine, scopes podle casti monorepa: `backend`, `frontend`, `tauri`, `ci`, `docs`, `repo`, `release`.
- Canonicky changelog je root `CHANGELOG.md`; modulove changelogy jen pro technicke/migracni detaily.
- **Microsoft/Outlook jede VYHRADNE pres OAuth.** IMAP+heslo neni alternativa — Microsoft vypnul basic auth pro osobni ucty (outlook/hotmail/live/msn) 16.9.2024. Kod uz odpovida (preset `flow: 'oauth'`, seed `supports_oauth2=1`). Detail viz [[project_microsoft_oauth_azure]] + archive.
- GitHub publikace je pozastavena, dokud nebude projekt kompletne lokalne odladeny.
- **Sifrovani DB at-rest:** pro V0.1.0 vedome NEsifrujeme `mail.db`. Sloupcove sifrovani koliduje s FTS5 + ORDER BY (subject/sender/received_at), full-DB SQLCipher je odlozeno, at zustane auditovane SQLite jadro stabilni pred prvnim releasem. Reziduum = offline pristup k vypnutemu disku (ztracene/ukradene zarizeni, zkopirovana data slozka); credentials zustavaji zapecetene pres DPAPI. Mitigace = BitLocker + ACL profilu, zdokumentovano v [PRIVACY.md](PRIVACY.md), [frontend/END_USER_README.md](frontend/END_USER_README.md) a threat modelu jako AR-1 ([SECURITY_THREAT_MODEL.md](SECURITY_THREAT_MODEL.md)). Upgrade path az se zmeni apetit: SQLCipher pres drop-in `Willena/sqlite-jdbc-crypt` (Apache 2.0), DB klic zapeceteny stavajicim `SecretStore`/DPAPI — povysi tela zprav na stejnou at-rest uroven, jakou uz maji credentials, beze zmeny schematu/FTS5/sync.

---

## Pred prvnim commitem — manualni smoke (vyzaduje uzivatele)

Kod je hotovy + zeleny; tohle nejde autonomne, zbyva rucni overeni v `tauri:dev`:

- [ ] **DPAPI `crypto.bin` — zbyva jen cast s uctem:** pridat ucet → restart → sync/login funguje (credentials se desifruji). Format i migrace overeny 2026-06-12 v tauri:dev: cisty profil generuje `VOXSEC1` + DPAPI blob (provider GUID sedi, zadny plaintext), podvrzeny legacy plaintext se in-place migruje a klic prezije (fingerprint match + `mail.log` "[SECURITY] Migrated").
- [ ] **Smoke `tauri:dev` pred commitem:** (1) IMAP ma realne timeouty (drive infinite), (2) OAuth refresh Google i Microsoft, (3) bootstrap prazdne DB na V1 schema, (4) SMTP send timeout, (5) IMAP fetch robustness — Seznam INBOX vraci vsechny zpravy i s malformed BODYSTRUCTURE jako envelope-only stub + `contentError`, (6) edit uctu pres PUT (PATCH/accounts odstranen), (7) Threading Phase 1 backfill v `mail.log` bez chyb.
- [ ] **Merge kontaktu** — 2+ kontakty → dialog → overit SR ohlaseni preview + warning pri >10 emailech.
- [ ] **About dialog** — NOTICE.txt se nacte pres `resolveResource` do read-only textarea (fs scope `$RESOURCE/NOTICE.txt`), graceful fallback mimo Tauri.
- [ ] **BootErrorView Retry** — po selhani backendu Retry/Restart nevyrobi druhy backend (exit 78 "already running" + osirely proces); generacni fix v [sidecar.ts](frontend/src/lib/backend/sidecar.ts) kryje unit test, rucne overit v `tauri:dev`.

Pozn.: dialog "mail.exe — Failed to launch JVM" (jpackage launcher, pred startem Javy) — pravdepodobna pricina nalezena 2026-06-12: jpackage launcher hleda `app\<basename-exe>.cfg`, takze primé spusteni triple-named exe (nebo castecne synchronizovany layout) selze a visi na neviditelnem error dialogu. `sync-backend-sidecar-windows.mjs` nove drzi OBA nazvy cfg vedle sebe (mail.cfg pro dev/release launcher, triple pro prime spusteni); overeno end-to-end v tauri:dev.

---

## First Release Gate

- [ ] Projit cely `backend/RELEASE_CHECKLIST.md` pro konkretni kandidat (fresh install, account flows, mail workflows, sidecar lifecycle, diagnostics, 24h long run).
- [ ] End-to-end release dry run z cisteho checkoutu: tag / draft release, Windows signed workflow, upload artefaktu, overeni instalatoru, `latest.json`, podpisu, rucni instalace na cistem profilu.
- [ ] Fresh install / reinstall pres stejnou verzi / uninstall + chovani dat v `%LOCALAPPDATA%\VoxRox\Mail`.
- [ ] Recovery scenare: sidecar kill, orphan proces, restart pocitace behem syncu, poskozena DB, obnova ze zalohy, disk full.
- [ ] Produkcni OAuth readiness: consent screen, scopes, loopback redirect URI s nahodnym portem, support/privacy URL, realny login, revoke, `requires_reauth`, re-login.
- [ ] Release proces: verze, tag, changelog, known issues, hashe artefaktu, draft vs public, approval.
- [ ] Privacy/legal balicek — CZ+EN draft hotov ([PRIVACY.md](PRIVACY.md) / [PRIVACY.en.md](PRIVACY.en.md)); zbyva support email, security disclosure kontakt, Tauri updater URL, pravni review.
- [ ] Third-party license audit — inventare + bundled NOTICE.txt + SBOM hotove; pred kazdym release regenerovat (`npm run regen:licenses:all`).

---

## Publikace (po lokalnim odladeni)

- [ ] Prvni commit + push do `TheVoxRox/mail`.
- [ ] Branch protection na `main`.
- [ ] Po publikaci: rucne zapnout GitHub secret scanning + Dependabot alerts (CodeQL workflow [.github/workflows/codeql.yml](.github/workflows/codeql.yml) uz commitnut, spusti se sam).

---

## Microsoft OAuth — zbyva pro produkci

Rozhodnuto: OAuth-only (viz Rozhodnuti). PKCE explicitne zapnut v [SecurityConfig](backend/src/main/java/org/voxrox/mailbackend/core/config/SecurityConfig.java). Detail kontextu [[project_microsoft_oauth_azure]] + archive.

- [ ] **Verified publisher (MPN / Partner Center)** — JEDINY produkcni blocker. Bez nej cizi/enterprise ucty: "publisher not verified" / AADSTS700016. Entra-account prereq splnen (info@voxrox.org); zbyva business verification + `voxrox.org` jako DNS-overena publisher domain. Pozn.: enterprise tenanty (napr. @tyflocentrum-ol.cz) stejne vyzaduji rucni admin-consent jejich IT.
- [x] **Public client migrace (RFC 8252)** — HOTOVO 2026-06-10. Azure App registration `VoxRox Mail`: platforma Web → "Mobilni a klasicke aplikace", redirect URI `http://localhost/login/oauth2/code/microsoft`, "Povolit toky verejnych klientu" = Ano. Prehled ukazuje "0 web, 0 spa, 1 verejny klient". Kod uz odpovidal (`client-authentication-method=none` + PKCE), pipeline secret nepredava; `MICROSOFT_OAUTH_CLIENT_SECRET` odstranen z `.env.example` i lokalniho `backend/.env`. Zbyva jen: smoke login.
- [x] ~~Rotace client secret do 2028-04-15~~ — odpada, public client zadny secret nepouziva (viz vyse).
- [ ] Smoke: SMTP send pres OAuth + login `@outlook`/`@hotmail`/`@live` (jen MSA / vlastni tenant — enterprise az po verified publisher).

---

## Release & Update (Tauri updater)

- [ ] Vygenerovat Tauri Ed25519 signing key + ulozit private key offline + do GitHub secrets.
- [ ] Overit, ze GitHub release ma podepsany `voxrox-mail-<version>-x64-setup.exe` + `.sig` + `latest.json` na updater URL.
- [ ] Otestovat prvni update pres Tauri updater + smoke vN-1 → vN (bez ztraty dat, bez GUI regrese).

---

## Bezpecnost (release-ops)

- [ ] Rotovat Google OAuth credentials + vygenerovat novy `MAIL_CRYPTO_KEY` a `MAIL_CRYPTO_SALT`.
- [ ] Git-history secret scan po prvnim pushi (gitleaks/trufflehog; workspace scan uz cisty).
- [ ] Vulnerability scan backend SBOM / Maven tree (CVE) + nastavit OWASP Dependency-Check gate v CI.
- [ ] **NVD API key** pro [vuln-scan.yml](.github/workflows/vuln-scan.yml) — jinak Dependency-Check narazi na rate limit (429). Z [nvd.nist.gov](https://nvd.nist.gov/developers/request-an-api-key) → `NVD_API_KEY` v repo secrets.
- [x] **`cargo audit` na Rust/Tauri crate strome** — HOTOVO 2026-06-13: 0 vulnerabilities (597 deps); 20 warningu jen unmaintained/unsound (11 Linux-only GTK3 mimo Windows build, zbytek tranzitivni). Pridano do CI: blokujici gate v [ci.yml](.github/workflows/ci.yml) job `tauri` + scheduled report v [vuln-scan.yml](.github/workflows/vuln-scan.yml) job `cargo-audit-tauri` (cargo-audit 0.22.2, cachovany). Prijate warnings v [frontend/src-tauri/.cargo/audit.toml](frontend/src-tauri/.cargo/audit.toml). Pozn.: Trivy SBOM scan uz Rust castecne kryl (obecna DB, HIGH/CRITICAL, report-only) — cargo audit doplnuje RustSec DB. Detail v [backend/SECURITY_RELEASE_CHECK.md](backend/SECURITY_RELEASE_CHECK.md).
- [x] **Tauri capability / CSP / IPC audit** — HOTOVO 2026-06-13: posture potvrzena silna, findings narovnany do [SECURITY_THREAT_MODEL.md](SECURITY_THREAT_MODEL.md) v1.2 (§4 Boundary 4). Klicova zjisteni: zadny rucni `#[tauri::command]` ani `invoke_handler` v [lib.rs](frontend/src-tauri/src/lib.rs) (nulova vlastni IPC plocha), `shell:allow-spawn`/`allow-kill` scopovane na `binaries/mail` sidecar s `args:false`, http plugin jen loopback bez cookies, mail body v `sandbox=""` iframe s vlastnim `default-src 'none'` CSP. Opraveny 3 drifty v threat modelu (chybejici shell spawn/kill, neexistujici remote-image "UI toggle", stale `sanitizeMessageHtml()`).
- [ ] **(volitelne) Zostrit globalni CSP** v [tauri.conf.json](frontend/src-tauri/tauri.conf.json) — pridat explicitni `script-src 'self'` (ted pada na `default-src`) a odstranit nepouzity `customprotocol:` placeholder z `default-src`/`devCsp`; overit pri smoke v `tauri:dev`/`tauri:build`, ze se SvelteKit skripty/styly stale nacitaji (CSP zmenu nejde overit autonomne, nutny vizualni smoke).
- [x] **Log hygiene audit** — HOTOVO 2026-06-13: cisty, detaily v [backend/SECURITY_RELEASE_CHECK.md](backend/SECURITY_RELEASE_CHECK.md). Zadne credentials/tokeny/tela zprav v lozich, vsech 7 email log-site pres `LogMasker`, zadny JavaMail debug, prod `INFO`, frontend bez console->soubor mustku a egress jen loopback. Opraven latentni leak: `AccountEntity`/`AccountCredentialEntity` `toString()` ted maskuji email/username pres `LogMasker` (driv nemaskovane; build overen `mvn spotless:apply compile`). Pozn.: backend sink `/internal/client-errors` zatim neexistuje (FE self-disable na 404) — az vznikne, logovat bounded bez PII.
- [ ] Pred releasem zkontrolovat podpisy, updater manifest a recovery postup v `OPERATIONS.md`.

---

## Startup follow-up

Backend (headless) cast zmerena a uzavrena — sekce "Startup audit — mereni 2026-06-11" v [backend/PERFORMANCE_BASELINE.md](backend/PERFORMANCE_BASELINE.md) (AppCDS/AOT decision gate rozhodnut uz 2026-06-03). Zbyva jen GUI:

- [ ] Manualni startup smoke checklist v `tauri:dev` (rychly) a `tauri:build` (uplny) — viz archive pro detailni kroky.
- [ ] Tauri release smoke s AOT cache — `npm run tauri:build:with-sidecar` s `$EnableAotCache=$true`, zmerit desktop `appReady` cold start.

---

## Produktove funkce (backlog)

- [ ] iCloud OAuth.
- [ ] Threading Phase 2 (V0.2) — UI grouping toggle, thread row aria-tree, bulk akce, a11y pass. + References-only orphan reconciliation (dite linkuje parenta jen pres `References`) — vyzaduje normalizovanou junction tabulku (token match ve free-text je neindexovatelny). Detail [backend/docs/THREADING_DESIGN.md](backend/docs/THREADING_DESIGN.md).
- [ ] Filtrovani / pravidla pro automaticke trideni zprav.
- [ ] Full QRESYNC SELECT s VANISHED — vetsi refactor `ImapFolderExecutor`; po release, pokud bude cleanup latency bottleneck.
- [ ] Self-update standalone backendu (jen pokud vznikne deployment mimo Tauri bundle).
- [ ] Dlouhodobe sledovat startup performance a velikost bundle.

---

## Dev spousteni

```powershell
cd C:\dev\java\mail\frontend
npm run tauri:dev
```

Po zmene backendu (prebalit + zkopirovat sidecar):

```powershell
cd C:\dev\java\mail\backend
.\scripts\package-sidecar-windows.ps1 -SkipTests
cd C:\dev\java\mail\frontend
npm run sidecar:sync:windows
npm run tauri:dev
```

---

## Pre-Push Quality Gate

Pred kazdym pushem zelene:

- Backend: `mvn verify`, `mvn spotless:check`, `mvn spotbugs:check`.
- Frontend: `npm run check`, `npm run check:i18n`, `npm run check:i18n:backend`, `npm run check:translations:strict`, `npm run knip`, `npm audit --audit-level=high`, `npm run test:unit`, `npm run test:functional:stable`, `npm run test:a11y:stable`.
- Po `git add .` rucne projit staged soubory.
- Git hooky: `git config core.hooksPath .githooks` (per clone).

---

## Definition of Done

- Polozka se odskrtava az po realnem overeni.
- Nove ukoly maji byt akcni, s jasnym vysledkem.
- Hotove implementacni deniky se do tohoto souboru nepridavaji — patri do commitu, changelogu nebo dokumentace.
- U zmen backendu pro Tauri nezapomenout prebalit a zkopirovat sidecar.
