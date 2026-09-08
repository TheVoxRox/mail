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

| Metrika                         | Hodnota | Poznamka |
| ------------------------------- | ------: | -------- |
| Cold start backendu po `.ready` |         |          |
| Cas do prvniho health 200       |         |          |
| Full sync 10k+ zprav            |         |          |
| Pocet stazenych zprav           |         |          |
| Prumer zprav/s                  |         |          |
| FTS5 hit search                 |         |          |
| FTS5 miss search                |         |          |
| Odeslani mailu s prilohou       |         |          |
| Max RSS / working set           |         |          |
| Java heap max observed          |         |          |
| SQLite DB velikost              |         |          |
| SQLite WAL max velikost         |         |          |
| Diagnostic dump velikost        |         |          |

## Startup audit 2026-05-17 - before/after

Po implementaci sady zmen z auditu startupu (todo.md sekce "Startup A Komunikace
FE-BE"). Cilem je zmerit realny gain na cold start a vnimanou rychlost a
rozhodnout, jestli ma smysl pokracovat s JEP 483 AOT cache (#10 v auditu).

Mereni delat na **cistem profilu** (cerstve `%LOCALAPPDATA%\VoxRox\Mail`, zadne
predchozi WAL, prazdna DB). Spustit kazdy beh **3x** a brat median, ne jeden vzorek.

### Mereni na strane backendu

`StartupTimingService` loguje na INFO uroven kazdou fazi (`[BOOT] Startup
timing: phase=<klic> durationMs=<ms>`). Klicove fáze:

| Faze                        | Klic                                | Typicke pred zmenami |                 Cil po zmenach |
| --------------------------- | ----------------------------------- | -------------------: | -----------------------------: |
| Spring AppContext refresh   | (Spring `Started ... in X seconds`) |               ~3-5 s |                 -20-40 % (AOT) |
| Flyway migrace              | `db.flyway-migrate`                 |            50-300 ms |   beze zmeny (no-op pri ready) |
| Pre-migration backup        | `db.pre-migration-backup`           |            50-200 ms | **0 ms** (pri zadnych pending) |
| SQLite PRAGMA verify        | `db.verify-pragmas`                 |            30-100 ms |                     beze zmeny |
| Crypto subsystem init       | `crypto.service-init`               |               <10 ms |          beze zmeny (jiz lazy) |
| Storage permissions         | `storage.permissions`               |             10-50 ms |                     beze zmeny |
| Handshake session write     | `handshake.session-write`           |               <10 ms |                     beze zmeny |
| ApplicationReadyEvent total | `spring.application-ready`          |           ~50-500 ms |          -10-20 % (mensi heap) |

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
console.info("[mail] boot timings", $bootState.timings);
```

Klicove deltas (relativni k `uiStart=0`):

| Mezicas                       | Klic             |  Typicke pred |                            Cil po |
| ----------------------------- | ---------------- | ------------: | --------------------------------: |
| Sidecar process running       | `sidecarRunning` |     50-300 ms |                        beze zmeny |
| `.ready` + session.json videt | `sessionFound`   | +1000-5000 ms | -300-500 ms (mensi JVM heap, AOT) |
| Handshake OK                  | `handshakeOk`    |     +0-200 ms |      **0 ms** (smazano, mark jen) |
| Readiness OK                  | `readinessOk`    |     +0-200 ms |  beze zmeny (1 polling roundtrip) |
| Client config nactena         | `clientConfigOk` |   +100-300 ms |     -50 ms (paralelne s accounts) |
| Accounts nactene              | `accountsLoaded` |   +100-500 ms |       -50 ms (paralelne s config) |
| App ready (full shell)        | `appReady`       | ~2000-6000 ms |                      -500-1500 ms |

### Co overit po smoke

- [x] Cold start `appReady` median pres 3 behy — **4621 ms od spawnu**, overeno
      2026-09-08 v desktop bundlu (sekce „`appReady` v desktop bundlu" nize).
      Z toho je 4178 ms cekani na sidecar; UI prida 45 ms.
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

| Varianta                  | Median cold start |                  Delta |
| ------------------------- | ----------------: | ---------------------: |
| Bez AOT cache             |       **8553 ms** |               baseline |
| S `-XX:AOTCache=mail.aot` |       **5415 ms** | **−3138 ms (−36.7 %)** |

**Velikost cache:** 130.62 MB (`mail.aot`). Cache je vázaná na přesný jar hash

- Java verzi — při rebuildu jaru se musí regenerovat (~70 s pro training +
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

| Varianta                  | Median cold start (→ `.ready`) |                  Delta |
| ------------------------- | -----------------------------: | ---------------------: |
| Bez AOT cache             |                   **10902 ms** |               baseline |
| S `-XX:AOTCache=mail.aot` |                    **7055 ms** | **−3847 ms (−35.3 %)** |

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

### Startup audit — měření 2026-09-08 (drží `-XX:TieredStopAtLevel=1` i s AOT cache?)

Kontext: bod 5 v [#392](https://github.com/TheVoxRox/mail/issues/392) ptá se, jestli
se C1-only flag ještě vyplácí, když je AOT cache trvale zapnutá — obě optimalizace
míří na tutéž vteřinu startu a jen jedna z nich stojí CPU na výpočetně náročné
práci. Premisa, že takovou práci má appka při prvním syncu velké schránky, byla
v témže issue vyvrácena (první sync stahuje 100 zpráv, doplňování po 30), takže
zbývala právě tahle otázka.

**Setup:** jar `-Paot`, JDK 25.0.4.1, AOT cache 133,9 MB z
`scripts/generate-aot-cache-windows.ps1`, prod JVM flagy, dummy crypto klíče
z env, čerstvý tmp `APP_DATA_DIR` per běh, metrika start JVM → `.ready`.
**15 kol, varianty střídavě a s obráceným pořadím každé druhé kolo** — blokový
design (nejdřív všechny A, pak všechny B) předá drift zátěže stroje té variantě,
která běžela druhá. Skript: [scripts/measure-cold-start-windows.ps1](scripts/measure-cold-start-windows.ps1).

**Výsledky (n=15 na variantu):**

| Varianta                          | Median | Rozsah    |                      vs. release konfigurace |
| --------------------------------- | -----: | --------- | -------------------------------------------: |
| AOT + `-XX:TieredStopAtLevel=1`   |   5479 | 5009–5675 |                                     baseline |
| AOT, plný tiered (flag odstraněn) |   5226 | 5058–5646 |     −253 ms (−4,6 %), z = −1,22 → neprůkazné |
| Bez AOT cache + flag              |   7819 | 6600–8025 | +2340 ms (+42,7 %), z = −4,67 → **průkazné** |

(Mann-Whitney U; `|z| >= 1,96` = průkazné na 5 %.)

**Závěry:**

- **AOT cache znovu potvrzena: −2,3 s / −42,7 %.** Sedí na historických −36,7 %
  (2026-05-19) a −35,3 % (2026-06-03), takže „AOT default ON pro release" stojí
  na třech nezávislých měřeních.
- **`-XX:TieredStopAtLevel=1` už měřitelný přínos na start nemá.** Dvě měření
  téhož dne se rozešla ve **znaménku**: dřívější dvouvariantní běh dal +78 ms ve
  prospěch flagu (z = −2,97, průkazné), tenhle třívariantní −253 ms proti němu
  (neprůkazné). To není „jednou tak, jednou tak" — je to důkaz, že efekt leží pod
  rozlišovací schopností téhle metody na tomhle stroji. Rozhodně to není 10–15 %,
  kterými se flag zdůvodňuje.
- **Rozhodnutí o flagu se tím nemění, ale jeho zdůvodnění ano.** Zůstává (žádné
  měření neukazuje škodu a před releasem nemá smysl hýbat shipnutou JVM
  konfigurací kvůli nule), ale argument „šetří 10–15 % startu" už neplatí.
  Otevřená zůstává druhá polovina, kterou nikdo neměřil: **cena** C1-only na
  výpočetní práci (MIME parsing, threading, FTS5 indexace) při prvním syncu.
  Až bude, dá se flag rozhodnout na obou stranách místo jedné.
- **Absolutní čísla driftují o ~15 % během hodiny na tomtéž stroji** (medián
  s AOT 4739 ms dopoledne vs 5479 ms odpoledne). Srovnávat se dá jen uvnitř
  jednoho běhu, nikdy napříč sekcemi tohoto dokumentu.

**Tři pasti, které to měření stály jeden běh každá** (a proto jsou ve skriptu
zakomentované): přesměrovaný, ale nečtený stdout/stderr zaplní rouru a JVM
**zatuhne v půlce bootu** — vypadá to jako pomalý start, ne jako deadlock;
`CryptoProperties` odmítne klíč kratší než 32 znaků a selhání se projeví až jako
chyba bindování beanu hluboko v refreshi kontextu; a rozdíl mediánů sám o sobě
při téhle velikosti efektu netvrdí nic, rozhoduje až rankový test.

### Startup audit — měření 2026-09-08 (desktop bundle s AOT cache)

**Stroj: pomalejší notebook** (doplněno tentýž den maintainerem, když se čísla
rozešla s večerním měřením na desktopu — viz sekce „`appReady` v desktop
bundlu"). Absolutní hodnoty téhle sekce tedy nejsou přenositelné na jiný
hardware; poměry uvnitř ní ano.

Ten samý den a stroj jako měření výše, aby čísla šla porovnat. Release bundle
postavený `npm run tauri:build:with-sidecar` nad sidecarem s AOT cache, měřeno
`npm run tauri:smoke:release-startup -- --runs=3 --isolate-app-data` (isolate
přejmenuje existující `%LOCALAPPDATA%\VoxRox\Mail` stranou, měří na čistém
profilu a pak ho vrátí).

| Běh    | `.ready` + session.json | readiness `READY` |
| ------ | ----------------------: | ----------------: |
| cold   |                 8182 ms |           8329 ms |
| warm   |                 8260 ms |           8438 ms |
| warm   |                 9784 ms |           9937 ms |
| median |             **8260 ms** |       **8438 ms** |

**Co to říká:** headless start téhož jaru s toutéž AOT cache měl týž den medián
5479 ms do `.ready`. Desktop bundle je na 8260 ms, tedy **~2,8 s navíc jde na
vrub jpackage launcheru, spawnu z Tauri a cesty k session.json** — přesně ten
overhead, který sekce z 2026-05-19 odhadovala slovy „reálné `appReady` bude
vyšší", ale nikdo ho nezměřil. Readiness endpoint přidává dalších ~180 ms.

**Co to NEříká:** tohle **není `appReady`**. Smoke skript tehdy končil u backend
readiness; plný `appReady` (paint okna, handshake, načtení účtů) žije
v `bootState.timings` uvnitř webview. Doměřeno téhož dne — viz následující
sekce, která zároveň opravuje odhad cesty: CDP potřeba nebyl.

## Startup audit — `appReady` v desktop bundlu, měření 2026-09-08 (večer)

Doměřuje otevřený bod předchozí sekce. `npm run tauri:smoke:release-startup -- --runs=3 --isolate-app-data`,
release `app.exe` z `tauri:build --no-bundle`.

**Cesta k číslu je jiná, než předchozí sekce odhadovala, a je to důležitější
než samotná čísla.** WebView2 remote debugging ani CDP potřeba nejsou: klient
ta timings **už sám posílá** na `POST /api/internal/client-boot` ve statementu
hned za `completeBoot()`, backend drží poslední snapshot a diagnostic dump je
vydává jako `client-boot.json`. Smoke skript si tedy po readiness stáhne dump
a přečte je — na shipnuté binárce, bez debuggeru a bez build flagu (konzolový
log boot timings je pod `import.meta.env.DEV`, v release buildu tedy mlčí).
Most mezi hodinami je `reportedAt`: klient ho razítkuje `new Date()` na téže
nástěnné hodině, takže `reportedAt − spawn` dá čas od spuštění procesu.

| Běh        | spawn → `uiStart` | `sidecarRunning` | `sessionFound` | `readinessOk` | `appReady` (UI hodiny) | spawn → `appReady` |
| ---------- | ----------------: | ---------------: | -------------: | ------------: | ---------------------: | -----------------: |
| cold       |            585 ms |            71 ms |        4403 ms |       4436 ms |                4460 ms |            5045 ms |
| warm       |            423 ms |            32 ms |        4138 ms |       4160 ms |                4181 ms |            4604 ms |
| warm       |            398 ms |            34 ms |        4178 ms |       4201 ms |                4223 ms |            4621 ms |
| **medián** |        **423 ms** |        **34 ms** |    **4178 ms** |   **4201 ms** |            **4223 ms** |        **4621 ms** |

**Co to říká: `appReady` není nad backend readiness o neznámou, je nad ním
o desítky milisekund.** Od `sessionFound` k `appReady` uplyne 57 / 43 / 45 ms —
handshake, readiness, client config a účty dohromady. Celý rozpočet startu je
čekání na sidecar: `sessionFound` je 4,1–4,4 s a všechno ostatní v UI je šum
proti němu. Optimalizace UI startu tedy nemá co získat; jediná páka je JVM.

**Druhá věc, kterou nikdo neměřil: 400–585 ms před `uiStart`.** To je spawn
procesu, start WebView2 a doběhnutí prvního skriptu — do `bootState` se
nepromítne, protože jeho hodiny začínají až v `beginBoot()`. U cold běhu je to
585 ms, u warm ~400 ms.

**Absolutní čísla nejsou srovnatelná s předchozí sekcí, protože každá vznikla na
jiném stroji.** Tahle sekce je měřená na **AMD Ryzen 9 9900X** (12 jader /
24 vláken, 4,4 GHz, 62 GB RAM), předchozí ranní na **pomalejším notebooku**
(podle maintainera; `todo.md` popisuje jako pomalý stroj i7-1255U, 15W U-series,
1,7 GHz base, 16 GB). Sidecar se navíc liší: ten v `src-tauri/binaries/` byl
přebalený týž den v 16:03 **bez JEP 483 AOT class cache** —
`mail-x86_64-pc-windows-msvc.cfg` nese `-Dspring.aot.enabled=true`
a `-XX:TieredStopAtLevel=1`, ale žádné `-XX:AOTCache`, a vedle jaru žádný cache
soubor neleží.

Dvě proměnné naráz tedy znamenají, že **z rozdílu 4,2 s vs 8,3 s do `.ready`
neplyne nic o AOT cache** — 15W mobilní CPU proti dvanáctijádrovému desktopu
vysvětlí dvojnásobek sám o sobě. Dokumentovaný zisk cache (−42,7 %, sekce
2026-09-08 výše) stojí na kontrolovaném střídavém běhu **na jednom stroji**
a tímhle měřením zpochybněný není.

**Pravidlo, které z toho plyne a platí pro každý další zápis:** číslo startu bez
jména stroje je nepoužitelné. Tenhle repozitář na to naráží podruhé — „brána
běží ~50 minut" z `todo.md` je táž třída záhady a taky se rozpustila, jakmile se
změřilo, na čem. Startupové sekce výše, které stroj neuvádějí, je proto potřeba
brát jako čísla bez měřítka.

Rozkladu `appReady` výše se nic z toho netýká: je to poměr uvnitř jednoho běhu,
takže na rychlosti stroje nezávisí.

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
