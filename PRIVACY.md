# Zásady ochrany soukromí — VoxRox Mail

_Verze: 2026-06-01 (návrh před prvním vydáním). Tato verze je předběžná, ještě
nebyla schválena právníkem ani pokrývá konečný kontaktní bod podpory — viz
sekce „Otevřené body" na konci dokumentu._

_English version: [PRIVACY.en.md](PRIVACY.en.md)._

VoxRox Mail je desktopový e-mailový klient pro Windows. Aplikace běží lokálně
na vašem počítači. Vaše e-maily, kontakty, přihlašovací údaje k účtům a logy
zůstávají na vašem zařízení a neposílají se na žádný server VoxRox ani jiné
třetí strany — kromě těch, které si sami zvolíte přidáním e-mailového účtu
(váš poskytovatel e-mailu, případně Google nebo Microsoft pro OAuth přihlášení).

VoxRox neprovozuje pro tuto aplikaci žádný backendový server, neukládá vaše
data v cloudu a neshromažďuje telemetrii, analytiku ani crash reporty na
externí endpoint.

## Kdo je správce dat

Správcem osobních údajů ve smyslu GDPR jste **vy sami** — všechna data jsou
uložena lokálně na vašem zařízení pod vaším účtem operačního systému. VoxRox
poskytuje pouze software, který data zpracovává podle vašich pokynů, a nemá
k vašim datům přístup.

## Jaká data aplikace ukládá lokálně

Veškerá perzistentní data jsou ve standardním Windows datovém adresáři:

```text
%LOCALAPPDATA%\VoxRox\Mail\
```

Obsah:

| Soubor / složka | Co obsahuje |
|---|---|
| `crypto.bin` | Lokální šifrovací klíč pro přihlašovací údaje (vzniká při prvním startu). |
| `crypto.fingerprint` | Otisk klíče pro detekci výměny nebo poškození `crypto.bin`. |
| `session.json` | Port a interní API klíč aktuálně běžícího backendu (přepisuje se při každém startu). |
| `db/mail.db` | SQLite databáze: účty, kontakty, hlavičky a těla zpráv, stav synchronizace. |
| `db/mail.db.backup-pre-v*` | Záloha DB před každou migrací schématu (uchovává se 3 nejnovější). |
| `attachments/` | Lokální kopie příloh stažených ze serveru. |
| `logs/mail.log` | Aplikační log (rotuje, max 7 souborů × 10 MB, celkem cca 100 MB). |
| `logs/audit.log` | Bezpečnostní/auditní log (retenční doba 365 dní, max cca 500 MB). |
| `tmp/` | Dočasné soubory (mažou se automaticky). |

### Co je šifrované

- **Hesla k IMAP/SMTP** a **OAuth tokeny** (Google, Microsoft) jsou šifrované
  algoritmem AES-GCM s klíčem odvozeným z `crypto.bin`. Bez `crypto.bin`
  uloženého ve vašem profilu je nelze dešifrovat.
- **`crypto.bin` samotný** je chráněn pouze oprávněními Windows souborového
  systému vašeho uživatelského profilu. Doporučení: nezálohujte ho do
  veřejných cloudových úložišť bez dodatečného šifrování.

### Co šifrované není

- Hlavičky a těla e-mailových zpráv, kontakty, předměty zpráv, odesílatelé,
  přílohy, log soubory a metadata synchronizace jsou v SQLite databázi a na
  disku v otevřené podobě. Pokud potřebujete ochranu před fyzickým přístupem
  k disku, použijte šifrování celého disku (BitLocker apod.).

## Jaká data putují po síti

Aplikace zahájí síťovou komunikaci jen v těchto případech:

1. **Připojení k vašemu e-mailovému serveru (IMAP a SMTP)** — stahování pošty,
   odesílání, synchronizace složek. Kam přesně se aplikace připojuje záleží
   na účtu, který přidáte (Gmail, Outlook, Seznam, vlastní server).
2. **OAuth přihlášení (Google, Microsoft)** — pokud zvolíte tento způsob
   přihlášení místo aplikačního hesla. Aplikace otevře váš výchozí prohlížeč
   na přihlašovací stránce poskytovatele, obdrží access token + refresh
   token a uloží je lokálně (šifrované, viz výše). Pravidelně si od
   poskytovatele bere nový access token přes refresh token.
3. **Aktualizace aplikace** (Tauri updater) — kontrola dostupné nové verze
   proti release endpointu a stažení podepsaného instalátoru. Konkrétní URL
   bude potvrzena před prvním veřejným vydáním.

Aplikace **neposílá** vaše e-maily, kontakty ani aktivitu na servery VoxRox
ani na žádnou analytickou platformu třetí strany.

### Diagnostické reporty z klienta

Frontend posílá technické chyby (JavaScript exceptions, neúspěšné API volání)
na **lokální** backendový endpoint `POST /api/internal/client-errors`, který
běží na 127.0.0.1 ve vašem počítači. Tyto reporty se nikam neodesílají, slouží
pouze k zápisu do lokálních logů.

Pokud kontaktujete podporu a poskytnete jí ručně exportovaný diagnostický
balíček (`GET /api/internal/diagnostic-dump`), tento balíček obsahuje pouze
maskované e-mailové adresy, konfiguraci účtů (host, port, SSL), stav
synchronizace a runtime metriky. **Neobsahuje** přihlašovací údaje, OAuth
tokeny, obsah zpráv ani předměty.

## Třetí strany

Pokud přidáte účet, vstupují do hry tito poskytovatelé:

- **Váš poskytovatel e-mailu** (Gmail, Outlook, Seznam, vlastní server, ...)
  — řídí se vlastními zásadami zpracování dat.
- **Google** — pokud používáte Gmail s OAuth ([https://policies.google.com/privacy](https://policies.google.com/privacy)).
- **Microsoft** — pokud používáte Outlook/Hotmail/Live s OAuth ([https://privacy.microsoft.com/](https://privacy.microsoft.com/)).
- **GitHub** — pokud se v budoucnu rozhodneme distribuovat aktualizace přes
  GitHub Releases, aplikace bude periodicky kontrolovat dostupnost nové
  verze proti GitHub API ([https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement)).

VoxRox nemá s těmito poskytovateli žádnou sdílecí dohodu o vašich datech.
Komunikace probíhá přímo mezi vaším počítačem a daným serverem.

## Vaše práva a možnosti kontroly

- **Smazat účet z aplikace** — odeberte účet v Nastavení. Tím se kaskádově
  smaže záznam účtu, přihlašovací údaje, stav synchronizace, zprávy a
  kontakty z lokální databáze.
- **Odvolat OAuth přístup** u poskytovatele:
  - Google: [https://myaccount.google.com/permissions](https://myaccount.google.com/permissions) → vyhledat „VoxRox
    Mail" → Odebrat přístup.
  - Microsoft: [https://account.microsoft.com/privacy/app-access](https://account.microsoft.com/privacy/app-access) (osobní
    účty) nebo [https://myapps.microsoft.com](https://myapps.microsoft.com) (firemní účty) → najít aplikaci
    podle Client ID → „Remove permission". Microsoft neimplementuje
    standardní revoke endpoint (RFC 7009), takže odvolání musíte provést
    u nich.
- **Kompletní odstranění dat** — odinstalujte aplikaci a poté **ručně
  smažte** adresář `%LOCALAPPDATA%\VoxRox\Mail\`. Instalátor data adresář
  záměrně nemaže (aby šlo bezpečně přeinstalovat bez ztráty pošty).
- **Export dat** — kontakty lze exportovat do vCard přes Nastavení > Kontakty.
  Export celé databáze není ve verzi 0.x dostupný v UI; pokročilí uživatelé
  si mohou zkopírovat `db/mail.db` přímo a otevřít v jakémkoli SQLite
  prohlížeči (např. DB Browser for SQLite).
- **Přístup k logům** — soubory v `logs/` jsou prostý text čitelný editorem.

## Děti

Aplikace není cíleně určená pro děti pod 16 let, ale ani jejich použití
neomezuje — funkce závisí výhradně na účtech, které si uživatel sám přidá.
Doporučení rodičům: ověřte, že zvolený poskytovatel e-mailu povoluje použití
dítětem.

## Bezpečnostní oznámení

Pokud objevíte zranitelnost nebo bezpečnostní problém v aplikaci, **nehlaste
ho prosím veřejně v issue trackeru.** Kontaktní bod pro bezpečnostní hlášení
bude potvrzen před prvním veřejným vydáním — do té doby používejte stejný
kontakt jako pro běžnou podporu (viz níže).

## Změny těchto zásad

Tato verze je předběžná. Před prvním veřejným vydáním a při každé materiální
změně (např. zapojení telemetrie, změna seznamu třetích stran) tento dokument
aktualizujeme. Aktuální verze je vždy součástí instalace a v repozitáři projektu.

## Otevřené body před prvním vydáním

Tyto položky budou doplněny předtím, než je tento dokument finalizován:

- [ ] Kontakt na podporu (e-mail / URL) — odkazovaný ve frontend UI i tomto
      dokumentu.
- [ ] Kontakt pro bezpečnostní hlášení (responsible disclosure).
- [ ] Konkrétní Tauri updater endpoint URL.
- [x] Anglická verze tohoto dokumentu — [PRIVACY.en.md](PRIVACY.en.md), 2026-06-01.
- [ ] Právní review (zejména GDPR formulace „správce dat" pro variantu, kdy
      uživatel nasazuje aplikaci v rámci organizace).

---

_VoxRox Mail je open-source software pod licencí MIT (viz [LICENSE](LICENSE)).
Závislosti třetích stran jsou uvedené v `THIRD_PARTY_LICENSES.md` jednotlivých
modulů ([backend](backend/THIRD_PARTY_LICENSES.md), [frontend](frontend/THIRD_PARTY_LICENSES.md),
[Tauri runtime](frontend/src-tauri/THIRD_PARTY_LICENSES.md))._
