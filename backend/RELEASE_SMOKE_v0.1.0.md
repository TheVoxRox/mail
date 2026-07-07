# Release smoke runbook — VoxRox Mail v0.1.0

Per-candidate worksheet pro ruční smoke ([RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md) §3–§8). §1 (backend build) a §2 (frontend automation) jsou hotové v RELEASE_CHECKLIST.md — tady interaktivní část.

**Tento soubor je zformalizovaný z existujících důkazů (2026-06-23 tauri:dev smoke, 2026-06-25 Phase B signed smoke na čistém profilu, 2026-06-26 build+log-scan gate).** Hotové položky nesou datum+zdroj, otevřené `[ ]` jsou reálné mezery, které ještě nikdo neproved.

### Legenda
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

---

## Build kandidáta

- **Signed (autoritativní pro release rozhodnutí):** GitHub `windows-signed-release.yml` → `voxrox-mail-0.1.0-windows-x64-setup.exe`, `.sig`, `latest.json`.
- **Lokální generálka (postaveno 2026-06-26 z HEADu 3516bf5):**
  ```powershell
  cd C:\dev\java\mail\backend ; .\package-sidecar-dev-windows.ps1 -SkipTests
  cd C:\dev\java\mail\frontend ; npm run tauri:build:with-sidecar
  # → src-tauri\target\release\bundle\nsis\voxrox-mail-0.1.0-windows-x64-setup.exe
  ```

### Helper — health / diagnostic-dump (čte port+klíč ze session.json)

```powershell
$data = "$env:LOCALAPPDATA\VoxRox\Mail"
$s = Get-Content "$data\session.json" -Raw | ConvertFrom-Json
$s | Format-List appVersion, apiVersion, minClientVersion, dbSchemaVersion, port, baseUrl
Invoke-RestMethod "$($s.baseUrl)/internal/health" -Headers @{ 'X-API-KEY' = $s.apiKey }   # → 200 + status
# Invoke-WebRequest "$($s.baseUrl)/internal/diagnostic-dump" -Headers @{ 'X-API-KEY' = $s.apiKey } -OutFile "$env:TEMP\diag-dump.zip"
```

### Helper — log scan (bug D + ostatní)

```powershell
$logs = "$env:LOCALAPPDATA\VoxRox\Mail\logs"
Select-String -Path "$logs\mail.log" -Pattern 'ERROR|WARN' | Measure-Object
Select-String -Path "$logs\mail.log","$logs\mail.log.*" -Pattern 'failed to create new store connection' | Measure-Object  # bug D ≤~1/den = šum
Select-String -Path "$logs\audit.log" -Pattern 'CRITICAL'
```

---

## §3 Fresh install

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

## §3a Installer / update chování

- [ ] Instalace je per-user (`installMode: currentUser`) — bez volby režimu, bez elevace (UAC). Binárky v `%LOCALAPPDATA%\Programs\VoxRox\Mail`.
- [ ] Jazyk instalátoru automaticky CZ/EN podle systému.
- [ ] Desktop shortcut = volitelný checkbox.
- [ ] Start menu shortcut `VoxRox\VoxRox Mail`.
- [ ] Reinstall stejné verze bez ztráty dat.
- [ ] Downgrade na starší verzi zablokovaný.
- [ ] Signed release obsahuje `voxrox-mail-0.1.0-windows-x64-setup.exe`, `.sig`, `latest.json`. _(= Release&Update / Tauri updater položka, signed-only.)_

→ Pozn.: celá §3a neitemizovaná — mode/jazyk/shortcuts/reinstall/downgrade zbývají k cílenému ověření.

## §4 Account flows

- [ ] PASSWORD účet s předdefinovaným providerem. _(Netestováno — bez password účtu.)_
- [ ] PASSWORD účet s custom IMAP/SMTP. _(Netestováno.)_

### Google OAuth
Předpoklady: [x] reálné prod `GOOGLE_OAUTH_CLIENT_ID/SECRET` (org-based client, **2026-06-23**) · **[ ] consent screen `In production` — projekt je pořád `Testing`** (refresh token padá po 7 dnech mimo test users) · **[ ] restricted scope `https://mail.google.com/` CASA verifikace — BLOKUJÍCÍ pro consumer release.**

- [x] Přidat Google OAuth přes loopback — **2026-06-23 + 2026-06-25** (po fixu E #71 bez `auth-failed`).
- [x] První IMAP sync — **2026-06-23** (bounded sync bez zaseknutí).
- [x] SMTP send přes OAuth — **2026-06-23**.
- [ ] Revoke grant u Googlu → `requires_reauth=true` → UI výzva → re-login. _(Cyklus neproveden — patří k „Produkcni OAuth readiness".)_

### Microsoft OAuth
Předpoklady: [x] App Registration `VoxRox Mail` pod `info@voxrox.org` · [x] public client bez secretu (PKCE), platforma „Mobilní a klasické", redirect `http://localhost/login/oauth2/code/microsoft`, public-client flows = Ano (**2026-06-10**) · [ ] Verified publisher ODLOŽEN _(není blocker pro MSA/vlastní tenant)._

- [x] Přidat MS OAuth proti `@outlook.com` — **2026-06-23** (MSA login).
- [x] Po consent účet vytvořen, provider `outlook.com`, `requires_reauth=false` — **2026-06-23**.
- [x] První IMAP sync přes XOAUTH2 (`outlook.office365.com:993`) — **2026-06-23**.
- [x] SMTP send přes OAuth (587/STARTTLS po fixu #58) — **2026-06-23**.
- [x] Refresh token roundtrip (auto-refresh po expiraci) — **2026-06-23** (vč. po restartu).
- [ ] Zopakovat s `@hotmail.com` a `@live.com`. _(Jen `@outlook.com` testován.)_
- [ ] Revoke MS grant → `requires_reauth=true` → UI výzva → re-login. _(Cyklus neproveden.)_

→ Pozn.: enterprise tenanty (B2B) až po verified publisher — mimo cílové publikum v0.1.0.

## §5 Mail workflows

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

## §6 Sidecar lifecycle

- [x] Kill Tauri parent → sidecar NEzůstane osiřelý — **2026-06-23** (BootErrorView Retry: žádný orphan/dvojí spawn).
- [x] Kill jen sidecar → UI error stav → nabídne restart — **2026-06-23**.
- [x] Restart sidecaru obnoví health + API (přesně 1 backend) — **2026-06-23**.
- [ ] Restart počítače uprostřed syncu → SQLite WAL recovery → aplikace pokračuje. _(Netestováno.)_

→ Pozn.: kill/orphan/restart pokryto; chybí jen reboot-mid-sync WAL recovery.

## §7 Diagnostics

- [x] `logs\mail.log` bez neočekávaných `ERROR` — **2026-06-26** log-scan gate (bug D = 0).
- [x] `logs\audit.log` bez `CRITICAL` — **2026-06-26** (0 CRITICAL).
- [ ] Vygenerovat `/api/internal/diagnostic-dump` (helper výše). _(Netestováno.)_
- [ ] ZIP obsahuje `summary.json`, `accounts.json`, `folder-sync-states.json`, `message-counts.json`, `runtime.json`. _(Netestováno.)_
- [ ] ZIP NEobsahuje plné e-mailové adresy, OAuth tokeny, interní API klíč, obsah zpráv. _(Netestováno — důležitý PII check.)_

→ Pozn.: log/audit scan zelený; diagnostic-dump + PII redakce zbývají.

## §8 Long run (24 h)

- [ ] Celá sekce NEPROVEDENA — 24h soak + JFR, vyhodnocení v JMC (lock contention `accountLocks`/`refreshLocks`, leak vláken, růst DB/WAL, duplicity, thread dump). Samostatný celodenní běh.

---

## §9 Release decision

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
