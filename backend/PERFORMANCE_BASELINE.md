# Performance baseline

Tento soubor slouží pro ruční E18 smoke měření před releasem. Vyplnit na reálném mailboxu s 10k+ zprávami.

```text
Datum:
Release kandidat:
Backend commit:
Frontend commit:
Platforma:
CPU:
RAM:
Disk:
JDK/runtime:
Mailbox provider:
Pocet uctu:
Pocet zprav:
Velikost priloh:
```

## Scenar

1. Fresh install nebo čistý `${app.data-dir}`.
2. Přidat reálný účet.
3. Spustit full sync.
4. Po syncu provést FTS5 search na známý hit a známý miss.
5. Odeslat zprávu s přílohou.
6. Nechat aplikaci běžet alespoň 30 minut s běžným background syncem.

## Metriky

| Metrika | Hodnota | Poznamka |
|---|---:|---|
| Cold start backendu po `.ready` |  |  |
| Cas do prvniho health 200 |  |  |
| Full sync 10k+ zprav |  |  |
| Pocet stazenych zprav |  |  |
| Prumer zprav/s |  |  |
| FTS5 hit search |  |  |
| FTS5 miss search |  |  |
| Odeslani mailu s prilohou |  |  |
| Max RSS / working set |  |  |
| Java heap max observed |  |  |
| SQLite DB velikost |  |  |
| SQLite WAL max velikost |  |  |
| Diagnostic dump velikost |  |  |

## Startup audit 2026-05-17 - before/after

Po implementaci sady zmen z auditu startupu (todo.md sekce "Startup A Komunikace
FE-BE"). Cilem je zmerit realny gain na cold start a vnimanou rychlost a
rozhodnout, jestli ma smysl pokracovat s JEP 483 AOT cache (#10 v auditu).

Mereni delat na **cistem profilu** (cerstve `%LOCALAPPDATA%\VoxRox\Mail`, zadne
predchozi WAL, prazdna DB). Spustit kazdy beh **3x** a brat median, ne jeden vzorek.

### Mereni na strane backendu

`StartupTimingService` loguje na INFO uroven kazdou fazi (`[BOOT] Startup
timing: phase=<klic> durationMs=<ms>`). Klicove fáze:

| Faze | Klic | Typicke pred zmenami | Cil po zmenach |
|---|---|---:|---:|
| Spring AppContext refresh | (Spring `Started ... in X seconds`) | ~3-5 s | -20-40 % (AOT) |
| Flyway migrace | `db.flyway-migrate` | 50-300 ms | beze zmeny (no-op pri ready) |
| Pre-migration backup | `db.pre-migration-backup` | 50-200 ms | **0 ms** (pri zadnych pending) |
| SQLite PRAGMA verify | `db.verify-pragmas` | 30-100 ms | beze zmeny |
| Crypto subsystem init | `crypto.service-init` | <10 ms | beze zmeny (jiz lazy) |
| Storage permissions | `storage.permissions` | 10-50 ms | beze zmeny |
| Handshake session write | `handshake.session-write` | <10 ms | beze zmeny |
| ApplicationReadyEvent total | `spring.application-ready` | ~50-500 ms | -10-20 % (mensi heap) |

Zpusob mereni:

```powershell
# 1. Fresh data dir
Remove-Item -Recurse -Force "$env:LOCALAPPDATA\VoxRox\Mail" -ErrorAction SilentlyContinue
# 2. Spustit Tauri release build
& "$env:ProgramFiles\VoxRox Mail\voxrox-mail.exe"
# 3. Po startu aplikace zkopirovat log a precist
Get-Content "$env:LOCALAPPDATA\VoxRox\Mail\logs\mail.log" |
  Select-String "Startup timing" | Select-Object -First 20
```

### Mereni na strane frontendu

`bootstrap.ts` zapisuje timings do `bootState.timings` (klice viz
[frontend/src/lib/stores/boot.ts](../frontend/src/lib/stores/boot.ts)). Dev console:

```js
console.info('[mail] boot timings', $bootState.timings);
```

Klicove deltas (relativni k `uiStart=0`):

| Mezicas | Klic | Typicke pred | Cil po |
|---|---|---:|---:|
| Sidecar process running | `sidecarRunning` | 50-300 ms | beze zmeny |
| `.ready` + session.json videt | `sessionFound` | +1000-5000 ms | -300-500 ms (mensi JVM heap, AOT) |
| Handshake OK | `handshakeOk` | +0-200 ms | **0 ms** (smazano, mark jen) |
| Readiness OK | `readinessOk` | +0-200 ms | beze zmeny (1 polling roundtrip) |
| Client config nactena | `clientConfigOk` | +100-300 ms | -50 ms (paralelne s accounts) |
| Accounts nactene | `accountsLoaded` | +100-500 ms | -50 ms (paralelne s config) |
| App ready (full shell) | `appReady` | ~2000-6000 ms | -500-1500 ms |

### Co overit po smoke

- [ ] Cold start `appReady` median pres 3 behy
- [ ] Heap pri startu (`Get-Process java | Select-Object PrivateMemorySize64`)
- [ ] Tauri okno se zobrazi do <500 ms
- [ ] Shell-first placeholder (AppRail + sidebar gray boxes) je videt
      okamzite po zobrazeni okna, nezustava blank screen
- [ ] Pre-migration backup file (`db/mail.db.backup-pre-v*`) NEvznikne
      pri startu na sjednocenem schema
- [x] Springdoc classes v fat jaru chybi (`jar tf mail-backend-*.jar |
      Select-String springdoc` = empty) — overeno 2026-06-11 po oprave
      vylouceni (viz mereni nize; puvodni `<excludes>` jen s groupId tise
      nefungoval a springdoc v jaru zustaval)
- [x] AOT artefakty v jaru pritomne (`jar tf mail-backend-*.jar |
      Select-String "__BeanDefinitions"` non-empty) — overeno 2026-06-11,
      310 trid
- [ ] Global bootstrap timeout: pri umelem sidecar failure (mock
      `mail.e2e.sidecarFailure=once`) frontend zobrazí chybu **do 60 s**,
      ne po polling smyckach (drive ~90 s)

### Decision gate pro AppCDS / JEP 483 (#10)

Jestlize median cold start `appReady` po teto sade zmen je **> 3.5 s**, ma
smysl pokracovat s JEP 483 AOT class cache (training run, build cache, deploy
s fat jar). Pokud je median **<= 3.5 s**, gain z AOT cache (typicky +200-500 ms
dalsi sleva) nezdovodnuje komplexitu (training run pri build, cache invalidace
pri zmene jaru, distribucni rozmer).

### JEP 483 AOT cache — měření 2026-05-19

Provedeno po refaktoru self-injection (`MessageContentPersister`,
`ContactBulkService`), který odstranil runtime CGLib proxy generation z
`MailContentService`/`ContactService`. Předchozí `ObjectProvider<Self>` bránila
AOT cache restoru přes `ClassCastException`.

**Setup:**
- Plain `java -jar mail-backend-0.1.0.jar` (bez Tauri jpackage launcheru)
- Jar s `-Paot` (Spring AOT processing aktivní)
- Crypto klíče dummy z env
- `APP_DATA_DIR` = čerstvý tmp adresář per běh
- Median ze 3 nezávislých běhů per varianta

**Výsledky:**

| Varianta | Median cold start | Delta |
|---|---:|---:|
| Bez AOT cache | **8553 ms** | baseline |
| S `-XX:AOTCache=mail.aot` | **5415 ms** | **−3138 ms (−36.7 %)** |

**Velikost cache:** 130.62 MB (`mail.aot`). Cache je vázaná na přesný jar hash
+ Java verzi — při rebuildu jaru se musí regenerovat (~70 s pro training +
create přes `scripts/generate-aot-cache-windows.ps1`).

**Closing the gap:** Tauri jpackage launcher má další overhead navíc nad plain
`java -jar` (process spawn, sidecar handshake, .ready signal). Reálné `appReady`
v desktop bundle bude vyšší než 5,4 s, ale stejná relativní úspora (~37 %) se
zhruba propíše.

**Decision:** AOT cache je `OPT-IN` v `scripts/package-sidecar-windows.ps1`
přes `-EnableAotCache` switch. Build pipeline bez AOT je default kvůli rychlosti
CI/dev iterací (training run přidává ~70 s) a 130 MB cache se neukládá do gitu.
Pro release build použij `-EnableAotCache`.

### Startup audit — měření 2026-06-03 (re-validace AOT po startup sweep)

Provedeno po sadě startup optimalizací (AOT cache default ON pro release,
lazy import FE komponent, fs watch místo session pollingu, readiness single-shot
75→5 pokusů, `AttachmentService` cleanup přesunut z `@PostConstruct` na
`@Async @EventListener(ApplicationReadyEvent)`). Cíl: ověřit, že AOT úspora drží
a že přesun cleanup mimo boot thread nezhoršil cold start.

**Setup (shodný s 2026-05-19 pro srovnatelnost):**
- Jar s `-Paot`, JDK 25.0.3 (Temurin)
- Produkční JVM flagy: `-XX:TieredStopAtLevel=1 -Xms64m -Xmx384m -XX:+UseSerialGC --enable-native-access=ALL-UNNAMED -Dspring.aot.enabled=true`
- Crypto klíče dummy z env, `APP_DATA_DIR` = čerstvý tmp adresář per běh
- **Metrika:** wall-clock od startu JVM procesu do vzniku `.ready` souboru
  (přesně signál, na který čeká frontend `loadSession`), median ze 3 běhů
- Pozn.: měřeno na vytíženém stroji (běžely paralelní buildy), absolutní čísla
  jsou proto vyšší než 2026-05-19; **relativní delta je robustní signál**.

**Výsledky:**

| Varianta | Median cold start (→ `.ready`) | Delta |
|---|---:|---:|
| Bez AOT cache | **10902 ms** | baseline |
| S `-XX:AOTCache=mail.aot` | **7055 ms** | **−3847 ms (−35.3 %)** |

**Velikost cache:** 128.5 MB (`mail.aot`).

**Závěry:**
- AOT úspora **−35.3 %** potvrzuje historických −36.7 % → rozhodnutí „AOT cache
  default ON pro release" (CI `windows-signed-release.yml`) je validované reálnými čísly.
- Obě varianty dosáhly `.ready` čistě → `AttachmentService` cleanup je nyní mimo
  boot critical path (běží `@Async` po `ApplicationReadyEvent`), **bez regrese** boot času.
- **Decision gate (`appReady` > 3.5 s → AOT se vyplatí):** backend-to-`.ready`
  s AOT je 7,0 s, plný `appReady` v desktop bundlu bude ještě vyšší → gate splněn
  s rezervou, AOT jednoznačně opodstatněn.
- **Otevřené pro budoucnost:** absolutní 7 s i s AOT je stále těžké. Další páky
  (mimo scope tohoto auditu): jlink-trimmed runtime (jen použité moduly), méně
  Spring auto-configurations, lazy bean init. Vyžadují větší zásah a vlastní měření.

**Stále vyžaduje GUI měření (nelze headless):** plný `appReady` (okno paint +
handshake + accounts load) v Tauri bundlu — `frontend/scripts/tauri-release-startup-smoke.mjs`
na reálném buildu, a vnímaná rychlost (shell-first placeholder, lazy sidebar/dialog timing).

### Startup audit — měření 2026-06-11 (reálné vyloučení springdoc + NullAway sweep)

Kontext: při ověřování checklistu výše se ukázalo, že springdoc se z fat jaru
**nikdy nevylučoval** — `spring-boot-maven-plugin` `<excludes>` vyžaduje groupId
i artifactId a původní zápis jen s groupId tiše nematchnul nic. Opraveno přes
`<excludeGroupIds>org.springdoc</excludeGroupIds>` na exekucích `repackage` a
`process-aot` (druhé je nutné, jinak AOT vygeneruje springdoc
`__BeanDefinitions` classy odkazující na classy chybějící v jaru). Detail v
[OPERATIONS.md](OPERATIONS.md), reset pro debug build dělá profil `openapi`.

**Setup (shodný s 2026-06-03):** jar `-Paot`, JDK 25.0.3, prod JVM flagy
(`-XX:TieredStopAtLevel=1 -Xms64m -Xmx384m -XX:+UseSerialGC
--enable-native-access=ALL-UNNAMED -Dspring.aot.enabled=true`), dummy crypto
klíče z env, čerstvý tmp `APP_DATA_DIR` per běh, metrika start JVM → `.ready`,
3 běhy. Bez `-XX:AOTCache`. Stroj tentokrát nevytížený, jar čerstvě v FS cache
— absolutní čísla nejsou 1:1 srovnatelná s 2026-06-03 (tam vytížený stroj).

**Výsledky:** 3603 / 3658 / 3683 ms → **median 3658 ms** do `.ready`,
private memory při `.ready` ~235 MB. Všechny běhy dosáhly `.ready` čistě —
tj. AOT-enabled kontext **bootuje bez springdoc na classpath bez chyb**
(žádné NoClassDefFoundError z AOT bean definitions; @Schema anotace na DTO
jsou za běhu měkké reference, jejich absence ničemu nevadí).

**Závěr:** springdoc exclusion je teď reálný a běh ověřený. Backend-to-`.ready`
3,7 s na nevytíženém stroji bez JEP 483 cache; plný `appReady` v desktop
bundlu zbývá změřit GUI smokem (viz výše).

## Windows prikazy

```powershell
$data = "$env:USERPROFILE\.voxrox\mail-backend"
Get-ChildItem "$data\db" | Select-Object Name, Length, LastWriteTime
Get-Process java | Select-Object Id, ProcessName, CPU, WorkingSet64, PrivateMemorySize64
$session = Get-Content "$data\session.json" | ConvertFrom-Json
Invoke-RestMethod "$($session.baseUrl)/api/internal/health" -Headers @{ "X-API-KEY" = $session.apiKey }
Invoke-WebRequest "$($session.baseUrl)/api/internal/diagnostic-dump" -Headers @{ "X-API-KEY" = $session.apiKey } -OutFile "$env:TEMP\mail-backend-diagnostic.zip"
Get-Item "$env:TEMP\mail-backend-diagnostic.zip" | Select-Object Name, Length
```

## Linux/macOS prikazy

```bash
DATA="$HOME/.voxrox/mail-backend"
ls -lh "$DATA/db"
ps -o pid,pcpu,rss,vsz,comm -p "$(pgrep -f mail-backend | head -n 1)"
```

## Vysledek

```text
Blockery:

Regrese:

Poznamky:

Schvaleno:
```
