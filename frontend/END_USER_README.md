# Pošta

Pošta je desktopový e-mailový klient pro Windows. Aplikace se připojuje k
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

Před spuštěním doporučujeme ověřit, že se soubor při stahování nepoškodil ani
nezměnil. Ke každému instalátoru je v releasu přiložený soubor
`…-setup.exe.sha256` s očekávaným SHA256 otiskem. V PowerShellu:

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

Google OAuth je připravený pro provider flow. Microsoft OAuth a iCloud OAuth
jsou před prvním releasem vedené jako backend dependent položky.

## Aktualizace

Produkční build používá Tauri updater. Aktualizace budou dostupné až po
nastavení produkčního updater endpointu, podpisových klíčů a prvním ověřeném
release artefaktu `voxrox-mail-<version>-windows-x64-setup.exe`. Downgrade na starší
verzi není podporovaný běžným instalátorem; případný rollback řeší support podle
recovery postupu.

## Soukromí a diagnostika

Aplikace posílá chyby klienta na vlastní backend endpoint:

```text
POST /api/internal/client-errors
```

Report obsahuje technická metadata o chybě, trase aplikace, jazyku, uživatelském
agentu a verzi backendu. API klíč se do reportu neposílá.

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

## Licence

Aplikace a zdrojový kód jsou pod licencí MIT — viz `LICENSE` v rootu repa.
Závislosti třetích stran zůstávají pod vlastními licencemi.
