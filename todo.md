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

- [x] Prvni commit + push do `TheVoxRox/mail` — HOTOVO 2026-06-17.
- [x] Branch protection na `main` — HOTOVO 2026-06-17: ruleset (block force-push/deletion, require PR, admin bypass). POZOR: required status checks musi byt nazvy JOBU (`Backend quality`, `Tauri (Rust)`, `Lint`, `Svelte check`, `Frontend unit tests`, `Functional tests`, `Accessibility tests`, `Backend build`, `Frontend build`, `Analyze (java-kotlin)`, `Analyze (javascript-typescript)`), NE nazvy workflow (`ci`/`vuln-scan` = phantom checky, blokovaly vsechny PR).
- [x] GitHub secret scanning + Dependabot alerts — HOTOVO 2026-06-17: + push protection, private vulnerability reporting, dependabot security updates. CodeQL bezi.
- [x] Dependabot — HOTOVO 2026-06-18: 0 otevrenych PR, vsechny vyrizeny. Mergnuto vc. #2 (upload-artifact 4->7) a #3 (trivy 0.28->0.36); #13 (nullaway 0.13.7) vedome zavren (zustava 0.13.6 — 0.13.7 prisnejsi na JPA init, build-tooling-only bez runtime prinosu); #14/#19 self-closed jako already-up-to-date. Bot znovu navrhne pri pristi NullAway verzi. Config [.github/dependabot.yml](.github/dependabot.yml) pokryva npm/cargo/maven/github-actions (weekly).

---

## Microsoft OAuth — zbyva pro produkci

Rozhodnuto: OAuth-only (viz Rozhodnuti). PKCE explicitne zapnut v [SecurityConfig](backend/src/main/java/org/voxrox/mailbackend/core/config/SecurityConfig.java). Detail kontextu [[project_microsoft_oauth_azure]] + archive.

- [ ] **Verified publisher (Partner One ID, drive MPN) — ODLOZENO (rozhodnuto 2026-06-20: zatim bez pravnicke osoby).** NENI blocker prvniho release pro cilove publikum: osobni MS ucty (`@outlook`/`@hotmail`/`@live`) i vlastni `@voxrox.org` tenant dostanou consent i bez nej — jen uvidi "unverified" notici. Tvrdy blok (`AADSTS700016`) plati JEN pro cizi organizacni Entra tenanty (B2B), a to publikum je odlozeno, dokud nevznikne pravnicka osoba (MS business verification vyzaduje firmu overenou proti oficialnim rejstrikum — osobni identita / OSVC nejistá, nestaci osobni identita). Prereq "app pod Entra work/school uctem" uz splnen (info@voxrox.org). **Az/pokud vznikne entita**, postup (overeno 2026-06-20 z MS docs):
  1. Mit overeny **Microsoft AI Cloud Partner Program** ucet = **Partner Global Account** s dokoncenou business verifikaci (tax ID / D-U-N-S) → da **Partner One ID** (globalni, ne regionalni MPN). Registrace zdarma, vetting trva dny = ten pomaly krok.
  2. DNS-overit **`voxrox.org`** v tenantu Default Directory a nastavit jako publisher domain appky (musi sedet s domenou e-mailu pouziteho pri verifikaci Partner uctu).
  3. Role na `info@voxrox.org`: Entra `Application Administrator`/`Cloud Application Administrator` + Partner Center `CPP Partner Admin`/`Account Admin`; prihlasit pres MFA.
  4. portal.azure.com → Default Directory → App registration `VoxRox Mail` → Branding & properties → pole **Partner One ID** → **Verify and save** (~5 min, jakmile 1-3 sedi). Overit: consent obrazovka ukaze "Verified", AADSTS700016 zmizi.
  - **Pozor:** verified publisher NEobejde enterprise tenanty s vypnutym user-consent (napr. @tyflocentrum-ol.cz) — ty stejne vyzaduji RUCNI admin-consent jejich IT. MSA + vlastni @voxrox.org projdou i bez toho.
- [x] **Public client migrace (RFC 8252)** — HOTOVO 2026-06-10. Azure App registration `VoxRox Mail`: platforma Web → "Mobilni a klasicke aplikace", redirect URI `http://localhost/login/oauth2/code/microsoft`, "Povolit toky verejnych klientu" = Ano. Prehled ukazuje "0 web, 0 spa, 1 verejny klient". Kod uz odpovidal (`client-authentication-method=none` + PKCE), pipeline secret nepredava; `MICROSOFT_OAUTH_CLIENT_SECRET` odstranen z `.env.example` i lokalniho `backend/.env`. Zbyva jen: smoke login.
- [x] ~~Rotace client secret do 2028-04-15~~ — odpada, public client zadny secret nepouziva (viz vyse).
- [ ] Smoke: SMTP send pres OAuth + login `@outlook`/`@hotmail`/`@live` (jen MSA / vlastni tenant — enterprise az po verified publisher).

---

## Release & Update (Tauri updater)

- [x] Tauri Ed25519 signing key vygenerovan + ulozen do GitHub secrets — HOTOVO 2026-06-16: keypair `6334AE8C9D8495FE` (minisign), privatni klic `~/.tauri/mail.key` (heslem chraneny). Secrety `TAURI_SIGNING_PRIVATE_KEY` + `TAURI_SIGNING_PRIVATE_KEY_PASSWORD` + `TAURI_UPDATER_PUBKEY`, var `TAURI_UPDATER_ENDPOINTS`. Pubkey v [tauri.conf.json](frontend/src-tauri/tauri.conf.json) == lokalni `mail.key.pub`; release workflow [windows-signed-release.yml](.github/workflows/windows-signed-release.yml) klic spotrebuje a podpisy verifikuje.
- [x] **Offline zaloha signing key** — HOTOVO 2026-06-20: `~/.tauri/mail.key` + heslo ulozeny v offline uloziste mimo pracovni stroj. (Bylo to jedina extrahovatelna kopie — GitHub secret nelze precist zpet, takze pri ztrate disku by jinak slo o trvalou ztratu schopnosti podepisovat updaty.)
- [ ] Overit, ze GitHub release ma podepsany `voxrox-mail-<version>-x64-setup.exe` + `.sig` + `latest.json` na updater URL.
- [ ] Otestovat prvni update pres Tauri updater + smoke vN-1 → vN (bez ztraty dat, bez GUI regrese).

---

## Bezpecnost (release-ops)

- [ ] Rotovat Google OAuth credentials + vygenerovat novy `MAIL_CRYPTO_KEY` a `MAIL_CRYPTO_SALT`.
- [x] Git-history secret scan po prvnim pushi — HOTOVO 2026-06-18: gitleaks v8.30.1 na cele historii (90 commitu, ~4.12 MB) = 0 leaks; GitHub nativni secret scanning + push protection = 0 otevrenych alertu. Dva nezavisle skenery cisto, workspace scan byl cisty uz driv.
- [x] Vulnerability scan backend SBOM / Maven tree (CVE) + OWASP Dependency-Check gate — HOTOVO 2026-06-18: [vuln-scan.yml](.github/workflows/vuln-scan.yml) bezi scheduled (Po-Pa) + `workflow_dispatch`: CycloneDX SBOM (npm/Maven/Cargo) + OWASP Dependency-Check (`failBuildOnCVSS=8`) + Trivy SBOM scan + cargo audit + npm audit. Zhardenovano v PR #29 (NVD cache `nvd-v2`, retry + soft-fail na NVD vypadek, Trivy SARIF upload zahozen — viz [[reference_codeql_property_key_literals]] pro souvislost). Pozn.: scheduled report-only, ne PR-blokujici gate.
- [x] **NVD API key** pro [vuln-scan.yml](.github/workflows/vuln-scan.yml) — HOTOVO 2026-06-18: `NVD_API_KEY` v repo secrets (org VoxRox, info@voxrox.org). Workflow ho predava (`-DnvdApiKey`) a pri prechodnem NVD 503 soft-failne se zelena+warning (PR #29). Pozn.: NVD bulk API casto 503-uje; prvni uspesny full download naplni `nvd-v2` cache, pak uz jen inkrementalne.
- [x] **`cargo audit` na Rust/Tauri crate strome** — HOTOVO 2026-06-13: 0 vulnerabilities (597 deps); 20 warningu jen unmaintained/unsound (11 Linux-only GTK3 mimo Windows build, zbytek tranzitivni). Pridano do CI: blokujici gate v [ci.yml](.github/workflows/ci.yml) job `tauri` + scheduled report v [vuln-scan.yml](.github/workflows/vuln-scan.yml) job `cargo-audit-tauri` (cargo-audit 0.22.2, cachovany). Prijate warnings v [frontend/src-tauri/.cargo/audit.toml](frontend/src-tauri/.cargo/audit.toml). Pozn.: Trivy SBOM scan uz Rust castecne kryl (obecna DB, HIGH/CRITICAL, report-only) — cargo audit doplnuje RustSec DB. Detail v [backend/SECURITY_RELEASE_CHECK.md](backend/SECURITY_RELEASE_CHECK.md).
- [x] **Tauri capability / CSP / IPC audit** — HOTOVO 2026-06-13: posture potvrzena silna, findings narovnany do [SECURITY_THREAT_MODEL.md](SECURITY_THREAT_MODEL.md) v1.2 (§4 Boundary 4). Klicova zjisteni: zadny rucni `#[tauri::command]` ani `invoke_handler` v [lib.rs](frontend/src-tauri/src/lib.rs) (nulova vlastni IPC plocha), `shell:allow-spawn`/`allow-kill` scopovane na `binaries/mail` sidecar s `args:false`, http plugin jen loopback bez cookies, mail body v `sandbox=""` iframe s vlastnim `default-src 'none'` CSP. Opraveny 3 drifty v threat modelu (chybejici shell spawn/kill, neexistujici remote-image "UI toggle", stale `sanitizeMessageHtml()`).
- [ ] **Zostrena globalni CSP — zmena v kodu HOTOVA, zbyva jen vizualni smoke.** V [tauri.conf.json](frontend/src-tauri/tauri.conf.json) (oba bloky `csp` i `devCsp`, parita drzena `check:csp`): pridan explicitni `script-src 'self'` (skripty uz nepadaji na siroky `default-src`) a odstranen nepouzity `customprotocol:` placeholder (`default-src` ted `'self' asset:`). Bezpecne: inline SvelteKit bootstrap nema stabilni hash, ale Tauri ho pri buildu injektuje do skriptove smernice (stejny mechanismus, co default-src bez `'unsafe-inline'` uz dnes vyuziva) → s explicitnim `script-src` pujde hash do nej. **Smoke v `tauri:dev` I `tauri:build`:** appka se normalne nacte a v dev konzoli nejsou CSP violations (jinak revert / pripadne doladit per-mode). CSP zmenu nejde overit autonomne.
- [x] **Log hygiene audit** — HOTOVO 2026-06-13: cisty, detaily v [backend/SECURITY_RELEASE_CHECK.md](backend/SECURITY_RELEASE_CHECK.md). Zadne credentials/tokeny/tela zprav v lozich, vsech 7 email log-site pres `LogMasker`, zadny JavaMail debug, prod `INFO`, frontend bez console->soubor mustku a egress jen loopback. Opraven latentni leak: `AccountEntity`/`AccountCredentialEntity` `toString()` ted maskuji email/username pres `LogMasker` (driv nemaskovane; build overen `mvn spotless:apply compile`). Pozn.: backend sink `/internal/client-errors` zatim neexistuje (FE self-disable na 404) — az vznikne, logovat bounded bez PII.
- [x] **CodeQL log-injection (72 medium)** — HOTOVO 2026-06-18, merged v PR [#24](https://github.com/TheVoxRox/mail/pull/24); vsech 72 alertu dismissnuto (0 open). Reseni: globalni Logback guard pres custom converter [CrlfSafeMessageConverter](backend/src/main/java/org/voxrox/mailbackend/util/CrlfSafeMessageConverter.java), ktery prevazuje `%m`/`%msg`/`%message` v [logback-spring.xml](backend/src/main/resources/logback-spring.xml) a stripuje CR/LF na vystupni hranici (kryje vsech ~270 sinku + budouci, console+file+audit). Zvoleno misto `%replace` (Spring Boot 4 default pattern je komplexni — `%esb`/correlation/structured — hardcode by byl krehky) i misto rucniho `LogSanitizer` u 72 sinku. Overeno: 6 unit + 1 integracni test (rebind funguje na Logback 1.5.32), StartupSmokeTest cisto, spotless/NullAway/SpotBugs/ArchitectureTest zelene. Alerty dismissnuty "won't fix — mitigated globally"; CodeQL converter nemodeluje, takze BUDOUCI log-injection alerty se objevi znovu → dismissnout stejne (viz [[reference_github_dismiss_alerts]]).
- [x] **Mail TLS hardening (CodeQL `java/insecure-smtp-ssl`)** — HOTOVO 2026-06-18, merged v PR #25 + #26 (alert #13 → `fixed`, overeno proti post-merge scanu). SMTP [SmtpTransportFactory](backend/src/main/java/org/voxrox/mailbackend/feature/mail/service/SmtpTransportFactory.java) STARTTLS vetev nemela `starttls.required` (tichy plaintext downgrade u heslovych uctu) + `checkserveridentity` spolehalo na knihovni default. Fix: explicitni `ssl.checkserveridentity=true` + `starttls.required=true`, parita i pro IMAP [ImapConnectionManager](backend/src/main/java/org/voxrox/mailbackend/feature/mail/service/ImapConnectionManager.java) + [MailConnectionProbe](backend/src/main/java/org/voxrox/mailbackend/feature/mail/service/MailConnectionProbe.java). **Klicove:** CodeQL property-name queries chteji LITERAL klic (`"mail.smtp.ssl.checkserveridentity"`) — helper s konkatenaci (`"mail."+protocol+...`) je runtime-OK ale CodeQL ho nevidi (#25 helper nechal #13 open, #26 inline literal ho vyresil), viz [[reference_codeql_property_key_literals]]. Pozn.: plaintext IMAP (`useSsl=false`) zustava moznym dle designu. Zbyle low-prio CodeQL alerty (5,6,86,89,90,91) dismissnuty false-positive/won't-fix (po triáži 2026-06-18); `js/missing-origin-check` + `js/file-access-to-http` ×2 dismissnuty "used in tests". Jediny realny zbytek `java/unused-parameter` (`size` v MailSyncService) opraven v kodu.
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
- [ ] **Podpisy zprav (per ucet) — Faze 1 HOTOVA v kodu + zelena, zbyva manualni smoke v `tauri:dev`.** Implementovano: V2 migrace `signature TEXT` ([V2__add_account_signature.sql](backend/src/main/resources/db/migration/V2__add_account_signature.sql)), pole pres [AccountEntity](backend/src/main/java/org/voxrox/mailbackend/feature/account/entity/AccountEntity.java) → [AccountUpdateRequest](backend/src/main/java/org/voxrox/mailbackend/feature/account/dto/AccountUpdateRequest.java) (`@Size(max=10000)`, reuse `{validation.size.max}`) → [AccountResponse](backend/src/main/java/org/voxrox/mailbackend/feature/account/dto/AccountResponse.java) → [AccountMapper](backend/src/main/java/org/voxrox/mailbackend/feature/account/mapper/AccountMapper.java) → [AccountService.updateAccount](backend/src/main/java/org/voxrox/mailbackend/feature/account/service/AccountService.java); regen OpenAPI snapshot + `schema.d.ts` + rucni [types.ts](frontend/src/lib/types.ts); settings textarea "Podpis" (edit-only) v [AccountForm.svelte](frontend/src/lib/components/AccountForm.svelte) + i18n `accounts.form.signature*` (cs/en); cista [signature.ts](frontend/src/lib/compose/signature.ts) napojena v [ComposeForm.svelte](frontend/src/lib/components/ComposeForm.svelte) (append na nova/mailto, swap pri zmene From, rucni smazani se nevraci, draft/reply/forward netknute) + manualni toolbar tlacitko "Vlozit podpis" ([ComposeActionsBar.svelte](frontend/src/lib/components/compose/ComposeActionsBar.svelte), i18n `compose.insertSignature` cs/en) vlozi podpis na pozici kurzoru pro vsechny kindy a vrati focus do tela — zobrazene jen kdyz ma From ucet podpis. **Per-ucet prepinac auto-vkladani** (V3 migrace `signature_auto_insert BOOLEAN NOT NULL DEFAULT 1`, protazeno stejnym retezcem entity→DTO→mapper→service→OpenAPI→types; checkbox v AccountForm pod polem Podpis, i18n `accounts.form.signatureAutoInsert`): cisty helper `autoSignature(account)` v [signature.ts](frontend/src/lib/compose/signature.ts) ridi onMount append i swap pri zmene From — vypnuty ucet podpis automaticky nevlozi (a pri prepnuti na vypnuty ucet se predchozi blok odebere); manualni tlacitko funguje nezavisle na prepinaci. Testy zelene: AccountMapper (round-trip + default true) + AccountService (signature + flag persist) + FE [signature.test.ts](frontend/src/lib/compose/signature.test.ts) (22). **Smoke:** ucet s podpisem + auto-vkladani ZAP → nova zprava ho ma na konci, prepnuti From ho prohodi; auto-vkladani VYP → nova zprava ho NEMA, ale tlacitko "Vlozit podpis" ho stale vlozi; reply/forward podpis nikdy automaticky, jen tlacitkem na pozici kurzoru; a11y textarea + checkbox + tlacitko.
  - **Faze 2 (po release):** reply/forward placement vyreseno manualnim tlacitkem (viz vyse) — zbyva uz jen HTML-compose: az vznikne HTML editor, podpis sanitizovat na renderu (reuse [content-sanitizer](frontend/src/lib/mail/content-sanitizer.ts)). Vazba na [[project_desktop_app]] + HTML-compose polozku.
- [x] Tabulka kontaktu — sloupec "Aktualizovano" odebran z [ContactList.svelte](frontend/src/lib/components/ContactList.svelte), `min-w` snizen 58rem→50rem, sloupce prerozdeleny (6/6/6 buniek), sort `recent` v dropdownu ponechan funkcni. Smazan i nepouzity i18n klic `contacts.columnUpdated` (cs/en) + helper `updatedLabel`/importy. Overeno zelene: check/check:i18n/knip/eslint/prettier + contacts functional e2e (16) + a11y axe (53). Datum do detailu NEpresunuto (ContactForm je edit, ne read view) — vedome vynechano.
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
.\package-sidecar-dev-windows.ps1 -SkipTests
cd C:\dev\java\mail\frontend
npm run sidecar:sync:windows
npm run tauri:dev
```

`package-sidecar-dev-windows.ps1` je dev wrapper: nacte OAuth hodnoty z `backend/.env` a zavola `scripts/package-sidecar-windows.ps1`, takze lokalni build ma funkcni OAuth login. Cisty `scripts/package-sidecar-windows.ps1` cte jen prostredi (pro CI, kde secrets jdou z env) — bez `.env` zabali OAuth placeholdery.

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
