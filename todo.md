# TODO - VoxRox Mail

Aktualni pracovni seznam pro monorepo `backend/` + `frontend/`.

**Pravidlo:** jen ZIVE ukoly (`[ ]`) a rozhodnuti. Zadne implementacni deniky, audit reporty ani "HOTOVO" prozy — ty patri do commitu / `CHANGELOG.md` / docs. Tvrzeni o kodu vzdy s `file:line`, at jdou overit. **Hotovou polozku po zavreni zkrat na jeden radek** (nazev + datum + PR); plny detail patri do commitu nebo do [todo-archive.md](todo-archive.md).

Hotove reporty do 2026-06-07 jsou zamrazene v [todo-archive.md](todo-archive.md) (puvodni 748-radkovy todo). Pozdejsi hotove polozky tamtez v sekci "Presunuto z todo.md 2026-07-07".

**Po releasu:** tenhle soubor zrusit — ale nejdriv rozstehovat, ne smazat. Trvala rozhodnuti (`## Rozhodnuti`) → `docs/DECISIONS.md`; post-release backlog + rozepsane design plany (Interactive IMAP lane, Threading P2) → GitHub Issues (repo uz bude verejne); release-gate mechanika → archiv; `Dev spousteni`/`Pre-Push` → `CONTRIBUTING.md`. Az bude soubor prazdny, teprve pak ho smazat.

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
- **v0.1.0 = uzavrena beta:** Google zustava Testing (≤100 test-useru), bez CASA. Rozhodnuto, viz [[project_v0.1.0_closed_beta_smoke]].
- **Windows installer bez Authenticode podpisu.** Rozhodnuto 2026-07-18 (uzavira #170 nalez 5): placeny code-signing certifikat se pro opensource projekt neporizuje; secret `WINDOWS_CERTIFICATE_BASE64` v repu neexistuje (overeno `gh secret list`), `windows-signed-release.yml` bali installer nepodepsany by design. Updater je kryty nezavislym Ed25519 `.sig`; prvni instalace = SmartScreen "Neznamy vydavatel" + manualni overeni pres Sigstore attestation a `.sha256` (postup v [frontend/END_USER_README.md](frontend/END_USER_README.md)). Zaznamenano jako AR-4 v [SECURITY_THREAT_MODEL.md](SECURITY_THREAT_MODEL.md) v2.4. Upgrade path, kdyby se apetit zmenil: SignPath.io OSS / Azure Trusted Signing / OV cert pri vzniku entity — workflow se podpisu chopi automaticky, jakmile secrets existuji.
- **Sifrovani DB at-rest:** pro V0.1.0 vedome NEsifrujeme `mail.db`. Sloupcove sifrovani koliduje s FTS5 + ORDER BY (subject/sender/received_at), full-DB SQLCipher je odlozeno, at zustane auditovane SQLite jadro stabilni pred prvnim releasem. Reziduum = offline pristup k vypnutemu disku (ztracene/ukradene zarizeni, zkopirovana data slozka); credentials zustavaji zapecetene pres DPAPI. Mitigace = BitLocker + ACL profilu, zdokumentovano v [PRIVACY.md](PRIVACY.md), [frontend/END_USER_README.md](frontend/END_USER_README.md) a threat modelu jako AR-1 ([SECURITY_THREAT_MODEL.md](SECURITY_THREAT_MODEL.md)). Upgrade path az se zmeni apetit: SQLCipher pres drop-in `Willena/sqlite-jdbc-crypt` (Apache 2.0), DB klic zapeceteny stavajicim `SecretStore`/DPAPI.

---

## Pred prvnim commitem — manualni smoke

**Vse hotovo** (overeno 2026-06-23/24/30 v tauri:dev): DPAPI `crypto.bin`, smoke sync/OAuth/SMTP/PUT/threading, IMAP fetch robustness (Seznam, deterministicky IT), merge kontaktu, About dialog, BootErrorView Retry. Detail v [todo-archive.md](todo-archive.md). Znama pricina "Failed to launch JVM" dialogu (cfg basename) viz [[reference_sidecar_startup_debugging]].

---

## SR doposlech — audit Posty (vyzaduje uzivatele)

Deterministicke e2e kryti existuje (live-region asserty v [list-navigation.functional.e2e.ts](frontend/src/routes/mail/list-navigation.functional.e2e.ts) a spol.) — tohle je realny NVDA doposlech, ze ohlaseni skutecne zni. Opravy z SR auditu Posty (PR #118, merged 2026-07-06).

- [ ] **NVDA doposlech v `tauri:dev`** (rezim bez podokna cteni i vychozi split):
  1. Sipky v seznamu zprav jen ctou radky, zpravu otevre az Enter ([MessageList.svelte:167](frontend/src/lib/components/MessageList.svelte)).
  2. Delete ohlasi smazani a fokus pokracuje na sousednim radku (v off i split rezimu, i pres radkove menu).
  3. Prepnuti slozky ohlasi "Strana X z Y, N zprav".
  4. Tlacitko Synchronizovat ohlasi "Synchronizace zahajena.".
  5. Hvezdicka / precteno-neprecteno ohlasi vysledek (Ctrl+Q/U i radkove menu).
  6. Stazeni prilohy ohlasi toast "Priloha ... stazena.".
  7. Hledani ohlasi "Nalezeno N zprav" po prichodu vysledku a "Strana X z Y" pri strankovani.
  8. Titulek okna otevrene zpravy cte predmet ("Posta – <predmet>").

---

## v0.1.0 smoke — bugy Faze B

**Vse uzavreno** (2026-06-25/30). 8 bugu opraveno: FE update-dialog (#65), OAuth poll reconcile (#66), "duch" zpravy 404 A+B, SSE 30min ERROR (C), soubezny sync UNIQUE (F), HHH90003004 paginace (#70), duplicitni OAuth callback (#71/E), transient sync retry (#78/D), §6 sidecar zombie watchdog (#89/#90). Detail v [todo-archive.md](todo-archive.md); reziduum = pasivni log-watch v [backend/RELEASE_CHECKLIST.md](backend/RELEASE_CHECKLIST.md) §8.

---

## First Release Gate

Kanonicky seznam *kroku* je [backend/RELEASE_CHECKLIST.md](backend/RELEASE_CHECKLIST.md): fresh install (§3/§3a), account + OAuth flows (§4), mail workflows (§5), sidecar lifecycle + recovery (§6), diagnostics (§7), long run (§8). Tady drzime jen release-mechaniku, ktera v checklistu NENI, a rozhodovaci body.

- [ ] Projit cely `backend/RELEASE_CHECKLIST.md` pro konkretni kandidat (vc. §6 recovery a §8 long run).
- [ ] End-to-end release dry run z cisteho checkoutu: tag / draft release, Windows signed workflow, upload artefaktu, overeni instalatoru, `latest.json`, podpisu, rucni instalace na cistem profilu.
- [ ] Release proces: verze, tag, changelog, known issues, hashe artefaktu, draft vs public, approval.
- [ ] **Google OAuth — Publish + CASA pro verejnost.** Projekt `VoxRox` je zatim **Testing** (External). Restricted scope `https://mail.google.com/` ([application.properties:90](backend/src/main/resources/application.properties)) vyzaduje pro public **Publish + CASA verifikaci** (Tier 2, per-projekt, placene/rocni). Pro v0.1.0 se NEresi — closed beta zustava Testing bez CASA (viz Rozhodnuti + [[project_v0.1.0_closed_beta_smoke]]). Produkcni creds + consent screen uz hotove (archive). Vetsi blok nez creds → soucast produkcni OAuth readiness.
- [ ] **Rozhodnout deadline/trigger pro start CASA procesu (#170 nalez 2).** Testing rezim ma dva limity, na ktere beta muze narazit driv, nez by CASA stihla dobehnout: refresh tokeny expiruji po 7 dnech (znane, akceptovane) a cap 100 test useru — po prekroceni prihlaseni noveho testera skonci `access_denied`. CASA trva tydny a neco stoji; start az pri narazu na limit = vypadek bez rychleho reseni. Akce: stanovit pocet beta testeru (treba 50), pri kterem se CASA spusti, a prubezne sledovat obsazenost test-user seznamu v Google Cloud Console.
- [ ] Privacy/legal balicek — CZ+EN draft hotov ([PRIVACY.md](PRIVACY.md) / [PRIVACY.en.md](PRIVACY.en.md)); support email, security disclosure kontakt ([SECURITY.md](SECURITY.md)) i Tauri updater URL doplneny. Zbyva uz jen **pravni review** (GDPR "spravce dat" pro org. nasazeni).
- [ ] Third-party license audit — inventare + bundled NOTICE.txt + SBOM hotove; pred kazdym release regenerovat (`npm run regen:licenses:all`). Overeno 2026-06-30: regen = 0 license drift (631 komponent). Dalsi beh az pri samotnem release (tam se NOTICE.txt commituje) — do te doby NEopakovat.

---

## Microsoft OAuth — zbyva pro produkci

Rozhodnuto: OAuth-only (viz Rozhodnuti). PKCE explicitne zapnut v [SecurityConfig](backend/src/main/java/org/voxrox/mailbackend/core/config/SecurityConfig.java). Detail kontextu [[project_microsoft_oauth_azure]] + archive. Public client migrace (RFC 8252), smoke `@outlook` login + SMTP a MS 465→587/STARTTLS fix (PR #58) jsou **hotove** — detail v [todo-archive.md](todo-archive.md).

- [ ] **Verified publisher (Partner One ID, drive MPN) — ODLOZENO (rozhodnuto 2026-06-20: zatim bez pravnicke osoby).** NENI blocker prvniho release pro cilove publikum: osobni MS ucty (`@outlook`/`@hotmail`/`@live`) i vlastni `@voxrox.org` tenant dostanou consent i bez nej — jen uvidi "unverified" notici. Tvrdy blok (`AADSTS700016`) plati JEN pro cizi organizacni Entra tenanty (B2B), a to publikum je odlozeno, dokud nevznikne pravnicka osoba. Prereq "app pod Entra work/school uctem" uz splnen (info@voxrox.org). **Az/pokud vznikne entita**, postup (overeno 2026-06-20 z MS docs):
  1. Mit overeny **Microsoft AI Cloud Partner Program** ucet = **Partner Global Account** s dokoncenou business verifikaci (tax ID / D-U-N-S) → da **Partner One ID** (globalni). Registrace zdarma, vetting trva dny = ten pomaly krok.
  2. DNS-overit **`voxrox.org`** v tenantu Default Directory a nastavit jako publisher domain appky (musi sedet s domenou e-mailu pouziteho pri verifikaci Partner uctu).
  3. Role na `info@voxrox.org`: Entra `Application Administrator`/`Cloud Application Administrator` + Partner Center `CPP Partner Admin`/`Account Admin`; prihlasit pres MFA.
  4. portal.azure.com → Default Directory → App registration `VoxRox Mail` → Branding & properties → pole **Partner One ID** → **Verify and save** (~5 min, jakmile 1-3 sedi). Overit: consent obrazovka ukaze "Verified", AADSTS700016 zmizi.
  - **Pozor:** verified publisher NEobejde enterprise tenanty s vypnutym user-consent — ty stejne vyzaduji RUCNI admin-consent jejich IT. MSA + vlastni @voxrox.org projdou i bez toho.

---

## Release & Update (Tauri updater)

Ed25519 signing key + offline zaloha klice — **hotove** (2026-06-16/20), detail v [todo-archive.md](todo-archive.md).

- [ ] Overit podepsane release artefakty na updater URL: `voxrox-mail-<version>-windows-x64-setup.exe` + `.sig` + `latest.json` (= RELEASE_CHECKLIST §3a, tady jako updater-specificky gate).
- [ ] Otestovat prvni update pres Tauri updater + smoke vN-1 → vN (bez ztraty dat, bez GUI regrese). **Nejdulezitejsi bod release gate — updater se neda opravit pres updater, takze rozbity updater ve verzi, kterou uz lidi maji, je neopravitelny.** **Rozhodnuto 2026-06-30:** smoke se NEdela predem — repo `TheVoxRox/mail` je public a publikace je pozastavena, takze prod-endpoint test by vyzadoval verejne publikovany 0.1.1. Provede se jako posledni krok tesne pred prvnim verejnym shipem 0.1.0. Fresh install (§3/§3a), §4 Gmail OAuth + restart token refresh (DPAPI), §5 mail workflows i §6 watchdog (#90) uz overeny na signed buildu 0.1.0 (drzeny jako draft `v0.1.0` s cerstvym buildem z main vc. #94). **Tenhle smoke je zaroven jediny realny dukaz shody podpisoveho paru:** kdyz embedded `pubkey` (base `tauri.conf.json` / CI `TAURI_UPDATER_PUBKEY`) neodpovida `TAURI_SIGNING_PRIVATE_KEY`, updater odmitne `.sig` se signature error a instalace neprobehne — neopravitelne po shipnuti, proto ho nejde preskocit.

---

## Bezpecnost (release-ops)

**Hotovo** (detail v [todo-archive.md](todo-archive.md)): produkcni Google OAuth creds + consent screen (2026-06-23), git-history secret scan (gitleaks, 0 leaks), vuln-scan.yml (SBOM + OWASP DC + Trivy + cargo/npm audit) + NVD API key, cargo audit gate, Tauri capability/CSP/IPC audit, zostrena globalni CSP, log hygiene audit, CodeQL log-injection (72 alertu, PR #24), Mail TLS hardening (PR #25/#26), recovery runbook OPERATIONS.md.

- [ ] **jackson-databind 2.x — CVE-2026-54515: FIX APLIKOVAN 2026-07-10, zbyva dohled na OSV sync.** Upstream fix vysel den po prehodnoceni z 2026-07-07: databind #5962 opraven v **2.21.5 (6.7.)** a 2.22.1 (7.7.), GitHub advisory uz ma ohranicene rozsahy (`>=2.19.0,<2.21.5`; `>=2.22.0,<2.22.1`). Aplikovan override `<jackson-2-bom.version>2.21.5</jackson-2-bom.version>` v [backend/pom.xml](backend/pom.xml) (stejna minor jako managed 2.21.4, cisty patch) + `mvn clean verify`. **Pozor: osv.dev mirror je pozadu** (open-ended rozsahy, flaguje i opravene verze) — lokalni OSV scan bude nalez ukazovat, dokud se mirror nedotahne na GHSA; Dependabot/GHSA-based nastroje jsou ciste. Zbyva: (a) po case overit, ze OSV scan zmlkl; (b) override odstranit, az Spring Boot managed `jackson-2-bom.version` >= 2.21.5.

---

## Koncept s rozepsanou adresou nejde ulozit (nalez z ziveho testu 2026-07-14)

**Hotovo 2026-07-16** — `MimeMessageBuilder.AddressPolicy` STRICT (send, beze zmeny) / DRAFT (draft-save vynecha nedokonceny token z hlavicky pres `parseHeader` + per-token `validate()`; raw text zustava v lokalnim radku). Scope-blocker overen: cteci cesta je fail-soft (`MessageFetcher` zpravu s necitelnou ENVELOPE preskoci) — proto se token vynechava a nezapisuje raw. Testy: `MimeMessageBuilderTest` (Address policy), `DraftLifecycleGreenMailIT` faze 4 (round-trip pres livy IMAP). Detail v changelogu + commitu.

---

## Startup follow-up

Backend (headless) cast zmerena a uzavrena — sekce "Startup audit — mereni 2026-06-11" v [backend/PERFORMANCE_BASELINE.md](backend/PERFORMANCE_BASELINE.md). Zbyva jen GUI:

- [ ] **Manualni startup SR sign-off na packaged buildu (`tauri:build`)** — overit, ze pri objeveni okna cteka cte "načítání". Defenzivni fixy uz hotove (2026-06-30, detail v archive): explicitni polite announce na startu bootu ([bootstrap.ts](frontend/src/lib/bootstrap.ts)) + loading-aware nativni titulek okna (`VoxRox Mail – načítání…` v [lib.rs](frontend/src-tauri/src/lib.rs), prepnut na app name pri boot `ready`/`failed` pres [windowTitle.ts](frontend/src/lib/windowTitle.ts)). Dev SR smoke ukazal, ze spolehlivy kanal pri objeveni okna je titulek, ne prvni aria-live. Gate-relevantni je shell-first "blank screen do 500 ms"; boot timings jsou nice-to-have. Pokud SR smoke ukaze tiche pre-hydratacni okno, doplnit staticky loading text do [app.html](frontend/src/app.html).

---

## Produktove funkce (backlog)

Hotove: **Podpisy zprav — Faze 1** (auto-insert + From-swap + manualni tlacitko + per-ucet prepinac, smoke 2026-06-23) a **Tabulka kontaktu** (sloupec Aktualizovano odebran) — detail v [todo-archive.md](todo-archive.md).

- [ ] iCloud OAuth.
- [ ] Threading Phase 2 (V0.2) — UI grouping toggle, thread row aria-tree, bulk akce, a11y pass. + References-only orphan reconciliation (dite linkuje parenta jen pres `References`) — vyzaduje normalizovanou junction tabulku (token match ve free-text je neindexovatelny). Detail [backend/docs/THREADING_DESIGN.md](backend/docs/THREADING_DESIGN.md).
- [ ] **Podpisy zprav — Faze 2 (po release):** reply/forward placement vyreseno manualnim tlacitkem (Faze 1) — zbyva uz jen HTML-compose: az vznikne HTML editor, podpis sanitizovat na renderu (reuse [content-sanitizer](frontend/src/lib/mail/content-sanitizer.ts)). Vazba na [[project_desktop_app]] + HTML-compose polozku.
- [ ] **Interactive IMAP lane — druhe spojeni na ucet pro uzivatelske cteni.** Rozhodovaci gate: implementovat az kdyz beta feedback ukaze "otevreni zpravy/prilohy obcas visi behem syncu" — po fixech z 2026-07-03 (folder-lock dedup, neblokujici dispatch, FolderListCache) je to posledni zdroj cekani na cteci ceste. **Problem:** jeden pooled `Store` na ucet + fair lock ([ImapConnectionManager.java:105](backend/src/main/java/org/voxrox/mailbackend/feature/mail/service/ImapConnectionManager.java)); sync drzi zamek po celou folder cyklu ([MailSyncService.java:170](backend/src/main/java/org/voxrox/mailbackend/feature/mail/service/MailSyncService.java) — cely `executeInFolder` = download + flag sweep + cleanup, i desitky sekund), takze prvni fetch tela (`MailContentService.getOrFetchMessageContent`) a stazeni prilohy ([AttachmentService.java:78](backend/src/main/java/org/voxrox/mailbackend/feature/mail/service/AttachmentService.java) — cely transfer pod zamkem) cekaji ve FIFO fronte za nim. **Plan:**
  1. `ImapConnectionManager`: enum `Lane {INTERACTIVE, BACKGROUND}`, pool + lock mapy klicovane `(accountId, lane)`; `executeWithLock(accountId, lane, action)`, stavajici signatura deleguje na BACKGROUND (zadna zmena call-sites v kroku 1). Pravidlo "lock entries never removed" (CONCURRENCY.md rule 4) plati per lane; `purgeAccount` + `@PreDestroy` zaviraji obe lane.
  2. Fallback na limit spojeni: kdyz interactive connect selze (server limit soubeznych sessions — Gmail 15 OK, Seznam overit zive), degradovat na BACKGROUND lane + WARN a metrika; chovani pak identicke s dneskem, zadny novy failure mode.
  3. Klasifikace call-sites v `ImapFolderExecutor` (lane parametr): INTERACTIVE = fetch tela (`MailContentService`), prilohy (`AttachmentService`), reply/forward content, lazy page fetch + server count (`fetchServerCountAndEnsurePageLocally` — idempotence uz resi `dropAlreadyPersisted`), cold `getFolders`. BACKGROUND = sync cykly, backfill, maintenance, `ImapActionService` move/flag propagace (lokalni zapis uz je hotovy, serverova strana je eventual), `ImapAppendService` drafty.
  4. OAuth beze zmeny: `refreshLocks` zustava per ucet sdileny pres obe lane (uz dnes serializuje sync vs. SMTP); auth-retry vetev v `executeWithLock` funguje per lane. Lock-order kontrakt rozsirit: nikdy nedrzet zamky obou lane naraz; poradi SyncLockManager → connection lock (jedne lane) → refresh lock zustava.
  5. Docs + testy: radky v [backend/docs/CONCURRENCY.md](backend/docs/CONCURRENCY.md) (inventar + lock order), ArchitectureTest beze zmeny (Store zustava ve `feature.mail.service`). Latch-style GreenMail IT dokazujici invariant "fetch tela dobehne, zatimco BACKGROUND lane drzi bezici sync" (vzor `MailSyncGreenMailIT`); unit testy na lane routing + fallback z kroku 2. Pred releasem 24h soak s JFR (lock contention obou lane).
  6. Rizika k overeni v IT: expunge race (INTERACTIVE cte zpravu, kterou BACKGROUND prave mazal → musi skoncit fail-soft `contentError`, ne 500); UIDVALIDITY reset pozorovany jen sync lane (read path uz je stub-tolerantni). Odhad ~1 den vc. testu. Levnejsi nouzovka, kdyby limit spojeni branil druhe lane: rozsekat sync cyklus na kratsi `executeWithLock` useky (fair lock pusti cekajici mezi davkami) za cenu SELECT overheadu a peclivosti na invariant "local = souvisle nejnovejsi okno".
- [ ] Full QRESYNC SELECT s VANISHED — vetsi refactor `ImapFolderExecutor`; po release, pokud bude cleanup latency bottleneck.
- [ ] Self-update standalone backendu (jen pokud vznikne deployment mimo Tauri bundle).
- [ ] Dlouhodobe sledovat startup performance a velikost bundle.
- [ ] Tauri release smoke s AOT cache (perf, NE release blocker) — `npm run tauri:build:with-sidecar` s `$EnableAotCache=$true`, zmerit desktop `appReady` cold start a zapsat do [backend/PERFORMANCE_BASELINE.md](backend/PERFORMANCE_BASELINE.md). Backend cold start uz je v gate (3,7 s); tohle je optimalizacni cislo, ne blocker.

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
- Hotove implementacni deniky se do tohoto souboru nepridavaji — patri do commitu, changelogu nebo dokumentace. Hotovou polozku zkratit na jeden radek + pripadne presunout detail do [todo-archive.md](todo-archive.md).
- U zmen backendu pro Tauri nezapomenout prebalit a zkopirovat sidecar.
