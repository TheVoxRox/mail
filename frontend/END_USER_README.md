# VoxRox Mail

VoxRox Mail je desktopový e-mailový klient pro Windows. Aplikace se připojuje k
backendové službě, která drží lokální konfiguraci účtů, komunikuje s
IMAP/SMTP servery a poskytuje API pro uživatelské rozhraní.

## Co aplikace umí

- Správa více e-mailových účtů.
- Čtení složek a práce s detailem zprávy.
- Vyhledávání zpráv. Hledá se v poště stažené do počítače, kde nemusí být
  starší zprávy ani složky, které jste ještě neotevřeli; u neotevřených zpráv
  jen v předmětu, odesílateli a příjemcích. Výsledek hledání to vždy uvádí.
- Zobrazení pošty po jednotlivých zprávách, nebo seskupené do konverzací
  (vlákno drží zprávy pohromadě i napříč složkami; Odeslané, Koš, Spam a
  Rozepsané zůstávají stranou a seskupují se samy pro sebe).
- Vytváření nové zprávy, odpovědi, přeposlání a ukládání konceptů; adresy
  našeptává adresář i historie vaší korespondence. Návrh z historie, který
  nechcete (u takového návrhu stojí „není v kontaktech"), odeberete křížkem na
  jeho řádku, nebo klávesou Delete: šipkou na něj přejděte a s kurzorem na
  konci pole stiskněte Delete. Adresa se vrátí s další zprávou od toho
  člověka nebo pro něj.
- Psaní v Markdownu: napíšete-li `**tučně**`, `# nadpis` nebo `- odrážku`,
  příjemce dostane zprávu i naformátovanou. Editor zůstává obyčejné textové
  pole a odchozí zpráva vždy nese i přesně to, co jste napsali — kdo Markdown
  nepoužívá, nepozná rozdíl.
- Běh v oznamovací oblasti: ve výchozím nastavení křížek aplikaci ukončí.
  Přepnete-li Nastavení → Vzhled → Zavření okna na „nechat běžet", zavření
  okna aplikaci jen skryje a ta dál stahuje poštu a hlásí nové zprávy; ukončit
  ji pak jde přes nabídku ikony v oznamovací oblasti.
- Oznámení Windows o nové poště: když je okno skryté v oznamovací oblasti nebo
  pracujete v jiné aplikaci, ohlásí nově doručenou poštu do Doručených a
  Hromadných oznámení systému Windows s počtem zpráv, účtem a složkou (bez
  odesílatele a předmětu). Kliknutí na oznámení, dokud je na obrazovce, vrátí
  okno aplikace. Vypnout ho jde v Nastavení → Vzhled → Oznámení Windows o nové
  poště, nebo v nastavení oznámení ve Windows.
- Oznámení v pravém horním rohu: chybová zůstávají, dokud je nezavřete,
  ostatní se po několika sekundách zavřou sama. Dokud je na oznámení ukazatel
  myši nebo fokus, odpočet stojí. V Nastavení → Vzhled → Zavírání oznámení je
  můžete nechat zobrazená všechna, dokud je nezavřete.
- Hromadné akce se zprávami: označení jako přečtené, mazání, přesun a spam.
- Správa kontaktů, vlastní štítky kontaktů (Rodina, Klienti, …) a export
  kontaktů do vCard.
- Blokování vzdálených obrázků ve zprávách s možností je povolit jednorázově
  nebo trvale pro konkrétního odesílatele. Trvalé povolení jde kdykoli odebrat
  v nastavení účtu a ve složce Spam se nepoužije (viz
  [PRIVACY.md](../PRIVACY.md)).
- Ovládání klávesnicí s výpisem zkratek v Nastavení → Klávesové zkratky;
  volba vzhledu (světlý/tmavý), rozložení čtení a jazyka rozhraní.
- Lokální diagnostické logy pro podporu.

## Instalace a ověření instalátoru

Instalátor stahujte jen ze stránky
[Releases](https://github.com/TheVoxRox/mail/releases) tohoto repozitáře.
Jmenuje se `voxrox-mail-<verze>-windows-x64-setup.exe` a nová instalace
nepotřebuje práva správce. Kopii odjinud mají odhalit právě kontroly níže.

Instalátor zatím **není podepsaný** certifikátem Authenticode; jak budeme
vydání podepisovat, popisují
[zásady podepisování kódu](https://voxrox.org/code-signing/). Windows
SmartScreen proto při spuštění zobrazí varování „Windows ochránil váš počítač /
Neznámý vydavatel". Instalaci spustíte přes **Další informace → Přesto
spustit** — až když soubor ověříte.

### Ověření před spuštěním

<!-- The same procedure in English is docs/VERIFYING_THE_INSTALLER.md, linked
from the release notes. A change to one is a change to both. -->

První dvě kontroly potřebují [GitHub CLI](https://cli.github.com/) (`gh`)
přihlášené k libovolnému účtu GitHub (`gh auth login`); bez přihlášení ani
jeden příkaz neproběhne. Spusťte je v PowerShellu ve složce, kam jste instalátor
stáhli, a `<verze>` nahraďte staženou verzí, například `0.2.0`.

Nejsilnější je **ověření původu (build provenance)** — potvrdí, že instalátor
postavil přímo oficiální release workflow tohoto repozitáře (kryptograficky,
přes Sigstore; nelze podvrhnout pouhou záměnou souboru v releasu):

```powershell
gh attestation verify "voxrox-mail-<verze>-windows-x64-setup.exe" --repo TheVoxRox/mail
```

Releasy vydané po verzi 0.2.0-beta.1 jsou navíc neměnné: po vydání v nich GitHub
nedovolí žádný soubor vyměnit. Že je stažený instalátor přesně ten vydaný,
potvrdí:

```powershell
gh release verify-asset v<verze> "voxrox-mail-<verze>-windows-x64-setup.exe" --repo TheVoxRox/mail
```

Přiložený `…-setup.exe.sha256` slouží už jen jako **rychlá kontrola integrity**
(že se soubor nepoškodil při stahování) — sám o sobě nechrání proti úmyslné
záměně, protože leží ve stejném releasu:

```powershell
certutil -hashfile "voxrox-mail-<verze>-windows-x64-setup.exe" SHA256
```

Vypsaný otisk musí odpovídat hodnotě v `…-setup.exe.sha256`.

**Když některá kontrola neprojde**, instalátor nespouštějte. Stáhněte ho znovu
ze stránky Releases a kontrolu zopakujte. Pokud neprojde ani potom, nahlaste to
jako bezpečnostní problém podle stránky [Podpora](https://voxrox.org/support/).

Aktualizace nainstalovaná z aplikace nic z toho nepotřebuje: aplikace sama
ověří podpis každé aktualizace, než ji nainstaluje (viz
[Aktualizace](#aktualizace)).

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

Jak se účet přihlašuje, záleží na poskytovateli. Průvodce přidáním účtu ho
pozná podle adresy a nabídne jen to, co u něj funguje. Heslo aplikace je
samostatné heslo, které si vytvoříte v nastavení účtu u poskytovatele a které
platí jen pro poštovní programy.

- **Gmail a Google Workspace:** tlačítko „Přihlásit přes Google", nebo „Použít
  IMAP a heslo aplikace". Přihlášení přes Google zatím funguje jen pro účty,
  které vývojář aplikace přidal mezi testovací uživatele; ostatní účty přidejte
  přes heslo aplikace. Google přes IMAP nepřijme hlavní heslo k účtu, jen heslo
  aplikace, a to jde vytvořit až se zapnutým dvoufázovým ověřením. Google heslo
  ukazuje ve čtyřech skupinách po čtyřech písmenech; vložit ho můžete i s
  mezerami, aplikace je vynechá. U účtu Google Workspace může hesla aplikace
  vypnout správce organizace.
- **Outlook, Hotmail, Live a pracovní nebo školní účty Microsoft 365:** jen
  „Přihlásit přes Microsoft". Microsoft přes IMAP nepřijme heslo k účtu ani
  heslo aplikace. U pracovního nebo školního účtu může být potřeba, aby
  aplikaci nejdřív schválil správce organizace; správce také může mít pro
  schránku vypnutý přístup přes IMAP nebo odesílání přes SMTP.
- **Seznam.cz:** heslo k účtu. Máte-li na Seznamu zapnuté dvoufázové ověření,
  použijte místo něj heslo aplikace.
- **Ostatní poskytovatelé a vlastní servery:** heslo, které poskytovatel pro
  poštovní programy vyžaduje. Kde doporučuje heslo aplikace, nepoužívejte
  hlavní heslo k účtu. Přihlášení k iCloudu přes OAuth aplikace zatím
  nepodporuje, použijte heslo aplikace.

Když průvodce doménu adresy nezná, nabídne vedle ručního nastavení serverů i
přihlášení přes Microsoft a Google: pracovní a školní účty se přihlašují pod
vlastní doménou organizace.

## Aktualizace

Produkční build používá Tauri updater. Aplikace se **jednou při spuštění**
zeptá, jestli existuje novější verze, proti
`https://github.com/TheVoxRox/mail/releases/latest/download/latest.json`
a stahuje jen podepsané instalátory (Ed25519). **Za běhu se už sama znovu
neptá** — další dotaz proběhne až při příštím spuštění, nebo když kontrolu
sami vyvoláte v Nastavení → O aplikaci. Když se aplikaci nepodaří spustit,
nabídne tlačítko **Zkontrolovat aktualizace** i na obrazovce s chybou a při
spuštění se zeptá i tak, protože opravou takové chyby bývá právě nová verze.
Při této kontrole GitHub dočasně
vidí vaši IP adresu a dotazovanou verzi — viz [PRIVACY.md](../PRIVACY.md).
Kontrolu při spuštění můžete v Nastavení → O aplikaci vypnout; aplikace se pak
ke GitHubu připojí jen tehdy, když kontrolu vyvoláte sami. Na totéž se ptá už
instalátor na stránce Soukromí.
Kontrola se aktivuje, jakmile je publikovaný release. Downgrade na starší verzi
není podporovaný běžným instalátorem; případný rollback řeší support podle
recovery postupu.

V Nastavení → O aplikaci si můžete zvolit kanál aktualizací: **Stabilní**
(výchozí) nebo **Beta**. Beta kanál dostává nové verze dříve, na otestování
před stabilním vydáním — kontroluje se proti
`https://github.com/TheVoxRox/mail/releases/download/beta/latest.json` a platí
pro něj stejné ověření podpisu. Beta verze mohou obsahovat chyby; budeme rádi
za jejich nahlášení na **info@voxrox.org**.

## Soukromí a diagnostika

Neošetřené chyby rozhraní aplikace zapisuje do svého lokálního logu
(`logs/mail.log`), nikam je neodesílá.

Když narazíte na chybu, otevřete **Nastavení → O aplikaci** a stiskněte
**Stáhnout diagnostiku**. Uloží se ZIP se stavem aplikace a s jejími logy,
který můžete přiložit k hlášení chyby. Logy v něm jsou tak, jak je aplikace
ukládá ve vašem počítači, tedy i s názvy složek a účtů; hesla, tokeny ani obsah
zpráv v něm nejsou a e-mailové adresy jsou zamaskované. Co přesně obsahuje,
popisuje [PRIVACY.md](../PRIVACY.md) v části „Diagnostický balíček“.

Pokud se aplikace nespustí a ukáže chybu, stiskněte na té obrazovce **Uložit
logy**. Do složky Stažené soubory se uloží ZIP s logy aplikace a aplikace
oznámí, jak se soubor jmenuje. Ten přiložte k hlášení chyby.

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

## Poškozená databáze

Když aplikace při startu oznámí, že je databáze pošty poškozená, restart
nepomůže. Databázi obnovíte ze zálohy, kterou aplikace dělá sama:

1. Aplikaci zavřete. Ve Správci úloh zkontrolujte, že už neběží proces
   `voxrox-mail-backend`.
2. Otevřete složku `%LOCALAPPDATA%\VoxRox\Mail\db` (cestu vložte do adresního
   řádku Průzkumníka).
3. Soubor `mail.db` přejmenujte na `mail.db.broken`. Soubory `mail.db-wal`
   a `mail.db-shm` smažte, pokud ve složce jsou.
4. Najděte nejnovější soubor, jehož jméno začíná `mail.db.backup-pre-v`,
   zkopírujte ho do stejné složky a kopii přejmenujte na `mail.db`.
5. Spusťte aplikaci.

Záloha vzniká před každou aktualizací, která mění databázi, takže v ní chybí
to, co přibylo potom. Pošta se ze serveru stáhne znovu, ale novější kontakty
a štítky se ztratí.

Když žádná záloha není nebo obnova nepomůže, vynechte krok 4. Aplikace se pak
spustí jako po nové instalaci a účty bude potřeba přidat znovu; pošta se ze
serveru stáhne znovu, kontakty a štítky se ztratí. Soubor `mail.db.broken` si
ponechte, podpora z něj často dokáže část dat zachránit.

## Licence

Aplikace a zdrojový kód jsou pod licencí MIT — viz `LICENSE` v rootu repa.
Závislosti třetích stran zůstávají pod vlastními licencemi.
