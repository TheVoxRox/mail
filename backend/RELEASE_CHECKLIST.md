# Release checklist

Checklist pro poslední ověření před vydáním desktop aplikace se sidecar backendem.

Vyplňovat pro konkrétní kandidát:

```text
Release:
Datum:
Backend commit:
Frontend commit:
Platforma:
Tester:
```

## 1. Backend build

- [x] `mvn -Dmaven.repo.local=.m2repo -Dapp.data-dir=target/test-data spotless:check`
- [x] `mvn -Dmaven.repo.local=.m2repo -Dapp.data-dir=target/test-data package`
- [x] Ověřit, že build hlásí `Tests run: 568, Failures: 0, Errors: 0, Skipped: 0` nebo vyšší aktuální počet.
- [x] Vznikl artefakt `target/mail-backend-0.1.0.jar`.
- [x] Windows sidecar balení prošlo přes `scripts/package-sidecar-windows.ps1`.
- [x] Sidecar launcher `app/mail-x86_64-pc-windows-msvc.cfg` nese zapečené `...google.client-id` (ne `mail-local-*` placeholder). Signed workflow i `package-sidecar-dev-windows.ps1` to zajišťují; bare `package-sidecar-windows.ps1` bez OAuth env teď build shodí, pokud neběží s `-AllowPlaceholderOAuth`. **Ověřeno 2026-06-26** (`package-sidecar-dev-windows.ps1 -SkipTests`): 3 OAuth hodnoty injektnuty, vestavěný verifikační krok hlásí „Google client-id baked into the launcher; no placeholder"; `.cfg` nese `google.client-id` + `google.client-secret` + `microsoft.client-id`, žádný `mail-local-*`.
- [x] Sidecar výstup obsahuje `mail-x86_64-pc-windows-msvc.exe`, `app/` a `runtime/`. **Ověřeno 2026-06-26:** top-level `mail-x86_64-pc-windows-msvc.exe` (495 KB launcher) + `app/` (jar 77.9 MiB + `.cfg` + `.jpackage.xml`) + `runtime/` (123 MB bundled JRE).
- [ ] Release kandidát běží na čistém Windows profilu bez systémově instalované Java/JDK/JRE. Pozn.: `runtime/` je strukturálně self-contained — bundled JRE `JAVA_VERSION=25.0.3` s `bin/jli.dll`, `bin/java.dll`, `bin/server/jvm.dll` (nativní launcher zavádí JVM přes JNI, samostatné `java.exe` jpackage app-image záměrně nepřibaluje). Zbývá už jen reálný běh na čistém stroji.

## 2. Frontend automation

Spouštět ve frontend repu proti dev/preview buildu se spuštěným backend sidecarem nebo mock režimem tam, kde je to záměrné.

- [x] `npm run generate:api`
- [x] `npm run check:i18n`
- [x] `npm run build`
- [x] `npm run test:e2e`
- [x] `npm run test:functional`
- [x] `npm run test:a11y`

Poznámky:

```text
2026-05-08: Backend package prošel s Tests run: 625, Failures: 0, Errors: 0, Skipped: 0.
2026-05-08: Windows sidecar balení prošlo po samostatném backend package přes scripts/package-sidecar-windows.ps1 -SkipTests a následně npm run sidecar:sync:windows.
2026-05-08: npm run test:e2e prošel; wrapper spouští functional suite a a11y suite přes stable preview režim.
2026-05-08: npm run test:a11y byl sjednocen na stable preview runner a prošel 42/42.
2026-06-26: `mvn verify` na main (3210e1a) zelený — surefire 885 + failsafe 28 = 913 testů, 0 fail/error/skip; artefakt mail-backend-0.1.0.jar (77.9 MB). Frontend gate (3210e1a) zelený — check (1368 souborů, 0 chyb), check:i18n (570 klíčů), check:i18n:backend (82), check:translations:strict, knip, test:unit (357), build, test:functional:stable (116), test:a11y:stable (54). Log-scan gate (§7/§8) projet ručně na posledním smoke logu: bug D = 0, audit.log 0 CRITICAL; zbylé ERROR/WARN jsou pre-fix šum z buildu před #69/#70/#71 (bugy F/G/E).
2026-06-26: §1.23–24 (sidecar balení) ověřeno čerstvým `package-sidecar-dev-windows.ps1 -SkipTests` z main (3516bf5, kód beze změny od 3210e1a — od té doby jen docs commity). BUILD SUCCESS, jpackage app-image do `target/sidecar/x86_64-pc-windows-msvc/`. §1.23: vestavěný verify krok „Google client-id baked into the launcher; no placeholder", `.cfg` nese google client-id+secret + microsoft client-id, 0× `mail-local-`. §1.24: top-level exe + `app/` (jar 77.9 MiB) + `runtime/` (123 MB JRE, Java 25.0.3, jli/java/jvm.dll). §1.25 zůstává — runtime self-contained, ale reálný běh na čistém Windows profilu bez systémové Javy je ruční krok.
```

## 3. Fresh install

- [ ] Nainstalovat release kandidáta přes Windows NSIS installer `voxrox-mail-<version>-windows-x64-setup.exe` na čistý profil bez existujícího `%LOCALAPPDATA%\VoxRox\Mail`.
- [ ] Ověřit výchozí cestu instalace (per-user, `installMode: currentUser`): binárky v `%LOCALAPPDATA%\Programs\VoxRox\Mail`, oddělené od dat v `%LOCALAPPDATA%\VoxRox\Mail`.
- [ ] V instalační složce `%LOCALAPPDATA%\Programs\VoxRox\Mail` jsou přibalené sidecar `mail-x86_64-pc-windows-msvc.exe`, `app/` a `runtime/`.
- [ ] Spustit klienta.
- [ ] Backend sidecar se auto-startne.
- [ ] Vznikne `${app.data-dir}/crypto.bin`.
- [ ] Po prvním spuštění existuje `%LOCALAPPDATA%\VoxRox\Mail\webview\` (WebView2) a `%LOCALAPPDATA%\VoxRox\Mail\logs\mail-frontend.log` (Tauri log).
- [ ] V `%LOCALAPPDATA%` NEVZNIKLA žádná složka `org.voxrox.mail*` — všechna data jsou pod `VoxRox\Mail`.
- [ ] Release start nepoužívá `MAIL_CRYPTO_KEY`/`MAIL_CRYPTO_SALT` z lokálního `backend/.env`; desktop default je `crypto.bin`.
- [ ] Opakovaný start se stávajícím `crypto.bin` projde i při stale `crypto.fingerprint`; interní handshake API klíč je generovaný v paměti při každém startu a zapsaný do `session.json` (žádný persistentní artefakt k rotaci), uživatelské credentials zůstávají nedotčené.
- [ ] Vznikne `${app.data-dir}/session.json`.
- [ ] `session.json` obsahuje aktuální náhodný loopback port a `baseUrl` ve tvaru `http://127.0.0.1:<port>/api`.
- [ ] Vznikne `${app.data-dir}/.ready`.
- [ ] UI neukáže error dialog.
- [ ] `/api/internal/health` vrací 200 s `X-API-KEY` ze `session.json`.

## 3a. Installer and update behavior

- [ ] Instalace je per-user (`installMode: currentUser`) — installer neukazuje volbu režimu ani nevyžaduje elevaci (UAC). Binárky jdou do `%LOCALAPPDATA%\Programs\VoxRox\Mail`.
- [ ] Jazyk instalátoru je automaticky Czech/English podle systému; ostatní locale padají na English.
- [ ] Desktop shortcut je volitelný checkbox.
- [ ] Start menu shortcut vznikne jako `VoxRox\VoxRox Mail`.
- [ ] Reinstall stejné verze projde bez ztráty dat.
- [ ] Downgrade na starší verzi je zablokovaný.
- [ ] Signed release obsahuje `voxrox-mail-<version>-windows-x64-setup.exe`, `.sig` a `latest.json`.
- [ ] **Shoda podpisového páru:** veřejný klíč zabalený v běžící appce odpovídá podpisovému privátnímu klíči (`TAURI_UPDATER_PUBKEY` ↔ `TAURI_SIGNING_PRIVATE_KEY`). Dokáže to úspěšný update smoke vN-1 → vN — při nesouladu updater odmítne `.sig` se „signature" chybou. **Neopravitelné po shipnutí**, proto povinný bod.
- [ ] Release build prošel podepsaným workflow (`windows-signed-release.yml`), který aplikuje `tauri.release.conf.json` (env `TAURI_UPDATER_*`). Base `tauri.conf.json` updater blok (endpoint + pubkey) je jen dev reference — holý `npm run tauri:build` by shipnul base hodnoty bez `.sig`; ručně sestavený build nepublikovat.
- [ ] Release channels: tag odpovídá verzi v `tauri.conf.json` (workflow jinak spadne); prerelease-suffixovaný tag (`vX.Y.Z-…`) vznikl jako GitHub **prerelease** (mimo `releases/latest` redirect = mimo stabilní kanál); po Publish doběhl `beta-channel.yml` a `releases/download/beta/latest.json` nese publikovanou verzi. Halt/re-point postup: [OPERATIONS.md](OPERATIONS.md) „Release channels".

## 4. Account flows

- [ ] Přidat PASSWORD účet s předdefinovaným providerem.
- [ ] Přidat PASSWORD účet s custom IMAP/SMTP nastavením.

### Google OAuth (Gmail)

Předpoklady před smoke:

- [ ] `GOOGLE_OAUTH_CLIENT_ID` / `GOOGLE_OAUTH_CLIENT_SECRET` v release buildu jsou *reálné produkční* hodnoty z Google Cloud Console ("Desktop app" client), ne `mail-local-*` placeholder. Bez nich Google odmítne login `Error 401: invalid_client` / "OAuth client was not found" (root cause 2026-06-17: produkční sidecar zabalený bez OAuth env → launcher `.cfg` bez `...google.client-id`; ověřit položkou v §1).
- [ ] OAuth consent screen je publikovaný (`In production`), ne `Testing` — jinak refresh token exspiruje po 7 dnech a přihlásí se jen přidaní test users.
- [ ] Restricted scope `https://mail.google.com/` (`application.properties`) má dokončené Google verification (CASA security assessment). Bez něj externí uživatelé dostanou "unverified app" obrazovku / limit 100 uživatelů. **Blokující bod pro produkční consumer release** (protějšek MS „verified publisher").

Smoke flow:

- [ ] Přidat Google OAuth účet proti reálnému účtu přes loopback redirect s aktuálním náhodným portem. **Blokující — potvrzuje, že zapečené client-id Google přijme.**
- [ ] Revoke Google grant na straně Googlu.
- [ ] Další sync označí účet jako `requires_reauth=true`.
- [ ] UI ukáže výzvu k opětovnému přihlášení.
- [ ] Re-login účet vrátí do syncable stavu.

### Microsoft OAuth (Outlook / Exchange Online)

Předpoklady před smoke:

- [ ] Azure App Registration `VoxRox Mail` existuje pod `info@voxrox.org` v tenantu `Default Directory (infovoxrox.onmicrosoft.com)`.
- [ ] **Application (client) ID** v `backend/.env` (`MICROSOFT_OAUTH_CLIENT_ID`) odpovídá hodnotě v Azure → Přehled → "ID aplikace (klienta)". POZOR: nepoužít hodnotu sloupce "Tajné ID" z tabulky client secrets — to je Secret ID, ne Application ID (záměna byla root cause `unauthorized_client` 2026-05-20).
- [ ] **Bez client secret** — Microsoft je registrovaný jako *public client* (`client-authentication-method=none`, flow jištěný PKCE). `MICROSOFT_OAUTH_CLIENT_SECRET` se neposílá na token endpoint ani nezabaluje do instalátoru; je nepoužitý a může zůstat prázdný.
- [ ] **Supported account types** v Azure → Authentication = "Účty v libovolném adresáři organizace + osobní účty Microsoft" (multitenant + MSA, souhlasí s tenantem `common` v `application.properties`).
- [ ] **Platform** = "Mobilní a klasické aplikace" (Mobile and desktop applications), **Redirect URI** = přesně `http://localhost/login/oauth2/code/microsoft` (bez portu, bez https, `localhost` NE `127.0.0.1`). POZOR: migrace z původní Web platformy — pod Web platformou by public client (bez secretu) skončil na `AADSTS7000218` (chybí client_secret).
- [ ] Azure → Authentication → **"Povolit toky veřejného klienta" (Allow public client flows) = Ano**.
- [ ] ~~Client secret rotace~~ **odpadá** — public client žádný secret nepoužívá, takže není co rotovat ani co exspiruje (dříve plánováno na 2028-04-18).
- [ ] **Verified publisher / Partner One ID** (dříve MPN ID) — **ODLOŽENO (rozhodnuto 2026-06-20: zatím bez právnické osoby).** NENÍ blokující pro consumer release: MSA účty (`@outlook.com`/`@hotmail.com`/`@live.com`) i vlastní tenant (`@voxrox.org`) dostanou consent i bez něj (uvidí jen „unverified" notici). Tvrdý blok AADSTS700016 (reprodukováno 2026-05-20 s cizím enterprise tenantem) platí JEN pro cizí organizační Entra tenanty (B2B) — to publikum je odloženo, dokud nevznikne ověřená právnická osoba (MS business verification vyžaduje firmu ověřenou proti oficiálním rejstříkům). Postup až/pokud entita vznikne viz `todo.md` sekce „Microsoft OAuth".

Smoke flow:

- [ ] Přidat Microsoft OAuth účet proti reálnému `@outlook.com` účtu — wizard nabídne Microsoft tlačítko po zadání domény.
- [ ] Microsoft authorization stránka **ukáže account picker** (důsledek `prompt=select_account` v `application.properties`), případně i consent screen.
- [ ] Po consent přesměrování na `http://localhost:<port>/login/oauth2/code/microsoft` projde, účet je vytvořen v DB s providerem `outlook.com` a `requires_reauth=false`.
- [ ] První IMAP sync naběhne přes OAuth2 / XOAUTH2 SASL proti `outlook.office365.com:993` a stáhne realný inbox.
- [ ] SMTP send přes OAuth proti reálnému účtu odešle zprávu (vyžaduje `SMTP.Send` scope v tokenu — již je v `application.properties`).
- [ ] Opakovat smoke s `@hotmail.com` a `@live.com` (oba jsou v provider seedu).
- [ ] Refresh token roundtrip: po expiraci access tokenu (3599 s) `MicrosoftTokenService` automaticky obnoví token a sync pokračuje bez user akce.
- [ ] Revoke Microsoft grant na straně Microsoftu (`https://account.microsoft.com/privacy/app-access`).
- [ ] Další sync označí účet jako `requires_reauth=true`.
- [ ] UI ukáže výzvu k opětovnému přihlášení.
- [ ] Re-login účet vrátí do syncable stavu.

## 5. Mail workflows

- [ ] Full sync 5000+ zpráv proti reálnému IMAP účtu.
- [ ] Otevřít detail zprávy s HTML obsahem.
- [ ] Otevřít zprávu s přílohou.
- [ ] FTS5 search najde očekávaný hit.
- [ ] FTS5 search vrátí prázdný výsledek pro neexistující dotaz.
- [ ] Odeslat nový e-mail.
- [ ] Odeslat e-mail s 30 MB přílohou.
- [ ] Uložit draft.
- [ ] Odeslat uložený draft.
- [ ] Reply.
- [ ] Reply all.
- [ ] Move to Trash.
- [ ] Move to custom folder.
- [ ] Toggle `seen`.
- [ ] Toggle `flagged`.

## 6. Sidecar lifecycle

- [ ] Ukončit Tauri parent proces přes Task Manager / `kill -9`.
- [ ] Ověřit, že sidecar backend nezůstane běžet jako osiřelý Java proces.
  - _Od 2026-06-30 to hlídá parent-death watchdog ([ParentProcessWatchdog.java](src/main/java/org/voxrox/mailbackend/core/lifecycle/ParentProcessWatchdog.java)): frontend spouští sidecar s `MAIL_SIDECAR_WATCH_PARENT=1`, backend čte `System.in` a na uzavření stdin pipe (smrt frontendu, vč. force-kill) zavolá `System.exit(0)`. Tento bod ověřuje, že to na zabaleném buildu reálně funguje — JVM `mail` má do pár sekund zmizet z Task Manageru._
  - _Ověřeno 2026-06-30 na dev-zabaleném sidecaru přes tauri:dev: force-kill `app.exe` → oba `mail.exe` (launcher + JVM grandchild) zmizely za ~13 s, 0 orphanů (z toho ~10 s graceful timeout na visící SSE, pak čistý DB close). Na podepsaném release buildu zopakovat._
- [ ] Ukončit jen sidecar backend.
- [ ] UI ukáže srozumitelný error stav.
- [ ] UI nabídne restart sidecaru.
- [ ] Restart sidecaru obnoví health a běžné API volání.
- [ ] Restart počítače uprostřed syncu.
- [ ] Další spuštění projde SQLite WAL recovery a aplikace pokračuje.
- [ ] Poškozená DB (`mail.db` nebo WAL) — aplikace nastartuje do čitelného error stavu, ne do tichého pádu.
- [ ] Obnova ze zálohy `db/mail.db.backup-pre-v*` (pre-migration snapshot) — po nahrazení DB zálohou appka nastartuje a data odpovídají snapshotu.
- [ ] Disk full během syncu — sync selže kontrolovaně (čitelný error, žádná korupce DB), po uvolnění místa pokračuje.

## 7. Diagnostics

- [ ] Zkontrolovat `logs/mail.log` bez neočekávaných `ERROR` (známé transienty — viz log-scan gate v §8).
- [ ] Zkontrolovat `logs/audit.log` bez neočekávaných `CRITICAL`.
- [ ] Vygenerovat `/api/internal/diagnostic-dump`.
- [ ] ZIP obsahuje `summary.json`, `accounts.json`, `folder-sync-states.json`, `message-counts.json`, `runtime.json`.
- [ ] ZIP neobsahuje plné e-mailové adresy, OAuth tokeny, interní API klíč ani obsah zpráv.

## 8. Long run

**Gate (proporcionální pro v0.1.0):** nechat appku běžet hodiny až 24 h se zapnutým syncem + projet log-scan gate a kontroly růstu paměti / SQLite DB / WAL / duplicit níže. **Hloubková JFR + JDK Mission Control analýza (lock contention) je volitelný post-release deep-dive** — dělat až při reálné stížnosti na výkon nebo podezření na leak, ne jako blocker prvního releasu.

- [ ] Nechat aplikaci běžet 24 h se zapnutým syncem.
- [ ] *(Volitelné, post-release deep-dive)* Spustit běh s JFR: `-XX:StartFlightRecording=duration=24h,filename=soak.jfr,settings=profile` (u sidecaru přidat do `--java-options` v package skriptu, u dev běhu do `JAVA_TOOL_OPTIONS`).
- [ ] *(Volitelné, post-release deep-dive)* Vyhodnotit `soak.jfr` v JDK Mission Control: lock contention (Java Monitor Blocked / Park) na `accountLocks`/`refreshLocks`, počty výjimek, růst vláken (leak executorů).
- [ ] *(Volitelné, post-release deep-dive)* Na konci běhu sebrat thread dump (`jcmd <pid> Thread.print`) — žádná osiřelá/parkovaná vlákna mimo známé pooly.
- [ ] Zkontrolovat paměťovou stopu.
- [ ] Zkontrolovat růst SQLite DB/WAL.
- [ ] Zkontrolovat, že IMAP pool nerecykluje chybně mrtvá spojení.
- [ ] Zkontrolovat, že opakované syncy nevytvářejí duplicitní zprávy.
- [ ] Log-scan gate po každém smoke/long runu: `Select-String -Path logs\mail.log -Pattern "ERROR|WARN"` a každý nález buď vysvětlit, nebo založit issue — tichá chybová cesta je přesně třída chyb z review 2026-06.
- [ ] Pasivní log-watch přechodného hiccupu **D** (`failed to create new store connection`) — od #78 obalený bounded retry+backoff, transient klasifikuje [TransientMailErrors.java:39](src/main/java/org/voxrox/mailbackend/feature/mail/service/TransientMailErrors.java). Scan `logs\mail.log` na tři signály:
  - **Zdravé:** `WARN` „Transient IMAP error during folder sync … reconnecting and retrying" ([MailSyncService.java:255](src/main/java/org/voxrox/mailbackend/feature/mail/service/MailSyncService.java)) následované zotavením v dalším pokusu — pár/den je očekávaný šum, jen zaznamenat počet.
  - **Eskalovat (má být ~0):** `ERROR` „Folder sync … still failing after N transient-retry attempt(s)" ([MailSyncService.java:250](src/main/java/org/voxrox/mailbackend/feature/mail/service/MailSyncService.java)) = retry budget vyčerpán → prošetřit příčinu / zvětšit `mail.client.retry.*`.
  - **Prošetřit klasifikátor:** `ERROR` „Critical error during folder sync … failed to create new store connection" ([MailSyncService.java:239](src/main/java/org/voxrox/mailbackend/feature/mail/service/MailSyncService.java)) by se pro transientní příčinu už neměl objevit; pokud ano, `TransientMailErrors` ji minul → doplnit klasifikátor.
  - Při eskalaci zapsat dimenze: shlukuje se po sleep/wake nebo změně sítě? provider/složka? potvrzené zotavení v dalším cyklu?

## 8a. Docs & web sync

- [ ] `npm run check` zelený — zahrnuje doc-claims lint (verze stacku a zastaralé fráze v README/CONTRIBUTING vs `pom.xml`/`package.json`/`.nvmrc`).
- [ ] Datum verze na <https://voxrox.org/privacy/> (CS i EN) odpovídá `PRIVACY.md` / `PRIVACY.en.md` v repu.
- [ ] Stránka <https://voxrox.org/support/> odpovídá `SECURITY.md` (podporované verze, lhůty, rozsah zranitelností).
- [ ] `frontend/END_USER_README.md` odpovídá vydávané verzi (funkce, stav OAuth providerů, název instalátoru).
- [ ] Status badge na <https://voxrox.org> („Ve vývoji (Beta)" apod.) odpovídá realitě vydání.
- [ ] GitHub repo metadata: description + Website (`https://voxrox.org`) vyplněné; stránka Releases obsahuje publikovaný release (ne jen draft).

## 8b. Trvalá pravidla od publikace release (revize #170)

Časované procesní bomby z revize 2026-07-17 (issue #170). První dva body nejsou jednorázové kontroly — dnem publikace prvního release se z nich stávají trvalá pravidla.

- [ ] **V1 migrace je dnem publikace zmražená.** Pre-release pravidlo „edituj `V1__init.sql` in place + resetni dev DB" končí prvním nainstalovaným release. Každá další změna schématu = nová `V2+` migrace. `application.properties` Flyway validaci nevypíná (default `validate-on-migrate=true`) — instalace se zapsaným checksumem V1 by po updatu s editovanou V1 spadla na `Migration checksum mismatch` a sidecar nenastartuje (projeví se jako záhadný crash při startu u uživatele).
- [ ] **Každý publikovaný release = plný podepsaný build.** Stabilní kanál čte `releases/latest/download/latest.json` (`frontend/src-tauri/tauri.conf.json`) a signed workflow zapíná `VITE_ENABLE_AUTO_UPDATE_CHECK=1`, takže **cokoli** publikovaného v repu se stává „latest" a všechny instalace to kontrolují při každém startu. Release publikovaný bez `latest.json` + `.sig` (třeba jen tag s poznámkami) = chyba update checku při každém startu všech instalací. Poznámky, částečné buildy apod. držet jako **draft** nebo **prerelease** (prereleasy `releases/latest` redirect přeskakuje — viz OPERATIONS.md „Release channels").
- [ ] **Revize dočasných pinů a výjimek** (při každém release + při každém bumpu dotčené závislosti zkontrolovat removal conditions):
  - jackson override `<jackson-2-bom.version>2.21.5</jackson-2-bom.version>` v `backend/pom.xml` — odstranit, až Spring Boot managed `jackson-2-bom.version` >= 2.21.5 (SB 4.1.0 = 2.21.4).
  - quick-xml výjimka ve vuln-scanu — removal conditions u výjimky.
  - dependabot ignore TypeScript 7.x (typescript-eslint cap < 6.1.0, viz #147).

## 9. Release decision

```text
Blockery:

Known issues:

Schváleno:
```

---

## Příloha — smoke worksheet v0.1.0

Per-candidate worksheet pro ruční smoke (§3–§8 výše). §1 (backend build) a §2 (frontend automation) jsou odškrtané v checklistu výše — tady interaktivní část. _(Do 2026-07-20 samostatný soubor `RELEASE_SMOKE_v0.1.0.md`, sloučeno sem při konsolidaci dokumentace.)_

**Worksheet je zformalizovaný z existujících důkazů (2026-06-23 tauri:dev smoke, 2026-06-25 Phase B signed smoke na čistém profilu, 2026-06-26 build+log-scan gate).** Hotové položky nesou datum+zdroj, otevřené `[ ]` jsou reálné mezery, které ještě nikdo neproved.

#### Legenda
- `[x]` — přímo ověřeno (datum + zdroj inline)
- `[x] (impl.)` — implicitně pokryto širším smoke (clean boot / reálný sync by bez toho neproběhl), neitemizováno zvlášť
- `[ ]` — reálná mezera, zbývá ověřit

```text
Release:        VoxRox Mail 0.1.0  (identifier org.voxrox.mail)
Datum sign-off: ____________
Backend commit: 3516bf5  (monorepo HEAD 2026-06-26 14:44)
Frontend commit:3516bf5  (stejné monorepo)
Platforma:      Windows 11 Pro x64
Tester:         Lukáš Lacina
Build původ:    Phase B (2026-06-25) = signed build · lokální čerstvý NSIS 2026-06-26 (HEAD 3516bf5)
```

### Build kandidáta

- **Signed (autoritativní pro release rozhodnutí):** GitHub `windows-signed-release.yml` → `voxrox-mail-0.1.0-windows-x64-setup.exe`, `.sig`, `latest.json`.
- **Lokální generálka (postaveno 2026-06-26 z HEADu 3516bf5):**
  ```powershell
  cd C:\dev\java\mail\backend ; .\package-sidecar-dev-windows.ps1 -SkipTests
  cd C:\dev\java\mail\frontend ; npm run tauri:build:with-sidecar
  # → src-tauri\target\release\bundle\nsis\voxrox-mail-0.1.0-windows-x64-setup.exe
  ```

#### Helper — health / diagnostic-dump (čte port+klíč ze session.json)

```powershell
$data = "$env:LOCALAPPDATA\VoxRox\Mail"
$s = Get-Content "$data\session.json" -Raw | ConvertFrom-Json
$s | Format-List appVersion, apiVersion, minClientVersion, dbSchemaVersion, port, baseUrl
Invoke-RestMethod "$($s.baseUrl)/internal/health" -Headers @{ 'X-API-KEY' = $s.apiKey }   # → 200 + status
# Invoke-WebRequest "$($s.baseUrl)/internal/diagnostic-dump" -Headers @{ 'X-API-KEY' = $s.apiKey } -OutFile "$env:TEMP\diag-dump.zip"
```

#### Helper — log scan (bug D + ostatní)

```powershell
$logs = "$env:LOCALAPPDATA\VoxRox\Mail\logs"
Select-String -Path "$logs\mail.log" -Pattern 'ERROR|WARN' | Measure-Object
Select-String -Path "$logs\mail.log","$logs\mail.log.*" -Pattern 'failed to create new store connection' | Measure-Object  # bug D ≤~1/den = šum
Select-String -Path "$logs\audit.log" -Pattern 'CRITICAL'
```

### §3 Fresh install

- [x] Instalace `voxrox-mail-0.1.0-windows-x64-setup.exe` na čistý profil — **2026-06-25 Phase B** (signed build, čistý `%LOCALAPPDATA%\VoxRox\Mail`).
- [ ] Výchozí cesta instalace (per-user, `installMode: currentUser`): binárky v `%LOCALAPPDATA%\Programs\VoxRox\Mail`, data oddělená v `%LOCALAPPDATA%\VoxRox\Mail`. **Znovu ověřit na currentUser buildu — Phase B testovala starší layout.**
- [x] V instalační složce `mail-x86_64-pc-windows-msvc.exe`, `app/`, `runtime/` — sidecar balení **§1.24 ověřeno 2026-06-26**.
- [x] Spustit klienta → sidecar auto-start — **2026-06-23 + 2026-06-25**.
- [x] Vznikne `crypto.bin` — **2026-06-23** (čistý profil → `VOXSEC1` + DPAPI blob, žádný plaintext).
- [x] (impl.) `webview\` + `logs\mail-frontend.log` — app běžela, bez nich Tauri webview nenaběhne.
- [x] V `%LOCALAPPDATA%` NEVZNIKLA `org.voxrox.mail*` — data pod `VoxRox\Mail` (project_data_dirs, Phase B čistý profil).
- [x] Release start NEpoužívá `MAIL_CRYPTO_KEY/SALT` z `.env` — `crypto.bin` per-machine **2026-06-23**.
- [x] Restart se stávajícím `crypto.bin` projde i při stale fingerprint, credentials nedotčené — **2026-06-23** (restart → oba účty dešifrují bez re-loginu).
- [x] (impl.) `session.json` s portem + `baseUrl http://127.0.0.1:<port>/api` — clean boot syncoval poštu, což bez session.json nejde.
- [x] (impl.) Vznikne `.ready` — FE nepokračuje za boot bez `.ready`.
- [x] UI bez error dialogu — **2026-06-25** (smoke po fixech #65–#71 proběhl čistě).
- [x] (impl.) `/api/internal/health` → 200 s `X-API-KEY` — FE bez health 200 nezačne mluvit s backendem.

→ Pozn.: all-users (`%ProgramFiles%`) režim byl 2026-07-07 zrušen — instalace je nově výhradně per-user (`installMode: currentUser`, viz threat model AR-2), install cesta se přesunula na `%LOCALAPPDATA%\Programs\VoxRox\Mail`.

### §3a Installer / update chování

- [ ] Instalace je per-user (`installMode: currentUser`) — bez volby režimu, bez elevace (UAC). Binárky v `%LOCALAPPDATA%\Programs\VoxRox\Mail`.
- [ ] Jazyk instalátoru automaticky CZ/EN podle systému.
- [ ] Desktop shortcut = volitelný checkbox.
- [ ] Start menu shortcut `VoxRox\VoxRox Mail`.
- [ ] Reinstall stejné verze bez ztráty dat.
- [ ] Downgrade na starší verzi zablokovaný.
- [ ] Signed release obsahuje `voxrox-mail-0.1.0-windows-x64-setup.exe`, `.sig`, `latest.json`. _(= Release&Update / Tauri updater položka, signed-only.)_

→ Pozn.: celá §3a neitemizovaná — mode/jazyk/shortcuts/reinstall/downgrade zbývají k cílenému ověření.

### §4 Account flows

- [ ] PASSWORD účet s předdefinovaným providerem. _(Netestováno — bez password účtu.)_
- [ ] PASSWORD účet s custom IMAP/SMTP. _(Netestováno.)_

#### Google OAuth
Předpoklady: [x] reálné prod `GOOGLE_OAUTH_CLIENT_ID/SECRET` (org-based client, **2026-06-23**) · **[ ] consent screen `In production` — projekt je pořád `Testing`** (refresh token padá po 7 dnech mimo test users) · **[ ] restricted scope `https://mail.google.com/` CASA verifikace — BLOKUJÍCÍ pro consumer release.**

- [x] Přidat Google OAuth přes loopback — **2026-06-23 + 2026-06-25** (po fixu E #71 bez `auth-failed`).
- [x] První IMAP sync — **2026-06-23** (bounded sync bez zaseknutí).
- [x] SMTP send přes OAuth — **2026-06-23**.
- [ ] Revoke grant u Googlu → `requires_reauth=true` → UI výzva → re-login. _(Cyklus neproveden — patří k „Produkcni OAuth readiness".)_

#### Microsoft OAuth
Předpoklady: [x] App Registration `VoxRox Mail` pod `info@voxrox.org` · [x] public client bez secretu (PKCE), platforma „Mobilní a klasické", redirect `http://localhost/login/oauth2/code/microsoft`, public-client flows = Ano (**2026-06-10**) · [ ] Verified publisher ODLOŽEN _(není blocker pro MSA/vlastní tenant)._

- [x] Přidat MS OAuth proti `@outlook.com` — **2026-06-23** (MSA login).
- [x] Po consent účet vytvořen, provider `outlook.com`, `requires_reauth=false` — **2026-06-23**.
- [x] První IMAP sync přes XOAUTH2 (`outlook.office365.com:993`) — **2026-06-23**.
- [x] SMTP send přes OAuth (587/STARTTLS po fixu #58) — **2026-06-23**.
- [x] Refresh token roundtrip (auto-refresh po expiraci) — **2026-06-23** (vč. po restartu).
- [ ] Zopakovat s `@hotmail.com` a `@live.com`. _(Jen `@outlook.com` testován.)_
- [ ] Revoke MS grant → `requires_reauth=true` → UI výzva → re-login. _(Cyklus neproveden.)_

→ Pozn.: enterprise tenanty (B2B) až po verified publisher — mimo cílové publikum v0.1.0.

### §5 Mail workflows

- [ ] Full sync 5000+ zpráv. _(Sync ověřen, ale ne v tomto měřítku.)_
- [x] (impl.) Detail zprávy s HTML obsahem — **2026-06-25** (otevírání detailu jádro bugu „duch zprávy 404").
- [ ] Zpráva s přílohou. _(Netestováno.)_
- [ ] FTS5 search — hit. _(Netestováno.)_
- [ ] FTS5 search — prázdný výsledek. _(Netestováno.)_
- [x] Odeslat nový e-mail — **2026-06-23** (SMTP send Google+MS).
- [ ] Odeslat e-mail s 30 MB přílohou. _(Netestováno.)_
- [ ] Uložit draft → odeslat uložený draft. _(Draft data-loss bug fixnut v auditu 2026-06-06, ale UI uložení/odeslání draftu v release smoke neitemizováno.)_
- [ ] Reply → Reply all. _(Netestováno.)_
- [ ] Move to Trash → Move to custom folder. _(Netestováno.)_
- [ ] Toggle `seen` → toggle `flagged`. _(Netestováno.)_

→ Pozn.: §5 největší reálná mezera — pokryto jen send + detail view; zbytek (příloha, scale, FTS, draft, reply, move, flagy) čeká.

### §6 Sidecar lifecycle

- [x] Kill Tauri parent → sidecar NEzůstane osiřelý — **2026-06-23** (BootErrorView Retry: žádný orphan/dvojí spawn).
- [x] Kill jen sidecar → UI error stav → nabídne restart — **2026-06-23**.
- [x] Restart sidecaru obnoví health + API (přesně 1 backend) — **2026-06-23**.
- [ ] Restart počítače uprostřed syncu → SQLite WAL recovery → aplikace pokračuje. _(Netestováno.)_

→ Pozn.: kill/orphan/restart pokryto; chybí jen reboot-mid-sync WAL recovery.

### §7 Diagnostics

- [x] `logs\mail.log` bez neočekávaných `ERROR` — **2026-06-26** log-scan gate (bug D = 0).
- [x] `logs\audit.log` bez `CRITICAL` — **2026-06-26** (0 CRITICAL).
- [ ] Vygenerovat `/api/internal/diagnostic-dump` (helper výše). _(Netestováno.)_
- [ ] ZIP obsahuje `summary.json`, `accounts.json`, `folder-sync-states.json`, `message-counts.json`, `runtime.json`. _(Netestováno.)_
- [ ] ZIP NEobsahuje plné e-mailové adresy, OAuth tokeny, interní API klíč, obsah zpráv. _(Netestováno — důležitý PII check.)_

→ Pozn.: log/audit scan zelený; diagnostic-dump + PII redakce zbývají.

### §8 Long run (24 h)

- [ ] Celá sekce NEPROVEDENA — 24h soak + JFR, vyhodnocení v JMC (lock contention `accountLocks`/`refreshLocks`, leak vláken, růst DB/WAL, duplicity, thread dump). Samostatný celodenní běh.

### §9 Release decision

```text
Blockery (otevřené):
  - Google consent screen v Testing + CASA verifikace restricted scope (consumer release blocker)
  - End-to-end signed release dry-run (.sig / latest.json / updater) neproveden
  - §8 24h soak neproveden

Known issues:
  - Bug D: transientní "failed to create new store connection" — pasivní watch (≤~1/den šum)

Reálné mezery ručního smoke (nízké/střední riziko, viz výše):
  - §3a installer chování (mode/jazyk/shortcuts/reinstall/downgrade)
  - §4 PASSWORD účty, Google+MS revoke→re-login cyklus, @hotmail/@live
  - §5 příloha / 30 MB / sync 5000+ / FTS / draft / reply / move / flagy
  - §6 reboot-mid-sync WAL recovery
  - §7 diagnostic-dump + PII check

Schváleno (datum + podpis):
```
