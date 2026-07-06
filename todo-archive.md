> **ARCHIV - zamrazeno 2026-06-07.** Tohle je puvodni 748-radkovy `todo.md`
> ponechany jako historie hotovych ukolu, audit reportu a implementacnich
> deniku. **Neudrzuje se** a muze obsahovat tvrzeni o kodu, ktera uz neplati
> (presne proto vznikl tento archiv). Zive ukoly viz [todo.md](todo.md).

---

# TODO - VoxRox Mail

Aktualni pracovni seznam pro monorepo `backend/` + `frontend/`.

Tento soubor ma obsahovat jen zive ukoly a rozhodnuti. Historicke analyzy, implementacni reporty a hotove plany patri do gitu, ne do aktualniho TODO.

---

## Rozhodnuti

- Frontend a backend jsou samostatne artefakty s vlastni verzí.
- Frontend verzi drzi `frontend/package.json`, `frontend/src-tauri/tauri.conf.json` a `frontend/src/lib/version.ts`; kontroluje je `npm run check:versions`.
- Backend verzi drzi `backend/pom.xml`; runtime ji cte pres `ApplicationVersion` z Maven `build-info.properties`.
- Kompatibilitu frontendu a backendu neresi rovnost release verzi, ale handshake pres `apiVersion` a `minClientVersion`.
- Tauri desktop bundle nese frontend i backend sidecar; po zmene backendu je nutne znovu zabalit sidecar a zkopirovat ho do frontendu.
- Commit messages pouzivaji Conventional Commits v anglictine, se scopes podle casti monorepa: `backend`, `frontend`, `tauri`, `ci`, `docs`, `repo`, `release`.
- Canonicky changelog pro vydani je root `CHANGELOG.md`; modulove changelogy zustavaji jen pro technicke/migracni detaily, pokud davaji smysl.
- GitHub publikace je pozastavena, dokud nebude projekt kompletne lokalne odladeny.

---

## Nejblizsi priorita

**Aktualne (2026-06-06): OS secret store (DPAPI) pro `crypto.bin` — IMPLEMENTACE HOTOVA, zbyva manualni Windows smoke.** Navazuje na nalez z bezpecnostniho review (2026-06-05): SQLite DB neni sifrovana a klic k jedinemu sifrovanemu sloupci (`account_credentials.secret_encrypted`, AES/GCM) lezel v `crypto.bin` ve **stejnem adresari jako DB** → kdo zkopiruje celou slozku, ma ciphertext i klic (ekvivalent Thunderbirdu bez master hesla). Best-practice (Outlook→DPAPI, Apple Mail→Keychain): secret nikdy nelezi vedle dat s ko-lokovanym klicem.

- **Rozhodnuti:** zvolen bod 1 z porovnani variant — ochranit bytes `crypto.bin` pres **Windows DPAPI** (`CryptProtectData`, USER scope + app entropy). **Mimo scope:** sifrovani cele DB (SQLCipher = samostatna varianta 2, pripadny budouci follow-up). CryptoService / format ciphertextu v DB / DB samotna se nemeni — meni se jen at-rest forma souboru `crypto.bin`.
- **Implementace (hotovo + zelene):**
  - Novy package `core/security/secret/`: [SecretStore](backend/src/main/java/org/voxrox/mailbackend/core/security/secret/SecretStore.java) (interface + `forCurrentOs()` factory), [WindowsDpapiSecretStore](backend/src/main/java/org/voxrox/mailbackend/core/security/secret/WindowsDpapiSecretStore.java) (cista Java 25 FFM/Panama na Crypt32 `CryptProtectData`/`CryptUnprotectData` + Kernel32 `LocalFree`, USER scope, app entropy `voxrox-mail-crypto-v1`, zadne JNI/nativni lib), [NoopSecretStore](backend/src/main/java/org/voxrox/mailbackend/core/security/secret/NoopSecretStore.java) (identity fallback pro non-Windows + testy), [SecretStoreException](backend/src/main/java/org/voxrox/mailbackend/core/security/secret/SecretStoreException.java) (unchecked → fail-closed, nikdy fallback na plaintext).
  - [StorageContextInitializer](backend/src/main/java/org/voxrox/mailbackend/core/init/StorageContextInitializer.java): injektujici konstruktor (test seam) + no-arg `this(SecretStore.forCurrentOs())`. Novy format `crypto.bin` = `VOXSEC1\n<base64(protect(key=…\nsalt=…))>`. **Bezesva migrace:** legacy plaintext `crypto.bin` se pri prvnim startu in-place prepise na `VOXSEC1` (beze zmeny klice → bez re-encrypt DB). Env-var rezim (`MAIL_CRYPTO_KEY`/`SALT`) netknut. jpackage launcher uz ma `--enable-native-access=ALL-UNNAMED` → bez upravy.
  - **Testy:** [StorageContextInitializerTest](backend/src/test/java/org/voxrox/mailbackend/core/init/StorageContextInitializerTest.java) +4 (round-trip protected formatu, in-place legacy migrace, tamper fail-closed pres injektovany reverzibilni fake store, a `@EnabledOnOs(WINDOWS)` realny DPAPI round-trip). `HandshakeServiceTest.storageInitializerBootstrapsCryptoWhenEnvIsMissing` opraven (injektuje `NoopSecretStore` + `readBootstrap` umi `VOXSEC1` format) — odhalena regrese diky plnemu buildu.
  - **Dokumentace:** [OPERATIONS.md](backend/OPERATIONS.md) (popis `crypto.bin`, backup/restore DPAPI USER-scope caveat, sekce Rotace crypto klice) + [SECURITY_THREAT_MODEL.md](SECURITY_THREAT_MODEL.md) (asset row + Boundary 5: tamper fail-closed at unprotect, novy radek „crypto.bin copied off-machine" → DPAPI seal).
- **Gate ZELENA:** `mvn -o test` **766/0/0** (vc. realneho Windows DPAPI testu, ne skip), `mvn spotless:check` clean.
- **Zbyva (vyzaduje uzivatele — manualni Windows smoke, plan verification krok 4):** zabalit sidecar, cisty profil → start → overit, ze `crypto.bin` zacina `VOXSEC1` a jeho bytes nejsou plaintext klic; pridat ucet, restart, overit ze sync/login funguje (credentials se desifruji). Pak podstrcit *legacy* plaintext `crypto.bin` → start → overit in-place migraci na `VOXSEC1` + funkcni credentials.
- **Znama omezeni (zdokumentovana):** DPAPI USER-scope nechrani proti malwaru beziciho pod stejnym uzivatelem (stejny strop jako Chrome do 2024); obnova zalohy pod jinym Windows uctem/strojem = credentials nerozlustitelne → `requiresReauth` → re-login (akceptovatelne, uz drive dokumentovane chovani pri ztrate klice).

---

**Aktualne (2026-06-05): serie code-quality review — KOMPLETNI.** Pet analyz hotovo a opraveno:
- **Fallback audit** → `clientConfig` tichy fallback nyni loguje `console.warn` pri degradaci (pozorovatelnost, source-of-truth zustava backend).
- **Error-handling + resource-leak audit** → opraven latentni NPE v `ImapFolderExecutor` (null `getMessage()` pred `.toLowerCase()`) + novy `ImapFolderExecutorTest` (2 testy), komentare o fragilite string-matchingu na text vyjimky (`ImapFolderExecutor` + `ImapActionService`), CZ→EN interni hlaska v `AccountLastErrorJson`. Backend `mvn -o test` na dotcenych tridach 5/5 zelene, spotless clean.
- **Pristupnost (a11y) audit** → viz odskrtnuta polozka nize. App je a11y zrala (persistentni `LiveAnnouncer` resi DOM-timing live regionu, landmarky bez duplikace role v aria-labelu, skip-linky, `afterNavigate` focus na `<main>`, MessageList ARIA grid, ContactList semanticka tabulka, ContactMergeDialog `aria-live` preview + `role=alert` warning). Jediny zaostavajici ostrov = stranka hledani (opraveno).
- **Concurrency / threading audit** → viz odskrtnuta polozka nize. Design je zraly a korektni, **zadny zivy bug**. Jeden hardening (visibility footgun) + dokumentovane neskodne observace.
- **Bezpecnostni review** → viz odskrtnuta polozka nize. Postura je **vyborna** napric vsemi plochami threat modelu. Jediny nalez = bezpecnostne zavadejici komentar o „API klic v URL pro SSE" (kod je spravne header-based, komentar lhal) — opraveno.

Vsech 5 polozek serie hotovo (serazeno od nejjednodussiho po nejvetsi rozsah) — serie code-quality review uzavrena, dalsi pripadne audity zacit novou serii:

- [x] ~~**#3 CryptoService typed exception**~~ — **HOTOVO 2026-06-05**. Nova `CryptoOperationException extends RuntimeException` v `core.security` (mimo `AppException` sealed hierarchii → generic 500 bez leaku detailu o crypto subsystemu, ale typovana/grep-ovatelna/testovatelna). Vsechny 4 crypto-related `throw new RuntimeException(...)` v `CryptoService` (encrypt, decrypt-integrity, decrypt-general, key-derivation) prevedeny; self-test `IllegalStateException` ponechan (boot invariant). `CryptoServiceTest` 6 assertci zprisneno z `RuntimeException.class` na `CryptoOperationException.class`. Bez serialVersionUID (konvence repa — zadna vyjimka ho nedeklaruje). `mvn -o test` CryptoServiceTest+AccountCredentialServiceTest **34/34 zelene**, spotless clean. Behavior zachovan (callers s `catch (RuntimeException)` chytaji dal).
- [x] ~~**Pristupnost (a11y) analyza**~~ — **HOTOVO 2026-06-05**. Sweep `aria-live` oznameni, focus management, landmarku a loading/error stavu napric FE. App je vetsinou vyborna (viz souhrn vyse). **Jediny realny nalez: stranka hledani (`/search/[accountId]`)** zaostavala za zbytkem:
  - **#1 Monolitcky aria-label (anti-pattern z [[feedback_a11y_screenreader]])** — kazdy vysledek byl *jeden `<button>`* s `messages.rowAriaLabel` slevajicim predmet+odesilatel+datum do jednoho retezce (a zamlcoval slozku). MessageList byl davno prevedeny na grid, search zustal pozadu — `rowAriaLabel` se pouzival uz jen tady (pozustatek). **Fix:** nova [SearchResultsGrid.svelte](frontend/src/lib/components/SearchResultsGrid.svelte) — stejny `role=grid` jako MessageList, 5 sloupcu (stav/predmet/odesilatel/datum/slozka), roving per-bunka navigace (sipky + Home/End + Ctrl+Home/End + PageUp/Down), Enter/Space/klik otevre. Per-bunka navigace = SR uzivatel cte kazde pole zvlast misto jedne flat hlasky. Mrtvy klic `messages.rowAriaLabel` smazan z cs/en, pridan `search.columnHeaderFolder`.
  - **#2 Ztrata focusu pri otevreni vysledku** — search otevira detail in-place pres `selectMessage()` (zadny `goto`), takze `afterNavigate` focus na `<main>` se nespusti → odmountovany button shodil focus na `<body>`, detail se neohlasil. (Zavreni naopak OK: `closeCurrentMessageDetail` dela `goto` → afterNavigate focus chyti.) **Fix:** `handleSelect` po `selectMessage` presune focus na `#main-content` (konzistentni s tim, co afterNavigate dela pro mail route).
  - **#3 (drobnost)** `routes/+page.svelte` `loadError` (selhani nacteni slozek pri root redirectu) dostal `role="alert"`.
  - **Dedup:** 2D nav math vytazena do sdileneho pure helperu `computeNextCell` v [rowNavigation.ts](frontend/src/lib/components/grid/rowNavigation.ts) (mirror existujiciho `computeNextRowIndex`, unit-testovatelny; MessageList ponechan beze zmeny aby se nesahalo na fungujici grid).
  - **Testy:** +8 unit testu `computeNextCell`, novy a11y e2e test (cell navigace + focus na `#main-content` pri otevreni), `search.functional.e2e.ts` aktualizovan (list→grid, button→row).
  - **Gate ZELENA:** svelte-check **1346/0/0**, lint (prettier+eslint+i18n) clean, `check:translations:strict` OK, knip clean, `test:unit` **285/285** (+8), `test:functional:stable` **107/107**, `test:a11y:stable` **53/53** (+1 search grid test, search route axe scan clean), i18n parita **565**.
- [x] ~~**Concurrency / threading analyza**~~ — **HOTOVO 2026-06-05**. Sweep thread-safety JavaMail `Store`/`Folder`, leaky spojeni, race v sync cyklu. (Pozn.: `THREADING_DESIGN.md` je o *konverzacnim* threadingu (feature), ne o OS concurrency — odkaz v todo byl zavadejici.) **Zaver: concurrency model je zraly a korektni, zadny zivy bug.** Overeno:
  - **Dvouvrstvy zamek**: `SyncLockManager` (`ConcurrentHashMap.newKeySet`, non-reentrant mutex, unlock vzdy ve `finally`) gatuje proti prekryvu periodickeho sync cyklu; `ImapConnectionManager` per-account fair `ReentrantLock` serializuje *veskery* pristup ke `Store`/`Folder` (JavaMail neni thread-safe) napric sync i user akcemi. Vsechny vstupy (`ImapFolderExecutor.executeReadOnly/Write`, `ImapAppendService`, `ImapFolderService`, `fetchServerCountAndEnsurePageLocally`, `moveOnServerAsync`→`openFolder`) tecou pres `executeWithLock` → per-account serializace v celem call grafu drzi.
  - **Executory** ([AsyncConfig](backend/src/main/java/org/voxrox/mailbackend/core/config/AsyncConfig.java)): 3 bounded virtual-thread pooly (`mailSyncExecutor` limit=maxConcurrentAccounts, `userMailExecutor` 8, `mailEventExecutor` unbounded) s dokumentovanou deadlock-avoidance invariantou. Zmapovany vsechny `@Async` callsity — invarianta (sync task nesubmituje dalsi `mailSyncExecutor` praci) drzi (`syncAllFolders`/`syncAndBackfillAsync` jsou same-class self-invocation → obchazi proxy, nebezi async).
  - **FolderCountCache** (`ConcurrentHashMap`, benigni cache check-then-act) + **TokenCache** (`Collections.synchronizedMap` + LRU `LinkedHashMap accessOrder`) thread-safe. localCount-inside-lock race uz opraven (Hybrid pagination Faze 1).
  - **SSE** ([SseNotificationService](backend/src/main/java/org/voxrox/mailbackend/feature/mail/service/SseNotificationService.java)): `CopyOnWriteArrayList` emitteru, remove-during-iteration bezpecny. Concurrent `emitter.send()` (broadcast z mailEventExecutor/userMailExecutor vs heartbeat ze scheduleru) — **overeno z bytecode**, ze Spring 7.0.6 `ResponseBodyEmitter` ma `protected final Lock writeLock`, ktery `sendInternal`/`complete` acquiruji → per-emitter serializace, **zadny race**.
  - **Leaky spojeni**: Folder/Store se vzdy zaviraji ve `finally` (`ImapFolderExecutor`, `moveOnServerAsync` dest), dead stores se zaviraji pred nahrazenim, `@PreDestroy` zavre vse. Zadny leak.
  - **Jediny hardening (F1):** `getConnectedStore` + `openFolder` byly `public`, ale jejich fast-path vydava IMAP prikazy *bez* zamku a spoléhají na to, ze caller drzi per-account zamek (vsichni soucasni calleri ho drzi — overeno gripem, takze **zadny zivy bug**). Zuzeno na **package-private** + zprisne lock-contract Javadocy (vc. `openFolder` vraci otevreny Folder, caller musi zavrit) — public surface zval budouci regresi, ktera by rozbila per-account serializaci Store. `executeWithLock` Javadoc nyni explicitne uvadi invariantu „single entry point". `ImapActionService` (jediny externi caller `openFolder`, same package) kompiluje dal.
  - **Observace (bez fixu, neskodne):** (a) `MailSyncScheduler` predava *detached* `AccountEntity` do `@Async syncAllFolders` — funguje jen proto, ze se sahá jen na basic fieldy (`getId`/`getEmail`); fragilni pokud nekdo pozdeji pristoupi k lazy `provider`/`credentials`. (b) Double OAuth refresh okno v `getAccessToken` (check-then-act bez zamku) pri souběhu IMAP connect + SMTP send — benigni (refresh token z DB, nerotuje se zpet, oba uspeji; jen 1 HTTP call navic). (c) `purgeAccount` odebira lock z mapy bez drzeni — benigni (terminalni operace pri smazani uctu).
  - **Gate:** `ImapConnectionManagerTest` **16/16** zelene (BUILD SUCCESS; stack traces v logu = ocekavane auth-fail testy), `mvn spotless:check` clean. Zmena je visibility + Javadoc only (zadna behavior change), takze ostatni testy nedotcene (test-compile celeho modulu prosel).
- [x] ~~**Bezpecnostni review**~~ — **HOTOVO 2026-06-05**. Sweep vsech ploch z [SECURITY_THREAT_MODEL.md](SECURITY_THREAT_MODEL.md) — overeno, ze deklarovane mitigace skutecne drzi v kodu. **Zaver: bezpecnostni postura je vyborna; zadna kodova zranitelnost.** Per plocha:
  - **Credential/token encryption at-rest** ([CryptoService](backend/src/main/java/org/voxrox/mailbackend/core/security/CryptoService.java)): AES/GCM/NoPadding, cerstvy 12B `SecureRandom` IV per encryption (zadny IV reuse), PBKDF2-HMAC-SHA256 256-bit per-account klic (salt = globalSalt‖accountId), **AAD = accountId** (brani cross-account zamene ciphertextu), zadne logovani plaintextu/klice, fail-closed self-test, `@PreDestroy` zeruje secret. Solidni.
  - **API klic v URL pro SSE** (todo-flagnuta obava): **NEEXISTUJE leak.** `SseClient` posila `X-API-KEY` jako **hlavicku** pres `httpFetch` streaming (`notifications.ts:77`), `buildApiUrl` vraci `{url, apiKey}` oddelene (url klic neobsahuje), backend [ApiKeyFilter](backend/src/main/java/org/voxrox/mailbackend/core/security/ApiKeyFilter.java) cte **jen** hlavicku (constant-time SHA-256 compare), `/notifications/stream` neni v `PUBLIC_ENDPOINTS` → `authenticated()`. **Jediny nalez: zastaraly + bezpecnostne zavadejici komentar** v [notifications.ts](frontend/src/lib/api/notifications.ts) tvrdil „backend cte klic z query param – we append it to the URL" (obe poloviny nepravda) — past pro budouci regresi (maintainer by mohl klic presunout do URL → leak do access logu/historie/Referer). Prepsano na popis skutecne header-based auth + explicitni varovani; pridruzeny `buildApiUrl` Javadoc v `client.ts` srovnan. Prettier + eslint clean.
  - **Attachment path traversal**: `partPath` se v `findPartByPath` stepi na `.` a kazdy segment parsuje jako **integer** (MIME index) — `../`/separatory neprojdou; temp soubor `Files.createTempFile(dir, "attach_"+stableId+"_", ".tmp")` (JDK overuje no-parent jmeno), `stableId` predem overen proti DB. Bez traversal.
  - **Content-Disposition** (attacker-ovladany attachment `fileName`): ASCII cast pres `sanitizeAsciiFileName` (vse mimo tisknutelne ASCII + `"\/;` → `_`, takze CRLF injection nemozna), RFC5987 cast URL-encoded; `attachment` disposition vynucuje download.
  - **XSS**: **trojvrstva obrana.** Backend [HtmlSanitizer](backend/src/main/java/org/voxrox/mailbackend/util/HtmlSanitizer.java) (Jsoup `Safelist.relaxed()`, zadny script/iframe/form/handler, `style` strip, linky jen http/https/mailto/tel + `rel=noopener`, obrazky jen `cid:`/`data:image` striktni base64 — **SVG vyloučen**, fail-closed) se skutecne vola (`MailContentService:74`); frontend `sanitizeMailHtml`; render v **sandboxovanem iframe** (`sandbox=""` + `csp` atribut `default-src 'none'`) pres `srcdoc`, plain-text vetev auto-escapovana (`{content}`, ne `{@html}`).
  - **SSRF**: `validateBackendBaseUrl` ([session.ts](frontend/src/lib/api/session.ts)) omezuje base URL na loopback host + `/api` path + match portu → API klic nikdy neodejde jinam; jediny backend outbound HTTP je OAuth `tokenEndpoint()` (fixni z configu, ne user input); remote `<img>` strhnuty sanitizerem (zadny backend fetch tracking pixelu).
  - **Drobne doc nepresnosti (bez fixu):** threat model rika „numeric loopback host" u `validateBackendBaseUrl`, kod ale (zamerne, neskodne) povoluje i jmeno `localhost`. CORS povoluje `localhost:[*]`/`127.0.0.1:[*]`/`file://*` s `allowCredentials` — neskodne, protoze realni gate je `X-API-KEY` (cross-origin stranka klic nezna; CSRF disabled je OK, auth neni cookie-based).
  - Pozn.: release-ops bezpecnostni ukoly (rotace OAuth credentials, novy `MAIL_CRYPTO_KEY`/salt, secret scan historie, vuln scan SBOM) zustavaji v sekci „## Bezpecnost" nize — nejsou soucasti tohoto code-review auditu.

**Aktualne (2026-06-02 odpoledne):** kompletni autonomni sweep — pre-push gate re-validovan, doplneny CI gate lokalne (spotless, lint, cargo), drift detection, dead-code cleanup, XSS unit tests, coverage thresholds. **9 nalezu opraveno**:

1. **`schema.d.ts` stale** — Threading Phase 1 + PATCH removal updatovaly BE OpenAPI snapshot, FE regeneraci se zapomnelo. `npm run generate:api:snapshot` ji obnovil.
2. **PATCH /accounts test mismatch** — `accounts.functional.e2e.ts:245` enumeroval PATCH variant pro endpoint, ktery byl 2026-06-01 odstranen. Zuzeno na POST/PUT.
3. **ErrorCode drift (provider-agnostic vs Gmail-specific text)** — FE `errors.mailOauth2ImapAccessDenied` rikal "**Google** odmítl…", ale BE kod se po 2026-05-16 vyhazuje i pro Microsoft OAuth. BE ma vlastni provider-agnostic preklady (`error.mail.oauth2ImapAccessDenied` v `messages_cs/en.properties`) — FE override v `errors.ts` smazan, FE i18n klice `errors.*` smazany, vsech 28 callsite `toErrorMessage(err, $_)` → `toErrorMessage(err)`. Single source of truth (BE Accept-Language).
4. **Dead FE exports/code** — `getContact`, `updateContact` v `contacts.ts` (FE pouziva PATCH endpoint, ne PUT/GET), `selectedStableId` v `mail/actions.ts`, unexport `nextThemePreference`/`flagMessages`/`listDrafts` (used jen lokalne). 5 dead types/vars v handlers.ts, errors.ts, search.ts, generated.ts.
5. **Doc drift v ThreadResponse Javadoc** — `Response shape for GET /api/v1/mail/threads/{threadId}` → `GET /api/v1/messages/account/{accountId}/threads/{threadId}` (skutecny endpoint).
6. **Broken doc link** — `backend/PERFORMANCE_BASELINE.md:88` → `[stores/boot.ts](src/lib/stores/boot.ts)` resolvoval na `backend/src/lib/...`. Opraveno na `[frontend/src/lib/stores/boot.ts](../frontend/src/lib/stores/boot.ts)`.
7. **FE THIRD_PARTY_LICENSES.md drift** — 116 vs 116 ok, ale jeden balicek presel z MIT → MIT* (svelte-toolbelt 0.10.6). Pridan reproducibilni regen skript `frontend/scripts/regen-third-party-licenses.mjs` + `npm run regen:licenses`. Backend Maven + Cargo regen pripravit pred prvnim release (nejsou v scope dnes).
8. **XSS unit coverage `content-sanitizer.ts`** — 24% stmt / 10% branch → **95% stmt / 90% branch**. 29 novych testu pro script/style/iframe/form removal, on* handlers, javascript:/data: URLs, srcdoc/srcset/formaction, SVG onload, allowed inline-image whitelist, colspan/rowspan sanitization, DOMParser absence.
9. **Coverage thresholds** — `vitest.config.ts` ma nyni global floor 55% lines/statements/branches, 40% functions + per-file floor 90/85/90 pro `content-sanitizer.ts`. Floor je 5p pod aktualnim baseline (60/61/47/60), takze maly refactor proleti, ale regrese typu "smazat test sady" failne.

**Pred prvnim commitem zbyvaji DVE veci** (oboji vyzaduje uzivatelskou interakci, nelze autonomne): (1) manualni smoke v `tauri:dev` overujici reseny IMAP timeout bug, OAuth refresh po dedupe a OAuth flow po unexportu googleAuthStartUrl/startGoogleLogin (frontend audit), (2) puvodni smoke Merge dialogu.

### Pro pristi autonomni session (TODO 2026-06-03+)

Stav po 5 vlnach 2026-06-02: pre-push gate plne zelena (BE **766+22**, FE 191/191, lint+knip+coverage), tooling kompletni (3 license regen scripts, jacoco merged + threshold gate, knip config), GitHub scaffolding (SECURITY.md, CONTRIBUTING.md, PR template), CI vc. cargo + knip steps. Co lze udelat dale bez uzivatele:

**Vysoka priorita (security/coverage):**
1. ~~**`MailConnectionProbe` unit testy**~~ — **HOTOVO 2026-06-02 R5**. 10 testu pokryvajicich OAuth2-plaintext rejection guard, PASSWORD success (SSL i non-SSL flow), MessagingException wrap + finally close, swallowed close exception, OAuth2 access token authentication path, OAuth2 token resolution failure, SMTP success / MessagingException / RuntimeException wrap. INST 100% / LINE 100% / BRANCH 91.7% (predtim 11%/0%). MockedStatic na `Session.getInstance` pro IMAP, plain mock pro `SmtpTransportFactory`.
2. ~~**`AccountProviderService` unit testy**~~ — **HOTOVO 2026-06-02 R5**. 17 testu pokryvajicich `resolveProvider` (explicit ID found/missing, domain auto-detect found/missing/multi-domain, null + invalid email → ValidationException, domain normalization k lower-case), `loadProviderById` (found / missing → ProviderNotFoundException.byId), `findProviderByEmail` (match s DTO mapping, null/no-@ → empty, no-match → empty), `getProviderById`, `getAllProviders` toDto mapping + ordering. INST 96.9% / LINE 100% / BRANCH 69.2% (predtim 13%/0%).
3. ~~**`lib/backend/sidecar.ts` FE testy**~~ — **HOTOVO 2026-06-02 R5**. Rozsireno z 4 testu (`clearStaleHandshakeFiles`) na 22 testu: 4 puvodni handshake cleanup, 4 `usesBackendSidecar` env kombinace (non-Tauri, VITE_E2E_MOCK=1, VITE_BACKEND_SIDECAR=0, default), 6 `ensureBackendSidecar` short-circuits (disabled state, E2E failure once+always s localStorage clear/preserve, spawn success+pid, already-running short-circuit, spawn() throws → error state), 6 `handleUnexpectedExit` exit-code wireup pres `formatSidecarExitMessage` (78 → "already running", 130/143 → "external signal", null+signal → "signal #N", default → "code N", error event → "Failed to start"), 2 `restartBackendSidecar` (kill + respawn + counter reset, close-during-stop → no bounce). Mock Command.sidecar/spawn/EventEmitter, `vi.resetModules` + clear singleton runtime na `globalThis.__MAIL_BACKEND_SIDECAR__`. Vlastni in-memory `localStorage` stub (jsdom directive na Node 22 nepripravil). **17% stmt / 7% br → 89.62% stmt / 88.63% branch / 85% func / 93.06% lines.** Pre-push gate update: `npm run test:unit` **209/209** (+18 vs R4), bundle coverage **80.67% stmt / 80.28% br / 68.87% func / 82.21% lines** (predtim 70/73/59/71, +10/+7/+10/+11pp). Side-effect: 2 pre-existing untracked test soubory (`client.test.ts`, `errors.test.ts`) s CZ load-bearing ProblemDetail fixtures pridany na `frontend/docs/translation-whitelist.txt` (analogie MSW handlers pattern).

**Stredni priorita (code quality):**
4. ~~**FE deep test audit**~~ — **HOTOVO 2026-06-02 R5d**. Audit 16 `*.test.ts` souboru:
   - **Redundance smazane** (3): `stores/session.test.ts:26-28` + `api/client.test.ts:46-48` (afterEach `vi.clearAllMocks()` duplikuje `clearMocks:true` v vitest.config), `mail/message-seen.test.ts:147-149` (3 mid-test `mockClear()` calls — auto-clear bezi pred kazdym testem).
   - **Zachovane `mockReset()` v beforeEach** s nove pridanym vysvetlujicim komentarem: pattern `mockResolvedValueOnce` / `mockRejectedValueOnce` plni internal queue, kterou `clearMocks` (jen call history) NEresetuje. Musi byt `mockReset` (history + impl queue). Pres ruzne nalezy (session, client, sidecar, i18n) to platí konzistentne.
   - **`as never` / `as unknown` casts** prozkoumane (6 callsites), vsechny zachovaly kontext:
     * `providerResolver.test.ts:12,14` — partial MailProviderResponse fixture (DTO s mnoha polozkami)
     * `message-seen.test.ts:40` — minimal SelectedMessage.detail fixture
     * `errors.test.ts:41,48,60` — pragmaticky test cast pro ProblemDetail options bag, `Partial<ProblemDetail>` by neprinesla cistsi typecheck
     * `sidecar.test.ts:67` — globalThis narrowing pro singleton delete
     * `rowNavigation.test.ts:50` — null-guard test schvalne predava `undefined` proti signature `EventTarget | null`
   - **Pre-push gate update**: `npm run test:unit` **260/260** zelene, prettier + eslint + i18n + 551 klicu match clean.
5. ~~**Spotbugs deep sweep**~~ — **HOTOVO 2026-06-02 R5c**. Spustil `mvn spotbugs:spotbugs` s docasnym `<threshold>Low</threshold>` v pom.xml (po auditu vraceno na Medium). **46 priority-3 (Low) findings** napric 10 patternu: 20× DM_CONVERT_CASE (toLowerCase bez Locale, vsechny domain-name lookup contexts kde ASCII-only mas zaruceno z RFC), 8× THROWS_METHOD_THROWS_RUNTIMEEXCEPTION (deklarace neprilezuse `throws RuntimeException` — stylisticky neutralni), 5× REC_CATCH_EXCEPTION (vsech 5 je legitimni broad catch v security-critical paths: CryptoService.performSelfTest/getOrDeriveKey/cleanUp + HandshakeService.onApplicationReady + SmtpMessageService.send/save — boot/lifecycle/security context musi byt bulletproof), 3× DE_MIGHT_IGNORE (cleanup exception ignored — vsech 3 v finally / @PreDestroy paths, namely CryptoService.cleanUp, ImapConnectionManager.removeConnectionLocked, SmtpTransportFactory.openOAuthTransport - legitimni), 3× NP_NULL_ON_SOME_PATH (Path.getFileName() teoreticky null pri root path, ale Files.list(dir) nikdy nevraci root → false positive ve 2 z 3; ImapConnectionManager TOCTOU-style double-call opraven extractnutim do local var `lastError`), 2× NP_LOAD_OF_KNOWN_NULL_VALUE (MessageFetcher.mapToResponse — analyza spojuje 2 mutually-exclusive code paths, false positive), 2× SIC_INNER_SHOULD_BE_STATIC_ANON (anonymni inner class — kosmeticky), 1× SE_NO_SERIALVERSIONID (jedna IllegalStateException subclass), 1× SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE (DatabaseConfig.readPragma — vsechny 5 callsites volaji s hardcoded literaly `"journal_mode"`, `"synchronous"` apod., zadny user input, **0 SQL injection risk**), 1× THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION. **Jediny realny opravu**: `ImapConnectionManager:374-375` defensive refactor (TOCTOU). Vse ostatni false positives nebo stylisticky nepřinášející bezpečnostní hodnotu. **Doporuceni**: zachovat aktualni Medium threshold v CI gatu — Low findings nepřinášejí actionable signál pro tento projekt.
6. ~~**`lib/i18n/index.ts` FE testy**~~ — **HOTOVO 2026-06-02 R5b**. 16 testu pokryvajicich SUPPORTED_LOCALES / LOCALE_LABELS expose, `detectInitialLocale` fallback chain (browser=false → DEFAULT, localStorage stored supported / unsupported → navigator, localStorage throw → continues, navigator.language short-code, all-unsupported → DEFAULT), `setLocale` transitions (appLocale derived store, localStorage persistence, `<html lang>` for a11y SR pronunciation, document.title), `syncNativeWindowTitle` Tauri-only path (no-op when non-Tauri, setTitle called when Tauri, rejected setTitle swallowed), `initI18n` idempotent. Pattern: `vi.resetModules` + jsdom env + `Object.defineProperty(window, 'localStorage')` stub. **54.38%/30.76%/53.33%/57.14% → 94.73%/80.76%/100%/95.91%.** Pozn.: svelte-i18n's `locale` store je globalThis singleton ktery prezije vi.resetModules — testy asseruji na *transition*, ne na absolute initial value.
7. ~~**`lib/compose/addresses.ts` FE testy**~~ — **HOTOVO 2026-06-02 R5b**. 35 testu (4 funkce x ~9 scenarios) pokryvajicich `parseAddressList` (split na `,;\n`, mixed separators, consecutive separators collapse, whitespace trim, empty input), `serializeAddressList` (round-trip), `isValidEmailAddress` (simple, subdomain, trim, empty/whitespace, no-@/no-dot/leading-@/trailing-@, internationalised local-part, **security boundary: rejects `,;<>` and whitespace** — would smuggle a second recipient via parse→validate flow), `invalidAddressList` (mixed list filter, order preservation). **33.33%/100%/16.66%/33.33% → 100%/100%/100%/100%** (file no longer appears in v8 coverage report — fully covered).

**Nizsi priorita (nice-to-have):**
8. ~~**Issue templates**~~ — **HOTOVO 2026-06-02 R5e**. `.github/ISSUE_TEMPLATE/bug.yml` (verze, OS, provider dropdown, repro, expected/actual, logs s redact pripomínkou, search confirmation), `feature.yml` (problem, proposal, alternatives, area dropdown), `config.yml` (`blank_issues_enabled: false` + contact_links na SECURITY policy a CONTRIBUTING.md pres `TheVoxRox/mail` URL). YAML schema validovan pres `js-yaml`.
9. **CODEOWNERS** — `.github/CODEOWNERS` (vyzaduje user GitHub handle: `* @TheVoxRox` nebo podobne).
10. **Full QRESYNC SELECT s VANISHED** — backend refactor pro IMAP cleanup latency (todo:313). Po release prioritizovat — neblokuje V0.1.0.

**Známé chovani / pitfalls pro pokracovani:**
- Bash tool falesne reportuje "exit code 0" pri BUILD FAILURE — verifikovat per surefire/failsafe XML (`tests="N" failures="0"`), neverit jen task notification status.
- `mvn verify` ma flaky bean wiring problem v IT testech (videno 1x u AccountRepositoryIT — `UnsatisfiedDependencyException` na AccountRepository). Re-run cisty. Mozna Spring context cache pollution z paralelnich tests. Pokud uvidis znovu, podivat se zda neni problem v `prepare-agent-integration` argLine.
- Spotless 4.37 Eclipse formatter prelozi ~30 souboru pri prvnim `spotless:apply` (radkove a `import` reordering). Komitovat sebrane zmeny v jednom commitu, ne mixovat s funkcni zmenou.
- `npm run knip` ma 2 known false-positive hints (`schema.d.ts`, `components/ui/**` "Remove from ignore") — knip detekuje ze ignore neni potreba, ale v praxi ignoruje generated `schema.d.ts` a Bits-UI re-export pattern v `components/ui/**`. Tyto hints ignorovat.
- Pri pridavani novych BE testu zkontrolovat `@PostConstruct` pattern: pokud test pouziva `new Service(...)` misto Spring, manualne volat post-construct metody (vzor v `AttachmentServiceTest.createService()`).
- Mockito `<T> T getArgument(int)` infer-ne T z assignment targetu — **nepotrebuje `@SuppressWarnings("unchecked")` v call site**. Hledat dalsi taková místa pri pridavani novych mocks.

### Functional/a11y test debt — VYRESENO 2026-05-31

Pred fixy: `npm run test:functional:stable` 87 pass / **17 fail**, `npm run test:a11y:stable` 51 pass / **1 fail** (vsechny pre-existing, parallel user changes nezachycene v testech).

Po fixy: **functional 104/104, a11y 52/52.** Provedene zmeny:

- **#1 Pre-i18n EN sidecar text** — `boot.functional.e2e.ts:260` aktualizovan z "E2E sidecar selhal pri startu" na "E2E sidecar failed to start" (sidecar.ts generuje EN po Frontend translation Faze 1 z 2026-05-26).
- **#2 A11y vCard toast role** — `ToastRegion.svelte` ziskal `role={tone === 'error' ? 'alert' : 'status'}` + `aria-live="off"` na visual toast div. Visual toast je nyni semanticky spravne identifikovany jako status/alert (a11y semantic + locator-friendly), `aria-live="off"` zabranuje double-announce s LiveAnnouncer (ktery zachoval persistent live regions pro reliable SR announce per komentar v LiveAnnouncer.svelte:7-10). Initial pokus pridat role primo na LiveAnnouncer regiony byl revertovan — zpusoboval strict-mode regrese v `a11y.e2e.ts:335` (`getByRole('alert')` matchoval LiveAnnouncer + form error).
- **#3 Update prompt dialog** — opraveno jako side-effect #2 + #5 (po `aria-live="off"` na visual toastu + locator filter v testech).
- **#4 Outlook/Seznam OAuth wizard** — `accounts.functional.e2e.ts:80` prepsan na OAuth wizard assertions ("Přihlášení k účtu user@outlook.com" + "Přihlásit přes Microsoft" CTA, no IMAP form). Test :100 (Seznam) updateoval `#quick-password` → `#acc-password` (id pole po refactoringu AccountForm).
- **#5 Toast strict-mode locator filter (10 testu)** — `sync.functional.e2e.ts`, `compose.functional.e2e.ts`, `contacts.functional.e2e.ts` aktualizovany na `page.getByRole('region', { name: 'Oznámení' }).getByText(...)` — visual toast region je unique vůči LiveAnnouncer. `sync.functional.e2e.ts:40` regex `/INBOX \+2/` zmenen na `/Doručené \+2/` (folder label nyni lokalizovan po i18n migraci).

**Pre-push gate status: ZELENA (re-validovano 2026-06-02 vecer R5, plny CI parita + tooling rozsireny + security tests + GitHub scaffolding + 2 nove security-boundary test suite).** 
- Backend: `mvn verify` **766+22** (+27 testu vs R3 dik MailConnectionProbeTest 10 + AccountProviderServiceTest 17), jacoco merged report + bundle threshold **70%/50%/70%** INST/BR/LINE (skutecne **80.4%/63.6%/80.0%**, +2.0/+2.2/+2.9pp vs R3), `mvn spotless:check` clean (po jednom apply na drift v AttachmentServiceTest.java), `mvn -DskipTests package` 86.9 MB jar. **AccountCredentialService** (R3): 9% line → 100% / 89% branch. **MailConnectionProbe** (R5): 11% line / 0% branch → 100% / 91.7%. **AccountProviderService** (R5): 13% line / 0% branch → 100% / 69.2%. Spotbugs 0 bugs.
- Frontend: `npm run check` 1338/0/0, `npm run lint` clean, `npm run knip` clean, `npm run check:i18n` 551 klicu match, `npm run check:translations:strict` OK (po pridani client.test.ts + errors.test.ts na whitelist), `npm audit --audit-level=high` 0 vuln.
- Frontend tests: `test:unit` **260/260** (+69 vs R4: +18 sidecar, +35 addresses, +16 i18n), `test:unit:coverage` **84.42% stmt / 82.95% branch / 76.82% func / 85.56% lines** (+14/+10/+18/+15pp vs R4 baseline). **`lib/backend/sidecar.ts`** (R5): 17%/7% → 89.62%/88.63%. **`lib/compose/addresses.ts`** (R5b): 33%/100%/17%/33% → 100%/100%/100%/100%. **`lib/i18n/index.ts`** (R5b): 54%/31%/53%/57% → 94.73%/80.76%/100%/95.91%. `test:functional:stable` **104/104**, `test:a11y:stable` **52/52**, `test:performance:stable` 1/1.
- Node verze sjednocena lokal ↔ CI: **Node 26 LTS** napric. `.github/workflows/*.yml` 8 callsites bumpnuty 22 → 26 (ci.yml 6×, vuln-scan.yml 1×, windows-signed-release.yml 1×). [.nvmrc](.nvmrc) v rootu pinuje na 26 pro nvm/fnm/volta uzivatele. [frontend/package.json](frontend/package.json) ma `engines.node = ">=26"` pro npm install warning pri divergenci. Pre-push gate ZELENA na Node 26: `npm run test:unit` 260/260, `npm run check` 1340/0/0, `npm run lint` clean, `npm run knip` clean.
- Tauri: `cargo check` + `cargo clippy -- -D warnings` clean.

**Round 4 (2026-06-02 noc R4) — kompletni audit test kvality:**
- **2 redundantni `@SuppressWarnings("unchecked")` smazany** — `MessageDownloaderTest.java:83` a `ImapFolderServiceTest.java:194`. Root cause: Mockito `<T> T getArgument(int)` se inferuje z assignment targetu (`Consumer<TransactionStatus>` resp. `StoreAction<List<FolderResponse>>`), na call site neni unchecked cast. Overeno pres `mvn test-compile -Dmaven.compiler.testCompilerArgument="-Xlint:all -Werror"` clean.
- **8 zbyvajicich `@SuppressWarnings("unchecked")` overeno** — vsechny mají skutečný unchecked cast (raw `Object` → parameterized type přes reflection, Mockito `mock(...)` raw → parameterized, `ArgumentCaptor.forClass(List.class)`). Nutne ponechat.
- **`lenient()` audit** — 12 použití v 6 souborech, všechny legitimni (`@BeforeEach` shared stubs pro testy, ktere je nepotrebuji). Komentare uz vysvetluji proc. Ponechano.
- **`verify(mock, times(1))` audit** — 5 výskytů ve 2 souborech, všechny záměrný kontrast vůči `never()` nebo `times(2)` v sousedních testech v stejné třídě. Documentation quality > de-noise. Ponechano.
- **AssertJ vs JUnit assertions** — 0 JUnit assertions v celem `backend/src/test/`, AssertJ konzistentne pouzivany napric repo. Cisto.
- **SpotBugs CT_CONSTRUCTOR_THROW v `AttachmentService`** — `cleanupStaleTempFiles()` z konstruktoru → `@PostConstruct initStaleTempCleanup()`. `AttachmentServiceTest.createService()` aktualizovan aby manualne volal post-construct (simulace Spring lifecycle).
- **Spotless formatter alignment** — `mvn spotless:apply` po nedavnem prubehu Eclipse 4.37 reformatu vsech ~30 dotcenych Java souboru na novy format. Vse passes spotless:check.
- **Final pre-push gate ZELENA:** mvn verify **739+22** (+15 z AccountCredentialServiceTest), `npm run test:unit` 191/191, `npm run lint` + `knip` clean.

**Round 3 (2026-06-02 vecer R3):**
- **`AccountCredentialServiceTest`** 15 unit testu pro security-critical crypto sluzbu (encrypt/decrypt IMAP hesla + OAuth refresh tokenu). Bundle BE coverage 76.9% → **78.4% lines**, 59.1% → **61.4% branches**.
- **`AttachmentService` SpotBugs fix** (CT_CONSTRUCTOR_THROW) — `cleanupStaleTempFiles()` presunut z konstruktoru do `@PostConstruct initStaleTempCleanup()`. Standard Spring best practice; konstruktor nemuze uniknout polorozjety objekt pri vyhozeni z file-system call.
- **[SECURITY.md](SECURITY.md)** v rootu — disclosure proces, 7-day acknowledgment, 90-day default disclosure window, seznam co je/neni vulnerability, podporovane verze (placeholder email).
- **[CONTRIBUTING.md](CONTRIBUTING.md)** v rootu — kompletni pre-push gate checklist se 2026-06-02 baseline cisly, repo layout, lokalni setup, conventional commits scope mapping, regenerable artefakty (schema, OpenAPI snapshot, 3 license inventory).
- **[.github/pull_request_template.md](.github/pull_request_template.md)** — pre-push gate checkbox + reviewer notes section.
- **CI workflow** ([.github/workflows/ci.yml](.github/workflows/ci.yml)) — pridan `npm run knip` step v lint jobu, novy `tauri` job pro `cargo check` + `cargo clippy -- -D warnings`, jacoco coverage upload zahrnuje merged report.

**Tooling pridano 2026-06-02 vecer:**
- `frontend/scripts/regen-third-party-licenses.mjs` + `npm run regen:licenses` (FE)
- `backend/scripts/regen-third-party-licenses.mjs` (Maven license-plugin wrapper) + `npm run regen:licenses:backend`
- `frontend/src-tauri/scripts/regen-third-party-licenses.mjs` (cargo metadata walker) + `npm run regen:licenses:tauri`
- `npm run regen:licenses:all` — bulk regen vsech 3 inventories pred releasem
- `frontend/knip.json` — knip config s SvelteKit/test-fixtures/scripts entries + `npm run knip`
- Backend `pom.xml` Jacoco: pridano `prepare-agent-integration` + `report-integration` + `merge` + `report-merged` + `check-merged` (bundle threshold gate)
- Vitest coverage thresholds zvyseny na 65/65/65/55 + per-file 90/85/90 pro content-sanitizer, 85/80/80 pro client.ts.

**4 dalsi nalezy opraveny:**
10. **`e2e-helpers.ts` dead block** — knip nalezl `loadBackendSession`, `seedSessionOverride`, `resolveMailFixture` exports bez calleru. Smazany vc. souvisejicich helperu (`fetchApi`, `SESSION_PATH`, `resolveMailDataDir`, `MailFixture`, `SessionPayload` etc.) — 60 radku → 5.
11. **License drift v `THIRD_PARTY_LICENSES.md`** — FE balicek `svelte-toolbelt` 0.10.6 prejmenovan MIT → MIT* (license-checker guess). BE inventory hand-curated 132 vs raw 160 (Spring transitive). Vse regenerovano programaticky.
12. **Backend coverage visibility** — pred dnesnim sweep jacoco se mereno jen unit testy a bez gate. Nyni unit + IT (merged) + bundle threshold gate, pre-push validuje na regresi BE coverage.
13. **Knip false-positives** — pred konfiguraci nasel 8 dead souboru + 1 dead dep + 44 dead exports (vetsina FP — type-contract.ts compile-time guard, test-stubs aliased pres vitest config, UI lib Bits-style re-exports). Po `knip.json` s entries pro routes/e2e/scripts/test-fixtures + `tw-animate-css` CSS import → 0 FP.

Pred prvnim pushem zbyvaji jen manualni smoke testy (IMAP timeout overit v realne aplikaci, Merge dialog smoke, 7 bodu z "Smoke v `tauri:dev` pred commitem" sekce nize).

### Functional test debt (2026-05-31) — historicky context

`npm run test:functional:stable` aktualne 87 pass / **17 fail**. Vsechny pre-existing (nesouvisi s YAGNI sweep A-D), zpusobeno parallel user changes mezi sessionami ktere nebyly zazálohovany na testy. Kategorie:

1. **Toast + ARIA live announcer dual rendering** (~10 testu): `lib/stores/toasts.ts:48-49` zámerne pushuje text do `toasts` (visual) i `politeAnnouncements`/`assertiveAnnouncements` (live region) — komentar na radku 15-23 vysvetluje proc (NVDA/JAWS reliability). Result: kazdy toast = 2 DOM uzly se stejnym textem, `expect(page.getByText('…')).toBeVisible()` v strict mode failuje s `resolved to 2 elements`. Postizene: `sync.functional.e2e.ts:30,61`, `compose.functional.e2e.ts:301,321`, `contacts.functional.e2e.ts:172,208,243,327,353,409`, `mail/toolbar.functional.e2e.ts:71,108,135`. Fix: pridat `.first()` nebo `getByRole('alert')` filter, nebo zmenit DOM tak, aby visual toast nemel viditelny text duplicate s aria-live nodem.
2. **Pre-i18n EN error fallback texty** (1 test): `boot.functional.e2e.ts:260` ceka CZ text "E2E sidecar selhal pri startu", aktualni `lib/backend/sidecar.ts:255,258` generuje EN "E2E sidecar failed to start" (per Frontend translation Faze 1 hotova 2026-05-26 — pre-i18n boot error fallbacky se prelozily do EN). Fix: aktualizovat test assertion na EN.
3. **Outlook/Seznam OAuth migrace** (2 testy): `accounts.functional.e2e.ts:80` (Outlook), `:100` (Seznam) cekaji wizard heading "Detekovano: Outlook / Office 365" a text "Pro Outlook ... potrebujete heslo aplikace" — IMAP-style assertions. Aktualni `providerPresets.ts` ma Outlook `flow: 'oauth'` (per Microsoft OAuth dokonceni 2026-05-16) — wizard ukazuje OAuth tlacitko, ne app-password warning. Fix: aktualizovat test pro OAuth wizard nebo prepnout Outlook docasne na IMAP fallback (per nedoreseny verified publisher blocker).
4. **Update prompt dialog** (1 test): `boot.functional.e2e.ts:381 'update failure ukáže toast a GitHub Releases fallback link'` ceka 'getByRole(alert).filter({ hasText: Aktualizace se nezdařila })`. Klic v cs.json existuje. Potreba detail debug — pravdepodobne dialog/alert role mismatch po refactoringu UpdateFailureDialog.svelte.

5. **A11y test: vCard export toast role mismatch** (`a11y.e2e.ts:261`): ceka `getByRole('status').filter({ hasText: 'Adresář byl exportován.' })`, ale `lib/components/ToastRegion.svelte` jen wrap s `role="region"` — jednotlive toast uzly nemaji `role="status"` (jen polite live region pro announcement, ne semantic role). Pro nevidomeho uzivatele (per `memory/feedback_a11y_screenreader.md`) je tohle skutecny a11y blokator — success toast by mel byt `role="status"` (NVDA announce automaticky), error toast `role="alert"`. Fix: revize ToastRegion.svelte tak, aby visual toast prislusne role a aria-live attributy nesly skrze visual node, ne dual rendering pres LiveAnnouncer.

**Pre-push gate status:** backend `mvn verify` 712+22 zelene, `npm run check` 0/0, `npm run check:i18n` cs+en match (553 klicu), `npm run check:translations:strict` OK, `npm audit --audit-level=high` 0 vuln, `npm run test:unit` 144/144, `npm run test:a11y:stable` **51 pass / 1 fail** (vCard toast role), `npm run test:functional:stable` **87 pass / 17 fail** (pre-existing). Doreseni Functional test debt + a11y vCard role je separatni ukol pred push.

### Audit progress (2026-05-30)

**Hotovo:**
- Backend dead-code + YAGNI cleanup, 8 fazi (6.11–6.18). Viz konec souboru pro detaily kazde faze.
- 1 production bug nalezen + opraven: IMAP timeouty (Faze 6.15) — `ImapConnectionManager` predaval `Duration` objekty do JavaMail Properties, JavaMail `PropUtil.getIntProperty` cte jen String/Integer hodnoty, takze IMAP pripojeni nemela ZADNY efektivni timeout (silent fallback na infinite).
- 1 nontriv refactor: Google/Microsoft TokenService dedupe (Faze 6.16) — ~170 radku duplicate logic centralizovano do `OAuth2TokenService` abstract class.
- `mvn verify` zelene **712 unit + 22 integration** stabilne pres vsechny faze.

**Pristi krok pri pokracovani:**
- [x] ~~**Frontend YAGNI/dead-code audit**~~ — **HOTOVO 2026-05-31**. Sweep 5 fazi (A-E): A) 4 dead-code nalezy smazany (`googleAuthStartUrl`/`startGoogleLogin` historicky pre-Microsoft-OAuth wrapper v `googleAuth.ts`, `resetClientBootDiagnosticsForTests` sibling bez E2E wireup, `flagMessages` re-export "for future use", re-export `cycleTheme`/`nextThemePreference`/`setReadingPaneMode`/`switchLanguage` v `mail/actions.ts` jako neuzitecny kompat shim). B) 57 over-exported symbolu unexportovano (10 funkci + 47 typu, vsechny jen interni state shape / options bagy / return-type aliasy bez externiho konzumenta — analogie backend Faze 6.13 single-impl interface trim). C) 48 dead i18n klicu z cs.json + en.json (z 601 → 553 klicu na jazyk; dalsich 30 dynamic-pattern klicu ponechano jako load-bearing). D) Dead config knoby `SseClientOptions.{initialBackoffMs,maxBackoffMs}` inline-novany do `INITIAL_BACKOFF_MS`/`MAX_BACKOFF_MS` konstant v SseClient (analogie backend Faze 6.12C). E) Inline 7 thin wrappers v `mail/actions.ts` (toggleMessageFlagById/Seen, deleteMessageById, bulkMarkMessagesRead/Delete/Move, moveMessageToFolder) — 17 callsites v `mailCommands.ts`, `MailToolbar.svelte`, `MessageList.svelte`, `MessageRowActionsMenu.svelte`, `MessageMoveControl.svelte` aktualizovany na primy import z `mail/mailbox.js`. `mail/actions.ts` se zmensil z 141 → 95 r. (-46), kompoziční vrstva pro message mutations zrusena (komponenty volaji `deleteMessages([id])` misto `deleteMessageById(id)` atd.), `mail/actions.ts` ponechan jen pro navigation helpers (goTo*, replyToMessage, forwardMessage, closeCurrentMessageDetail). Typ `Command.run` v `commands/shared.ts` a `ToolbarAction.run` v MailToolbar rozsireny z `Promise<void>` na `Promise<unknown>` (mailbox vraci BulkResult, ne void). `npm run check` 1243 souboru / 0 errors / 0 warnings, `npm run test:unit` 144 zelene, `test:functional:stable` 104/104, `test:a11y:stable` 52/52, strict translation lint OK. Zadny production bug nalezen (na rozdil od backend Faze 6.15 IMAP timeout).
- [ ] **Smoke v `tauri:dev` pred commitem** — overit:
  1. IMAP pripojeni nyni maji realne timeouty (60s read, 30s connect per `ImapProperties`). Drive bylo infinite. Pomaly server typu Seznam by se ted mel vracet s exception misto tichneho viseni az do GC.
  2. OAuth refresh pres Google i Microsoft funguje po dedupe v Faze 6.16 (unified log msg vsude obsahuje `scope: {grantedScope}`).
  3. Existing dev DB (zatim prazdna) se bootne bez problemu na nove V1 schema (bez `settings` tabulky).
  4. SMTP send pri novem `SmtpProperties.readTimeout` (default 10s) funguje stejne jako driv.
  5. **IMAP fetch robustness (2026-06-01):** 6/77 Seznam zprav s malformed BODYSTRUCTURE se nyni objevi v listu s envelope-only stubem + `contentError`, ne tisi zahozene. Overit zda Seznam INBOX vraci alespon vsechny zpravy s castecnym contentem v list view. Detail pri kliknuti zopakuje fetch a zobrazi `contentError`.
  6. **PATCH /accounts removed (2026-06-01):** FE vola jen PUT pro account edits — overit, ze edit accountu i s OAuth2 retrigger funguje. Smazani celeho PATCH surface bylo deklarativni cleanup, nemelo ovlivnit production code path.
  7. **Threading Phase 1 backend (2026-06-01):** V2 migrace + `thread_id` assignment na novych zpravach + silent backfill na startup. Pri prvnim startu po update by mel `mail.log` zobrazit `[SYNC] Threading backfill: assigning thread_id ...`. FE nezobrazuje threading (per design 1a) — overuje se jen z log/SSE, ze prislušne audit eventy probehly bez chyby.

- [x] ~~**Pred prvnim pushem:** dokoncit migraci ceskych komentaru a logu do anglictiny.~~ — **HOTOVO**. Backend 2026-05-25, frontend 2026-05-26 (oboji Faze 0–6 vcetne CI lint wire-up, viz sekce "Migrace komentaru a logu do anglictiny" + "...FRONTEND" nize).
- [ ] Po kompletnim lokalnim odladeni udelat prvni commit a push do `TheVoxRox/mail`.
- [ ] Po publikaci na GitHub nastavit branch protection na `main`.
- [ ] **IMAP fetch robustness** — 2026-05-21 pri prvotnim syncu Seznam INBOX (77 zprav) selhalo 6 zprav na `MessagingException: Unable to load BODYSTRUCTURE` v `MessageFetcher`. Sync pokracuje (71/77 stazeno), ale uzivatel ty zpravy nikdy neuvidi. Pridat fallback: pokud BODYSTRUCTURE selze, zkusit minimal fetch (jen ENVELOPE + HEADERS) a ulozit zpravu s `content=null` + `contentError`, at je aspon v listu. **2026-06-01: code fix hotov** — [MessageFetcher.java](backend/src/main/java/org/voxrox/mailbackend/feature/mail/service/MessageFetcher.java) ma try/catch kolem MIME walk (BODYSTRUCTURE failure → envelope-only stub s `contentError`) + samostatny try/catch kolem header reads (`getHeader("In-Reply-To")`, `Message-ID`) takze zpravy s expungeovany na server mezi batch fetch a per-message processing taky nepropadnou. 3 nove unit testy v [MessageFetcherTest.Resilience](backend/src/test/java/org/voxrox/mailbackend/feature/mail/service/MessageFetcherTest.java) — `bodystructureFailureProducesEnvelopeOnlyStubWithContentError`, `mixedBatchKeepsBothGoodAndStubbedMessages`, `headerReadFailureKeepsEnvelopeWithoutThreadingFields`. Reálné ověření proti Seznam serveru patří do manualniho smoke vyse.

---

## First Release Gate

Blokujici body pred prvnim verejnym release artefaktem:

- [x] ~~**Pred prvnim pushem:** migrace ceskych komentaru a logu do anglictiny.~~ — **HOTOVO**. Backend 2026-05-25, frontend 2026-05-26. Oboji vcetne CI lint wire-up, gate aktivni v `.github/workflows/ci.yml`: step "Translation whitelist lint" v jobu `backend` (bash skript, strict) a v jobu `check` (npm skript, strict).
- [ ] Projit cely `backend/RELEASE_CHECKLIST.md` pro konkretni release kandidat, hlavne fresh install, account flows, mail workflows, sidecar lifecycle, diagnostics a 24h long run.
- [ ] Udelat end-to-end release dry run z cisteho checkoutu: tag nebo draft release, Windows signed workflow, upload artefaktu, overeni instalatoru, `latest.json`, podpisu a rucni instalace na cistem Windows profilu.
- [ ] Otestovat fresh install, reinstall pres stejnou verzi, uninstall a jasne chovani dat v `%LOCALAPPDATA%\VoxRox\Mail`.
- [ ] Otestovat recovery scenare: sidecar kill, orphan proces, restart pocitace behem syncu, poskozena DB, obnova ze zalohy a disk full.
- [ ] Otestovat produkcni OAuth readiness: Google consent screen, scopes, loopback redirect URI s nahodnym backend portem, support/privacy URL, realny login, revoke, `requires_reauth` a re-login.
- [x] ~~Projit Tauri security konfiguraci: CSP, capabilities/permissions, shell spawn/kill pouze pro sidecar, HTTP pouze loopback, FS scope jen nutne soubory.~~ — **HOTOVO 2026-05-31** (Audit F): CSP zúženy v `tauri.conf.json` — smazany nepouzite `ws://localhost:* ws://127.0.0.1:*` z connect-src (jen SSE/HTTP), `data:` z frame-src (zadny iframe s data: URL), `blob:` z img-src (URL.createObjectURL jen v `<a href>` pro download, ne v `<img src>`). Capabilities zúzeny v `default.json` — `core:default` (9 sub-permissions) → explicit `core:path:default` + `core:window:default` (jen ty se realne pouzivaji: localDataDir/join, getCurrentWindow), `shell:default` → explicit `shell:allow-open` (clarity), `updater:default` (4 perms) → `updater:allow-check` + `updater:allow-download-and-install` (jen tyto 2 callsites). FS scope a HTTP allowlist zustaly puvodni (uz minimalni). `cargo check` zeleny, `npm run check` + `test:functional:stable` 104/104 + `test:a11y:stable` 52/52 vse zelene. Pozn.: skutecne overeni Tauri permissions je az pri `tauri:dev` runtime — pokud nejaka chybi, selze IPC call v aplikaci.
- [ ] Pripravit release proces pro prvni vydani: verze, tag, changelog, known issues, hashe artefaktu, draft vs public release a release approval.
- [ ] Dopsat koncovy privacy/legal balicek: privacy policy, security/support kontakt, popis lokalne ukladanych dat/logu/diagnostiky a uninstall/data removal postup. **2026-06-01: predbezny CZ + EN draft hotov** — [PRIVACY.md](PRIVACY.md) (CZ canonical) + [PRIVACY.en.md](PRIVACY.en.md) (EN parallel) v rootu repa pokryvaji data layout (zalozeno na OPERATIONS.md), sifrovani, sitovou komunikaci, OAuth provider revoke postupy, kompletni data removal, GDPR ramcove. Otevrene body v dokumentu samem: support email, security disclosure kontakt, Tauri updater URL, pravni review. Pred prvnim release projit s pravnikem a doplnit kontakty.
- [ ] Udelat third-party license/notice audit pro npm, Maven, Cargo a pribalene JRE runtime zavislosti. **2026-06-01: prvotni audit hotov** — vygenerovane inventare per modul: [frontend/THIRD_PARTY_LICENSES.md](frontend/THIRD_PARTY_LICENSES.md) (116 npm production deps), [backend/THIRD_PARTY_LICENSES.md](backend/THIRD_PARTY_LICENSES.md) (132 Maven compile/runtime artifacts), [frontend/src-tauri/THIRD_PARTY_LICENSES.md](frontend/src-tauri/THIRD_PARTY_LICENSES.md) (362 Cargo crates + OpenJDK note). Vse permissive (MIT/Apache/ISC/BSD/MPL-2.0 unmodified), zadny GPL/AGPL/SSPL. Pred kazdym release znova vygenerovat (`mvn license:add-third-party` + `npx license-checker --production` + `cargo metadata`). **2026-06-02 R6: bundled NOTICE.txt + SBOM hotove** — `npm run regen:notice` produkuje [frontend/src-tauri/resources/NOTICE.txt](frontend/src-tauri/resources/NOTICE.txt) (51.5 KB, vsechny 3 inventare sloucene a wired do Tauri bundle resources pro user-visible In-app/installer view); `npm run regen:sbom:all` produkuje CycloneDX 1.5 JSON SBOMs (frontend bom.json 115 components, backend/target/bom.json 132 components via `cyclonedx-maven-plugin` 2.9.1, frontend/src-tauri/bom.json 391 components via `cargo cyclonedx`) — gitignored gen-artefakty pro CVE scanner input + release artefact upload. ~~**Zbyva**: In-app About dialog (Svelte komponenta v Settings) ktera cte bundled NOTICE.txt + zobrazi v scrollable read-only viewu.~~ — **HOTOVO 2026-06-05**. [ThirdPartyNotices.svelte](frontend/src/lib/components/settings/ThirdPartyNotices.svelte) zaháknut do [AboutSettings.svelte](frontend/src/lib/components/settings/AboutSettings.svelte): disclosure tlacitko (`aria-expanded`/`aria-controls`) → on-demand cteni bundled `NOTICE.txt` pres `resolveResource('NOTICE.txt')` + `readTextFile`, zobrazeni v read-only `<textarea>` labelovanem nadpisem (`aria-labelledby`, zadny redundantni region landmark per memory). Mimo Tauri (web/E2E mock) ukaze hint „dostupne v desktop appce" misto chyby. Fs scope `$RESOURCE/NOTICE.txt` pridan do [capabilities/default.json](frontend/src-tauri/capabilities/default.json), `resolveResource` do test-stubu [tauri-path.ts](frontend/src/test-stubs/tauri-path.ts), i18n klice `settings.about.licenses.*` v cs/en (565 match). Gate: svelte-check **1345/0/0**, `npm run lint` (prettier+eslint+i18n) zelene, knip clean. **Manualni smoke v `tauri:dev` zbyva** — overit, ze `core:path:default` + novy fs scope realne nactou resource a textarea zobrazi 100 KB NOTICE (graceful fallback na `loadFailed` pokud selze).

  Pri overovani gatu opraveny 2 pre-existing slabiny (nesouvisi s About dialogem): (a) **flaky `i18n/index.test.ts`** — pod plnou paralelni zatezi obcas timeout 5 s na transform-heavy re-import modulu (`freshModule()` dela `vi.resetModules()` + dynamic import 16×, eager `import.meta.glob` obou cs/en bundlu); pridan `vi.setConfig({ testTimeout: 30000 })` s komentarem, `npm run test:unit` **277/277 zelene 2× po sobe** (predtim nedeterministicky 1–2 timeouty). (b) **`check:translations:strict`** padal na 3 untracked test souborech — whitelistovany v [translation-whitelist.txt](frontend/docs/translation-whitelist.txt) s odůvodněním: `addresses.test.ts` (IDN `café@münchen.de`), `accountLabel.test.ts` (non-ASCII free-text jmeno fixture), `mail/message-detail-actions.functional.e2e.ts` (e2e proti CZ UI). Strict translation 0 non-whitelisted.

  **Git hooky pridany** (prevence driftu, ktery vyse popsane nalezy zpusobil — projekt ma 0 commitu + zadny aktivni hook, takze gate dosud bezel jen rucne na konci session): tracked [.githooks/](.githooks/) (`pre-commit` = prettier+check:i18n+check:translations:strict jen kdyz commit sahne na `frontend/`; `pre-push` = lint+check+translations:strict+knip+test:unit). Zadna npm zavislost. **Aktivace per clone** (core.hooksPath je lokalni config, neprenasi se klonem): `git config core.hooksPath .githooks` (uz nastaveno na tomto stroji, hooky v indexu jako 100755). Backend `mvn verify` / Playwright e2e / Tauri cargo zamerne mimo hook (prilis pomale) — zustavaji na CI / rucni gate. Dokumentace v [CONTRIBUTING.md](CONTRIBUTING.md) sekce „Git hooks". Failure path overen (un-whitelisted CZ → exit 1, commit blokovan).

---

## Dev Spousteni

Spravny desktop dev beh:

```powershell
cd C:\dev\java\mail\frontend
npm run tauri:dev
```

Po zmene backendu:

```powershell
cd C:\dev\java\mail\backend
.\scripts\package-sidecar-windows.ps1 -SkipTests

cd C:\dev\java\mail\frontend
npm run sidecar:sync:windows
npm run tauri:dev
```

Hotovo (detaily v gitu): boot/readiness UX (BootState orchestrator, `/api/v1/system/readiness` endpoint, fazi-aware UI s eskalaci a recovery, boot timing metriky, frontend/backend testy pomaleho startu); release `app.exe` cold/warm mereni 2026-05-08 (cold 5456 ms, warm 5136 ms; report `frontend/target/tauri-release-startup-2026-05-08T13-44-35-367Z.json`); SpringDoc vypnuty v produkcnim sidecaru.

---

## Release A Update

- [ ] Vygenerovat Tauri Ed25519 signing key.
- [ ] Ulozit private signing key do bezpecneho offline uloziste a GitHub secrets podle release workflow.
- [ ] Overit, ze GitHub release obsahuje podepsany `voxrox-mail-<version>-x64-setup.exe`, `.sig` a `latest.json` na URL, kterou ceka Tauri updater.
- [ ] Otestovat prvni update pres Tauri updater z publikovaneho artefaktu.
- [x] Nastavit code signing pipeline pro Windows release.
- [ ] Smoke test vN-1 -> vN: update bez ztraty dat, bez GUI regrese a s citelnym recovery postupem.

---

## Bezpecnost

- [ ] Rotovat Google OAuth credentials v Google Cloud Console.
- [ ] Vygenerovat novy `MAIL_CRYPTO_KEY` a `MAIL_CRYPTO_SALT`.
- [ ] Pred prvnim pushem udelat secret scan cele git historie a aktualniho workspace. **2026-06-01: pre-commit workspace scan hotov** — 21 staged + 559 untracked-non-gitignored souboru, 0 high-confidence secret matches (AKIA/ghp/GPG/EC/RSA/sk-/xox patterns), 0 hardcoded credential assignments v config souborech, vsechny CI workflow secrets ridi pres `${{ secrets.X }}`. Sentinel `mail-local-{google,microsoft}-client-secret` v [application.properties](backend/src/main/resources/application.properties) jsou env-var fallback placeholdery, ne realne credentials. Doplneno `.gitignore` o `*.jks`, `*.ed25519`, `*.patch`, `_tmp_*` — eliminuje 3 nalezene workspace artefakty: `.tmp-diff.patch`, `tmp-diff-copy.patch`, `backend/scripts/_tmp_acct.mjs`. Zbyva: rotace Google + Microsoft OAuth credentials v `backend/.env` (per polozka vyse + [SECURITY_RELEASE_CHECK.md:56](backend/SECURITY_RELEASE_CHECK.md)), a po prvnim pushi spustit i git-history scan (`gitleaks`/`trufflehog`) jako defense-in-depth, ackoli neexistuje predchozi historie tam scanovat.
- [ ] Spustit vulnerability scan nad backend SBOM / Maven dependency tree s aktualni CVE databazi.
- [ ] Rozhodnout a nastavit release/CI vulnerability gate (napr. OWASP Dependency-Check v CI s NVD API key). **2026-06-01: CodeQL drilled-in jako prvni vrstva** — [.github/workflows/codeql.yml](.github/workflows/codeql.yml) pokryva Java backend + JS/TS frontend, query suite `security-and-quality`, push/PR/weekly cron. OWASP Dependency-Check je oddeleny ukol (CVE databaze pres NVD vs SAST pres CodeQL), zustava na todo. SpotBugs nyni bezi jako soucast `mvn verify` po pridanim plugin executions binding v [backend/pom.xml](backend/pom.xml) — lokalni gate je tim zarovnany s tim, co CI vynucuje.
- [ ] Pred releasem zkontrolovat podpisy, updater manifest a recovery postup v `OPERATIONS.md`.
- [ ] Po publikaci repa zapnout GitHub secret scanning, Dependabot alerts a pripadne CodeQL. **2026-06-01: CodeQL workflow uz commitnut** v [.github/workflows/codeql.yml](.github/workflows/codeql.yml), spusti se automaticky pri prvnim push. Zustava: rucne aktivovat secret scanning + Dependabot alerts v Settings → Security po publikaci.

---

## Produktove Funkce

- [ ] iCloud OAuth.
- [ ] Threading / konverzace — Phase 1 backend hotov 2026-06-01, V0.1.0 UI zustava bez konverzaci. **Phase 1 implementovan**: V2 Flyway migrace (`thread_id` / `thread_root_message_id` / `thread_position` na `messages` + 2 indexy), [ThreadingService](backend/src/main/java/org/voxrox/mailbackend/feature/mail/service/ThreadingService.java) (JWZ-light, per-account, 12 golden testu), [ThreadingBackfillService](backend/src/main/java/org/voxrox/mailbackend/feature/mail/service/ThreadingBackfillService.java) (silent on startup), `GET /api/v1/messages/account/{accountId}/threads/{threadId}` endpoint (3 testy), `thread_updated` SSE event v `oneOf` SSE schema, hidden `POST /api/internal/threading/recompute` pro QA, frontend typy `ThreadResponse` + `ThreadUpdated` exportovany s contract guardem. UI consumer v V0.1.0 neni (per design decision 1a). Otevrene pro Phase 2 (V0.2): UI grouping toggle, thread row aria-tree, bulk akce, a11y test pass. Detail v [backend/docs/THREADING_DESIGN.md](backend/docs/THREADING_DESIGN.md).
  - **Audit 2026-06-06 — sync/read jadro (MailSyncService/MessageDownloader/FlagSyncService/MailboxMaintenanceService/MailFacade) oznaceno jako release-ready, viz [[project_imap_sync_audit]].** Nalezy v threading reconciliation (bez V0.1.0 dopadu — threading nema UI konzumenta):
    - [x] ~~`thread_position` kolize po merge~~ — **HOTOVO 2026-06-06**. [ThreadingService.reconcileLateArrivingParent](backend/src/main/java/org/voxrox/mailbackend/feature/mail/service/ThreadingService.java) po `reassignThreads` neprepocitaval `thread_position` → pozde dorazivsi parent (root, pos=1) kolidoval s orphan detmi (taky pos=1) a `getThread` (ORDER BY thread_position ASC, id ASC) vracel root mimo poradi (deti maji nizsi id). Pridan `renumberThreadPositions(accountId, threadId)` — po merge prepocita cely thread podle `(receivedAt, id)`. +1 test (`ThreadingServiceTest` 12 → 13), spotless clean.
    - [x] ~~**Genuine late-parent reconciliation realne nikdy nevystrelila**~~ — **HOTOVO 2026-06-06**. `findOrphanThreadIdsForRoot` hledala orphany jen pres `threadRootMessageId = :selfMessageId`, ale orphan dite ma ze `startNewThread` root = *vlastni* messageId, ne rodicovo → merge fungoval jen pro cross-folder dupy, NE pro „reply dorazil driv nez original" (dokumentovany ucel kroku 4). Fix: dotaz prejmenovan na `findMergeableOrphanThreadIds` a rozsiren na `(threadRootMessageId = :messageId OR inReplyTo = :messageId)`; novy index `idx_messages_account_in_reply_to` ([V3__add_in_reply_to_index.sql](backend/src/main/resources/db/migration/V3__add_in_reply_to_index.sql) + `@Index` na entite) drzi lookup O(log n) na bulk syncu. `reassignThreads` nyni re-rootuje na `msg.getThreadRootMessageId()` (pravy koren threadu, ne vlastni id late parenta — Case B). **Bug byl v `@Query` SQL, ktery mockovane unit testy nemohly chytit** → pridan [MessageRepositoryIT](backend/src/test/java/org/voxrox/mailbackend/feature/mail/repository/MessageRepositoryIT.java) (3 testy proti realnemu SQLite, vc. regresniho In-Reply-To matche) + 2 unit testy (Case B re-root, position renumber). Gate: `mvn verify` **768 unit + 25 IT zelene**, spotless clean.
    - [ ] **Zbyva Phase 2 (nice-to-have):** References-only orphan (dite linkuje parenta jen pres `References`, bez `In-Reply-To`) se zpetne nereconciluje — token match v free-text `references` je neindexovatelny (O(n^2) na bulk syncu), vyzadoval by normalizovanou junction tabulku. Pozicni schema je navic arrival-order napric (i `attachToExistingThread` dela `maxPos+1`, ne striktne `receivedAt`), takze „ascending by receivedAt" v THREADING_DESIGN.md je aspiracni — pri Phase 2 tree view zvazit striktni receivedAt poradi.
- [ ] Filtrovani / pravidla pro automaticke trideni zprav.

---

## Notifikace o vysledku odeslani e-mailu (HOTOVO 2026-05-28)

Implementovano a overeno: backend `mvn verify` 704 unit + 22 integration zelene (vc. broadcast asercí v SmtpMessageServiceTest a sendId v MailWrite/DraftControllerTest), frontend `npm run check` 0 chyb, `npm run test:unit` 144 zelene, dva nove compose e2e testy (pending -> send_completed / send_failed) zelene. Zbyva jen volitelny manualni smoke v `tauri:dev` (skutecny SMTP fail flow).

Cil: uzivatel se po odeslani dozvi REALNY vysledek. Dnes frontend ukaze optimisticky toast "Odeslano" hned po 202 a odnaviguje; skutecne SMTP odeslani bezi async a pri selhani se zapise jen do `account.lastError` — uzivatel se to aktivne nedozvi. UX rozhodnuti (potvrzeno uzivatelem): pravdivy stav "Odesila se..." -> "Odeslano" / "Odeslani selhalo".

Architektura (best-practice): korelace pres server-generovany `sendId` (UUID v tele 202), vysledek se posle pres existujici SSE kanal jako event `send_completed` / `send_failed`. Frontend drzi `Map<sendId, toastId>` rozpracovanych odeslani ve store (compose se po odeslani odmountuje, pending stav proto nesmi byt v komponente). SSE event nese jen `sendId` + vysledek + `errorCode` (z `AccountLastErrorCode`); prijemce/predmet zna frontend z pending mapy (zadne zdvojene PII pres drat). Trvanlivy `account.lastError` zustava jako defense-in-depth pro pripad odpojeneho SSE.

Backend:

- [x] Sealed interface `SseEvent { String type(); }`, implementuji `SyncNotification` i novy `SendNotification` record; `SseNotificationService.broadcast` zobecnen na `broadcast(SseEvent)`.
- [x] Novy record `SendNotification(type, sendId, accountId, errorCode)` + `SendAcceptedResponse(sendId)`.
- [x] `MailWriteController.sendEmail`: generuje `sendId`, vraci v tele 202, protahuje do `sendEmailAsync`.
- [x] `DraftController` send endpoint: stejne (sendId -> `sendDraftAsync`).
- [x] `SmtpMessageService.sendEmailAsync` + `sendDraftAsync`: inject `SseNotificationService`, broadcast `send_completed` po uspechu a `send_failed` (s errorCode) ve vsech catch/early-exit vetvich.
- [x] OpenAPI snapshot regenerovan.

Frontend:

- [x] `mailWrite.ts` `sendMail` + `drafts.ts` `sendDraft` vraci `{ sendId }`; API typy (`schema.d.ts`) regenerovany, `SendAcceptedResponse` v `generated.ts`.
- [x] SSE klient: `handleChunk` propousti `send_completed`/`send_failed` (whitelist event typu); Listener typ rozsiren na `StreamNotification`.
- [x] `types.ts`: pridan `SendNotification` + `StreamNotification` + `SendAcceptedResponse`.
- [x] `notifications` store: pending mapa `Map<sendId,…>` s fallback timeoutem, `registerPendingSend()`, handler na vysledne eventy (dismiss pending toast + outcome toast + nativni notifikace u selhani).
- [x] `ComposeForm.handleSend`: misto optimistickeho success toastu vola `registerPendingSend()` po resolveu sendMail/sendDraft.
- [x] i18n klice `toast.sendPending/sendCompleted/sendFailed` do cs.json + en.json; nepouzity `compose.sent` odstranen.

Testy:

- [x] `SmtpMessageServiceTest` (broadcast pri uspechu/selhani), `MailWriteControllerTest` + `DraftControllerTest` (202 vraci sendId), MSW handlery (`acceptedSend` + draft-send routing), SSE bridge `pushSendCompleted/Failed`, dva `compose.functional.e2e.ts` testy (pending -> vysledek). Vse zelene.

---

## Microsoft OAuth (blokuje release)

Stav: backend i frontend hotove a end-to-end overene (MSA + vlastni tenant, smoke 2026-05-20). Backend: `MicrosoftTokenService`, `MicrosoftClaimsExtractor`, Azure registration `microsoft` v `application.properties`, seed `outlook.com, hotmail.com, live.com, msn.com` v `V1__init.sql` s `supports_oauth2=1`. Frontend: `outlook` preset je `flow: 'oauth'` (prepnuto 2026-05-16), wizard nabidne Microsoft tlacitko. **Outlook jede VYHRADNE pres OAuth — IMAP+heslo neni alternativa, Microsoft ho pro osobni ucty outlook.com/hotmail.com/live.com/msn.com vypnul 16.9.2024 (viz rozhodnuti nize).** Jediny zbyvajici blocker pro produkci je verified publisher (cizi/enterprise ucty), viz nize.

### Azure App Registration - kontext

- **Owner / login:** **Preregistrovano 2026-05-20 na `info@voxrox.org`** (drive byl owner osobni MSA ucet vlastnika). Aktualni Client ID nize odpovida nove registraci pod info@voxrox.org v tenantu `Default Directory (infovoxrox.onmicrosoft.com)`. Historicky kontext zustava nize jako audit trail — drive uvedene `47e9a2b0-...` Client ID a tenant `a13fb107-...` patrily stare registraci a uz se nepouzivaji.
- **Pred preregistraci (historicky, NEAKTIVNI):** registrace byla pod osobnim MSA uctem vlastnika, tenant `Default Directory` ID `a13fb107-ae27-4571-bade-1e3d57b2a28c`, Client ID `47e9a2b0-bc26-4df6-b01d-12486cb4116e`. Microsoft puvodne blokoval vytvareni App Registration mimo Entra tenant, proto byl Azure free zaregistrovan na osobni MSA. Preregistrace pod `info@voxrox.org` probehla po prevodu na Entra-managed identitu.
- **App display name:** `VoxRox Mail` (zobrazi se na consent screenu).
- **Tenant ID (aktivni):** `a0e3ded3-4a52-4bfb-b2fe-7ef2860762b2` (`Default Directory` / `infovoxrox.onmicrosoft.com`). Backend pouziva `common`, takze tenant ID v configu neni.
- **Application (client) ID (aktivni):** `bb630b4b-8bb3-4198-b969-7fbb555ae33d` — z Prehled stranky Azure App registration. Soulad s `MICROSOFT_OAUTH_CLIENT_ID` v `backend/.env` overen 2026-05-20. **POZOR:** drive zde uvedene `580b25a1-1645-4672-98bb-a097f42c52d7` NEBYL Client ID, ale Secret ID client secretu (zamena v Azure UI, viz nize "Reseny bug").
- **Client secret:** vygenerovan 2026-05-16, **exspirace 2028-05-18** (730 dni). Popis v Azure: `VoxRox Mail dev`. Secret ID (interni identifikator secretu, ne aplikace): `580b25a1-1645-4672-98bb-a097f42c52d7`. Hodnota patri vyhradne do `backend/.env` (NIKDY do gitu). Rotace nejpozdeji **2028-04-18** (30 dni pred exspiraci) - viz polozka v "Otevrene blokery a ukoly" nize.
- **Account types:** multitenant + personal MSA (souhlasi s tenantem `common` v backendu).
- **Redirect URI:** Web platform, `http://localhost/login/oauth2/code/microsoft`. Microsoft pro `localhost` ignoruje port, takze nahodny backend port projde. **Backend musi v `session.json` mit `baseUrl` s hostem `localhost`** - Spring Security z toho cte `{baseUrl}` pro callback. Fix: `HandshakeService.onApplicationReady()` od 2026-05-17 generuje `http://localhost:<port>/api` (drive `127.0.0.1`, Microsoft ho odmital s `invalid_request` protoze 127.0.0.1 NENI alias localhost pro Web platform).
- **Portal:** `https://portal.azure.com` → vyhledat `App registrations` → `VoxRox Mail`.

### Otevrene blokery a ukoly

- [ ] **Verified publisher pres MPN ID** - blokuje produkcni consumer OAuth. Microsoft od 11/2020 nedovoluje externim uzivatelum (vcetne `@outlook.com`/`@hotmail.com`) udelit consent na multitenant aplikaci bez verified publisher. Pro dev test s osobnim MSA uctem vlastnika to projde (vlastnik tenantu), ale realni uzivatele dostanou „publisher not verified". MPN registrace je zdarma, vyzaduje firemni udaje a tax ID, schvalovani trva dny. **Priorita zvysena 2026-05-20** — viz reprodukce AADSTS700016 nize.
- [ ] **2026-05-20 — Reprodukovan AADSTS700016 v enterprise tenantu**. Pri pokusu o prihlaseni uctem z ciziho enterprise tenantu Microsoft vratil `AADSTS700016: Application … was not found in the directory '<organizace>'`. Konfigurace overena: Client ID `bb630b4b-8bb3-4198-b969-7fbb555ae33d` v `backend/.env` souhlasi s Azure registraci pod `info@voxrox.org`, Account types = Multitenant + personal MSA, redirect URI `http://localhost/login/oauth2/code/microsoft` neporusena (Spring redirect_uri match). Root cause neni client-side bug — cizi tenant (admin policy) blokuje lazy-provisioning Service Principalu pro neoveroveho publishera. Microsoft tuhle situaci hlasi pres 700016 misto consent screenu, protoze user-consent je v tenantu zakazany. **Cesta vpred:** verified publisher (viz bod vyse). **Mezitim:** pro smoke pouzivat jen MSA (`@outlook.com`/`@hotmail.com`/`@live.com`) nebo vlastni tenant ucty (`info@voxrox.org`).
- [x] ~~**2026-05-20 — `unauthorized_client` na consent screenu**~~ — **HOTOVO 2026-05-20**. Microsoft odmital authorize request s "Vasemu pozadavku nelze vyhovet" + `unauthorized_client` jeste pred consent screenem. Root cause: `MICROSOFT_OAUTH_CLIENT_ID` v `backend/.env` mel hodnotu `580b25a1-1645-4672-98bb-a097f42c52d7`, coz je **Tajne ID (Secret ID)** vygenerovaneho client secretu, NE Application (client) ID aplikace. V Azure portalu na strance `Certifikaty a tajne kody` ma tabulka sloupce "Hodnota" (skutecny secret) a "Tajne ID" (identifikator zaznamu) — pri kopirovani doslo k zamene. Fix: prepsat Client ID v `.env` na `bb630b4b-8bb3-4198-b969-7fbb555ae33d` z Prehled stranky aplikace. Client secret value byla OK a zustala beze zmeny. Lesson learned dopisana do `memory/project_microsoft_oauth_azure.md`.
- [x] ~~**2026-05-20 — Multi-account browser session vyber spatny ucet**~~ — **HOTOVO 2026-05-20**. Po fixu `unauthorized_client` flow proseu, ale Microsoft pouzil aktivni browser session (`info@voxrox.org` z Azure portalu) misto uctu zadaneho ve wizardu (osobni MSA ucet vlastnika). Backend pak hlasil `PROVIDER_NOT_FOUND` pro doménu `voxrox.org` (neni v seedu). Root cause: `authorization-uri` v `application.properties` mel `?prompt=consent`, ktery vynuti consent screen ale NE account picker — Microsoft pouzije aktivni session bez ohledu na to. Fix: `prompt=consent` → `prompt=select_account` v `application.properties` + aktualizovan Javadoc v `MicrosoftClaimsExtractor`. `offline_access` scope zustava a refresh token Microsoft vraci nezavisle na `prompt` hodnote. End-to-end smoke z `tauri:dev` s osobnim MSA uctem vlastnika proseu uspesne 2026-05-20.
- [x] ~~**Lepsi UX pri OAuth chybe (any provider)**~~ - **HOTOVO 2026-05-20**. Spring default `/login?error` zobrazoval matouci "Invalid credentials" stranku (designovanou pro form-login), takze uzivatel u OAuth chyby nemohl odlisit `state expired` od `wrong password`. Pridan custom `oauth2FailureHandler` v `SecurityConfig` — loguje skutecnou chybu na WARN (`errorCode`, `description`, `exceptionType`) a presmerovava na novou stranku `backend/src/main/resources/static/auth-failed.html`. Hint pro uzivatele dvojjazycne, reason kod v `?reason=...` zobrazen v rožku stranky pro diagnostiku. Pokryto 3× unit testem v `SecurityConfigTest`. `auth-finished.html` + `auth-failed.html` pridany do `PUBLIC_ENDPOINTS` jako defense-in-depth.
- [x] ~~**Riziko `127.0.0.1` vs `localhost` v redirect URI**~~ - **POTVRZENO + FIXNUTO 2026-05-17**. Tauri sidecar generoval `session.json` s `baseUrl=http://127.0.0.1:<port>/api`, Spring Security z toho sestavil `redirect_uri=http://127.0.0.1:<port>/login/oauth2/code/microsoft`, Microsoft odmital s `invalid_request` (Azure ma registrovany jen `http://localhost/...`). Prvni pokus o fix (zmenit baseUrl na localhost v HandshakeService) odhalil druhotny problem: Windows `localhost` resolvuje na IPv6 `::1`, Tomcat poslouchá jen na IPv4 (`server.address=127.0.0.1`), Tauri plugin-http nemá spolehlivy IPv6 → IPv4 fallback a frontend visel na readiness checku. Finalni fix: `session.baseUrl` zustava 127.0.0.1 (Tauri webview pouziva IPv4 deterministicky), `frontend/src/lib/api/googleAuth.ts:oauthAuthStartUrl()` prepisuje hostname na `localhost` pro OAuth start URL - externi prohlizec ma Happy Eyeballs IPv6 → IPv4 fallback, Spring uvidi Host=localhost a poskladá redirect_uri s localhost. Backend `HandshakeService` a testy revertnuty na 127.0.0.1. Folow-up bug: stary `mail.sessionOverride` v dev WebView2 localStorage prebijal cerstvy session.json po rebuildu - vyresheno refactoringem na in-memory `window.__MAIL_TEST_SESSION__` gated by `VITE_E2E_MOCK` build flag + novy `npm run tauri:dev:fresh` skript pro hard reset dev cache.
- [ ] **Best-practice migrace na Public client (RFC 8252)** - pred produkcnim release prepnout Azure platformu z Web na "Mobile and desktop applications", pridat loopback URI `http://localhost` (pripadne i `http://127.0.0.1` pro RFC 8252 soulad), v `application.properties` zmenit `microsoft.client-authentication-method` z `client_secret_post` na `none`, smazat `MICROSOFT_OAUTH_CLIENT_SECRET` z `.env` a z release pipeline. Duvod: Public client + PKCE nepotrebuje client_secret, ktery by stejne musel jit s desktop binarkou ven a je trivialne extrahovatelny. Microsoft pro Public clients PKCE preferuje; PKCE je od 2026-06-07 explicitne zapnut i pro soucasny confidential client pres `OAuth2AuthorizationRequestCustomizers.withPkce()` v [SecurityConfig](backend/src/main/java/org/voxrox/mailbackend/core/config/SecurityConfig.java) (drive NEbyl — Spring confidential klientum PKCE defaultne neposila, presto to todo/komentare tvrdily; opraveno), takze `code_challenge_method=S256` se posila uz ted a po prechodu na public client zustane spravne. Pokryto `SecurityConfigTest.authorizationRequestIncludesPkceForConfidentialClient`.
- [ ] **Rotace client secret do 2028-04-15** (exspirace 2028-05-15, popis `VoxRox Mail dev`). Vygenerovat novy v Azure → Certifikaty a tajne kody → Nove, aktualizovat `backend/.env` na vsech instalacich, smazat stary az po overeni, ze novy funguje.
- [x] ~~Rucne overit backend OAuth flow s osobnim MSA uctem~~ - **HOTOVO 2026-05-16**. Flow `/oauth2/authorization/microsoft` projde end-to-end, ID token validovan, claims (oid, email, name, preferred_username) vyextrahovany, novy Outlook ucet vytvoren v DB. Tenant `9188040d-6c67-4c5b-b112-36a304b66dad` = oficialni personal MSA tenant. Pri smoke odhalena konfiguracni chyba: `user-info-uri=https://graph.microsoft.com/oidc/userinfo` musel byt odstranen, protoze Microsoft v2 vraci access_token pro jediny resource (outlook.office.com, opaque, ne JWT) a Graph ho odmita s `IDX14100`. Spring si user info udela z id_token, ktery prijde paralelne. Fix commitnut v `application.properties`.
- [x] ~~Overit refresh token roundtrip~~ - **HOTOVO 2026-05-16**. Prvni IMAP sync osobniho MSA uctu automaticky pres `MailSyncScheduler` projel uspesne. `MicrosoftTokenService` obnovil access_token (platnost 3599s), `ImapConnectionManager` se pripojil k `outlook.office365.com:993` pres OAuth2/XOAUTH2 SASL, stahla se realna data (Inbox 4 zpravy, Junk 6 zprav).
- [ ] Otestovat SMTP send pres OAuth - vyzaduje frontend wizard nebo manualni curl proti `/api/v1/messages/send` endpointu. Backend kod existuje a `SMTP.Send` scope je v tokenu, ale prakticky overit az pri frontend smoke.
- [x] ~~`frontend/src/lib/accounts/providerPresets.ts` - prepnout Outlook preset na `flow: 'oauth'`~~ - **HOTOVO 2026-05-16**.
- [x] ~~`frontend/src/routes/settings/accounts/new/+page.svelte` - parametrizovat OAuth tlacitko a popis~~ - **HOTOVO 2026-05-16**. Pouzity nested klice `accounts.wizard.${preset.key}.description` a `loginButton`.
- [x] ~~i18n klice pro Microsoft~~ - **HOTOVO 2026-05-16**. Flat `accounts.loginWithGoogle` migrovan do `wizard.gmail.loginButton`, doplnen `wizard.outlook.{description,loginButton}` v cs/en. `npm run check:i18n` + `npm run check` zelene.
- [ ] Smoke v `tauri:dev`: zadat `@outlook.com`, `@hotmail.com`, `@live.com` ve wizardu, overit ze se nabidne Microsoft OAuth tlacitko, projit cely flow vcetne callbacku, nasledny mail send/receive a re-login po revoku. **POUZIT JEN MSA ucty nebo `info@voxrox.org` — cizi enterprise tenanty padnou na AADSTS700016 do doby, nez bude verified publisher.** Pokud OAuth flow selze, server zaloguje konkretni errorCode na WARN do `mail.log` a uzivatel vidi `auth-failed.html` s reason kodem (oboje pridano 2026-05-20).
- [x] ~~Doplnit Microsoft OAuth body do `backend/RELEASE_CHECKLIST.md` paralelne s Google OAuth oddilem (vcetne verified publisher checkpointu).~~ — **HOTOVO 2026-05-20**. Pridana podsekce `### Microsoft OAuth (Outlook / Exchange Online)` v sekci 4 (Account flows) s prerekvizitami (Application ID vs Secret ID guard, Supported account types, Platform/Redirect URI, secret rotace, verified publisher blocker) a smoke flow (account picker, prvni sync, SMTP send, refresh token, revoke + re-login).
- [x] ~~Rozhodnout zaverem: bud Microsoft OAuth dotahnout do prvniho release (vc. verified publisher), nebo docasne shodit `supports_oauth2=1` u Outlook seedu a nechat jen IMAP rezim.~~ — **ROZHODNUTO 2026-06-07: Outlook jede VYHRADNE pres OAuth.** IMAP+heslo fallback uz neexistuje — Microsoft k 16.9.2024 vypnul basic auth (a app passwords) pro osobni ucty outlook.com/hotmail.com/live.com/msn.com v ne-MS klientech, takze "shodit na IMAP rezim" = rozbity Outlook, ne degradace. Realna volba byla jen OAuth, nebo Outlook z wizardu uplne odebrat — zvolen OAuth. Kod uz tomu odpovida (preset `flow: 'oauth'`, seed `supports_oauth2=1`), zadna zmena netreba. Zbyva jen verified publisher (viz blocker vyse) — bez nej OAuth funguje jen pro testovaci MSA/vlastni-tenant ucty.

---

## Vicejazycny Backend

- [x] Frontend API klient posila `Accept-Language` podle aktualniho jazyka aplikace.
- [x] Backend validacni hlasky pouzivaji message keys a jsou prelozene v `messages_cs/en.properties`.
- [x] User-facing backend texty mimo vyjimky a account `lastError` jsou projite a prelozitelne.
- [x] DB drzi strukturální error `code + args` a fallback text jen pro diagnostiku / starsi klienty.
- [x] Testy pokryvaji `Accept-Language: cs` a `Accept-Language: en` pro klicove chyby, validation a account `lastError`.

---

## ContactList Vylepseni

Hotovo (detaily v gitu): Vlna 1 (a11y/SR pattern), Vlna 2 (UX polish), Vlna 3 vCard drag-and-drop import, sort + label filter UI s URL persistenci, Merge duplicit (backend endpoint, dialog, A11y), modal editace kontaktu misto inline rozsireni radku.

Otevrene:

- [ ] Merge: manualni smoke v `tauri:dev` pred prvnim commitem (vybrat 2+ kontakty, otevrit dialog, overit SR ohlaseni preview a warning pri >10 emailech).

---

## Startup A Komunikace FE-BE (audit 2026-05-17)

Cilem je zkratit cold start, odstranit redundantni polling fazi a snizit dobu, kdy uzivatel kouka na blank/text spinner.

Hotovo v gitu jako serie zmen 2026-05-17:

- [x] Sloucit `/handshake` do `/system/readiness` (`SystemReadinessResponse` ma nyni `appName`; `verifyBackendCompatibility` polling smycka smazana, `api/handshake.ts` zruseno, kompatibilitni a session-drift kontroly se delaji uvnitr `waitForBackendReadiness`). Backendovy `/api/handshake` endpoint zachovan pro pripadnou diagnostiku.
- [x] Paralelizovat `loadClientConfig()` a `loadAccounts()` v `bootstrap.ts` pres `Promise.all`.
- [x] Globalni timeout 60 s na cely bootstrap (`BootTimeoutError`, AbortController), E2E override pres `mail.e2e.bootTimeoutMs`.
- [x] Shell-first rendering: pri loading state se vykresluje AppRail placeholder + sidebar placeholder + loading text v `<main>`, ne uz jen vycentrovany text na blank screen.
- [x] JVM args v `backend/scripts/package-sidecar-windows.ps1`: `-XX:TieredStopAtLevel=1`, `-Xms64m`, `-Xmx384m`, `-XX:+UseSerialGC`, `-Dspring.aot.enabled=true`.
- [x] Spring AOT zapnut pres Maven profile `aot` (`mvn -Paot package`), package script ho ma aktivni.
- [x] Pre-migration backup v `DatabaseConfig.preMigrationBackupStrategy` bezi jen pri `flyway.info().pending().length > 0`.
- [x] Springdoc vylouceny z fat jaru pres `spring-boot-maven-plugin` excludes; `OpenApiConfig` chraneny `@ConditionalOnClass`; profile `openapi` umoznuje fat jar s docs.
- [x] RAM cache pro `/api/v1/client-config` — implicitne uz existuje pres `clientConfigState` writable store; `loadClientConfig()` se vola jen z bootstrapu, re-navigace cte snapshot synchronne.

Otevrene (follow-up po smoke + mereni):

- [ ] **Smerit** cold start pred/po na PERFORMANCE_BASELINE.md. Konkretne sekce
      "Startup audit 2026-05-17 - before/after" tam ma tabulky pred/po a
      decision gate pro AppCDS.
- [ ] Manualni smoke checklist v `tauri:dev` (rychly check) a `tauri:build`
      (uplny check):
      - Cisty profil (`Remove-Item %LOCALAPPDATA%\VoxRox\Mail`) → spustit.
      - Tauri okno se zobrazi do 500 ms, NEsmi byt blank screen — musi byt
        videt AppRail placeholder + sidebar placeholder (sedonove obdelnicky).
      - Mereni boot timings: dev console → `console.info('boot', $bootState.timings)`.
      - Backend log `logs/mail.log`: hledat `[BOOT] Startup timing: phase=
        spring.application-ready durationMs=...` (cilem -20-40 % vs pred AOT).
      - **AOT trash check**: `IllegalStateException` z chybejicich AOT hints
        v logu pri startu — pokud se objevi, nahlas konkretni bean a vypni AOT
        pres `mvn package` (bez `-Paot`).
      - **Pre-migration backup**: pri opakovanem startu nesmi vznikat novy
        soubor `db/mail.db.backup-pre-v*` (DB beze zmen, zadne pending).
      - **Sidecar failure recovery**: nastavit
        `localStorage.setItem('mail.e2e.sidecarFailure', 'once')` (po E2E test
        mode), restart aplikace → musi zobrazit chybu **do 60 s**, ne po
        ~90 s viseni v pollingu.
      - **Springdoc kontrola**: `jar tf
        backend/target/mail-backend-0.1.0.jar | Select-String springdoc` musi
        byt prazdne (pokud build byl bez `-Popenapi`).
- [x] ~~**JEP 483 AOT cache**~~ — **HOTOVO 2026-05-19**. Self-injection unblocker
      (`MessageContentPersister`, `ContactBulkService`), training run + cache
      generace (130 MB), runtime restore bez výjimek. **Reálný gain:** plain
      `java -jar` cold start 8,55 s → 5,41 s = **−36.7 %**, viz tabulka v
      [PERFORMANCE_BASELINE.md](backend/PERFORMANCE_BASELINE.md) sekce „JEP 483
      AOT cache — měření 2026-05-19". V Tauri release buildu se použije přes
      `scripts/package-sidecar-windows.ps1 -EnableAotCache` (opt-in default
      kvůli +70 s build time pro training run).

- [ ] **Tauri release smoke s AOT cache** — `npm run tauri:build:with-sidecar`
      s `$EnableAotCache = $true` v `package-sidecar-windows.ps1`. Změřit
      reálný desktop `appReady` cold start (baseline 5,1–5,5 s před touto
      sérií změn) a aktualizovat PERFORMANCE_BASELINE.md sekci „Mereni na
      strane backendu / frontendu". Smoke checklist viz tatáž sekce.

---

## Hybrid pagination Fáze 1 (HOTOVO 2026-05-30)

Implementace [MailFacade.getEmails](backend/src/main/java/org/voxrox/mailbackend/feature/mail/service/MailFacade.java) + lazy fetch + server-side total v `fetchServerCountAndEnsurePageLocally` ([MailSyncService](backend/src/main/java/org/voxrox/mailbackend/feature/mail/service/MailSyncService.java)) hotova vcetne vsech pre-commit cleanupu. `mvn verify` 715 unit + 22 integration zelene, strict translation lint OK.

- [x] **Cache server count per (account, folder)** s TTL ~60 s — [FolderCountCache](backend/src/main/java/org/voxrox/mailbackend/feature/mail/service/FolderCountCache.java) (ConcurrentHashMap + Clock-injectable TTL, default 60 s). Plni se z `performFullSyncCycle` (MailSyncService line 180) a z `fetchServerCountAndEnsurePageLocally` (line 296). `MailFacade.getEmails` fast-path: `cached.isPresent() && needed <= localCount` → instant return bez IMAP.
- [x] **Race na stale `localCount`** ve `fetchServerCountAndEnsurePageLocally` — `countByAccountIdAndFolderName` se nyni cte UVNITR `executeInFolder` lambdy (MailSyncService line 281), pod per-account zamkem. Komentar u call site vysvetluje, ze tim se eliminuje overlap s periodickym syncem.
- [x] **Catch (Exception e) v MailFacade.getEmails** zuzeno na `RuntimeException` (MailFacade line 143). Error/InterruptedException propadaji vys.
- [x] **Test duplikace stubExecuteInFolder helperu** — vytazen na outer class `MailSyncServiceTest` jako `stubExecuteInFolderRunCallback(Folder)` (line 111), vola se z obou nested trid `FetchServerCountAndEnsurePageLocally` i `LastErrorPropagation`.

---

## Audit 2026-05-19 — Otevřené body

- [ ] **NVD API key** pro `vuln-scan.yml` — bez něj poběží OWASP Dependency-Check, ale narazí na rate limit (429). Získat z [nvd.nist.gov](https://nvd.nist.gov/developers/request-an-api-key) a nastavit `NVD_API_KEY` v repo secrets.
- [ ] **Plný QRESYNC SELECT s VANISHED** (follow-up po CONDSTORE) — aktuálně jede cleanup smazaných přes `UID FETCH 1:* (UID)` (lightweight UID-only enumerace). Plný QRESYNC SELECT s VANISHED untagged response by byl o krok lepší (server pošle jen smazaná UID), ale vyžaduje raw `IMAPProtocol.command("SELECT", QRESYNC(...))` před `folder.open()` flow — větší refactor `ImapFolderExecutor`. Po release prioritizovat pokud cleanup latency bude bottleneck. Implementace by mohla doplnit `ImapCondstoreCommands` o `selectWithQresync(folder, uidvalidity, modseq)` co vrátí Set\<Long\> VANISHED UIDs.

---

## Migrace komentaru a logu do anglictiny (analyza 2026-05-22)

Cilem je sjednotit programatorsky text v backend Java kodu na anglictinu (komentare, log messages, audit labely, internal exception messages) — kod uz ma anglicke identifikatory, smiseny stav po prvni publikaci na GitHub vypada nekonzistentne. **Provadi se PRED prvnim commitem/pushem** — jakmile to pujde ven, `git blame` vsech 136 souboru ukaze "translation pass" commit misto puvodni historie kodu, a po pushi to uz nelze prepsat bez force-pushe. Prvni verejny snapshot ma byt v anglictine.

### Rozsah (kvantitativni, stav po dnesni JEP 511 re-migraci)

- Backend `src/main/java`: **136 souboru, 1654 radku** s ceskou diakritikou.
- Backend `src/test/java`: 66 souboru, 952 radku.
- Vzorek (`AccountService.java`, 86 Cz radku): ~80 % Javadoc, ~10 % log/audit volani, ~10 % string literaly.

Top 10 souboru podle hustoty Cz textu (kandidati pro prvni davku):

```
86  feature/account/service/AccountService.java
66  feature/mail/service/ImapConnectionManager.java
50  feature/mail/service/MailFacade.java
45  feature/auth/service/GoogleTokenService.java
44  feature/mail/service/SmtpMessageService.java
43  feature/contact/service/ContactService.java
43  feature/auth/service/MicrosoftTokenService.java
43  exception/GlobalExceptionHandler.java          (POZOR: user-facing)
42  feature/mail/service/FlagSyncService.java
40  feature/contact/controller/ContactController.java
```

### Scope — PRELOZIT

1. **Javadoc komentare** (`/** ... */`) — public API i internal.
2. **Inline komentare** (`//` na vlastnim radku i trailing).
3. **Log messages** — argumenty `log.info/warn/error/debug/trace(...)`, vcetne audit log labelu pres `AuditLog.X(...)`.
4. **Internal exception messages** — pokud se NEDOSTANOU do UI (testovat: cte se v `GlobalExceptionHandler` jako fallback misto i18n message key? Pokud ano, je to UI-facing).
5. **Test komentare a `@DisplayName`** — pro konzistenci napric repo. Bonus, ne blokator.

### Out of scope — NESAHAT

1. **User-facing e-mail content v `MailDraftService`** — `"Puvodni zprava"`, `"Preposlana zprava"`, `"Predmet: "`. Tyto stringy jdou pres SMTP ven k ceskemu prijemci a NESMI byt prelozeny. Pokud bude i18n preposlanych emailu, je to samostatny ukol s template systemem.
2. **i18n properties** — `messages.properties`, `messages_cs.properties`, `messages_en.properties`. To je ZDROJ prekladu, ne cil.
3. **Validation/error stringy mapovane pres `MessageSource`** — uz jsou i18n. Keys (`account.validation.X`) zustavaji, `messages_cs/en.properties` pokryvaji jazyk.
4. **SQL migration komentare** (`V1__init.sql`, `V2__add_modseq.sql`) — auditni, zustavaji v puvodnim jazyce.
5. **Bilingual static stranky** (`auth-failed.html`, `auth-finished.html`) — per Microsoft OAuth sekce todo.md jsou cilene dvojjazycne, zustava.
6. **Frontend Svelte/TS komentare** — samostatny review pass (mensi rozsah, jiny review proces, jiny i18n setup `frontend/src/lib/i18n/messages/`).

### Edge cases a pravidla

- **Idiomaticke ceske komentare** typu `// carkovy trik` v [AccountProviderService.java](backend/src/main/java/org/voxrox/mailbackend/feature/account/service/AccountProviderService.java) (domain matching pres comma-anchored LIKE) — **preformulovat anglicky s vysvetlenim**, ne doslovne prelozit. Spatne: "comma trick". Dobre: "Match domain against comma-separated list via LIKE with surrounding commas to avoid substring false positives."
- **`GlobalExceptionHandler`** — projit per metoda. Pokud konkretni handler buduje response body z hard-coded ceskeho stringu (ne `MessageSource.getMessage(key, ...)`), je to user-facing → musi nejprve projit i18n migraci, az pak preklad (nebo preskocit).
- **Audit log labely** — preklad OK. Predpoklad: log file je primarne pro dev/ops, ne uzivatel. Pokud vznikne compliance pozadavek na Cz audit log (GDPR pro CZ uzivatele), revertovat per handler.
- **Doslovne fragmenty v komentari** popisujici Cz uzivatelske rozhrani — `// uzivatel vidi "Puvodni zprava"`. Cizi retezec citovat v puvodnim jazyce, popis preformulovat anglicky.

### Pristup

1. **Samostatne PRs**, nikdy nemixovat s funkcnimi zmenami. Diff musi byt cisty "docs only".
2. **Po modulech** (`feature/account/`, `feature/mail/`, `feature/contact/`, `core/security/`, ...) — jeden PR per logicky modul, ne giant PR.
3. **Po kazdem PR**: `mvn verify` zelene + grep verifikace:
   ```bash
   grep -rlP "[áéíóúýčďěňřšťůžÁÉÍÓÚÝČĎĚŇŘŠŤŮŽ]" backend/src/main/java/<modul>/
   ```
   Musi vracet jen explicitne whitelistovane soubory (`MailDraftService` + cokoli dalsiho z out-of-scope sekce).

### Navrzene faze

- [x] **Faze 0 — priprava**: vytvoren [backend/docs/translation-whitelist.txt](backend/docs/translation-whitelist.txt) s `MailDraftService.java` (jediny permanent — SMTP-bound labely v reply/forward quoted body). Lint skript [backend/scripts/check-translation-whitelist.sh](backend/scripts/check-translation-whitelist.sh) s rezimy `--mode=report` (default, exit 0, tracking progressu) a `--mode=strict` (exit 1 pokud non-whitelisted soubor obsahuje diakritiku). Baseline: 135 main + 66 test souboru k prelozeni. CI integrace odlozena na Fazi 6. `GlobalExceptionHandler` ma 4 hard-coded fallback hlasky v `resolveMessage(..., "Czech default", ...)` ktere jsou UI-facing pres MessageSource fallback — rozhodnuti per-radek/i18n migrace patri do Faze 4.
- [x] **Faze 1 — low risk services**: `feature/account/service/`, `feature/contact/service/`, `feature/mail/service/` (excl. `MailDraftService`). Hlavne Javadoc + log. **HOTOVO 2026-05-24** vcetne ImapActionService / FlagSyncService / SmtpMessageService / MailFacade / ImapConnectionManager / MailAuthMechanisms / ImapFolderAction / JavaMailConnectionProbe / ImapCapabilities / MailContentService / MessageContentPersister / ImapFolderService. Aktualizovany testy: SmtpMessageServiceTest, ImapConnectionManagerTest, MailSyncServiceTest, ContactServiceTest, AccountConnectionTestServiceTest. Pridan i18n key `validation.account.patchProviderXorCustom` do `messages*.properties`. `ContactService.NOTE_SEPARATOR` zmenen z `"— sloučeno —"` na neutralni `"---"`. Lint skript prepnut z grep -P na portable `grep -F`.
- [x] **Faze 2 — auth/OAuth**: `feature/auth/service/{Google,Microsoft}TokenService.java`, `OAuth2CallbackController`. Vcetne log messages. **HOTOVO 2026-05-24**. Vsech 12 auth main souboru prelozeno: GoogleTokenService / MicrosoftTokenService (vc. requires_reauth fallback hlasek), OAuth2CallbackController (OpenAPI `@Tag`/`@Operation` + ValidationException fallback), OAuth2LoginService, OAuth2ClaimsExtractor(Registry), OAuth2TokenService(Registry), GoogleClaimsExtractor, MicrosoftClaimsExtractor, InMemoryTokenCache, TokenCache, CachedToken, ExternalUserClaims, AuthType. OpenAPI snapshot regenerovan (`mvn -Dopenapi.snapshot.update=true test -Dtest=OpenApiSnapshotTest`) — diff jen v `Authentication` Tag + `/api/v1/auth/oauth2/start` a `/success` operacich, ostatni Cz texty v DTO @Schema description zustavaji do Phase 3. `mvn verify` 676 unit + 22 integration zelene.
- [x] **Faze 3 — controllers + DTOs**: `*Controller`, DTOs s `@Schema(description = "...")` (jsou v OpenAPI specu pro klienty). **HOTOVO 2026-05-24**. 20 souboru: vsechny DTOs (account/dto/* 7, contact/dto/* 5, mail/dto/* 9 — MailRequest/MailDetailResponse/MailSummaryResponse/MoveRequest/FolderResponse/MessageFlag/DraftRequest/FolderConstants/`FolderRole` na whitelistu kvuli load-bearing "koš" substring matchi, core/dto/PagedResponse) a vsechny controllery (account: AccountController + MailProviderController, mail: MailReadController + MailWriteController + MailActionController + MailFolderController + DraftController + NotificationController, contact: ContactController). Phase 3 vcetne `@Tag`/`@Operation`/`@Schema description` + log messages + ValidationException fallback texts. OpenAPI snapshot regenerovan, FE typy zustanou nezmeneny (jen popisek-only zmeny). mvn verify 676 unit + 22 integration zelene. Dale: Phase 4 (`GlobalExceptionHandler`).
- [x] **Faze 4 — exception handling**: `GlobalExceptionHandler` s i18n auditem (per-method rozhodnuti UI-facing vs internal). **HOTOVO 2026-05-24**. Vsech 9 souboru v `exception/` prelozeno: GlobalExceptionHandler (5 hard-coded fallbacku — vsechny maji i18n klic, takze EN fallback je bezpecny: `error.validation`, `error.validation.missingParam`, `error.badRequest.unreadableJson`, `error.resource.notFound`, `error.internal`), AccountAlreadyExistsException, AccountInactiveException, AccountNotFoundException, ContactNotFoundException, DuplicateContactException, MailAuthenticationException, ProviderNotFoundException, ErrorCode (komentare). mvn verify 676 + 22 zelene bez test updatu (CZ stringy v ContactControllerTest a GlobalExceptionHandlerTest jsou jen v `Accept-Language: cs` assertions, ktere ctou z `messages_cs.properties` bundlu — fallback string change se jich netyka).
- [x] **Faze 4.5 — core + util + feature entity/repo/mapper + MailBackendApplication**: **HOTOVO 2026-05-25**. Vse mimo testy a vyse uvedene moduly prelozeno: `core/config/` (OpenApiConfig / SecurityConfig / DatabaseConfig / AsyncConfig / RetryConfig / RetryProperties / ClientConfigProperties), `core/init/` (StorageContextInitializer / HandshakeService / HandshakeResponse / HandshakeController / StartupInitializer / AotTrainingExitListener), `core/security/` (CryptoServiceImpl / CryptoProperties / ApiKeyFilter / CryptoMigrationService), `core/health/SyncHealthIndicator`, `core/clientconfig/` (ClientConfigController / ClientConfigResponse), `core/backup/` (DatabaseBackupService / BackupProperties), `core/metrics/MailMetrics`, `core/system/` (SystemReadinessController / SystemReadinessResponse), `core/service/FileSystemService`, `core/diagnostic/DiagnosticDumpService`, `util/` (AuditLog / HtmlSanitizer / LogMasker / AutoClosingFolderInputStream / MimePartExtractor), `feature/account/{entity,mapper,repository}` (AccountEntity / AccountCredentialEntity / MailProviderEntity / AccountMapper / AccountRepository / MailProviderRepository / AccountLastError), `feature/contact/{EmailLabel,repository/ContactRepository}`, `feature/mail/{entity/FolderSyncStateEntity,listener/MailSyncEventListener,mapper/MessageMapper,repository/{FolderSyncStateRepository,MessageRepository}}`, `MailBackendApplication`. OpenAPI snapshot regenerovan (`mvn -Dopenapi.snapshot.update=true`). Pridany whitelist zaznamy pro `MessageMapper.java` (LEGACY_NO_SUBJECT/LEGACY_UNKNOWN_SENDER sentinel konstanty pro detekci historickych DB radku napsanych pred i18n) — vedle existujicich `MailDraftService.java` a `FolderRole.java`. Aktualizovane assertion stringy v testech: MailBackendApplicationTest (`server.port must be an integer`), DatabaseConfigTest (`Application failed to start after update`), StorageContextInitializerTest (`Crypto configuration does not match` + `Crypto fingerprint does not match`), CryptoServiceImplTest (`integrity`). `mvn verify` 676 unit + 22 integration zelene. **Hlavni src ma 0 non-whitelisted souboru s diakritikou.**
- [x] **Faze 5 — testy**: **HOTOVO 2026-05-25**. Pozn.: baseline z 22.5. (66 souboru / 931 radku) byl pred prubeznymi prekladovymi opravami v testech behem Faze 1–4.5; realny zbytek byl 3 nediakritikove soubory + 2 soubory s nediakritickou cestinou v `@DisplayName`. Konkretne:
  - SmtpMessageServiceTest.java — Nested `BoundaryErrorHandling` Javadoc + ctyri @DisplayName + closing comment.
  - SmtpTransportFactoryTest.java — class Javadoc, `setUp()` komentar, vsech 7 @DisplayName, jeden trailing comment.
  - SseNotificationServiceTest.java — 11 @DisplayName + helper Javadoc.
  - MessageMapperTest.java — class Javadoc + helper section header + 8 @DisplayName + 4 inline komentare. Fixtures (`"Testovaci predmet"`, `"Telo zpravy"` apod.) zachovany — jsou jen test data, lint nezachycuje a nemaji load-bearing roli.
  - MailFacadeTest.java — 33 @DisplayName + 2 inline komentare; load-bearing nazvy testovych metod (`shouldThrowWhenDraftBelongsToDifferentAccount` apod.) zustaly.
  - StorageContextInitializerTest.java — 1 @DisplayName.
  - `mvn verify` 676 unit + 22 integration zelene, `bash backend/scripts/check-translation-whitelist.sh --mode=strict` zelene. Zadne fixture changes => zadne updates testovych assertions nepotreba.
- [x] **Faze 6 — done check**: **HOTOVO 2026-05-25**. Strict lint zapojen do `.github/workflows/ci.yml` jako krok "Translation whitelist lint" v jobu `backend` mezi `spotless:check` a `mvn verify` (fail-fast pred testy). Grep verifikace: `bash backend/scripts/check-translation-whitelist.sh --mode=strict` returns `OK (strict)` lokalne. Po prvnim pushi se gate aktivuje na GitHub Actions.
- [x] **Faze 6.5 — re-audit 2026-05-25** (vecer): doplnen audit nad ramec strict lintu, ktery chytá jen diakritiku. Vlatne tyto:
  - **Whitelist soubory** (`MailDraftService.java`, `MessageMapper.java`, `FolderRole.java`) — lint je preskakuje, ale jejich whitelist entry povoluje jen load-bearing fixtures. Komentare a log messages musi byt prelozene. V `MailDraftService.java` zbyvaly 2 komentare + 1 log message — opraveno.
  - **Diacritic-free cestina** — `presmerovani`, `odeslan`, `Testovaci` apod. lint neexistuje. Nasly se: `OpenApiEndpointDisabledTest:81`, `OpenApiSnapshotTest:113`, `StorageContextInitializerTest:66`, `AccountMapperTest:47` — opraveno.
  - **Non-Java resources** mimo scope strict lintu: `application.properties` (~30 Cz komentaru), `application-dev.properties` (5), `logback-spring.xml` (4) — kompletne prelozeno.
  - `mvn verify` zelene po vsech opravach (676+22).
- [x] **Faze 6.6 — siroky audit 2026-05-25** (pozdni vecer): kompletni sweep nad celym backendem.
  - **Build/config XML**: `pom.xml` (5 komentaru), `spotbugs-exclude.xml` (2 komentare) — prelozeno.
  - **PowerShell skripty**: `run-dev.ps1` (5), `scripts/generate-aot-cache-windows.ps1` (synopsis + 4 inline + 1 throw), `scripts/package-sidecar-windows.ps1` (1) — prelozeno.
  - **Test resources**: `application-it.properties` (4) — prelozeno.
  - **Veřejné šablony**: `.env.example` (kompletne prepsano) — `.env` (lokalni dev secret, gitignored) zustava.
  - `check-translation-whitelist.sh` `diacritic_chars=(á é í...)` je load-bearing pattern skriptu — NESAHAT.
- [x] **Faze 6.7 — cleanup minulosti 2026-05-25** (jeste pozdejsi vecer): user povolil, ze projekt je ve faze vyvoje a nema produkcni data → nemusime davat ohled na minulost.
  - `MessageMapper.java`: smazany `LEGACY_NO_SUBJECT` / `LEGACY_UNKNOWN_SENDER` sentinely (byly jen pro historicke DB radky pred i18n). Soubor i jeho test wypadli z whitelistu (3 → 2 main, 11 → 10 test).
  - `V1__init.sql` + `V2__add_modseq.sql`: vsechny Cz komentare prelozene na EN (audit-trail princip neplati, projekt jeste neni v produkci). Migrace samotne se nemeni — jen komentare.
  - `mvn verify` zelene (676 + 22), strict lint OK.
- [x] **Faze 6.8 — slouceni V2 do V1 2026-05-25** (nocni): Flyway migration cleanup. V2 (`add_modseq.sql`) byla puvodne samostatny step kvuli inkrementalnimu vyvoji CONDSTORE/QRESYNC podpory. V pre-produkcnim stavu zbytecne — `last_known_modseq INTEGER` slouceno do `CREATE TABLE folder_sync_state` ve V1, V2 smazana. Smazane vsechny dev DB v obou lokacich (`%LOCALAPPDATA%\VoxRox\Mail\db\` Tauri sidecar 3.5MB WAL + bare-mvn `~/.voxrox/mail/db/` 147KB) vc. orphan `attachments/`. Crypto.bin + crypto.fingerprint + logs zachovany. Pri dalsim startu se vytvori cista DB z V1, vsechny ucty musi projit OAuth flow znova. `mvn verify` zelene (676+22).
- [x] **Faze 6.10 — best-practice cleanup 2026-05-25** (nocni): dva drobne cleanupy.
  - `SyncStateService.getOrCreateState(2-arg)` smazano — dead code bez calleru, "backwards-compatible variant" bez backwards-compat smyslu.
  - `ContactBulkService` Javadoc preformulovan z backward-looking historie ("The previous version handled this...") na forward-looking architectural guard ("Do not merge this class back into ContactService"). Stejna informace, lepsi signal pro buduciho readera kteri muze refactor pokazit (JEP 483 AOT cache).
  - `mvn verify` zelene.
- [x] **Faze 6.11 — smazani feature/config + settings tabulky 2026-05-30**: generic key/value tabulka `settings` mela od V1 jediny key (`internal_api_key`), persistovany API klic ale neslo o realny pozadavek — frontend cte `session.json` pri kazdem startu sidecaru (port se vzdy meni), takze stabilita klice pres restarty nemela zadny UX/security profit. Smazano:
  - `feature/config/entity/SettingEntity.java` + `feature/config/repository/SettingRepository.java` (cely balik).
  - V `HandshakeService`: pole `settingRepository` + `cryptoService` + metody `decryptStoredApiKeyOrRotate`/`createAndSaveApiKey`/`saveInternalKey`. Nahrazeno `volatile String apiKey` cache + `SecureRandom`+`Base64.getUrlEncoder().withoutPadding()` pri prvnim volani `getOrCreateApiKey()`. Klic se generuje jednou per JVM proces, sidecar restart = novy klic = frontend ho prevezme z noveho `session.json`.
  - V `V1__init.sql` smazana sekce 7 (`CREATE TABLE settings`), sekce 8/9 prečíslovany na 7/8.
  - `HandshakeServiceTest`: stary test `applicationReadyRotatesUnreadableInternalApiKey` nahrazen `apiKeyIsStableWithinProcess` (idempotence per-process). `applicationReadyWritesSessionAndReadyGate` zjednodusen — assertuje shodu klice mezi `getOrCreateApiKey()` a session.json.
  - Aktualizovany docs: `OPERATIONS.md:320` (sekce o crypto rotaci), `RELEASE_CHECKLIST.md:57` (smoke kontrola), `SECURITY_RELEASE_CHECK.md:35` (release security audit) — vsude prepsano "persistovany internal_api_key se prekreneruje" na "in-memory only, generovany pri kazdem startu sidecaru".
  - `mvn verify` zelene (715 unit + 22 integration, beze zmeny v poctu testu — 1 maly test smazan, 1 maly pridan).
- [x] **Faze 6.12 — crypto subsystem trim 2026-05-30**: bundle tri navaznych cleanupu po Fazi 6.9/6.11 (po removalu CryptoMigrationService a HandshakeService persistence).
  - **A. Dead metody v `CryptoService`** — `generateRandomBytes(int)`, `encodeBase64(byte[])`, `decodeBase64(String)` mely 0 produkcnich calleru od Faze 6.11. Smazane z interface + impl. Zmizel nested test `Helpers` v `CryptoServiceImplTest` (3 testy: `generateRandomBytesShouldReturnRequestedLength`, `generateRandomBytesShouldNotRepeat`, `base64RoundTrip`).
  - **B. Dead parametr `iterations` v `CryptoServiceImpl.getOrDeriveKey`** — zbytek po Fazi 6.9. Vsechny callsity predavaly `cryptoProperties.pbkdf2Iterations()`, takze `iterations` byl dead. Signatura zuzena na `getOrDeriveKey(Long accountId)`. `CacheKey(accountId, iterations)` record zrusen, cache type zjednoduseny z `Map<CacheKey, SecretKey>` na `Map<Long, SecretKey>`. `evictCache(accountId)` pouziva `keyCache.remove(accountId)` misto `removeIf` filtru.
  - **C. Over-configurable knoby v `CryptoProperties`** — `algorithm` (`AES/GCM/NoPadding`), `tagLengthBit` (`128`), `ivLengthByte` (`12`), `keyLengthBit` (`256`) byly knoby ktere nikdo nemuze zmenit bez tiche rozbiti dekryptace stavajicich ciphertextu (zadna migration path od Fazi 6.9). Presunuto do `CryptoServiceImpl` jako `static final` konstanty: `CIPHER_ALGORITHM`, `GCM_TAG_LENGTH_BIT`, `GCM_IV_LENGTH_BYTE`, `DERIVED_KEY_LENGTH_BIT`. CryptoProperties record zmenseny ze 7 na 4 fieldy (`key`, `salt`, `pbkdf2Iterations`, `maxCacheSize`). Smazane radky `application.properties:18-21` (`mail.crypto.algorithm/tag-length-bit/iv-length-byte/key-length-bit`), pridan komentar vysvetlujici proc.
  - `mvn verify` zelene: **712 unit + 22 integration** (–3 testy z odstraneneho `Helpers` bloku).
- [x] **Faze 6.13 — sloučení single-impl interfaců 2026-05-30**: dva "interface for testability" patterny zbytecne v projektu, ktery pouziva Mockito 2.x+ (mockuje konkretni tridy bez problemu).
  - **D. `CryptoService` interface → konkretni tridu**: smazany interface `core/security/CryptoService.java`, soubor `CryptoServiceImpl.java` smazany, jeho obsah presunut do nove `CryptoService.java` jako `@Service public class CryptoService` (bez `implements`, bez `@Override`). Test soubor prejmenovan `CryptoServiceImplTest.java` → `CryptoServiceTest.java`, vsechny refs `CryptoServiceImpl` → `CryptoService` (vc. `@link` Javadocu a `CryptoProperties` `{@link CryptoServiceImpl}` → `{@link CryptoService}`). Whitelist entry v `backend/docs/translation-whitelist.txt` aktualizovan (test ma load-bearing Cz pangram fixture). Komentar `InMemoryTokenCache.java:17` aktualizovan. `application.properties:15` komentar `CryptoServiceImpl` → `CryptoService`. Constructor injection v `AccountCredentialService` + `GoogleTokenService` zustal beze zmeny (typ `CryptoService` se nemenil).
  - **E. `MailConnectionProbe` interface → konkretni tridu**: smazan interface `feature/mail/service/MailConnectionProbe.java`, soubor `JavaMailConnectionProbe.java` smazan, jeho obsah presunut do nove `MailConnectionProbe.java` jako `@Component public class MailConnectionProbe` (bez `implements`, bez `@Override`). Constructor injection v `AccountConnectionTestService` + mock v `AccountConnectionTestServiceTest` zustaly beze zmeny.
  - `mvn verify` zelene: **712 unit + 22 integration** (beze zmeny v poctu testu, jen rename).
- [x] **Faze 6.14 — exception subsystem dead-code cleanup 2026-05-30**: bundle 4 nalezu (F+G+H+I), vsechno pure dead code se 0 produkcnimi callsites.
  - **F. `UnauthorizedException` smazan** — class nikdy instancovana. ApiKeyFilter pise `response.setStatus(401)` bez body, OAuth/IMAP code pouziva `MailOperationException` s `HttpStatus.UNAUTHORIZED` a vlastnimi error codes. Smazano: `UnauthorizedException.java`, entry v `AppException` sealed permits list, `ErrorCode.UNAUTHORIZED` enum value, `error.unauthorized` ze 3 souboru `messages*.properties`.
  - **G. `AccountInactiveException` smazan** — stejny pribeh, 0 throw sites. Account `active=false` se kontroluje silently v scheduler/sync path, nikoli pres exception. Smazano: `AccountInactiveException.java`, entry v `AppException` permits, `ErrorCode.ACCOUNT_INACTIVE`, `error.account.inactive` ze 3 messages files.
  - **H. Dead `ErrorCode` enum values** — `INVALID_CREDENTIALS`, `API_KEY_REQUIRED`, `INVALID_EMAIL_FORMAT` deklarovane v enum, ale nikdo je nepouziva. Smazane z enum.
  - **I. `MailSyncEventListener.logSyncStats` smazan** — duplikatni listener vedle `handleSyncCompleted`. Loggoval jen `log.debug("Stats: folder X synchronized at Y")`, coz je informacne redundantni (handleSyncCompleted uz logguje na INFO s vetsim detailem, timestamp je v kazdem log line jako prefix). 4 radky.
  - FE side `client.ts:64` defensive kontrola `code === 'UNAUTHORIZED' || code === 'API_KEY_REQUIRED'` PONECHANA — je to ucelena obrana proti budoucim BE zmenam, smazani by uskodilo robustnosti za usporu 1 radku.
  - `mvn verify` zelene: **712 unit + 22 integration** (testy nebyly dotcene, dead code nemel zadne pokryti).
- [x] **Faze 6.15 — token cache collapse + IMAP timeout bug fix 2026-05-30**: bundle 3 nalezu (J+K+L), z toho jeden ostry production bug.
  - **J. `TokenCache` interface → konkretni tridu**: stejny vzor jako D/E v Fazi 6.13. Javadoc explicitne pripravoval "future scale-out s Redis/Hazelcast" — single-user desktop app nikdy nedostane Redis. Smazany interface `feature/auth/service/TokenCache.java`, soubor `InMemoryTokenCache.java` smazany, jeho obsah presunut do nove `TokenCache.java` jako `@Component public class TokenCache` (bez `implements`, bez `@Override`). Aktualizovany 2 testy: `MicrosoftTokenServiceTest.java:70` + `GoogleTokenServiceTest.java:84` (oboji `new InMemoryTokenCache()` → `new TokenCache()`). Constructor injection v `GoogleTokenService` + `MicrosoftTokenService` zustal beze zmeny (typ `TokenCache` se nemenil).
  - **K. `OAuth2TokenServiceRegistry.find()` smazana** — 0 callerů. Jen `resolve()` se pouziva. Odstranen import `java.util.Optional`.
  - **L. REAL BUG: IMAP timeouts nefunguji v produkci**. `ImapConnectionManager:342-343` predaval `Duration` objekty do JavaMail Properties; JavaMail `PropUtil.getIntProperty` cte hodnoty pres `props.getProperty(name)`, ktery vraci `null` pro ne-String hodnoty. Vysledek: `mail.imapX.timeout` + `mail.imapX.connectiontimeout` se silently ignorovaly, **IMAP pripojeni nemela zadny efektivni timeout** (silent fallback na infinite). Probe (`MailConnectionProbe:52-54`) pritom spravne pouzival `.toMillis()` s `ImapProperties.{read,connection}Timeout`, takze credential test mel jiny timeout nez realna produkce. Top-level `MailClientProperties.timeout` field byl navic duplicate `ImapProperties.connectionTimeout`/`readTimeout` (30s/60s) i `SmtpProperties.connectionTimeout` (15s). Fix:
    - Pridan `readTimeout` do `SmtpProperties` (default 10s, zachova aktualni chovani SMTP read timeout, ktery driv chodil pres top-level field).
    - Smazan top-level `MailClientProperties.timeout` field.
    - `ImapConnectionManager:342-343` opraven: `String.valueOf(mailProps.imap().readTimeout().toMillis())` + `String.valueOf(mailProps.imap().connectionTimeout().toMillis())`, komentar vysvetluje proc PropUtil potrebuje String.
    - `SmtpTransportFactory:61` opraven: `mailProperties.smtp().readTimeout().toMillis()` misto `mailProperties.timeout().toMillis()`.
    - `application.properties:123` smazan radek `mail.client.timeout=10s`.
    - Aktualizovano 5 testu, ktere konstruovaly `MailClientProperties` s Duration prvnim argumentem: `SyncHealthIndicatorTest`, `RetryConfigTest`, `FlagSyncServiceTest`, `SseNotificationServiceTest`, `SmtpTransportFactoryTest` (posledni take aktualizoval `SmtpProperties` konstruktor s novym `readTimeout` arg).
    - **Behavior change**: IMAP pripojeni nyni maji realne timeouty (60s read, 30s connection per ImapProperties defaults). SMTP read timeout zustava 10s (per novy SmtpProperties.readTimeout default). Před fixem mělo IMAP no-timeout, takže `mvn -P*-it` integracni testy by mohly chytit jinak visici scenare — aktualne 22 integration testu projde zelene, ale je to oblast k overit při smoke testu (IMAP servery typu Seznam, ktere driv tise visely az do GC, by se nyni mely vratit s exception po 60s).
  - `mvn verify` zelene: **712 unit + 22 integration** (testy nebyly pridany ani smazany, jen 5 konstruktoru zmeneno).
- [x] **Faze 6.16 — Google/Microsoft TokenService dedupe 2026-05-30**: extrahovan sdileny token-refresh kod do abstract base class. Refresh / cache / requires_reauth marking / error handling / RestClient setup byly v podstate identicke mezi Google a Microsoft (~170 radku duplikace). Provider-specific diferencne jen URL endpointu, display name, optional scope param u Microsoftu a revokeToken (Google RFC 7009 HTTP, Microsoft jen local cache).
  - **Pred**: `OAuth2TokenService` (interface, 54 r.) + `GoogleTokenService` (289 r.) + `MicrosoftTokenService` (263 r.) = **606 r.**
  - **Po**: `OAuth2TokenService` (abstract class, 288 r.) + `GoogleTokenService` (116 r.) + `MicrosoftTokenService` (110 r.) = **514 r.** (−92 r., ~170 r. duplicate logic centralizovano).
  - `OAuth2TokenService` zmenen z interface na abstract class s common kodem: `getAccessToken` (final), `invalidate` (final), `getCacheStats` (final), `exchangeRefreshToken` (final), `doRefresh` (private), `tokenRefreshFailure` (private), `parseExpiresIn` (private static), `buildRestClient` (private static), `isPermanentRefreshRejection` (private static), nested `CacheStats` record. Abstract hooks: `providerName()`, `providerDisplayName()`, `tokenEndpoint()`, `clientId()`, `clientSecret()`, `customizeRefreshBody(body)` (no-op default), `revokeToken(account)`.
  - `GoogleTokenService` slim: `@Value` clientId/clientSecret/tokenEndpoint/revokeEndpoint fieldy + 5 hook overridu + RFC 7009 revokeToken HTTP POST. `CryptoService` + `AccountCredentialRepository` zustavaji subclass dependencies (pouze pro revoke flow).
  - `MicrosoftTokenService` slim: `@Value` fieldy + 5 hook overridu + `customizeRefreshBody` pridava `scope` param + revokeToken jen lokalni cache cleanup (Microsoft nepodporuje RFC 7009).
  - **Sealed pattern zrusen**: puvodne `abstract sealed class permits Google, Microsoft`, ale Mockito 5.x neumi mockovat sealed tridy ("Unsupported settings"). 47 selhanych AccountServiceTest testu (mockuje `OAuth2TokenService` pres `OAuth2TokenServiceRegistry`). Drop `sealed`/`permits` — compile-time exhaustivita byla nice-to-have, ne load-bearing (providers se pridavaji explicitne pres `@Service` annotation).
  - **Unified log/audit messages**: success log nyni vsude obsahuje `scope: {grantedScope}` (Google to logoval, Microsoft ne — pridano kvuli stejnemu diagnostickemu duvodu, scope field je v response u obou providers). Critical error log: `"Critical error while communicating with the {} OAuth provider for {}"` (driv ruzne wording). Permanent reject error msg: `"{Provider} authorization has expired or been revoked."` (parametrizovano). AADSTS mention odstranen z bezneho log msg — Microsoft AADSTS kody zustavaji v `e.getStatusCode()` raw response.
  - Testy GoogleTokenServiceTest + MicrosoftTokenServiceTest beze zmeny (kontruktor a `ReflectionTestUtils.setField` cesty zustaly identicke).
  - `mvn verify` zelene: **712 unit + 22 integration**.
- [x] **Faze 6.17 — i18n + docs tidy 2026-05-30**: bundle dvou drobnych nalezu z audit kola 5.
  - **M. Duplicate `error.validation.detail` i18n key smazan** — pouzival se jen v `ValidationException(String message)` konstruktoru, hodnota stejna jako `error.validation`. ValidationException nyni pouziva `error.validation`. Smazane radky z 3 messages files (`messages.properties:12`, `messages_cs.properties:12`, `messages_en.properties:12`).
  - **N. Stale Javadoc/popisek v `MailMetrics`** — po dedupe v Fazi 6.16 OAuth refresh metrika sbira z obou providers, ale class Javadoc i `recordOauthRefresh` description zminovaly jen Google. Prepsano na "provider access token (Google, Microsoft, ...)".
  - `mvn verify` zelene: **712 unit + 22 integration**.
- [x] **Faze 6.18 — dead code v util/enum/properties 2026-05-30**: bundle 4 nalezu z audit kola 6.
  - **O. `AccountLastErrorCode.CRYPTO_CREDENTIAL_DECRYPT_FAILED` smazana** — pozustatek po Fazi 6.9 (CryptoMigrationService removal). Enum value 0 callers, i18n key `account.lastError.cryptoCredentialDecryptFailed` ve 3 messages files take dead. Vsechno smazano.
  - **P. `LogCategory.CONFIG` smazan** — 0 usages anywhere. Spotlessly dead enum value.
  - **Q. `LogMasker` cleanup** — 5 dead public metod smazano (`maskIdentifier`, `maskToken`, `lazyUsername`, `lazyToken`, `lazyIdentifier`), 2 dalsi prepnuty na `private` (`maskUsername` pouziva interne `maskEmail` pro no-@ case, `lazy` pouziva interne `lazyEmail`). Public API zustava: `maskEmail` (59 callers), `lazyEmail` (1 caller). Javadoc class header aktualizovan.
  - **R. `SmtpProperties` trim** — 3 dead fieldy smazany: `defaultPort` (callers vzdy poskytuji port pres `AccountConnectionDetails.port()`), `protocolSsl` + `protocolStandard` (SmtpTransportFactory hardcoduje `"smtp"` v `session.getTransport(...)` a `mail.smtp.*` property prefix). Record zmensen z 5 na 2 fieldy: `(connectionTimeout, readTimeout)`. Smazane radky v `application.properties:129/131/132` (`mail.client.smtp.default-port`, `protocol-ssl`, `protocol-standard`). Test `SmtpTransportFactoryTest.java:65` aktualizovan na novy 2-arg konstruktor. Pridan Javadoc na SmtpProperties vysvetlujici proc protokol a default port zustaly hardcoded.
  - `mvn verify` zelene: **712 unit + 22 integration**.
- [x] **Faze 6.9 — smazani CryptoMigrationService subsystemu 2026-05-25** (nocni): celý mechanismus pro re-encrypt starých PBKDF2 ciphertextů (210k → 600k iter) byl mrtvý kód v pre-produkčním projektu bez historických dat. Smazano:
  - `CryptoMigrationService.java` (113 r.) + `CryptoMigrationServiceTest.java` (kompletní soubor)
  - `CryptoService.reEncryptIfNeeded()` interface metoda
  - `CryptoServiceImpl.reEncryptIfNeeded()` impl + `decryptWithConfiguredKeys()` legacy fallback path + `legacyFallbackEnabled()` helper + `DecryptionResult` record (~50 r.)
  - `CryptoProperties.legacyPbkdf2Iterations` field a validation
  - `application.properties:18` `mail.crypto.legacy-pbkdf2-iterations=210000` radek
  - 3 legacy testy v `CryptoServiceImplTest.java` (`legacyIterationFallbackShouldDecryptOldCiphertext`, `reEncryptIfNeededShouldUpgradeLegacyCiphertext`, `reEncryptIfNeededShouldKeepCurrentCiphertextUnchanged`) + zjednodušeny helper `newCryptoService` (smazany 2-arg overload pro legacy iterations).
  - Pro nový crypto key set v budoucnu: rotace = smazat dev DB a začít znovu (žádné in-place migration).
  - `mvn verify` zelene (testy klesly z 676 na ~670, neproveřeno přesné číslo).

### Definition of Done (pro tuhle migraci)

- Grep `[áéíóúýčďěňřšťůžÁÉÍÓÚÝČĎĚŇŘŠŤŮŽ]` v `backend/src/main/java/` vraci jen soubory z `translation-whitelist.txt`.
- `mvn verify` zelene po kazde fazi.
- Translation-whitelist.txt review s odůvodněním u kazdeho souboru.
- CI lint check pro whitelist je aktivni.

---

## Migrace komentaru a logu do anglictiny — FRONTEND (analyza 2026-05-26)

Cilem je dotahnout to same co backend [sekce vyse], ale ve `frontend/src/`: sjednotit programatorsky text (komentare, error stringy, log messages) na anglictinu. Identifikatory, i18n klice a UI labely uz jsou EN — Cz zustava jen v komentarich, par error throws (pre-i18n boot) a deklarovane na vybranych mistech.

Stejny princip jako u backendu: **PRED prvnim commitem/pushem**, aby `git blame` ukazoval realnou historii a nikoliv jeden "translation pass" commit napric celym frontendem.

### Rozsah (kvantitativni, stav 2026-05-26)

Grep `[áéíóúýčďěňřšťůžÁÉÍÓÚÝČĎĚŇŘŠŤŮŽ]` v `frontend/src/` najde **83 souboru, 1445 hitu (radku)**. Rozdeleni dle kategorie:

| Kategorie | Soubory | Radky | Stav |
|---|---|---|---|
| Source TS (`lib/`, `routes/*.ts`) | 45 | 1418* | **PRELOZIT** |
| Source Svelte | 10 | 194* | **PRELOZIT** (komentare ve `<script>`/`<!-- -->`) |
| Unit testy `*.test.ts` | 5 | 77* | **PRELOZIT** (komentare + `describe`/`it` popisky) |
| E2E `*.functional.e2e.ts`, `e2e-helpers.ts` | 13 | 1584* | **DEFER** (assertuji proti Cz UI s `mail.locale='cs'`) |
| Test fixtures `test-fixtures/msw/*` | 2 | 96* | **DEFER** (mock backend data) |
| `lib/i18n/messages/cs.json` | 1 | 1551* | **EXCLUDE** (zdroj Cz prekladu, ne cil) |

*sloupec "Radky" je `Select-String` count match-u (ne unique radky). Realny pocet radku k editaci v src je **54 souboru / 386 radku** podle line-grep (= `grep -c`).

Top 10 src souboru podle hustoty (kandidati pro prvni davku):

```
34  lib/api/session.ts                       (SessionLoadError hlasky + Javadoc)
32  lib/backend/sidecar.ts                   (exit-code hlasky + cleanup docs)
19  lib/types.ts                             (Javadoc na shared types)
17  lib/api/readiness.ts                     (BackendReadinessError hlasky + Javadoc)
16  lib/mail/mailbox.ts                      (fasada Javadoc)
15  routes/+layout.svelte                    (a11y/focus management komentare)
12  lib/accounts/providerResolver.ts         (provider matching docs)
12  lib/api/client.ts                        (RFC 9457 ProblemDetail komentare)
12  lib/shortcuts/globalShortcuts.ts         (hierarchia shortcutu Javadoc)
12  lib/components/AccountForm.svelte        (wizard/preset komentare)
```

### Scope — PRELOZIT

1. **TypeScript/Svelte Javadoc komentare** (`/** ... */`) — public API i internal.
2. **Inline komentare** (`//` na vlastnim radku i trailing), HTML `<!-- ... -->` ve Svelte template.
3. **Komentare uvnitr `<script>` v `.svelte`** — stejne jako TS soubor.
4. **Error stringy ktere jdou pred i18n init** — `SessionLoadError`, `BackendReadinessError`, sidecar exit-code hlasky v `sidecar.ts`. Zobrazi se v `BootErrorView` jeste pred `initI18n()`, takze i18n na ne nedosahuje. **Rozhodnuti: prelozit do EN (konzistentni s backend EN fallback v `GlobalExceptionHandler`).** Cz uzivatel uvidi anglictinu pri kritickem boot failu — akceptovatelne, ostatni komunikace je i18n'd.
5. **Test komentare a `describe`/`it` popisky** v `*.test.ts` (svelte-i18n unit tests, paletteRanking, atd.) — pro konzistenci napric repo. Bonus, ne blokator.
6. **Console logs** — pokud nejake v src/ jsou (predpoklad: minimum, primarne dev/debug).

### Out of scope — NESAHAT

1. **`lib/i18n/messages/cs.json`** — ZDROJ ceskeho prekladu (1551 hitu), nedotykat se. `en.json` je uz 100% EN (grep 0 hitu, overeno 2026-05-26).
2. **E2E testy `routes/**/*.functional.e2e.ts`** — 13 souboru, 1584 hitu. Vsechny nastavuji `window.localStorage.setItem('mail.locale', 'cs')` a assertuji proti realnemu Cz UI (`getByRole('button', { name: 'Přihlásit přes Google' })`, `getByText('Pro Outlook…')` atd.). Refactor na locale-agnostic selectory (test IDs, ARIA-only) je samostatny ukol mimo tuhle migraci.
3. **Test fixtures `test-fixtures/msw/{handlers,fixtures}.ts`** — mock backend simulujici Cz user data (subjects, account names: `'Pracovní účet'`, `'Doručené'`, `'Odeslaná zpráva'`). Slouzi jako realisticka data pro e2e/unit testy; preklad by mohl rozbit testy ktere ocekavaji konkretni stringy. Pokud bude budouci pass na e2e tests, projit pak.
4. **`workspaceCommands.ts:39` `['nastaveni', 'prepnout do nastaveni']`** — transliterovany alias pro command palette (user pise `nastaveni` bez diakritiky, dohleda "Nastaveni"). Load-bearing, NESAHAT.
5. **`lib/i18n/index.ts` storage key** `'mail.locale'` a `LOCALE_LABELS` — uz EN. Komentare uvnitr souboru prelozit.
6. **Frontend src-tauri/** — Rust/JSON, jen drobne komentare; pokud potreba, samostatny mini-PR.

### Edge cases a pravidla

- **Pre-i18n error hlasky** v `session.ts:56-132`, `readiness.ts:54-216`, `sidecar.ts:251-278` se objevi v `BootErrorView` PRED tim, nez svelte-i18n stihne inicializovat. Nejde je proto pohnout do `messages.json`. **Pravidlo: prelozit do EN, doplnit code path ktery probehne pred i18n initem.** Pokud to bude pozdeji vadit, lze pridat mini-bundle staticky importovany jeste pred bootstrapem (`bootMessages.cs.ts`/`bootMessages.en.ts`).
- **Komentare popisujici Cz UI strings** — citovat puvodni Cz retezec v uvozovkach (napr. `// shows "Přihlášení k účtu"`), zbytek komentare anglicky.
- **Cz strings v `throw new Error('…')` uvnitr fixtur** (`handlers.ts:65,94,...`) — zustavaji, MSW mock simuluje backend ProblemDetail s lokalizovanym `detail`. Mock vraci hlasky v Cz, aby unit/e2e testy odpovidaly chovani prod backendu pri `Accept-Language: cs`.
- **Diacritic-free Cz slova** (`presmerovani`, `Testovaci`, `prijat`, ...) — grep s diakritikou je nezachyti. Audit po prvnim passu (jako backend Faze 6.5/6.7).

### Pristup

1. **Samostatne PRs**, nikdy nemixovat s funkcnimi zmenami. Diff musi byt cisty "docs only" / "comments + log strings".
2. **Po vrstvach** (`lib/api/`, `lib/stores/`, `lib/backend/`, `lib/accounts/`, `lib/components/`, `routes/`) — jeden PR per logicky modul.
3. **Po kazdem PR**: `npm run check` + `npm run check:i18n` zelene + grep verifikace:
   ```powershell
   Select-String -Path 'frontend/src/<modul>/**/*.{ts,svelte}' -Pattern '[áéíóúýčďěňřšťůžÁÉÍÓÚÝČĎĚŇŘŠŤŮŽ]'
   ```
   Musi vracet 0 vysledku (krome explicitnich whitelistu).

### Navrzene faze

- [x] **Faze 0 — priprava**: hotovo — [check-translation-whitelist.mjs](frontend/scripts/check-translation-whitelist.mjs) (Node verze backend bash skriptu, beha na Windows bez WSL) + [translation-whitelist.txt](frontend/docs/translation-whitelist.txt) s rezimy `--mode=report` a `--mode=strict`. Whitelist pokryva i18n source (cs.json), 14 E2E specs, 2 MSW fixtures, command palette transliterated aliases (workspaceCommands.ts), a unit test fixtury s diakritikou (paletteRanking.test.ts).
- [x] **Faze 1 — `lib/api/` + `lib/backend/`**: hotovo. Vsechny soubory v `lib/api/` (session.ts, sidecar.ts, readiness.ts, client.ts, googleAuth.ts, notifications.ts, errors.ts, mailRead.ts, drafts.ts, contacts.ts, mailAction.ts, mailWrite.ts, http.ts, accounts.ts, clientConfig.ts, diagnostics.ts, folders.ts, providers.ts) a `lib/backend/` (sidecar.ts, data-dir.ts, nativeNotifications.ts) jsou EN. Pre-i18n error stringy (SessionLoadError, BackendReadinessError, sidecar exit-code hlasky) prelozeny do EN.
- [x] **Faze 2 — zbytek lib/ TS modulu**: hotovo. `lib/stores/`, `lib/mail/`, `lib/accounts/`, `lib/shortcuts/`, `lib/commands/`, `lib/actions/`, `lib/bootstrap.ts`, `lib/types.ts`, `lib/i18n/index.ts` — vsechny Javadoc/komentare EN. Diacritic-free CZ aliasy pro command palette fuzzy matching (`mailCommands.ts`, `viewCommands.ts`, `accountCommands.ts`) zustavaji jako load-bearing user-facing input — lint je nezachyti (nemaji diakritiku).
- [x] **Faze 3 — Svelte komponenty**: hotovo. `+layout.svelte`, AccountForm.svelte, message-detail komponenty, boot views, MailToolbar, MessageDetail, MessageRowActionsMenu, ComposeForm, route-level `+page.svelte` — vse EN.
- [x] **Faze 4 — Unit testy**: hotovo. `sidecar.test.ts`, `errors.test.ts`, `paletteRanking.test.ts`, `globalShortcuts.test.ts`, `readiness.test.ts` — komentare a describe/it popisky EN. paletteRanking.test.ts ma 2 load-bearing fixtures s diakritikou (`'Příště Žluťoučký kůň'`, `'Café'`) ve whitelistu.
- [x] **Faze 5 — CI lint wire-up**: hotovo. `npm run check:translations:strict` zapojen v `.github/workflows/ci.yml` jako step "Translation whitelist lint" v jobu `check` mezi `check:api` a `npm run check`. Fail-fast pred svelte-check + testy.
- [x] **Faze 6 — re-audit 2026-05-26**: hotovo. Strict lint `frontend summary: 17 file(s) with diacritics, 1024 line(s) total. Non-whitelisted: 0 file(s), 0 line(s). OK (strict)`. Diacritic-free CZ grep v `frontend/src` (mimo whitelist) — 0 hitu. Doplnen sweep `frontend/scripts/` (tauri-dev-fresh.mjs, tauri-dev-with-env.mjs, tauri-release-startup-smoke.mjs) a `frontend/src-tauri/Cargo.toml` — komentare prelozene. `frontend/README.md` uz EN. `frontend/END_USER_README.md` zustava cilene CZ-only (uzivatelska dokumentace). Root `CHANGELOG.md` zustava CZ (release notes pro koncove uzivatele, paralelni s END_USER_README.md). `npm run check` 1241 souboru / 0 errors / 0 warnings.

### Definition of Done (pro tuhle migraci)

- Grep `[áéíóúýčďěňřšťůžÁÉÍÓÚÝČĎĚŇŘŠŤŮŽ]` v `frontend/src/lib/` a `frontend/src/routes/**/*.svelte` + `**/*.ts` (bez `*.e2e.ts` a `*.test.ts` dokud Faze 4 nedobehne) vraci jen whitelist soubory.
- `npm run check` + `npm run check:i18n` zelene po kazde fazi.
- Pre-i18n boot error hlasky rucne testnute v EN po fazi 1 (vynutit error: vypnout backend, ocekavat EN BootErrorView).
- `translation-whitelist.txt` review s odůvodněním u kazdeho souboru.
- CI lint check pro whitelist aktivni v `.github/workflows/ci.yml`.

---

## Pre-Push Quality Gate

Pred kazdym pushem zelene:

- Backend: `mvn verify`, `mvn spotless:check`, `mvn spotbugs:check` (vynuceno v CI).
- Frontend: `npm run check`, `npm run check:i18n`, `npm audit --audit-level=high`, plne stable functional/a11y sady (`npm run test:functional:stable`, `npm run test:a11y:stable`).
- Po `git add .` rucne projit staged soubory.

---

## Backlog Po Prvnim Releasu

- [ ] Self-update standalone backendu jen pokud vznikne samostatny deployment mimo Tauri bundle.
- [ ] Dlouhodobe sledovat startup performance a velikost bundle.

---

## Definition Of Done

- Polozka se odskrtava az po realnem overeni.
- Nove ukoly maji byt akcni, s jasnym vysledkem.
- Hotove implementacni deniky se do tohoto souboru nepridavaji; patri do commitu, changelogu nebo dokumentace.
- U zmen backendu pro Tauri nezapomenout prebalit a zkopirovat sidecar.
