# VoxRox Mail Backend - provozni runbook

Interni udrzbarska prirucka pro single-user desktop backend. Cilem je rychle rozlisit, jestli problem lezi ve startu sidecaru, databazi, IMAP/SMTP/OAuth vrstve, nebo v klientovi.

## Zakladni cesty

Defaultni standalone datovy adresar backendu:

```text
${user.home}/.voxrox/mail
```

Desktop/Tauri release na Windows pouziva explicitni datovy adresar:

```text
%LOCALAPPDATA%\VoxRox\Mail
```

Obsah:

```text
crypto.bin                         lokální master key + salt (Windows: chráněný DPAPI, formát VOXSEC1), vzniká při prvním startu
session.json                       aktuální port, baseUrl, X-API-KEY a verze API
.ready                             ready signal pro Tauri klienta
db/mail.db                 SQLite DB
db/mail.db-wal             SQLite WAL, pokud je DB aktivní
db/mail.db-shm             SQLite shared memory file
logs/mail.log              hlavní aplikační log
logs/audit.log                     security/audit log
attachments/                       lokální přílohy
tmp/                               dočasné soubory
```

`app.data-dir` lze přepsat JVM argumentem:

```powershell
java -jar target\mail-backend-0.1.0.jar --app.data-dir=C:\temp\mail-smoke
```

## Start a sidecar

Backend poslouchá defaultně jen na loopbacku:

```text
server.address=127.0.0.1
server.port=0
```

`server.port=0` znamená náhodný volný port vybraný Springem. Skutečný port se
po startu zapíše do `session.json` jako `port` a `baseUrl`; klient nikdy nemá
hádat fixní číslo portu.

Pro diagnostiku nebo kompatibilitu s providerem, který vyžaduje přesný OAuth
redirect URI, lze port dočasně přepsat:

```powershell
java -jar target\mail-backend-0.1.0.jar --server.port=60100
```

Při explicitně zvoleném obsazeném portu skončí backend ještě před Spring startem
s exit code `78` a čitelnou hláškou.

Tauri klient má čekat na `${app.data-dir}/.ready`, potom načíst `${app.data-dir}/session.json` a používat `baseUrl` + `apiKey`. Pokud `.ready` neexistuje, hledat chybu v `logs/mail.log`.

## Health check

Endpoint:

```text
GET /api/internal/health
```

Je chráněný interním API klíčem ze `session.json`:

```powershell
$session = Get-Content "$env:LOCALAPPDATA\VoxRox\Mail\session.json" | ConvertFrom-Json
Invoke-RestMethod "$($session.baseUrl)/api/internal/health" -Headers @{ "X-API-KEY" = $session.apiKey }
```

Health obsahuje DB stav, diskspace a `sync` komponentu. `requiresReauth` účty netahají health do `DOWN`; znamenají uživatelskou akci (znovu přihlásit OAuth účet).

## Diagnostic dump

Interní support snapshot:

```text
GET /api/internal/diagnostic-dump
```

Endpoint je chráněný stejným `X-API-KEY` jako health check a vrací ZIP attachment. Obsahuje `summary.json`, `accounts.json`, `folder-sync-states.json`, `message-counts.json` a `runtime.json`.

Dump záměrně neobsahuje hesla, OAuth tokeny, interní API klíč, obsah zpráv, předměty zpráv ani plné e-mailové adresy. Účty jsou uvedené s maskovaným e-mailem, providerem, typem autentizace, IMAP/SMTP host/port/SSL konfigurací, stavem reauth a příznakem, zda existuje `last_error`.

Windows PowerShell:

```powershell
$session = Get-Content "$env:LOCALAPPDATA\VoxRox\Mail\session.json" | ConvertFrom-Json
Invoke-WebRequest "$($session.baseUrl)/api/internal/diagnostic-dump" -Headers @{ "X-API-KEY" = $session.apiKey } -OutFile "$env:TEMP\mail-diagnostic.zip"
```

## Logy

Hlavní log:

```text
${app.data-dir}/logs/mail.log
```

Audit log:

```text
${app.data-dir}/logs/audit.log
```

Hlavní log rotuje po `10MB`, drží 7 souborů a cap `100MB`. Audit log má delší retenci: 365 dní, `10MB` per soubor, cap `500MB`.

Rychlé hledání posledních chyb ve Windows PowerShellu:

```powershell
Select-String "$env:LOCALAPPDATA\VoxRox\Mail\logs\mail.log" -Pattern "ERROR|WARN|CRITICAL" | Select-Object -Last 80
Select-String "$env:LOCALAPPDATA\VoxRox\Mail\logs\audit.log" -Pattern "FAILURE|CRITICAL" | Select-Object -Last 80
```

## Databaze a zalohy

SQLite běží s WAL:

```text
journal_mode=WAL
synchronous=NORMAL
foreign_keys=ON
busy_timeout=5000
cache_size=-20000
```

Při startu backend ověřuje `PRAGMA quick_check`; cokoli jiného než `ok` fail-fastne start a zapíše `db_corruption_detected` do audit logu.

Bezpečná záloha:

1. Ukončit Tauri klienta a ověřit, že neběží Java sidecar.
2. Zkopírovat celý `${app.data-dir}` adresář, nejen samotný `.db` soubor.
3. Pro obnovu vrátit celý adresář včetně `crypto.bin`; bez něj nepůjdou dešifrovat credentials.

Pozn. (Windows): `crypto.bin` je chráněný přes DPAPI v **USER scope** — odšifrovat
ho umí jen stejný Windows uživatel na stejném stroji. Obnova zálohy pod jiným
uživatelským účtem nebo na jiném počítači proto credentials nerozluští; účty se
při startu označí `requiresReauth=true` a je nutné se znovu přihlásit (stejné
chování jako při ztrátě `crypto.bin`). Maily, kontakty a další data v `mail.db`
zůstanou čitelné — DB sama šifrovaná není.

Konzistence DB po podezřelém pádu:

```sql
PRAGMA quick_check;
SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;
```

`quick_check` musí vrátit `ok`; Flyway V1 musí mít `success = 1`.

## JVM tuning a Spring AOT

Sidecar JVM (jpackage app-image, Java 25) bezi s tunigem zameneným pro single-user
desktop scenar — narozdil od serverovych default JVM hodnot, ktere jsou
optimalizovane pro throughput pres deg dlouhy lifetime, sidecar potrebuje
**rychly start** a **maly heap**. Aktualni argumenty z
`backend/scripts/package-sidecar-windows.ps1`:

```text
--enable-native-access=ALL-UNNAMED  Java 25 native access (JNI) bez warningu.
-Dfile.encoding=UTF-8               Konzistentni encoding napric platformami.
-Dspring.aot.enabled=true           Aktivuje Spring AOT artefakty z jaru (viz nize).
-XX:TieredStopAtLevel=1             Jen C1 JIT (~10-15 % rychlejsi cold start).
-Xms64m / -Xmx384m                  Single-user sidecar; default Xmx (1/4 RAM)
                                    je radove vetsi nez potreba.
-XX:+UseSerialGC                    1 GC vlakno = minimalni overhead pri startu
                                    a maly footprint pro maly heap.
```

**Spring AOT** je zapnuty pres Maven profile `aot` (`mvn -Paot package`). Profile
pridava `spring-boot-maven-plugin` goal `process-aot`, ktery generuje
`__BeanDefinitions.java` classy do `target/spring-aot/main/sources` a zababaluje
je do fat jaru. Pri behu s `-Dspring.aot.enabled=true` je Spring kontextova
trida pouzije misto reflexive bean factory, coz srazi 20-40 % cold startu
(empirically pro tento projekt; viz `PERFORMANCE_BASELINE.md`).

AOT je `aot` profile, **NE** default — `mvn test` a `mvn spring-boot:run` ho
nespoustia, takze dev/test build neni zpomaleny `process-aot` interne
spustenim AppContextu pri buildu.

**Springdoc** (Swagger UI starter) je z fat jaru vyloucen pres
`spring-boot-maven-plugin` `<excludeGroupIds>` na exekucich `repackage` a
`process-aot` (POZOR: `<excludes>` vyzaduje groupId i artifactId — zapis jen
s groupId tise nematchne nic; exclusion na `process-aot` je nutny, jinak AOT
vygeneruje springdoc `__BeanDefinitions` odkazujici na classy chybejici v
jaru). Snizuje fat jar o ~3 MB a zkracuje classpath scan o ~200-500 ms.
Pro fat jar S Swagger docs (debug build):

```powershell
mvn -Popenapi -Dspringdoc.api-docs.enabled=true `
    -Dspringdoc.swagger-ui.enabled=true package
```

Dev `mvn spring-boot:run` ma springdoc v compile classpath dal — Swagger UI
funguje normalne pri vyvoji.

### JEP 483 AOT class cache (experimentalni, default OFF)

Java 25+ podporuje "ahead-of-time class loading & linking" — JVM si pri exit
zapise binarni cache class loading + linking metadat, ktera se pri dalsim
startu pripoji misto rebuildovani z jaru. V kombinaci se Spring AOT to muze
dale zkratit cold start o ~20-40 % nad ramec ostatnich JVM optimalizaci.

**Default je OFF** v `package-sidecar-windows.ps1` (parameter `-EnableAotCache`).
Cache se zapina explicitne kvuli velikosti (~115 MB vedle jaru) a vazbe na presny
jar hash + Java verzi — pri kazdem rebuildu jaru je nutne regenerovat.

> Historicka poznamka: starsi verze codebase mela v `ContactService` a
> `MailContentService` `@Lazy <Self> self` injection, ktera padala se Spring AOT
> (i bez `-XX:AOTCache`) na `ClassCastException` v
> `CglibAopProxy.setCallbacks` — proxy generovana pres
> `ContextAnnotationAutowireCandidateResolver.buildLazyResolutionProxy` dostala
> `SerializableNoOp` misto `Dispatcher`. Bug je vyresen prechodem na
> `ObjectProvider<Self>` (Spring native lazy resolution, ktera neprochazi pres
> CGLib). Pokud nekdy znova ucitis `@Lazy <Self>` injection nebo ekvivalentni
> CGLib lazy proxy, otestuj startup z `mvn -Paot package` jaru, ne jen ze
> `spring-boot:run`.

Workflow (pri zapnuti):

1. `package-sidecar-windows.ps1 -EnableAotCache` spousti `generate-aot-cache-windows.ps1`
   po jpackage app-image stepu:
   - phase 1 (record): JVM s `-XX:AOTMode=record -XX:AOTConfiguration=<file>`,
     env `MAIL_AOT_TRAINING_RUN=1` aktivuje `AotTrainingExitListener`, ktery po
     `ApplicationReadyEvent` zavola `System.exit(0)`. JVM zapise config soubor.
   - phase 2 (create): JVM s `-XX:AOTMode=create -XX:AOTConfiguration=<file>
     -XX:AOTCache=<file>` precte config a sestavi binarni cache (~100-200 MB).
   - cache se kopíruje do `<install_dir>/app/mail.aot`.
2. jpackage launcher pak spousti produkci s `--java-options
   "-XX:AOTCache=app\mail.aot"` (relativni path vuci install dir).

Manualne (experimentalne, proti `mvn package` jaru):

```powershell
.\scripts\generate-aot-cache-windows.ps1 `
    -JarPath target\mail-backend-0.1.0.jar `
    -CachePath target\mail.aot
```

Cache je vazana na presny jar hash + Java verzi — pri rebuild jaru je nutne
regenerovat. AOT record faze typicky vraci non-zero exit kvuli `Preload Warning:
Verification failed` u Spring Security konfiguraci (SAML, OAuth2 server, LDAP) —
warnings jsou benigní a skript je ignoruje, pokud config soubor vznikl.

### Mereni cold startu

```powershell
# Po `.ready` zapsani backend loguje souhrn:
Get-Content "$env:LOCALAPPDATA\VoxRox\Mail\logs\mail.log" |
  Select-String "Startup timing|Started MailBackend|spring.application-ready" |
  Select-Object -First 30
```

Pro frontend boot timings: dev console v `tauri:dev`, store `bootState.timings`.

## Update process

Aktualizace přicházejí jako celý Tauri bundle (frontend + backend sidecar společně). Verzový mismatch tím pádem nemůže nastat — co podepsala release pipeline jde k uživateli atomicky.

Co se děje při startu nové verze:

1. Backend se spustí ze stejného `${app.data-dir}` jako předchozí verze; data dir installer nemaže.
2. Před `flyway.migrate()` zapíše `DatabaseBackupService` snapshot DB jako `db/mail.db.backup-pre-v<currentAppVersion>` (idempotentní — pokud už pro danou verzi existuje, no-op).
3. Promaže staré zálohy mimo retention okno (default 3 nejnovější, viz `mail.backup.retention-count`).
4. Aplikuje kumulativní Flyway migrace (V2, V3, ...).
5. `verifySqlitePragmas` ověří `PRAGMA quick_check`. Selhání → fail-fast s recovery zprávou + audit `startup_health_gate_failed`.
6. `app_started` audit záznam zachycuje `appVersion`, `dbSchemaVersion` a `previousAppVersion` (odvozeno z nejnovějšího backup souboru).

Tauri klient přečte z handshake odpovědi `dbSchemaVersion` a zaloguje ho do diagnostic dumpu pro post-update support.

Manuální fallback, pokud Tauri updater selže (síťový timeout, signature mismatch, disk full): stáhnout aktuální `voxrox-mail-<version>-x64-setup.exe` z GitHub Releases ručně a spustit „přes". Datové soubory zůstanou. Downgrade běžným instalátorem je zakázaný kvůli migracím databáze.

### Update troubleshooting

`Sidecar nestartuje po update` (audit log obsahuje `startup_health_gate_failed`) → obnovit DB z nejnovější zálohy:

```powershell
# 1. Zastavit backend (kill Tauri / Java sidecar process)
# 2. Najít nejnovější zálohu
Get-ChildItem "$env:LOCALAPPDATA\VoxRox\Mail\db\mail.db.backup-pre-v*" |
  Sort-Object LastWriteTime -Descending | Select-Object -First 1
# 3. Přejmenovat poškozenou DB stranou
Move-Item "$env:LOCALAPPDATA\VoxRox\Mail\db\mail.db" `
          "$env:LOCALAPPDATA\VoxRox\Mail\db\mail.db.broken"
# 4. Obnovit ze zálohy (nahradit <ZALOHA> jménem souboru z kroku 2)
Copy-Item "$env:LOCALAPPDATA\VoxRox\Mail\db\<ZALOHA>" `
          "$env:LOCALAPPDATA\VoxRox\Mail\db\mail.db"
# 5. Spustit supportem schválený recovery build/postup (downgrade installer je blokovaný)
# 6. Reportovat bug se snippetem audit.log
```

`db_backup_failed` v audit logu při startu → zkontrolovat volné místo na disku a oprávnění k `${app.data-dir}/db/`. Backup nemůže selhat tichá — pokud ano, Flyway migrate se vůbec nespustí a uživatel zůstává na předchozí verzi schémat.

`Update notifikace se nezobrazuje` (Tauri klient nehlásí novou verzi) → zkontrolovat `tauri.conf.json` `bundle.updater.endpoints` URL, manifest signing key shoda s `pubkey`.

## Reset uctu

Preferovaný postup je smazání účtu přes aplikaci. Nouzový SQL postup pro support:

1. Zastavit backend.
2. Udělat zálohu celého `${app.data-dir}`.
3. V SQLite smazat účet podle ID nebo e-mailu:

```sql
DELETE FROM accounts WHERE email = 'user@example.com';
```

FK `ON DELETE CASCADE` smaže credentials, sync state, messages, contacts a související řádky. Po restartu uživatel účet přidá znovu.

## Rotace crypto klice

`crypto.bin` je trvalá lokální kotva pro šifrování credentials a interního API klíče. Nemazat ho samostatně.

Na Windows je `crypto.bin` uložený v chráněném formátu `VOXSEC1`: klíč+salt jsou
zabalené přes Windows DPAPI (`CryptProtectData`, USER scope + app entropy), takže
soubor zkopírovaný na jiný Windows účet/stroj je nepoužitelný. Na ostatních
platformách (a v testech) se použije identity fallback a důvěrnost stojí na
oprávněních souboru (`rw-------`), stejně jako dřív. Starší instalace s plaintext
`crypto.bin` se při prvním startu po update bezešvě zmigrují na `VOXSEC1` (in-place,
beze změny klíče → bez re-encrypt DB). DPAPI USER scope nechrání proti malwaru
běžícímu pod stejným uživatelem (unprotect pro něj projde) — to je mimo threat model.

Release a běžný Tauri desktop režim používají `crypto.bin` jako výchozí zdroj
klíče. Lokální `backend/.env` smí obsahovat `MAIL_CRYPTO_KEY` /
`MAIL_CRYPTO_SALT` pro explicitní backend-only override, ale Tauri dev/release
launcher je defaultně do sidecaru nepředává. Tím release běží stejně jako
fresh install: první start vytvoří `crypto.bin`, další starty ho znovu použijí.
Pokud je potřeba testovat explicitní env crypto režim, spouštěj ho odděleně a
ne proti existujícímu uživatelskému `${app.data-dir}`.

Pokud je `crypto.fingerprint` zastaralý nebo neodpovídá aktuálnímu
`crypto.bin`, desktop bootstrap režim ho při startu přegeneruje podle
`crypto.bin`. Interní handshake API klíč je generovaný v paměti při každém
startu sidecaru a zapsaný do `session.json` — žádný persistentní artefakt,
takže ho rotace crypto materiálu netýká. Uživatelské credentials jsou
oddělené: hesla/OAuth tokeny bez původního `crypto.bin` nebo původních env
crypto hodnot nelze obnovit. Startup takový účet označí jako
`requiresReauth=true`, nastaví `last_error` a aplikace pokračuje; účet je nutné
přihlásit nebo přidat znovu.

Praktický single-user postup rotace:

1. V UI odebrat všechny účty, nebo se smířit s tím, že je bude nutné přidat znovu.
2. Zastavit backend.
3. Zálohovat celý `${app.data-dir}`.
4. Smazat `crypto.bin` a DB, případně celý datový adresář.
5. Spustit backend; vytvoří nový `crypto.bin`, `session.json` a čerstvou DB.
6. Přidat účty znovu.

Změna `MAIL_CRYPTO_KEY`/`MAIL_CRYPTO_SALT` proti existující DB bez re-encrypt migrace způsobí nečitelné credentials.

## IMAP/SMTP diagnostika

Kontrolní pořadí:

1. `/api/internal/health` - DB, disk a sync komponenta.
2. `accounts.last_error` - poslední uživatelsky relevantní chyba účtu.
3. `logs/mail.log` - `IMAP`, `SMTP`, `SYNC`, `AUTH` kategorie.
4. `logs/audit.log` - `imap_auth`, `mail_send`, `account_requires_reauth`, `decrypt`, `api_key_auth`.

Užitečné SQL dotazy:

```sql
SELECT id, email, active, requires_reauth, last_sync_at, last_error
FROM accounts
ORDER BY id;

SELECT account_id, folder_name, last_uid, last_sync_at
FROM folder_sync_states
ORDER BY account_id, folder_name;
```

Pokud `requires_reauth = 1`, nejde o výpadek backendu. Uživatel musí projít OAuth login znovu.

## OAuth tokeny a cas systemu

Google a Microsoft access tokeny se cachuji v procesu (per-account, sdileny
in-memory `TokenCache`) a povazuji se za stale 60 sekund pred nominalni
expiraci. Backend tim chrani IMAP/SMTP XOAUTH2 prihlaseni pred tokenem,
ktery by expiroval tesne mezi cache hitem a pouzitim.

Produkce musi bezet s aktivni synchronizaci systemoveho casu (Windows Time/NTP).
Bez NTP muze velky clock drift zpusobit zbytecne predcasne refreshovani tokenu,
nebo kratkodobe pouziti uz expirovaneho tokenu. Druha varianta je benigni:
IMAP/SMTP auth fail invaliduje cache a dalsi pokus token obnovi, ale v logu se
objevi zbytecny auth retry sum.

### Microsoft refresh token revoke

Microsoft Identity Platform neimplementuje RFC 7009 token revoke. Backend pri
smazani uctu cisti pouze lokalni cache a zapise audit `token_revoke ...
revoke=local_cache_only`; refresh token na strane Microsoftu zustava platny
do uplynuti puvodni doby (typicky 90 dni neaktivity) nebo do explicitniho
odvolani uzivatelem.

Pokud uzivatel chce odvolat pristup okamzite (napr. pri ztrate zarizeni):

1. Otevrit `https://account.microsoft.com/privacy` (osobni MSA ucet) nebo
   `https://myapps.microsoft.com` (organizacni AAD ucet).
2. V sekci "Apps and services" / "Apps with access" najit aplikaci podle
   client ID a kliknout "Remove permission".
3. Backendovy zaznam stejne uz neexistuje (deleteAccount ho zmazal),
   takze tento krok je cisteni na strane provideru.

## Sidecar startup failures

Rozlišení:

```text
Není session.json ani .ready       backend se nedostal přes bootstrap
Je session.json, není .ready       backend spadl mezi handshake a ready signálem
Je .ready, health neodpovídá      port/firewall/špatný baseUrl nebo mrtvý proces
Health odpovídá, UI ne            problém v Tauri klientovi/API klientovi
```

Co hledat v logu:

```text
Explicitní port je obsazený       jiná instance backendu nebo cizí proces
SQLite quick_check selhal         podezření na DB korupci, obnovit ze zálohy
Crypto self-test failed           špatný nebo změněný crypto key/salt
GOOGLE_OAUTH_CLIENT_* missing     chybí OAuth konfigurace pro dev/prod build
MICROSOFT_OAUTH_CLIENT_* missing  chybí OAuth konfigurace pro Outlook/Exchange Online
```

Tauri klient by měl při pádu sidecaru zkusit omezený restart a pak ukázat cestu k logům. Backend je restart-idempotentní: Flyway je no-op při shodném schématu, SQLite WAL se obnoví automaticky, IMAP spojení se navazují znovu.

## Release smoke

Minimální backend ověření před vydáním:

```powershell
$env:MAVEN_OPTS='-Duser.home=C:\dev\java\mail\backend'
mvn.cmd "-Dmaven.repo.local=C:\dev\java\mail\backend\.m2repo" "-Dapp.data-dir=C:\dev\java\mail\backend\target\test-data" package
```

Očekávání:

```text
Tests run: 566, Failures: 0, Errors: 0, Skipped: 0
target/mail-backend-0.1.0.jar existuje jako repackaged Spring Boot JAR
StartupSmokeTest vytvoří crypto.bin, session.json, .ready a aplikuje Flyway V1
```

Sidecar artefakt pro Windows:

```powershell
.\scripts\package-sidecar-windows.ps1
```

> Vyžaduje reálné OAuth client id v prostředí (CI secrets) a jinak build shodí. Pro
> lokální build použij `package-sidecar-dev-windows.ps1` (načte je z `.env`), nebo
> přidej `-AllowPlaceholderOAuth` pro build bez funkčního OAuth loginu.

Výstup:

```text
target/sidecar/x86_64-pc-windows-msvc/
```

Do Tauri balíčku kopírovat celý adresář s `.exe`, `app/` a `runtime/`, ne jen samotný `.exe`.
