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
- [ ] Sidecar launcher `app/mail-x86_64-pc-windows-msvc.cfg` nese zapečené `...google.client-id` (ne `mail-local-*` placeholder). Signed workflow i `package-sidecar-dev-windows.ps1` to zajišťují; bare `package-sidecar-windows.ps1` bez OAuth env teď build shodí, pokud neběží s `-AllowPlaceholderOAuth`.
- [ ] Sidecar výstup obsahuje `mail-x86_64-pc-windows-msvc.exe`, `app/` a `runtime/`.
- [ ] Release kandidát běží na čistém Windows profilu bez systémově instalované Java/JDK/JRE.

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
```

## 3. Fresh install

- [ ] Nainstalovat release kandidáta přes Windows NSIS installer `voxrox-mail-<version>-x64-setup.exe` na čistý profil bez existujícího `%LOCALAPPDATA%\VoxRox\Mail`.
- [ ] Ověřit výchozí cestu instalace: current-user `%LOCALAPPDATA%\VoxRox\Mail`, all-users `%ProgramFiles%\VoxRox\Mail`.
- [ ] V instalační složce jsou přibalené sidecar `mail-x86_64-pc-windows-msvc.exe`, `app/` a `runtime/`.
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

- [ ] Installer nabízí current-user/per-machine režim (`both`).
- [ ] Jazyk instalátoru je automaticky Czech/English podle systému; ostatní locale padají na English.
- [ ] Desktop shortcut je volitelný checkbox.
- [ ] Start menu shortcut vznikne jako `VoxRox\VoxRox Mail`.
- [ ] Reinstall stejné verze projde bez ztráty dat.
- [ ] Downgrade na starší verzi je zablokovaný.
- [ ] Signed release obsahuje `voxrox-mail-<version>-x64-setup.exe`, `.sig` a `latest.json`.

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
- [ ] **Verified publisher / Partner One ID** (dříve MPN ID) — **ODLOŽENO (rozhodnuto 2026-06-20: zatím bez právnické osoby).** NENÍ blokující pro consumer release: MSA účty (`@outlook.com`/`@hotmail.com`/`@live.com`) i vlastní tenant (`@voxrox.org`) dostanou consent i bez něj (uvidí jen „unverified" notici). Tvrdý blok AADSTS700016 (reprodukováno 2026-05-20 s `@tyflocentrum-ol.cz`) platí JEN pro cizí organizační Entra tenanty (B2B) — to publikum je odloženo, dokud nevznikne ověřená právnická osoba (MS business verification vyžaduje firmu ověřenou proti oficiálním rejstříkům). Postup až/pokud entita vznikne viz `todo.md` sekce „Microsoft OAuth".

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
- [ ] Ukončit jen sidecar backend.
- [ ] UI ukáže srozumitelný error stav.
- [ ] UI nabídne restart sidecaru.
- [ ] Restart sidecaru obnoví health a běžné API volání.
- [ ] Restart počítače uprostřed syncu.
- [ ] Další spuštění projde SQLite WAL recovery a aplikace pokračuje.

## 7. Diagnostics

- [ ] Zkontrolovat `logs/mail.log` bez neočekávaných `ERROR` (známé transienty — viz log-scan gate v §8).
- [ ] Zkontrolovat `logs/audit.log` bez neočekávaných `CRITICAL`.
- [ ] Vygenerovat `/api/internal/diagnostic-dump`.
- [ ] ZIP obsahuje `summary.json`, `accounts.json`, `folder-sync-states.json`, `message-counts.json`, `runtime.json`.
- [ ] ZIP neobsahuje plné e-mailové adresy, OAuth tokeny, interní API klíč ani obsah zpráv.

## 8. Long run

- [ ] Nechat aplikaci běžet 24 h se zapnutým syncem.
- [ ] Spustit běh s JFR: `-XX:StartFlightRecording=duration=24h,filename=soak.jfr,settings=profile` (u sidecaru přidat do `--java-options` v package skriptu, u dev běhu do `JAVA_TOOL_OPTIONS`).
- [ ] Vyhodnotit `soak.jfr` v JDK Mission Control: lock contention (Java Monitor Blocked / Park) na `accountLocks`/`refreshLocks`, počty výjimek, růst vláken (leak executorů).
- [ ] Na konci běhu sebrat thread dump (`jcmd <pid> Thread.print`) — žádná osiřelá/parkovaná vlákna mimo známé pooly.
- [ ] Zkontrolovat paměťovou stopu.
- [ ] Zkontrolovat růst SQLite DB/WAL.
- [ ] Zkontrolovat, že IMAP pool nerecykluje chybně mrtvá spojení.
- [ ] Zkontrolovat, že opakované syncy nevytvářejí duplicitní zprávy.
- [ ] Log-scan gate po každém smoke/long runu: `Select-String -Path logs\mail.log -Pattern "ERROR|WARN"` a každý nález buď vysvětlit, nebo založit issue — tichá chybová cesta je přesně třída chyb z review 2026-06.
- [ ] Spočítat výskyty známého přechodného hiccupu **D**: `failed to create new store connection` ([MailSyncService.java:220](src/main/java/org/voxrox/mailbackend/feature/mail/service/MailSyncService.java)) — transientní IMAP chyba, kterou další sync cyklus sám zotaví (dnes ji catch-all loguje jako `ERROR` „Critical error during folder sync" a krátce nastaví účtu `last_error`, bez retry). **≤ ~1/den = očekávaný šum, jen zaznamenat; shluk nebo > pár/den = eskalovat** na bounded retry+backoff a transient překlasifikovat na `WARN`, ať gate nešumí. Při výskytu zapsat dimenze: shlukuje se po sleep/wake nebo změně sítě? provider/složka? potvrzené zotavení v dalším cyklu?

## 9. Release decision

```text
Blockery:

Known issues:

Schváleno:
```
