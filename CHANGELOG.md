# Changelog

Release-level changelog pro celé monorepo VoxRox Mail.

Backend a frontend mají vlastní verze, ale desktop release se vydává jako jeden
Tauri bundle se sidecar backendem. Každý release proto drží jednu společnou
sekci s podsekcemi podle artefaktu.

## Unreleased

### Backend

- Přidán sidecar lifecycle kontrakt: graceful shutdown, `session.json` + `.ready` gate.
- Přidán first-run crypto bootstrap do `${app.data-dir}/crypto.bin`; `MAIL_CRYPTO_KEY/SALT` zůstávají volitelný dev override.
- Konsolidace startup handshake do `/api/v1/system/readiness` (vrací `appVersion`, `apiVersion`, `minClientVersion`, `dbSchemaVersion` a `phase`). Původní `/api/handshake` endpoint odstraněn — desktop klient používá readiness.
- Pre-migration DB backup běží jen když má Flyway pending migrace (před každým release majoru jeden snapshot, ne při každém startu).
- Health gate při startu: při selhání `PRAGMA quick_check` se aplikace zastaví s recovery zprávou odkazující na `OPERATIONS.md`.
- Audit event `app_started` s `appVersion`, `dbSchemaVersion`, `previousAppVersion`.
- Spring AOT a JEP 483 AOT cache (opt-in přes packaging script): cold start jvm `java -jar` 8,5 s → 5,4 s (−37 %).
- Hybrid pagination fáze 1: `MailFacade.getEmails` má rychlý lokální fast-path + lazy IMAP fetch jen pokud klient žádá novější stránku. `FolderCountCache` drží server-side count per `(account, folder)` s TTL 60 s — eliminuje opakované `STATUS` volání na IMAP při scrollování.
- Send notifikace přes SSE — odeslání e-mailu vrací `sendId` v 202 odpovědi, async send výsledek doručen jako `send_completed` / `send_failed` event přes `SseNotificationService`. Frontend tak ukazuje pravdivý stav „odesílá se…" → „odesláno" / „odeslání selhalo" místo optimistického toastu.
- IMAP fetch robustness: pokud server vrátí malformed BODYSTRUCTURE pro konkrétní zprávu (typicky Seznam), `MessageFetcher` nově persistuje envelope-only stub místo aby celou zprávu zahodil. Uživatel zprávu uvidí v listu se subject/sender/date, content fetch se zopakuje při otevření detailu.
- Bezpečnost (audit B1-1): tělo zprávy se z IMAP nově čte s limitem 8 MB (bounded stream, `partialfetch` explicitně připnutý) místo neomezeného načtení do paměti — nepřátelský server už nemůže obřím tělem shodit sidecar. Příliš velká zpráva dostane persistentní příznak `body_oversize`, detail vrací lokalizovaný placeholder „zpráva je příliš velká" a opakované otevření už na server nesahá; u `multipart/alternative` se místo placeholderu použije textová alternativa (bez ohledu na pořadí částí), pokud se do limitu vejde. Odpověď/přeposlání takové zprávy cituje prázdné tělo, nikdy text placeholderu. Neznámá znaková sada těla nově degraduje na UTF-8 místo chyby načtení.
- Oprava zobrazení: `multipart/alternative` s vnořeným `multipart/related` (typicky Apple Mail — HTML s vloženými obrázky) se nově vykreslí jako HTML; dřív se tichým fallbackem zobrazila jen textová verze.
- Oprava boot blockeru: backend padal při startu na nerozresitelném placeholderu `microsoft.client-secret` (Microsoft je public client, property už neexistuje). `client_secret` přesunut ze sdíleného OAuth refresh body do Google-specific hooku; Microsoft cesta secret vůbec neposílá (public client by ho odmítl s AADSTS700025). Navíc po selhání startu JVM nově hned skončí s exit 1 — dříve ji ne-démonová vlákna držela naživu jako zombie proces, který blokoval data dir a další start končil exit 78 „already running".
- Programmer text v Java zdrojích, Flyway migracích a packaging skriptech přeložen do angličtiny. Strict lint v CI gate.

### Backend — threading / konverzace (Phase 1, backend-only)

- V2 Flyway migrace `add_threading.sql` přidává `thread_id` / `thread_root_message_id` / `thread_position` sloupce na `messages` + 2 composite indexy.
- `ThreadingService` implementuje JWZ-light algoritmus: direct In-Reply-To, References walk oldest-to-newest (cap 50), nový thread fallback, late-arriving-parent reconciliation. Per-account scope. Skip subject clustering (false positives na běžných subjectech).
- `ThreadingBackfillService` běží na `ApplicationReadyEvent` async — backfillne `thread_id` pro každý null řádek active+!requires_reauth účtů s audit eventy.
- Nový endpoint `GET /api/v1/messages/account/{accountId}/threads/{threadId}` vrací `ThreadResponse` (membery v ascending `threadPosition` order, unreadCount).
- Nový hidden `POST /api/internal/threading/recompute?accountId=N` pro QA / support reset.
- SSE `oneOf` rozšířen o `thread_updated` event — emitován při novém append do existujícího threadu nebo merge orphan chain.
- `MailSummaryResponse` + `MailDetailResponse` advertizují `threadId` (nullable). V0.1.0 frontend nepoužívá — typy připravené pro V0.2 UI.

### Backend — audit fáze 6.11–6.18 (cleanup před prvním release)

- Smazán `feature/config/settings` subsystem — handshake API klíč nově in-memory only (regeneruje se při každém startu sidecaru). Příslušná SQL sekce odstraněna z `V1__init.sql`.
- Crypto subsystem ztenčen: dead metody (`generateRandomBytes`, base64 helpers), dead konfigurační knoby (`algorithm`, `tag-length-bit`, `iv-length-byte`, `key-length-bit`) inlinovány jako static final konstanty.
- Single-impl interfaces sloučeny: `CryptoService`, `MailConnectionProbe`, `TokenCache` (Mockito 5+ je umí mockovat bez interface).
- Exception subsystem cleanup: smazány nepoužívané `UnauthorizedException`, `AccountInactiveException`, `ErrorCode` hodnoty bez callerů.
- Google + Microsoft `TokenService` deduplikované přes abstract `OAuth2TokenService` base class (~170 řádků duplicate refresh/cache/error logiky centralizováno).
- Dead code v `LogMasker`, `LogCategory.CONFIG`, `SmtpProperties` (dead fieldy `defaultPort`, `protocolSsl`, `protocolStandard`), `AccountLastErrorCode.CRYPTO_CREDENTIAL_DECRYPT_FAILED` odstraněn.
- Tauri security tightening: CSP zúžen (smazány nepoužité `ws://localhost`, `data:` ve frame-src, `blob:` v img-src), Tauri capabilities přepnuty z `core:default` na explicitní per-permission allow-list.
- Spotbugs fix v `HandshakeService.generateApiKey` — `DMI_RANDOM_USED_ONLY_ONCE` (Faze 6.11 refaktor zavedl `new SecureRandom()` per call). Cached jako `static final RANDOM`. Latentní 6+ měsíců, odhalil ho audit téhle session.
- OpenAPI surface audit (viz [backend/docs/OPENAPI_AUDIT.md](backend/docs/OPENAPI_AUDIT.md)) — A1: SSE stream nyní v OpenAPI advertizuje `oneOf: [SyncNotification, SendNotification, ThreadUpdated]` místo `SseEmitter`. A2: SSE description vyjmenovává všechny event typy. A3: „Electron" → „Tauri" v OpenAPI Info + ApiKeyAuth scheme. A4: explicitní `@ApiResponse(202/204)` na 8 endpointů kde `ResponseEntity.accepted()/.noContent()` springdoc neuměl staticky detekovat. B3: attachment download dokumentován jako `application/octet-stream` binary místo `StreamingResponseBody`. B4: SSE `operationId` přejmenován z generického `stream` na `streamNotifications`.
- Public API zúžen (před prvním release): B1-a smazán duplicate `GET /messages/account/{accountId}/folder/{folderRef}` (path-variant — frontend produkce vždy volala query-variant). B2-Vol3 smazán dead `PATCH /accounts/{id}` surface (controller + `AccountPatchRequest` DTO + `AccountService.patchAccount` + 15 test scenarios + i18n key + FE echoes v `generated.ts`/`type-contract.ts`/`lib/types.ts`/msw fixtures+handlers).

### Frontend

- Přidán boot/readiness orchestrator s konkrétními fázemi startu a recovery akcemi.
- Paralelizovaný bootstrap (`loadClientConfig` + `loadAccounts` přes `Promise.all`) a globální 60s timeout.
- Shell-first rendering — místo blank screenu se ihned vykreslí AppRail + sidebar placeholder.
- Přidány stable Playwright preview wrappery pro functional/a11y testy.
- Zlepšen a11y kontrast muted textu a disabled tlačítek.
- Compose flow ukazuje skutečný stav SMTP odesílání: po `sendMail` se zobrazí pending toast „Odesílá se…", při výsledku ze SSE (`send_completed` / `send_failed`) přepne na success / fail toast. Pending stav drží `notifications` store, ne komponenta (compose se mezitím odmountuje).
- Vlna kontaktů: vCard drag-and-drop import, sort + label filter s URL persistencí, Merge duplicit dialog, modal editace kontaktu místo inline rozšíření řádku.
- ToastRegion: visual toast má `role="status"` / `role="alert"` podle tónu, separátní `LiveAnnouncer` zajišťuje spolehlivé NVDA/JAWS čtení (bez double-announce díky `aria-live="off"` na visual nodu).
- YAGNI sweep (5 fází, A–E): 4 dead-code odstranění, 57 over-exportovaných symbolů sjednoceno jako interní, 48 dead i18n klíčů (601 → 553 per jazyk), inline 7 thin wrapperů v `mail/actions.ts`.
- Programmer text v Svelte + TypeScript zdrojích migrován do angličtiny. Strict lint v CI gate. Pre-i18n boot error hlášky (`SessionLoadError`, `BackendReadinessError`, sidecar exit-code) přepnuty na EN.

### Tauri / Release

- NSIS odinstalátor už nezobrazuje dialog výběru jazyka — jazyk se auto-detekuje z Windows locale stejně jako u instalátoru. (`MUI_UNGETLANGUAGE` se nově vkládá jen při `displayLanguageSelector: true`; bez selektoru se volba jazyka nikdy neukládá do registru, takže dialog vyskakoval při každé odinstalaci.)
- Checkbox „smazat data aplikace" v odinstalátoru nově maže i WebView2 profil `%LOCALAPPDATA%\org.voxrox.mail` (+ `%APPDATA%\org.voxrox.mail`) a prázdnou rodičovskou složku `%LOCALAPPDATA%\VoxRox`. Dříve po odinstalaci zůstával EBWebView profil v `C:\Users\...`.
- Doplněn český překlad vlastních Tauri hlášek instalátoru (`windows/Czech.nsh`, UTF-8 bez BOM — bundler soubor renderuje přes handlebars a BOM by pronikl do obsahu; zapojeno přes `customLanguageFiles`) — checkbox mazání dat, reinstall/downgrade stránka a WebView2 hlášky se v české lokalizaci už nezobrazují anglicky. Standardní MUI2 stránky byly česky už dřív (vestavěné překlady NSIS).

### Migrace / DB

- `V2__add_modseq.sql` (CONDSTORE `last_known_modseq` column) sloučena zpět do `V1__init.sql`. Bezpečné pře prvním release — projekt nemá produkční data.
- `V2__remote_image_allowlist.sql` (tabulka `remote_image_sender`, per-sender opt-in pro remote obrázky z nálezu F2) sloučena zpět do `V1__init.sql`, DDL beze změny. Existující dev DB je potřeba znovu vytvořit — checksum V1 se změnil a Flyway validace je odmítne.

### Bezpečnost a OAuth

- Microsoft OAuth (Outlook / Exchange Online) backend hotový — frontend wizard přepnutý na OAuth flow pro `@outlook.com`, `@hotmail.com`, `@live.com`. Před produkčním release ještě potřeba verified publisher.
- OAuth failure UX: dedikovaná `auth-failed.html` stránka s `?reason=...` kódem (Spring `oauth2FailureHandler` loguje na WARN).

### Repo

- Doplněn pre-push report a release/security checklist pro první publikaci repozitáře.
- Programmer text v `backend/src/{main,test}/java` a `frontend/src/{lib,routes}/**/*.{ts,svelte}` migrován do angličtiny; load-bearing CZ fixtures (SMTP-bound labely, IMAP folder name testy, UTF-8 stress fixtures) explicitně whitelistovány. CI lint gate aktivní.
- Třetí strany — third-party license audit pro npm (`frontend/THIRD_PARTY_LICENSES.md`, 116 prod deps), Maven (`backend/THIRD_PARTY_LICENSES.md`, 132 compile/runtime artefaktů) a Cargo + bundled OpenJDK (`frontend/src-tauri/THIRD_PARTY_LICENSES.md`, 362 crates). Veškeré licence jsou permissive nebo weak-copyleft s linking exception; žádný GPL/AGPL/SSPL.
- Návrh `PRIVACY.md` (CZ) a `PRIVACY.en.md` (EN) v rootu repa pokrývající lokální data layout, šifrování, síťovou komunikaci, OAuth provider revoke postupy a kompletní data removal.
- Pre-push bezpečnostní scan workspace (21 staged + 559 untracked-non-gitignored, 0 high-confidence secret matches). Doplněn `.gitignore` o `*.jks`, `*.ed25519`, `*.patch`, `_tmp_*` jako defense-in-depth.
- SpotBugs zavázán do Maven `verify` lifecycle phase přes plugin `<executions>` v `backend/pom.xml`. Lokální `mvn verify` od teď gate enforcuje stejně jako CI (předtím se `spotbugs:check` spouštěl jen explicitně, což propustilo reálnou chybu — viz Backend Spotbugs SecureRandom fix). CI workflow zjednodušen: dedikovaný „Run SpotBugs" step smazán, je teď součástí jednoho `mvn verify` job stepu.
- Nový GitHub Actions workflow [.github/workflows/codeql.yml](.github/workflows/codeql.yml) — Java backend + JS/TS frontend matrix, query suite `security-and-quality`, triggery `push`/`pull_request`/weekly cron, SARIF upload do GitHub Security tab. Komentáře vysvětlují advisory-only stav (až po sledování noise levelu lze wire do branch protection).
- README harmonizace — root [README.md](README.md) přejmenován z 65 → 88 řádků (+1 věta o sidecar architektuře, doc mapa rozdělena do 4 sekcí: Module entry points / Operations & release / History / Legal & compliance / Live task tracking). [frontend/README.md](frontend/README.md) ztenčen z 359 → 233 řádků (smazána duplicate Stack sekce, outdated „Publishing to GitHub" sekce, low-value „Current UI Guidance" sekce; Windows Code Signing přesunut do dedikovaného doc). Nový [backend/README.md](backend/README.md) (51 r.) jako thin TOC pointing to OPERATIONS / RELEASE_CHECKLIST / SECURITY / PERFORMANCE / CHANGELOG / THIRD_PARTY_LICENSES. Nový [frontend/docs/WINDOWS_SIGNING.md](frontend/docs/WINDOWS_SIGNING.md) (81 r.) s kompletním návodem na PFX cert, Tauri updater keys, GitHub secrets a local signed build.

## Šablona pro nový release

```text
## [0.X.0] - YYYY-MM-DD

### Backend
- Added / Changed / Fixed

### Frontend
- Added / Changed / Fixed

### Tauri / Release
- Installer, updater, signing, compatibility notes

### Migrations
- V<N>__<popis>.sql - dopad a recovery poznámky

### Security / OAuth
- Scope changes, secret rotation, dependency notes

### Manual steps
- Kroky pro release operatora nebo uživatele
```
