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
- **Adresar je jeden pro celou aplikaci, ne per-ucet.** Rozhodnuto 2026-08-12, jeste pred prvnim releasem, protoze pozdeji by to byla V2 + datova migrace slucujici duplicity: `contacts` i `contact_labels` prisly o `account_id`, endpointy jsou na `/api/v1/contacts` a `/api/v1/contact-labels`. Ucet zustava jen v `/contacts/autocomplete`, kde se do adresare micha per-uctova historie korespondence (`correspondent`). Detail v [CHANGELOG.md](CHANGELOG.md).
- **`accounts.signature` zustava jeden `TEXT` sloupec bez formatu.** Rozhodnuto 2026-08-12, jeste pred prvnim releasem, protoze po nem uz je zmena migraci nad uzivatelskymi daty. Puvodni obava (Faze 2 prinese HTML compose → bude potreba `signature_html` nebo `signature_format` discriminator) padla s tim, jak vypada odesilaci cesta: editor je plain `textarea` a zustava jim (kvuli odecitaci), Markdown se interpretuje az na ceste ven a HTML alternativa je odvozenina. Podpis navic neni samostatna entita — frontend ho vklada primo do tela jako `\n\n-- \n{text}` ([signature.ts](frontend/src/lib/compose/signature.ts)), takze jde stejnou cestou jako zbytek zpravy a jeho format uz definovany je: plain text, ktery smi obsahovat Markdown. Druhy sloupec by zavedl stav, ktery nema kdo naplnit, a discriminator by rozlisoval hodnoty, ktere se na dratu chovaji stejne. „Interpretace existujicich podpisu", kvuli ktere to vypadalo nalehave, nehrozi: podpis se interpretuje pri kazdem odeslani znovu, ne jednou pri ulozeni. Invariant hlida `MarkdownBodyRendererTest` (podpisovy blok sam o sobe HTML alternativu nezapina).
- GitHub publikace je pozastavena, dokud nebude projekt kompletne lokalne odladeny.
- **v0.1.0 = uzavrena beta:** Google zustava Testing (≤100 test-useru), bez CASA. Rozhodnuto, viz [[project_v0.1.0_closed_beta_smoke]]. **Trigger pro start CASA se nestanovuje (uzavira #170 nalez 2), rozhodnuto 2026-08-13:** beta se drzi na uvedenem stropu ~45 testeru, tedy pod polovinou capu, takze na limit nenarazi a predem stanovene cislo by hlidalo situaci, ktera nenastane. Cap je **100**, ne 50 — overeno v [Google dokumentaci](https://support.google.com/cloud/answer/15549945) 2026-08-13, protoze na tom cisle to rozhodnuti stoji. Kvota se ale **cerpa pridanim testera a odebranim se nevraci**, takze pri strindani lidi se rezerva tenci i bez rustu aktivnich — proto strop na polovine, ne na 90. Druhy limit Testing rezimu zustava znamy a akceptovany: souhlas testera i refresh token expiruji 7 dni od udeleni. Kdyby se strop bety mel zvedat nad ~70, je CASA aktualni znovu — trva tydny a neco stoji, takze se s ni nesmi zacit az pri narazu.
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
- [ ] **NVDA doposlech seskupeneho rezimu** (threading Phase 2, `mail.messageGrouping = grouped`) — rozbaleni/sbaleni vlakna, badge cizi slozky („Ve slozce Odeslane"), hromadne akce nad konverzaci. Pozor na to, co e2e nezachyti: ze ohlaseni odpovida skutecnosti a ze ovladaci prvek jde dosahnout jinak nez mysi — oba dosavadni nalezy v tomhle rezimu byly tohohle druhu (viz [[feedback-sr-intercepts-single-key-shortcuts]]).
- [ ] **NVDA doposlech indikatoru selhavajiciho syncu** (#226, merged 2026-08-07) — nova plocha, kterou predchozi dva body nepokryvaji: sticky toast pri `sync_failed` a trvale tlacitko v sidebaru vedle Synchronizovat ([MailSidebar.svelte](frontend/src/lib/components/sidebar/MailSidebar.svelte)), vedouci do Nastaveni → Ucty. Overit, ze toast ohlasi duvod z lokalizovaneho `lastError` a ze se na tlacitko da dostat klavesnici; e2e uz dokazuje, ze je fokusovatelne, ne ze ohlaseni zni srozumitelne.

---

## v0.1.0 smoke — bugy Faze B

**Vse uzavreno** (2026-06-25/30). 8 bugu opraveno: FE update-dialog (#65), OAuth poll reconcile (#66), "duch" zpravy 404 A+B, SSE 30min ERROR (C), soubezny sync UNIQUE (F), HHH90003004 paginace (#70), duplicitni OAuth callback (#71/E), transient sync retry (#78/D), §6 sidecar zombie watchdog (#89/#90). Detail v [todo-archive.md](todo-archive.md); reziduum = pasivni log-watch v [backend/RELEASE_CHECKLIST.md](backend/RELEASE_CHECKLIST.md) §8.

---

## First Release Gate

Kanonicky seznam _kroku_ je [backend/RELEASE_CHECKLIST.md](backend/RELEASE_CHECKLIST.md): fresh install (§3/§3a), account + OAuth flows (§4), mail workflows (§5), sidecar lifecycle + recovery (§6), diagnostics (§7), long run (§8). Tady drzime jen release-mechaniku, ktera v checklistu NENI, a rozhodovaci body.

Rozhodnuti, ktera zamykala schema, jsou hotova — adresar i format podpisu, obe v
sekci Rozhodnuti. Zbytek DB analyzy (katalog provideru mimo migraci, drift gate,
redundantni indexy, `NOT NULL`) taky, detail v [CHANGELOG.md](CHANGELOG.md).

- [ ] Projit cely `backend/RELEASE_CHECKLIST.md` pro konkretni kandidat (vc. §6 recovery a §8 long run).
- [ ] End-to-end release dry run z cisteho checkoutu: tag / draft release, Windows signed workflow, upload artefaktu, overeni instalatoru, `latest.json`, podpisu, rucni instalace na cistem profilu.
- [ ] Release proces: verze, tag, changelog, known issues, hashe artefaktu, draft vs public, approval.
- [ ] **Google OAuth — Publish + CASA pro verejnost.** Projekt `VoxRox` je zatim **Testing** (External). Restricted scope `https://mail.google.com/` ([application.properties:90](backend/src/main/resources/application.properties)) vyzaduje pro public **Publish + CASA verifikaci** (Tier 2, per-projekt, placene/rocni). Pro v0.1.0 se NEresi — closed beta zustava Testing bez CASA (viz Rozhodnuti + [[project_v0.1.0_closed_beta_smoke]]). Produkcni creds + consent screen uz hotove (archive). Vetsi blok nez creds → soucast produkcni OAuth readiness.
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

- jackson-databind CVE-2026-54515 — override `jackson-2-bom 2.21.5` aplikovan 2026-07-10, OSV mirror dotazen a scan ciste (overeno 2026-08-06).
- [ ] **Backend overrides v [backend/pom.xml](backend/pom.xml) — cekaji na Spring Boot.** Docasne bezpecnostni piny; odstranit, az je Boot dozene. Kontrolovat pri kazdem Boot bumpu (4.1.0 = posledni k 2026-08-06, stale managuje vsechny ctyri stare verze):
  - `<jackson-2-bom.version>2.21.5</jackson-2-bom.version>` (CVE-2026-54515) — odstranit az managed `jackson-2-bom.version` >= 2.21.5.
  - `<jackson-bom.version>3.1.5</jackson-bom.version>` (CVE-2026-59889, `@JsonView` obchazen u `@JsonUnwrapped` pri deserializaci, CVSS 6.5, advisory 2026-07-21). Tohle je primarni JSON stack (`spring-boot-starter-web` → `starter-jackson` → `tools.jackson.core:jackson-databind`), ne bridge bom. Netriggerovatelne — `@JsonView` ani `@JsonUnwrapped` v kodu nejsou. Odstranit az managed `jackson-bom.version` >= 3.1.5.
  - `<tomcat.version>11.0.24</tomcat.version>` — odstranit az managed `tomcat.version` >= 11.0.24. **Pozor: 11.0.24 sam je od 2026-08-06 zranitelny** (viz nasledujici polozka).
  - `<log4j2.version>2.25.5</log4j2.version>` (CVE-2026-49844) — odstranit az managed `log4j2.version` >= 2.25.5.
- [ ] **Tomcat CVE-2026-66299 — ceka na upstream release (high, code-scanning alert #125).** Uncontrolled resource consumption; postihuje 11.0.0-M20 az 11.0.24, tedy i nas soucasny pin. Zranitelna je vyhradne `examples` webapp, kterou embedded Tomcat ve Spring Bootu vubec nenese, takze to neni exploitovatelne — ale alert zustava otevreny a **fix verze 11.0.25 zatim NEVYSLA** (Maven Central ma k 2026-08-10 jako latest porad 11.0.24, advisory sama pise „when released"). Akce: sledovat Maven Central a bumpnout `<tomcat.version>` na 11.0.25, jakmile vyjde. Do te doby neni co delat — bump neexistuje.
- swagger-ui webjar 5.32.11 → 5.32.13 (DOMPurify 3.4.13) — HOTOVO 2026-08-17, zavira alerty #130/#131. Pin je nove nutne parovat s `springdoc.swagger-ui.version`, jinak Swagger UI v devu vraci 404; removal condition drzi komentar u zavislosti v [backend/pom.xml](backend/pom.xml), detail v [CHANGELOG.md](CHANGELOG.md).
- [ ] **Frontend overrides v [frontend/package.json](frontend/package.json) — chybely removal conditions, doplneno 2026-08-07.** Kazdy je pin bez data expirace; historie ukazuje, ze pin sam casem spadne do zranitelneho rozsahu (`js-yaml` 4.3.0, `postcss` 8.5.12, `brace-expansion` 2.1.2/5.0.7 — vsechny tri se to uz staly). Stav k 2026-08-07 (`npm audit` cisty na vsech severitach):
  - `js-yaml: 4.3.1` — **jediny nosny override**. `@redocly/openapi-core` si rika o **presnou** verzi `4.1.1` (ne caret), takze bez overridu npm nainstaluje zranitelnou 4.1.1. Prechod na js-yaml 5.x neni moznost (redocly 1.x pouziva `JSON_SCHEMA.extend()` + `types.*`, co 5.x nema). Odstranit az `openapi-typescript` vezme major s redocly 2.x — dnes drzi `@redocly/openapi-core: ^1.34.6` a rada 1.x skoncila na 1.34.18.
  - ~~`postcss: 8.5.25`~~ — **odstraneno 2026-08-07.** Override byl zbytecny (vsichni konzumenti maji caret rozsahy) a drzel postcss o patch zpatky; po odstraneni se resolvne 8.5.26. Overeno, ze emitovane CSS je pred i po bajtove identicke.
  - `undici: 7.29.0` a `brace-expansion@2: 2.1.4` / `@5: 5.0.9` — rovnez zbytecne, pin == nejnovejsi verze v rozsahu, ktery by caret vybral sam (`jsdom ^7.25.0`, `minimatch ^2.0.1` / `^5.0.5`). Nechat jen dokud se overuje, ze odstraneni nezmeni resolved verze.
  - `esbuild: 0.28.1` a `cookie: 0.7.2` (pod `@sveltejs/kit`) — nosne, ale jinak: tlaci balicek **nad** deklarovany rozsah konzumenta (`svelte-i18n` chce `esbuild ^0.19.2`, kit chce `cookie ^0.6.0`). Drzet, ale pri kazdem bumpu tech konzumentu overit, ze override neni potreba — a ze porad funguje.
  - Cargo ekvivalent uz vzor ma: [frontend/src-tauri/.cargo/audit.toml](frontend/src-tauri/.cargo/audit.toml) nese u kazdeho ignore removal condition v komentari. Revizi vsech tri sad hlida [backend/RELEASE_CHECKLIST.md](backend/RELEASE_CHECKLIST.md) §8b.

---

## Startup follow-up

Backend (headless) cast zmerena a uzavrena — sekce "Startup audit — mereni 2026-06-11" v [backend/PERFORMANCE_BASELINE.md](backend/PERFORMANCE_BASELINE.md). Zbyva jen GUI:

- [ ] **Manualni startup SR sign-off na packaged buildu (`tauri:build`)** — overit, ze pri objeveni okna cteka cte "načítání". Defenzivni fixy uz hotove (2026-06-30, detail v archive): explicitni polite announce na startu bootu ([bootstrap.ts](frontend/src/lib/bootstrap.ts)) + loading-aware nativni titulek okna (`VoxRox Mail – načítání…` v [lib.rs](frontend/src-tauri/src/lib.rs), prepnut na app name pri boot `ready`/`failed` pres [windowTitle.ts](frontend/src/lib/windowTitle.ts)). Dev SR smoke ukazal, ze spolehlivy kanal pri objeveni okna je titulek, ne prvni aria-live. Gate-relevantni je shell-first "blank screen do 500 ms"; boot timings jsou nice-to-have. Pokud SR smoke ukaze tiche pre-hydratacni okno, doplnit staticky loading text do [app.html](frontend/src/app.html).

---

## Produktove funkce (backlog)

Hotove: **Podpisy zprav — Faze 1** (auto-insert + From-swap + manualni tlacitko + per-ucet prepinac, smoke 2026-06-23) a **Tabulka kontaktu** (sloupec Aktualizovano odebran) — detail v [todo-archive.md](todo-archive.md).

- [ ] iCloud OAuth.
- [x] Naseptavac adres z historie korespondence — Faze 1 HOTOVO 2026-08-07: tabulka `correspondent` plnena pri syncu + backfill po startu, `GET /contacts/autocomplete` slucuje adresar a historii se `source: CONTACT | HISTORY`. Detail v [CHANGELOG.md](CHANGELOG.md).
- [ ] **Naseptavac adres z historie — Faze 2 (po release):** explicitni zachyceni do adresare — akce „Pridat do kontaktu" u odesilatele v detailu zpravy. Do naseptavace nepatri (rozptyluje pri psani a druha akce v polozce rozbije klavesovou navigaci). Otevrene otazky, ktere Faze 1 vedome nechala: (1) **zmerit realnou velikost `correspondent`** na plne schrance — pokud by slo o rad vic nez nizsi tisice radku na ucet, prestane stacit scan pres `ux_correspondent_account_email` a bude potreba normalizovany sloupec pro shodu podle jmena; (2) smazani vsi posty od nekoho ho necha v naseptavaci, dokud se cache neprepocita (prijatelne, ale je to rozhodnuti — souvisi s tim, jestli ma jit cache invalidovat mazanim).
- [x] Vlastni stitky kontaktu (Google model) — HOTOVO 2026-08-07 (#230): tabulky `contact_labels` + `contact_label_links` misto enumu, sprava stitku + hromadne prirazeni v UI, polozky sidebaru z `GET /contacts/counts`. Enum WORK/HOME/OTHER zustal jako typ adresy. Detail v [CHANGELOG.md](CHANGELOG.md).
- [x] Threading Phase 2 (V0.2) — HOTOVO, vse v `main`: grouped UI (#207/#208/#209) + Outlook-style cross-folder konverzace (#221, merged 2026-08-06). Detail v [todo-archive.md](todo-archive.md), design v [backend/docs/THREADING_DESIGN.md](backend/docs/THREADING_DESIGN.md). Ziva zbyva uz jen polozka NVDA doposlechu vyse.
- [ ] **Podpisy zprav — Faze 2 (po release):** reply/forward placement vyreseno manualnim tlacitkem (Faze 1) — zbyva uz jen HTML-compose: az vznikne HTML editor, podpis sanitizovat na renderu (reuse [content-sanitizer](frontend/src/lib/mail/content-sanitizer.ts)). Vazba na [[project_desktop_app]] + HTML-compose polozku.
- [ ] **Interactive IMAP lane — druhe spojeni na ucet pro uzivatelske cteni.** Rozhodovaci gate uz neni jen subjektivni beta feedback: metrika **`mail.imap.lock.skipped`** (pridana 2026-08-06) pocita, kolikrat se cteci cesta vzdala kvuli obsazenemu spojeni — rostouci hodnota = jedno spojeni na ucet je bottleneck. **Rozsah se mezitim zuzil:** vypis konverzaci uz na zamek neceka (`executeWithLockOrSkip`, timeout `mail.client.imap.role-lookup-timeout`), takze zbyva fetch tela a prilohy. **Problem:** jeden pooled `Store` na ucet + fair lock ([ImapConnectionManager.java:105](backend/src/main/java/org/voxrox/mailbackend/feature/mail/service/ImapConnectionManager.java)); sync drzi zamek po celou folder cyklu ([MailSyncService.java:194](backend/src/main/java/org/voxrox/mailbackend/feature/mail/service/MailSyncService.java) — cely `executeInFolder` = download + flag sweep + cleanup, i desitky sekund), takze prvni fetch tela (`MailContentService.getOrFetchMessageContent`) a stazeni prilohy ([AttachmentService.java:78](backend/src/main/java/org/voxrox/mailbackend/feature/mail/service/AttachmentService.java) — cely transfer pod zamkem) cekaji ve FIFO fronte za nim. **Plan:**
  1. `ImapConnectionManager`: enum `Lane {INTERACTIVE, BACKGROUND}`, pool + lock mapy klicovane `(accountId, lane)`; `executeWithLock(accountId, lane, action)`, stavajici signatura deleguje na BACKGROUND (zadna zmena call-sites v kroku 1). Pravidlo "lock entries never removed" (CONCURRENCY.md rule 4) plati per lane; `purgeAccount` + `@PreDestroy` zaviraji obe lane.
  2. Fallback na limit spojeni: kdyz interactive connect selze (server limit soubeznych sessions — Gmail 15 OK, Seznam overit zive), degradovat na BACKGROUND lane + WARN a metrika; chovani pak identicke s dneskem, zadny novy failure mode.
  3. Klasifikace call-sites v `ImapFolderExecutor` (lane parametr): INTERACTIVE = fetch tela (`MailContentService`), prilohy (`AttachmentService`), reply/forward content, lazy page fetch + server count (`fetchServerCountAndEnsurePageLocally` — idempotence uz resi `dropAlreadyPersisted`), cold `getFolders`. BACKGROUND = sync cykly, backfill, maintenance, `ImapActionService` move/flag propagace (lokalni zapis uz je hotovy, serverova strana je eventual), `ImapAppendService` drafty.
  4. OAuth beze zmeny: `refreshLocks` zustava per ucet sdileny pres obe lane (uz dnes serializuje sync vs. SMTP); auth-retry vetev v `executeWithLock` funguje per lane. Lock-order kontrakt rozsirit: nikdy nedrzet zamky obou lane naraz; poradi SyncLockManager → connection lock (jedne lane) → refresh lock zustava.
  5. Docs + testy: radky v [backend/docs/CONCURRENCY.md](backend/docs/CONCURRENCY.md) (inventar + lock order), ArchitectureTest beze zmeny (Store zustava ve `feature.mail.service`). Latch-style GreenMail IT dokazujici invariant "fetch tela dobehne, zatimco BACKGROUND lane drzi bezici sync" (vzor `MailSyncGreenMailIT`); unit testy na lane routing + fallback z kroku 2. Pred releasem 24h soak s JFR (lock contention obou lane).
  6. Rizika k overeni v IT: expunge race (INTERACTIVE cte zpravu, kterou BACKGROUND prave mazal → musi skoncit fail-soft `contentError`, ne 500); UIDVALIDITY reset pozorovany jen sync lane (read path uz je stub-tolerantni). Odhad ~1 den vc. testu. Levnejsi nouzovka, kdyby limit spojeni branil druhe lane: rozsekat sync cyklus na kratsi `executeWithLock` useky (fair lock pusti cekajici mezi davkami) za cenu SELECT overheadu a peclivosti na invariant "local = souvisle nejnovejsi okno".
- `OpenApiSnapshotTest` nedeterminismus — `canonicalOpenApi` rekurzivne radi klice pred zapisem i porovnanim, snapshot i `schema.d.ts` jednorazove preskladany (2026-08-07). Overeno trema po sobe jdoucimi update behy (bajtove identicke) + ostrym behem.
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
.\scripts\package-sidecar-dev-windows.ps1 -SkipTests
cd C:\dev\java\mail\frontend
npm run sidecar:sync:windows
npm run tauri:dev
```

`package-sidecar-dev-windows.ps1` je dev wrapper: nacte OAuth hodnoty z `backend/.env` a zavola `scripts/package-sidecar-windows.ps1`, takze lokalni build ma funkcni OAuth login. Cisty `scripts/package-sidecar-windows.ps1` cte jen prostredi (pro CI, kde secrets jdou z env) — bez nastavenych promennych **skonci chybou**, placeholder zabali jen kdyz mu das `-AllowPlaceholderOAuth` (fail-closed, aby se nedal omylem vydat build s nefunkcnim OAuth loginem).

---

## Pre-Push Quality Gate

Seznam gate je v [CONTRIBUTING.md](CONTRIBUTING.md) „Pre-Push Quality Gate" —
jediny zdroj pravdy. Zdejsi kopie se od nej rozesla (chybel v ni `npm run lint`,
tedy prettier + `check:md` + eslint + `check:i18n`, ktery pre-push hook realne
pousti jako prvni krok), takze se nahrazuje odkazem misto aby se dorovnavala.

Zbyva tady jen to, co v CONTRIBUTING neni:

- Po `git add .` rucne projit staged soubory.
- Git hooky: `git config core.hooksPath .githooks` (per clone).

- [ ] **Brana na pomalejsim stroji: ~50 minut se nereprodukuje.** Zmereno 2026-08-13 primo
      na tom stroji (i7-1255U, 15W U-series, 1,7 GHz base, 16 GB, NVMe, plan Rovnovaha),
      kroky jednotlive: cela brana 106 s. Nejdrazsi je `lint` 34,5 s a `test:unit` 27,4 s,
      zbytek `svelte-check` 10,8 s, `check:audits` 7,5 s, `svelte-kit sync` 6,7 s, `knip`
      6,6 s, ostatni `check:*` pod 3,5 s. Se starou konfiguraci pred #264 (`projectService`
      nad `.svelte`, coz je stav, ve kterem tech 50 minut bezelo) vychazi `lint` 120,6 s,
      tedy cela brana ~192 s — ani to na 50 minut nestaci, chybi faktor ~15.
      Vylouceno: `svelte-check` (desetina toho, co `lint`), Defender (na tom stroji vubec
      nebezi, `WinDefend` je Stopped a real-time dela ESET) i sit (brana je cela offline).
      Pricina tedy neni v repu. Stopa, kterou to merenim vydalo: cisla na tom stroji
      **kolisaji mezi behy** — druhy pruchod dal `lint` 19,4 s proti 34,5 s prvniho a
      celou branu 64 s proti 106 s, pri identickem prikazu i stromu. Propustnost stroje
      tedy neni stabilni (15W CPU, termalni skrceni), coz je presne ten typ chovani, ktery
      se za horsich podminek muze nasobit. Kdyby se to vratilo, zmerit **za behu** toho
      pomaleho pruchodu, ne zpetne — podezreli zustavaji jednorazove vlivy: soubezna zatez stroje,
      planovany sken ESETu, prvni pruchod pres cerstve `node_modules` po `npm ci` (ESET
      skenuje desitky tisic souboru pri prvnim dotyku), beh na baterii a termalni skrceni
      15W CPU. Stejne tak je mozne, ze „50 minut" byl wall-clock cele push session vcetne
      selhani a oprav, ne jeden pruchod branou.

---

## Definition of Done

- Polozka se odskrtava az po realnem overeni.
- Nove ukoly maji byt akcni, s jasnym vysledkem.
- Hotove implementacni deniky se do tohoto souboru nepridavaji — patri do commitu, changelogu nebo dokumentace. Hotovou polozku zkratit na jeden radek + pripadne presunout detail do [todo-archive.md](todo-archive.md).
- U zmen backendu pro Tauri nezapomenout prebalit a zkopirovat sidecar.
