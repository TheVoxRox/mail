# Security release check

Stav z 2026-04-30 pro aktuální backend artefakt a frontend dependency strom.

Aktualizace 2026-05-08: zopakovaný lokální pre-release secret audit po Tauri
release buildu.

## Ověřeno

- [x] Backend build prošel: `mvn package`
- [x] Testy prošly: `Tests run: 568, Failures: 0, Errors: 0, Skipped: 0`
- [x] SBOM vygenerován přes CycloneDX Maven plugin:
  - `target/bom.xml`
  - `target/bom.json`
- [x] SBOM obsahuje 155 komponent.
- [x] Maven dependency tree se sestaví bez konfliktů: `mvn dependency:tree`.
- [x] Výsledný JAR neobsahuje `application-it.properties`.
- [x] Výsledný JAR neobsahuje `.env`.
- [x] Výsledný JAR neobsahuje `session.json`.
- [x] Výsledný JAR neobsahuje `crypto.bin`.
- [x] Rychlý binary scan výsledného JARu nenašel:
  - `MAIL_CRYPTO_KEY=`
  - `MAIL_CRYPTO_SALT=`
  - `test-api-key`
  - `dev-api-key`
  - `dummy-secret`
  - `google-client-secret`
  - `internal_api_key`
- [x] `.env.example` nechává `MAIL_CRYPTO_KEY` a `MAIL_CRYPTO_SALT` prázdné.
- [x] Produkční `application.properties` odkazuje na env proměnné, neobsahuje konkrétní OAuth secret.
- [x] Aktuální Tauri sidecar app JAR ani launcher config neobsahují rychlé secret patterny ani `session.json` / `crypto.bin`.
- [x] Aktuální lokální `backend/.env` je ignorovaný gitem a obsahuje vyplněné jen lokální proměnné; hodnoty se nevypisují do logu auditu.
- [x] Root `.gitignore` doplněn o globální pojistky pro `.env`, lokální logy/DB a signing key/cert soubory (`*.key`, `*.pem`, `*.p12`, `*.pfx`), s explicitní výjimkou pro šablony/test env.
- [x] Tauri desktop režim defaultně nepředává `MAIL_CRYPTO_KEY`/`MAIL_CRYPTO_SALT` z `backend/.env`; release/dev sidecar používá lokální `crypto.bin` bootstrap, explicitní env crypto je opt-in.
- [x] Desktop bootstrap režim opraví stale `crypto.fingerprint` podle `crypto.bin`. Interní handshake API klíč je in-memory only (generovaný při každém startu sidecaru, zapsaný do `session.json`, nikdy persistovaný v DB), takže ho rotace crypto materiálu netýká. Nečitelná uživatelská encrypted credentials se nepřepisují, účet se označí k novému přihlášení.
- [x] Přímý scan aktuálních release artefaktů proti vyplněným hodnotám z `backend/.env` nenašel žádnou shodu:
  - `backend/target/mail-backend-0.1.0.jar`
  - `frontend/src-tauri/target/release/app.exe`
  - `frontend/src-tauri/target/release/mail.exe`
  - `frontend/src-tauri/target/release/bundle/nsis/Mail_0.1.0_x64-setup.exe`
  - `frontend/src-tauri/target/release/bundle/msi/Mail_0.1.0_x64_en-US.msi`
- [x] Přímý scan textových souborů, které by `git ls-files --others --exclude-standard` dovolil přidat, nenašel žádnou vyplněnou hodnotu z `backend/.env`.
- [x] Frontend `npm audit --json` po lockfile hardeningu hlásí 0 zranitelností.
- [x] Frontend dependency hardening:
  - `@sveltejs/kit -> cookie` override na `0.7.2` kvůli GHSA-pxg6-pf52-xh8x.
  - `postcss` override na `8.5.12` kvůli GHSA-qx2v-qp2m-jg93.
  - `svelte-i18n -> esbuild` override na `0.28.0` kvůli GHSA-67mh-4wv8-2f99.
- [x] Frontend ověření po hardeningu: `npm run check`, `npm run build`, `npm ls svelte-i18n esbuild postcss cookie`.

## Otevřené před releasem

- [ ] Spustit vulnerability scan nad `target/bom.json` nebo přímo Maven dependency tree v nástroji s aktuální CVE databází.
  - 2026-05-08: OWASP Dependency-Check lokálně nedoběhl kvůli NVD API 429 bez API key; SBOM `target/bom.json` byl znovu vygenerován.
- [ ] Rozhodnout, jestli se OWASP Dependency-Check zapojí do CI buildu. Doporučení: zapojit spíš v CI/release pipeline s NVD API key, ne jako povinný lokální build krok.
- [x] Po aktuálním lokálním installer/sidecar balení zopakovat secret scan nad distribuovaným bundlem, nejen nad backend JARem a aktuálním `src-tauri/binaries/app`.
- [ ] Před prvním GitHub pushem rotovat lokální Google OAuth secret nalezený v ignorovaném `backend/.env`, protože už byl použit v lokálním vývoji.

## Použité příkazy

```powershell
mvn -Dmaven.repo.local=C:\dev\java\mail-backend\.m2repo -Dapp.data-dir=C:\dev\java\mail-backend\target\test-data package
mvn -Dmaven.repo.local=C:\dev\java\mail-backend\.m2repo -Dapp.data-dir=C:\dev\java\mail-backend\target\test-data -DskipTests cyclonedx:makeAggregateBom
mvn -Dmaven.repo.local=.m2repo -Dapp.data-dir=target/test-data -DskipTests org.owasp:dependency-check-maven:check
mvn -Dmaven.repo.local=C:\dev\java\mail-backend\.m2repo dependency:tree
jar tf target\mail-backend-0.1.0.jar
npm audit --json
npm run check
npm run build
npm ls svelte-i18n esbuild postcss cookie
```
