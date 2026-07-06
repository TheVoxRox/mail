# VoxRox Mail

VoxRox Mail je desktopový e-mailový klient pro Windows. Aplikace se připojuje k
backendové službě, která drží lokální konfiguraci účtů, komunikuje s
IMAP/SMTP servery a poskytuje API pro uživatelské rozhraní.

## Co aplikace umí

- Správa více e-mailových účtů.
- Čtení složek, vyhledávání zpráv a práce s detailem zprávy.
- Vytváření nové zprávy, odpovědi, přeposlání a ukládání konceptů.
- Hromadné akce se zprávami: označení jako přečtené, mazání, přesun a spam.
- Správa kontaktů a export kontaktů do vCard.
- Lokální diagnostické logy pro podporu.

## Instalace a ověření instalátoru

Instalátor `voxrox-mail-<verze>-windows-x64-setup.exe` zatím **není podepsaný**
code-signing certifikátem (open source projekt). Windows SmartScreen proto při
spuštění zobrazí varování „Windows ochránil váš počítač / Neznámý vydavatel".
Instalaci spustíte přes **Další informace → Přesto spustit**.

### Ověření před spuštěním

Protože build není podepsaný code-signing certifikátem, doporučujeme ověřit jeho
původ. Nejsilnější je **ověření původu (build provenance)** pomocí GitHub CLI —
potvrdí, že instalátor postavil přímo oficiální release workflow tohoto
repozitáře (kryptograficky, přes Sigstore; nelze podvrhnout pouhou záměnou
souboru v releasu):

```powershell
gh attestation verify "voxrox-mail-<verze>-windows-x64-setup.exe" --repo TheVoxRox/mail
```

Přiložený `…-setup.exe.sha256` slouží už jen jako **rychlá kontrola integrity**
(že se soubor nepoškodil při stahování) — sám o sobě nechrání proti úmyslné
záměně, protože leží ve stejném releasu:

```powershell
certutil -hashfile "voxrox-mail-<verze>-windows-x64-setup.exe" SHA256
```

Vypsaný otisk musí odpovídat hodnotě v `…-setup.exe.sha256`.

## Před prvním spuštěním

Aplikace potřebuje běžící backend. V distribučním Tauri buildu je backend
součástí balíčku jako sidecar a spouští se automaticky. Ve vývojovém browser
režimu musí backend běžet zvlášť.

Distribuční build obsahuje vlastní Java runtime pro backend. Uživatel nemusí
instalovat Java, JDK ani JRE samostatně.

Backend zapisuje lokální handshake soubory do:

```text
%LOCALAPPDATA%\VoxRox\Mail\session.json
%LOCALAPPDATA%\VoxRox\Mail\.ready
```

Frontend z nich čte adresu backendu a API klíč. Tyto hodnoty neupravujte ručně.

## Účty a hesla

U providerů bez OAuth může aplikace vyžadovat aplikační heslo. To je samostatné
heslo vygenerované v nastavení poskytovatele e-mailu. Nepoužívejte hlavní heslo
k účtu, pokud poskytovatel doporučuje aplikační hesla.

Přihlášení přes OAuth je podporované pro Google (Gmail) a Microsoft
(Outlook/Hotmail/Live). iCloud OAuth zatím podporovaný není — u iCloudu
použijte aplikační heslo.

## Aktualizace

Produkční build používá Tauri updater. Aplikace periodicky kontroluje novou
verzi proti `https://github.com/TheVoxRox/mail/releases/latest/download/latest.json`
a stahuje jen podepsané instalátory (Ed25519). Při této kontrole GitHub dočasně
vidí vaši IP adresu a dotazovanou verzi — viz [PRIVACY.md](../PRIVACY.md).
Kontrola se aktivuje, jakmile je publikovaný release. Downgrade na starší verzi
není podporovaný běžným instalátorem; případný rollback řeší support podle
recovery postupu.

## Soukromí a diagnostika

Aplikace je připravená posílat chyby klienta na **lokální** backend endpoint na
127.0.0.1:

```text
POST /api/internal/client-errors
```

Tento endpoint zatím není implementovaný — klient po prvním neúspěšném pokusu
(HTTP 404) reportování sám vypne. Až vznikne, půjde jen o loopback zápis do
lokálních logů; report (technická metadata o chybě, trasa aplikace, jazyk, user
agent, verze backendu — API klíč se neposílá) **nikdy neopustí vaše zařízení**.
Podrobnosti viz [PRIVACY.md](../PRIVACY.md).

Release build také zapisuje lokální data a logy aplikace. Na Windows jsou v:

```text
%LOCALAPPDATA%\VoxRox\Mail
```

## Zabezpečení dat na disku

Aplikace ukládá staženou poštu, kontakty a stav synchronizace do lokální SQLite
databáze v `%LOCALAPPDATA%\VoxRox\Mail`. Přihlašovací údaje a OAuth tokeny jsou
šifrované, ale **obsah a hlavičky zpráv, předměty, odesílatelé a kontakty
v databázi šifrované nejsou** — chrání je pouze oprávnění vašeho uživatelského
profilu Windows.

Pokud se zařízením cestujete nebo na něm máte citlivou poštu, **zapněte
šifrování celého disku BitLockerem** (Nastavení Windows → Soukromí a zabezpečení
→ Šifrování zařízení / BitLocker). Tím se data ochrání i při ztrátě nebo krádeži
vypnutého počítače.

## Zálohy a vaše odpovědnost

Data zůstávají na vašem zařízení a VoxRox k nim nemá přístup — **za jejich
zálohu odpovídáte vy.** Chcete-li se chránit před ztrátou nebo poškozením disku,
zálohujte si celý adresář `%LOCALAPPDATA%\VoxRox\Mail` (bezpečný postup viz
[OPERATIONS.md](../backend/OPERATIONS.md)). Software je poskytován „tak jak je",
bez záruky (licence MIT).

## Licence

Aplikace a zdrojový kód jsou pod licencí MIT — viz `LICENSE` v rootu repa.
Závislosti třetích stran zůstávají pod vlastními licencemi.
