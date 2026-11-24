# Translating the interface

How the interface languages are put together, and how to add one. This is a
**living** document: it describes the code as it is.

## A language is two files

Each interface language is two files named by its two-letter code, with no
region: the first start reads only the first part of the Windows language
(`de` from `de-AT`), and the backend looks for `messages_<xx>.properties`.

| File                                                  | Holds                                                    |
| ----------------------------------------------------- | -------------------------------------------------------- |
| `frontend/src/lib/i18n/messages/<xx>.json`            | every text of the interface, and two word lists (below)  |
| `backend/src/main/resources/messages_<xx>.properties` | the backend's texts: errors, the quoted message's header |

Nothing in the code names a language. Once both files are in place:

- **Settings → Language** offers it under `app.localeLabel`, the language's
  name in itself ("Deutsch").
- A Windows set to it starts the app in it. Any language without a translation
  starts in English
  ([DECISIONS.md](DECISIONS.md), "A system language without a translation
  starts the app in English").
- `<html lang>`, the window title (`app.title`), dates and numbers (through
  `Intl`), the tray menu and the notifications follow it.
- The backend answers in it, because the client sends the interface language
  as `Accept-Language`.
- The command palette finds commands by their titles in it, and the attachment
  reminder reads its words.

## Adding a language

1. Copy `en.json` to `<xx>.json` in `frontend/src/lib/i18n/messages/` and
   translate every message. Leave the keys alone.
2. Give every message file a title for the new language in the
   `command.switchLanguageTo` family, and give the new file one for every
   language. The title names the target language in the reader's own: "Switch
   language to German" in `en.json`, "Přepnout jazyk na němčinu" in
   `cs.json`. Czech declines the name, so it cannot be an argument.
3. Copy `messages_en.properties` to `messages_<xx>.properties` in
   `backend/src/main/resources/` and translate it. The file is UTF-8, as Spring
   Boot reads it.
4. From `frontend/`, run `npm run check:i18n` and `npm run check:i18n:backend`
   and fix what they report, then `npm run test:unit`.
5. Add a bullet to `CHANGELOG.md`.

Optional, and fine to leave for later:

- **Installer.** Add the NSIS name of the language to `languages` in
  `frontend/src-tauri/tauri.conf.json`, and a `.nsh` file with the privacy
  page's strings beside `frontend/src-tauri/windows/Czech.nsh`, as UTF-8
  without a BOM (makensis fails on one). Without it, the installer shows
  English to that system.
- **Documents and the website.** `frontend/END_USER_README.md` is Czech only,
  and voxrox.org and the privacy policy are Czech and English.

## The two word lists

Two kinds of entry in a message file are data rather than sentences: comma
separated words. Matching ignores case and diacritics, so write the words as
the language writes them. The words of **every** language are used at once,
whatever the interface language, because what a user types or writes need not
be in it.

- `commandKeywords.<name>` gives synonyms to the palette command whose title is
  `command.<name>`. The titles are searched in every language already, so list
  only words a user would type that no title contains: "find" for Search,
  "flag" for Star. A command needs no entry.
- `compose.attachmentWords` are the words that make the reminder ask whether to
  send without an attachment. A word ending in `*` is a stem and takes any
  ending: `příloh*` covers "příloha", "v příloze", "přílohy". A word without one
  matches whole, for a stem that would catch an unrelated word: Czech keeps
  `přikládám` whole, because the stem would also catch "příklad" (example)
  once diacritics are gone.

## Plurals

A message that counts uses ICU's plural form:
`{count, plural, one {# zpráva} few {# zprávy} other {# zpráv}}`. Which
categories a language needs comes from its CLDR rules: Czech one/few/other,
English one/other, Polish one/few/many. Write every one a whole number
selects, even where two read the same; the gate treats a gap as a mistake.

A plural that only tells zero apart, `{failed, plural, =0 {} other {…}}`, is a
condition on the number rather than a word that inflects, and needs no
categories of the language.

## What the checks catch, and what they don't

| Check                                        | Fails on                                                                                       |
| -------------------------------------------- | ---------------------------------------------------------------------------------------------- |
| `check:i18n`                                 | a key missing or extra against `cs.json`; a message that does not parse                        |
|                                              | arguments or select options that differ from Czech; a plural form the language needs           |
|                                              | a locale code without plural rules (`cz`); a language with no `command.switchLanguageTo` title |
| `check:i18n:backend`                         | a key or placeholder missing; frontend and backend covering different languages                |
| `src/lib/i18n/bundles.test.ts` (`test:unit`) | a word list that holds a sentence; palette synonyms for a command that does not exist          |

None of them can tell whether a translation is right, reads naturally, fits
the space it is given (German runs longer: try a narrow window) or sounds
right in a screen reader, which reads every label and announcement aloud. A
native speaker reviews the translation, with a screen reader if possible.

The e2e and accessibility suites run the interface in Czech and in English
only, and a change to a Czech or English message marks nothing in the other
languages as out of date.
