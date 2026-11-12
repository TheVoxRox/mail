# Decisions

Standing decisions that shape the code, the release process or the product,
and the reason behind each. This is a **living** document: when a decision
changes, edit its entry in place, keep the original date and add the date of
the change. A decision taken in an issue or a PR is recorded here in the same
PR.

Most entries end with **Revisit when** — the condition that reopens the
question. Until it is met, the question is settled and does not need to be
argued again.

The entries moved here from the retired root `todo.md` on 2026-11-02; its last
state is in git history.

## Standing conventions

- The frontend and the backend are separate artifacts with their own
  versions.
- The frontend version lives in `frontend/package.json`,
  `frontend/src-tauri/tauri.conf.json` and `frontend/src/lib/version.ts`;
  `npm run check:versions` keeps them equal.
- The backend version lives in `backend/pom.xml`; at runtime
  `ApplicationVersion` reads it from Maven's `build-info.properties`.
- Frontend/backend compatibility is not equality of release versions but the
  handshake over `apiVersion` and `minClientVersion`.
- The Tauri desktop bundle carries the frontend and the backend sidecar; after
  a backend change the sidecar is repackaged and copied into the frontend
  (`npm run sidecar:rebuild:windows`).
- Commit messages are Conventional Commits in English; the types and scopes
  are listed in [CONTRIBUTING.md](../CONTRIBUTING.md) "Commit Conventions".
- The changelog is the root `CHANGELOG.md`, for every module.
  `backend/CHANGELOG.md` is a snapshot of the backend's notes up to
  2026-09-17 and is not written to.

## Product and data

### Microsoft accounts sign in through OAuth only

IMAP with a password is not an alternative: Microsoft turned off basic
authentication for personal accounts (outlook.com, hotmail, live, msn) on
2024-09-16. The code matches — the presets in
[providerPresets.ts](../frontend/src/lib/accounts/providerPresets.ts) use
`flow: 'oauth'` and the seed sets `supports_oauth2`.

### One address book for the whole application, not one per account

**Decided 2026-08-12**, before the first release, because later it would have
been a V2 migration merging duplicates. `contacts` and `contact_labels` have no
`account_id`, and the endpoints are `/api/v1/contacts` and
`/api/v1/contact-labels`. The account stays only in `/contacts/autocomplete`,
where the per-account correspondence history (`correspondent`) is mixed into
the address book.

### `accounts.signature` stays one plain `TEXT` column

**Decided 2026-08-12**, before the first release, because after it a change
means a migration over user data. The original worry — that HTML compose would
need a `signature_html` column or a `signature_format` discriminator — fell
with the shape of the send path: the editor is a plain `textarea` and stays one
(for the screen reader), Markdown is interpreted only on the way out, and the
HTML alternative is derived from it.

A signature is not a separate entity either: the frontend inserts it straight
into the body as `\n\n-- \n{text}`
([signature.ts](../frontend/src/lib/compose/signature.ts)), so it travels the
same path as the rest of the message and its format is already defined: plain
text that may contain Markdown. A second column would introduce state nobody
fills, and a discriminator would tell apart values that behave the same on the
wire. Reinterpreting existing signatures is not a risk either, because a
signature is interpreted again on every send, not once on save.
`MarkdownBodyRendererTest` holds the invariant: a signature block alone does
not turn on the HTML alternative.

### The mail database is not encrypted at rest

**Decided for V0.1.0.** Column encryption collides with FTS5 and with
`ORDER BY` on subject, sender and received time; full-database SQLCipher is
deferred so the audited SQLite core stays stable. The residual risk is offline
access to a powered-off disk (a lost or stolen device, a copied data folder);
credentials stay sealed with DPAPI. The mitigation is BitLocker plus the
profile's ACLs, documented in [PRIVACY.md](../PRIVACY.md),
[END_USER_README.md](../frontend/END_USER_README.md) and as AR-1 in
[SECURITY_THREAT_MODEL.md](../SECURITY_THREAT_MODEL.md). The upgrade path is
SQLCipher through the drop-in `Willena/sqlite-jdbc-crypt` (Apache 2.0), with
the database key sealed by the existing `SecretStore`/DPAPI.

**Revisit when:** the appetite for protecting data at rest changes.

### The desktop shortcut is always created

**Decided 2026-09-07** while revising the release checklist (#418). The
checklist had claimed an optional checkbox that never existed:
[nsis-installer.nsi](../frontend/src-tauri/windows/nsis-installer.nsi) calls
`CreateOrUpdateDesktopShortcut` unconditionally, and the template has neither a
components page nor `MUI_FINISHPAGE_SHOWREADME`, through which the default
Tauri template offers the choice. Rather than add the checkbox, the behaviour
is confirmed: the shortcut is always created, only `/NS` (NoShortcutMode) or
`/UPDATE` on the command line suppress it, and uninstall removes it.
[RELEASE_CHECKLIST.md](../backend/RELEASE_CHECKLIST.md) §3a therefore checks
that the shortcut exists and points at `${MAINBINARYNAME}.exe`, not that it can
be unticked.

**Revisit when:** testers ask for the option.

### The update check runs only at startup

**Decided 2026-08-28.** `checkForUpdateAndPrompt` is called only from the boot
sequence in [bootstrap.ts](../frontend/src/lib/bootstrap.ts), once per
process, after a boot that succeeded and after one that failed; a running app
does not ask again, and the other route is the manual check in Settings →
About. The argument for a periodic check was that with close-to-tray an
installation could run for weeks without asking — but `mail.closeAction`
defaults to `quit` ([uiLayout.ts](../frontend/src/lib/stores/uiLayout.ts)),
so the tray is opt-in and by default every launch is a check. What is left, a
long-running session, did not justify a new timer with everything it needs
(cleanup, guards against racing an open prompt and a running install).

The cost of the decision was a documentation fix, not code: both privacy
policies had said "periodically" and listed the update check among what keeps
running in the tray, where it does not run at all. Fixed in both languages and
on voxrox.org.

**Revisit when:** the default `closeAction` changes, or users turn out to leave
the app running for days.

### New mail is announced outside the window with a Windows notification

**Decided 2026-11-09** (#886). Until then new mail reached the user as the
in-app toast and its polite live region only, and both are inside the window:
with the window in the tray, or another application in front, nobody saw or
heard it — a screen reader reads the live regions of the foreground window
only. The tray module, the Settings hint and the privacy policy all said the
tray kept announcing new messages.

- **Mechanism:** a Windows toast through `tauri-plugin-notification`, driven
  from Rust only ([new_mail.rs](../frontend/src-tauri/src/new_mail.rs)). The
  page gets no permission for the plugin's own commands, so it cannot post a
  notification of its own; the one route is `notify_new_mail`. Version 2.6 was
  the first to report a click on a desktop notification, which brings the
  window back. Driving `tauri-winrt-notification` directly was the
  alternative; the plugin is that crate behind `notify-rust`, with the dev
  build's app id handled and maintained upstream.
- **When:** only while the main window is hidden, minimized or without the
  focus — Rust asks the window, since only it can. A window with the focus has
  just read the in-app toast out, and the same sentence twice is noise.
- **What counts:** only mail that has arrived, `arrivedMessagesCount` on
  `sync_completed`: downloaded above the folder's existing mirror into a folder
  whose stored role delivers new mail (`FolderRole.deliversNewMail`: Inbox,
  Newsletters). Never a first sync's window or the rebuild after a UIDVALIDITY
  change, a mirror of Sent, Drafts, Junk or Trash, or holes refilled inside the
  mirror. The in-app toast counted every download until the follow-up the
  same day, and now counts the same: inside the window it said "1 nová
  zpráva" in Sent for the message the user had just sent, and read a first
  sync out folder by folder.
- **What it says:** the count, the account's name and the folder; no sender,
  no subject. The account's address is added only where another account has
  the same name — the default name is the capitalised domain, so two Gmail
  accounts both start as "Gmail" — or where the name merely repeats the
  address. The toast says the same sentence. Windows keeps a notification in
  its notification centre and may show it on the lock screen, which
  [PRIVACY.md](../PRIVACY.md) now says.
- **Default:** on, switched off in Settings → Appearance next to the close
  action (`mail.newMailNotifications`); Windows' own per-app notification
  setting applies on top. The sound is Windows' default for the app — without
  a named sound the plugin marks the toast silent.

What a screen reader does with it is not yet heard. NVDA reports Windows
notifications while "Report notifications" is on, and it is on in the
maintainer's configuration. On 2026-11-09 seven test toasts, built the way the
plugin builds them, reached the notification centre of the maintainer's
machine, but no banner window appeared for any of them, so neither the
announcement nor the click could be observed there; the installed build is
where to listen. A click is reported only while the banner is on screen; one in
the notification centre would need a COM activator the installer registers.

**Revisit when:** users want the sender or subject in the notification (an
opt-in of its own, given the lock screen), the click from the notification
centre turns out to be needed, or notifications for Newsletters prove
unwanted.

### Remote images: trust is per sender, revocable, and not applied in junk

**Decided 2026-11-10**, after comparing the blocked-images banner with Gmail,
Outlook, Thunderbird and Apple Mail. Remote images stay blocked by default, a
message's images can be loaded once, and a sender can be trusted for good —
that much has shipped since the F2 fix of
[CONTENT_RENDERING_AUDIT.md](CONTENT_RENDERING_AUDIT.md). What changed is what
surrounds it.

- **Trust can be taken back.** The account's settings list its trusted senders
  with a button to remove each. "Always load images from …" was a one-way door
  until then: the backend could list and remove senders from the start, and
  nothing in the app called either. Every client compared has such a list.
- **The button names the address it trusts.** The key is the bare address from
  the `From` header, which a display name can differ from, so the button and
  the announcement both say it. Where there is no address or no account to
  store it under, the button is left out rather than quietly loading once.
- **Not in junk.** The key is `From`, which anyone can write, and a message
  forged in a trusted sender's name is what a server files under junk. Loading
  its images on open would confirm the address to the forger. In a folder the
  sync recorded as `JUNK` the content endpoint answers
  `remoteImagesAllowedForSender: false` and `remoteImageTrustOffered: false`,
  so the list is neither applied nor offered for extension; loading that one
  message's images stays the user's call. The role is read from the local
  record only (`ImapFolderService.isRecordedWithRole`): the question is asked
  on every message open and must not cost a server round trip. A folder with
  no recorded role is treated as not junk.
- **Reachable without the mouse and without a live region.** The banner stays
  silent on its own (#364: as a live region it read itself out on every arrow
  key in the split pane). Ctrl+Shift+W, Outlook's key for the InfoBar, moves
  focus to the banner's first button, or says the message has nothing blocked.
  It goes there rather than loading, because the banner offers two different
  things. The command palette has the entry that loads outright, listed only
  while there is something to load.
- **A deliberate open says so, in one sentence.** Opening a message with Enter
  or a click puts the reading cursor in the body, past the banner, so the app
  says "Obrázky z internetu jsou blokované." through the polite announcer at
  the moment the body takes focus. This is the mention the banner lost with its
  live region, back on the one path it was wanted on: the body now tells a
  deliberate open from a row change that follows focus (`mail/bodyFocus.ts`),
  which the live region could not, so paging through the split pane stays
  quiet. The maintainer asked for it on 2026-11-10, knowing it is one more
  sentence on every newsletter. Not yet heard in a screen reader: whether the
  sentence survives the speech the focus move itself causes is for the
  installed build to show.

Considered and not built:

- **Trusting a whole domain.** Outlook and Thunderbird offer it, and it would
  suit newsletters that rotate addresses. It needs a second kind of entry in
  the list, turns the banner's second button into a menu, and on a shared
  domain (`gmail.com`) trusts everyone.
- **A global "always load".** Gmail and the new Outlook can afford one because
  they fetch through their own proxy, which hides the reader's address. This
  application has no server to put in between, so the setting would hand every
  sender the reader's IP address and the time of each open.

**Revisit when:** users ask for domain trust; the sentence on open proves to be
noise, or is not heard at all; or a mail server is found that files legitimate
mail from trusted senders under junk often enough for the missing auto-load to
be the complaint.

### The pane toggle stands at the foot of the rail, outside its landmark

**Decided 2026-11-07** (#861), after testers took the toggle at the head of the
rail for part of the workspace switcher a second time: #772 had already given
it its own icon and a rule under it. The rail in
[AppRail.svelte](../frontend/src/lib/components/AppRail.svelte) is a column
holding the `<nav>` "Přepínač prostředí" with the three workspace links and,
below it at the foot, the toggle. Outside the landmark, because it switches
no workspace and a screen reader entering the landmark should meet only what
the name promises; at the foot, because it is then the last thing before the
pane it controls, in the Tab order and in browse mode, as after any disclosure
button.

Two other places were weighed. The pane's own header, where classic Outlook
puts its folder pane's fold: a folded pane is `hidden`, so showing it again
would need a second control elsewhere, two controls for one state with focus
passed between them. The head of the content: every workspace has a different
one, and a control of the window's layout would sit in `<main>`. A palette
command alone is not enough either, since a narrow window needs a visible
trigger for the pointer (WCAG 1.4.10).

Outside a landmark is not a WCAG failure; axe's `region` rule is a best
practice, and the suite runs the WCAG tags only
([a11y-target.ts](../frontend/src/routes/a11y-target.ts)).

**Revisit when:** the app gets a command bar across the top of the window,
which would be a place for the toggle that stays put whether the pane is
shown or not, or the foot of the rail is wanted for something else.

## Release process

### The release runbook holds steps; the reasons for them live here

**Decided 2026-11-11** (maintainer, #901).
[RELEASE_PROCESS.md](RELEASE_PROCESS.md) says what to do, in order, with the
commands. It does not say why. Why a rule exists is an entry in this section,
how the channels behave is OPERATIONS "Release channels", and what a section
checks is [RELEASE_CHECKLIST.md](../backend/RELEASE_CHECKLIST.md).

Why: the maintainer found the process hard to follow. The runbook had grown to
412 lines, and the history and reasoning of each rule sat beside its step,
mostly a second copy of an entry here. Its steps and the checklist's sections
were so interleaved that it opened with a paragraph on how to read "§"
against "step", and it gave verifying the candidate a step of its own,
because, in its own words, the two documents interleaved there and neither
said so.

Four reasons the runbook held and no entry did, kept here:

- **Tag first, then build from the tag.** A GitHub draft creates its tag only
  when it is published, from its `target_commitish`, and the draft of
  2026-06-17 held `main` there. Publishing it would have tagged whatever `main`
  was at that moment, not the commit its installer was built from, and a
  published tag cannot move. Windows Signed Release now refuses a tag that
  does not exist on origin, names another commit, is not on `main` or has no
  green CI run.
- **§9 on the sheet is the approval record, not the release page**, whose body
  can be edited after publishing without a trace. Merging the pull request
  that writes §9 is the approval.
- **The release notes live in `docs/release-notes/`, and the draft body is a
  copy.** Until 2026-09-13 the body was the only copy. A re-tag that deleted
  the draft had to save the text outside the repository first, and nothing
  reviewed it. `npm run release:status` compares the two.
- **The changelog section is cut before §9 exists.** Its known issues are
  provisional and are reconciled with §9 before approval. The section is
  grouped by artifact for a reader who was not there, and it stays the record
  when the release page is edited.

**Revisit when:** a release goes wrong because a step was followed without its
reason at hand.

### Everything ships through beta

**Decided 2026-08-28.** Every change ships first as a beta and is promoted to
stable after testing. **A beta is not a parking place for unfinished work** —
it carries finished features that have passed the developers' testing, and it
only means a wider circle of users.

Why it belongs here and not only in the runbook: it **voids the finding "the
beta guard rejects a legitimate stable hotfix"**. In this model the need to
ship a stable patch such as `0.2.1` next to a running beta `0.3.0-beta.1` never
arises, because `0.3.0` is releasable by definition. A candidate older than the
current beta manifest is therefore not a legitimate state, and a red
`beta-channel.yml` is the right signal, not noise.

The cost: a fix reaches stable users only after a beta cycle. Accepted for the
closed beta. The escape route for a fix that cannot wait is in
[OPERATIONS.md](../backend/OPERATIONS.md) "The release model", including that
beta testers then do not get the fix until the next beta.

**The one exception was the first ship, `v0.1.0`, straight to stable**
(decided 2026-09-09). There was no second audience to protect: the closed beta
was the whole audience. And a prerelease tag would have left the stable channel
empty, since the `releases/latest` redirect skips prereleases, while `stable`
is every installation's default channel; the startup check on a channel with
no manifest fails silently, by design, so a tester who never switched channels
would never have been told of an update. The "beta" label was carried by the
status badge on voxrox.org and the release title instead. It does not repeat:
from the release after `v0.1.0` on there is a stable channel to protect.

**Revisit when:** the beta opens to a larger audience, where the delay of a
stable fix stops being acceptable.

### A beta's sheet takes no manual section; the promotion carries them from the beta

**Decided 2026-11-10** (maintainer, #892), **revised 2026-11-11** (maintainer,
#899). A beta candidate runs what a machine checks: §0–§2 of
[RELEASE_CHECKLIST.md](../backend/RELEASE_CHECKLIST.md), the signed build,
Release Candidate Smoke with its update job, §8a, §8b, §9, and Release Channel
Check after publishing. Its sheet ticks none of the sections done by hand on
the installed candidate, §3 to §8. The maintainer takes those during
development and at the latest on the beta, once it is published.

The stable candidate that promotes the beta does not take them again. Each
section **carries from the beta**: `npm run release:status` compares the
section's inputs, the same table and the same comparison as the carry rule,
between the beta's sheet and the candidate's, and only what moved since the
beta, which no tester had, is taken by hand on the installed candidate. When
the installer is the beta's code under a new version, as it is for a
promotion, nothing is. The record of a carried section is the beta's sheet and
the `Heard by the maintainer: …` notes on the pull requests it shipped. The
machine half still runs on the stable candidate as on every candidate,
including the update smoke from the previous stable release. A stable
candidate with no beta of its version, which only `v0.1.0` has been, carries
from the last sheet that ticked each section as before. The updater taken by
hand stays tied to the beta: from an installation of the previous beta on the
beta channel, once the beta is published, and only when it does not carry
(§3b since #895, the next entry). It never carries from the beta, which proves
nothing about the updater by existing.

Why: the manual half is about half a day, and four betas were cut
between 2026-11-01 and 2026-11-10. The carry rule seldom spared any of it,
because §5 rests on the whole UI. The rule was already being worked around:
the 0.2.0-beta.3 sheet took §3a, §4, §5 and §7 after publishing, on the
installation the updater had taken to it, and said so in its §9. A beta reaches
only testers who chose the beta channel in Settings → About; a defect found
there is fixed by the next beta, not repaired in a stable release. What a
roll-forward cannot undo is guarded by the machine on every candidate, beta
included: the update over a running installation and the files it leaves, the
signing key and the manifest, and the frozen V1 migration
(`FlywayBaselineChecksumTest`).

What a beta's sheet still records is what nobody has seen or heard yet, such
as a screen-reader pass that needs an installed build. A pull request's "Not
verified" note says what its author could not check, and an agent cannot
listen with a screen reader, so the note alone is not that list: the
maintainer's own pass during development counts, and is written on the pull
request as `Heard by the maintainer: …` so that a release does not ask for it
again. Only what neither covered is taken by hand: on the beta once it is
published, or at the latest on the installed stable candidate before it is
published. Revised 2026-11-10 (#894): the
0.2.0-beta.4 cut had listed six pull requests' passes as unheard from their
notes alone, and the maintainer had heard every one of them during
development.

In the same change, a section's inputs that moved only in comments and layout
no longer count as moved, read the way `check:audits` reads audit drift ("A
change only in comments is not audit drift" below).

Why the revision of 2026-11-11: as first written, the promotion took the manual
sections "once, under the carry rule", and the carry rule compares against the
last sheet that ticked each section. No beta ticks one, by this same decision,
so the comparison reached back past every beta since: on the `v0.2.0`
promotion `release:status` reported every section as re-take against the
2026-11-03 sheet of 0.2.0-beta.3, the last cut before #892. That is the half day the decision set out to save, moved to
a candidate whose installer is the beta's code under a new version number.
The maintainer had taken the passes during development and on 0.2.0-beta.4,
and the 2026-11-11 sheet ticked §3 to §8 as carried by the maintainer's
decision, with a finding saying the table could not see where the passes were
taken (#898). The next promotion would have met the same mismatch. What a
version number can change, the new pair of versions, the installer over a
running installation, the manifest and its signature, the machine checks on
every candidate.

**Revisit when:** a beta ships a defect that a manual section would have
caught and that a roll-forward cannot undo (lost or damaged mail, an update
that does not arrive), or when the beta grows beyond the closed beta of about
45 testers, for example once Google sign-in leaves its tester list (#838). For
the revision: a stable release ships a defect in a by-hand item that a pass on
the installed stable candidate would have caught and the beta's testers did
not meet, or the beta cycle grows so short that the beta's testers stop being
a check on the build the promotion ships.

### The updater is taken by hand only when it changed

**Decided 2026-11-10** (maintainer, #895). The check that an installed release
takes the next one through its updater, finds it on the channel, downloads it,
accepts its signature and hands over to the installer, is
[RELEASE_CHECKLIST.md](../backend/RELEASE_CHECKLIST.md) §3b. It carries like
the manual sections: it is re-taken only when its row moved since the last
sheet that ticked it, that is the updater's configuration in
`tauri.conf.json`, `tauri-plugin-updater` in `Cargo.lock`, or the update code
in `lib.rs`, `updates.ts` and `updateChannel.ts`. Two re-takes the row cannot
compute are stated on the sheet line: a rotation of the signing key, which
lives in the release environment, and the release after a change to the update
code, the first that the new code updates into.

Why: it was asked of every beta, and the maintainer took it as a matter of
course by updating their own installation; what it cost was a record after
each publish. The key it exists to prove changed once, in #829, and was proven
by the update from 0.2.0-beta.2 to 0.2.0-beta.3 (#836). Between changes,
every candidate is still held by the machine to what the hand check would
see break: the build verifies the installer's signature against the shipped
pubkey, Release Candidate Smoke runs the installer over a running
installation, and Release Channel Check fetches the manifest through the
channel and checks the version and the signature it names. The update from
0.2.0-beta.3 to 0.2.0-beta.4 on 2026-11-10 is §3b's first tick.

**Revisit when:** an update through an unchanged updater fails in the field,
or the signing key moves somewhere the release environment does not hold it.

### A manual section carries for 90 days at most, and its libraries are inputs

**Decided 2026-11-11** (maintainer). Two limits on the carry rule of
[RELEASE_CHECKLIST.md](../backend/RELEASE_CHECKLIST.md) (appendix, "A manual
section carries when what it rests on has not moved"):

- **No section carries from a sheet more than 90 days older than the current
  one**, whatever its inputs did. `npm run release:status` reports such a
  section as re-take and says why.
- **The libraries a section's by-hand items rest on are inputs**, read as one
  version each rather than as the whole manifest: `tauri`, `wry` and
  `webview2-com` from `Cargo.lock` for §3, §5 and §6, the plugins a section
  exercises, `@tauri-apps/api`, and from `backend/pom.xml` the Spring Boot
  parent for §4, §5, §6 and §8 with the `jsoup`, `commonmark` and
  `sqlite-jdbc` properties where they reach.

Why: the rule compares paths in the repository, and two kinds of change moved
none of them. A library update edits a manifest, and until this no row named
`backend/pom.xml` at all, nor any audit's `Code paths` ("Dependency manifests
are not part of an audit's `Code paths`" below), nor any Tauri crate but the
updater plugin; a Spring Boot or Tauri update therefore carried every section
it could break. And what the sections rest on outside the repository (the
WebView2 runtime, Windows, NVDA and JAWS, Gmail and Outlook) moves with no
commit at all. Impact analysis is normally paired with a periodic full pass
for that second reason. Reading one version per library, not the manifest,
keeps the noise that decision measured out: in the 90 days before this one the
Spring Boot parent took three values and `tauri` with `wry` six, against
manifests that changed every few days. The 90 days are those of the audit
acknowledgement cap in `docs/audit-freshness.json`.

**Revisit when:** a section re-taken only for its age finds nothing over
several stable releases, which would make the limit ceremony; or a library
outside the rows breaks a by-hand item after its section carried.

### The release after v0.1.0 is 0.2.0: the number follows the content

**Decided 2026-10-20** as `0.1.1`, because everything since v0.1.0 had been
fixes — a patch by SemVer — and a minor was to come with the first new
feature. **Revised 2026-10-26 to `0.2.0`** (maintainer): #797 brought the first
new feature (Ctrl+Shift+A for an attachment, the total attachment size against
the limit, skipping the same file twice) and was wanted in `main` right away.
The release model forbids only a stable patch next to a running beta of a newer
version, and none was running. **The beta step applies here too**
(`0.2.0-beta.N`, then promotion). Its weak spot: the beta channel reaches only
testers who switched to it in Settings → About; the default is `stable`.

### A release candidate is a tag, not a commit on `main`

**Decided 2026-09-09.** Evidence for §0/§1/§2 written against a branch commit
goes stale at the first merge — exactly what happened to the sheet for
`9c51d9d`, several commits behind on the same day. A tag is immutable, so
nothing later can invalidate a ticked item.

**A merge freeze is rejected:** it cannot be enforced and fails silently, while
a tag gives the same for free. One window cannot be closed this way, because
the process itself needs it — §0/§1/§2 run before the version bump and the
changelog cut, and the tag comes after them. That window is kept to minutes.

**A written rule "a docs-only commit does not void §1" is rejected too.** It had
been applied once by hand (a §1 note of 2026-06-26, "code unchanged since
3210e1a — only docs commits since"), so it would promote a human judgement to a
claim nothing recomputes. If a gate against a moving `main` is ever needed, it
should be **computed**: compare the git object ids of the paths §1 rests on, as
`check:audits` does for audits.

**Revisit when:** releases stop going through a tag.

### The overnight run (§8.2) is not a manual gate before publishing

**Decided 2026-09-13.** A manual overnight run before every publish is unusual,
and this one never happened: it delayed publishing by a night for what its
length buys according to [RELEASE_CHECKLIST.md](../backend/RELEASE_CHECKLIST.md)
§8.2 — the machine's daily cycle (sleep, a network change, token expiry), not
the app's timers. The replacement is a deterministic test for each of those and
a scheduled accelerated soak against GreenMail (`soak.yml`); the daily cycle on
real machines is watched by the beta through diagnostic dumps. For v0.1.0 §8.2
did not run and went into §9 as an accepted risk.

**Revisit when:** beta dumps find bugs the overnight run would have caught and
the automation did not.

### The closed beta: Google stays in Testing, without CASA

**Decided 2026-06-25** for v0.1.0: the Google project stays in Testing (at most
100 test users) without CASA verification. **No trigger for starting CASA is
set** (closes #170 finding 2, decided 2026-08-13): the beta stays at its stated
ceiling of ~45 testers, under half the cap, so it does not hit the limit, and a
number fixed in advance would guard a situation that does not occur.

The cap is **100**, not 50 — checked in
[Google's documentation](https://support.google.com/cloud/answer/15549945) on
2026-08-13, since the decision rests on that number. The quota is **consumed by
adding a tester and not returned by removing one**, so rotating people thins
the reserve even without growth — hence the ceiling at half the cap, not at 90.
The second limit of the Testing mode stays known and accepted: a tester's
consent and refresh token expire 7 days after the grant. Publishing the app and
CASA are tracked in #838.

**Revisit when:** the beta ceiling is to rise above ~70 testers. CASA takes
weeks and costs money, so it must not start only when the limit is hit.

### The Windows installer has no Authenticode signature

**Decided 2026-07-18** (closes #170 finding 5): a paid code-signing certificate
is not justified for an open-source project. The repo has no
`WINDOWS_CERTIFICATE_BASE64` secret, and `windows-signed-release.yml` packages
the installer unsigned by design. The updater is covered by an independent
Ed25519 `.sig`; a first install shows SmartScreen's "Unknown publisher" and is
verified by hand through the Sigstore attestation and the `.sha256` (procedure
in [END_USER_README.md](../frontend/END_USER_README.md)). Recorded as AR-4 in
[SECURITY_THREAT_MODEL.md](../SECURITY_THREAT_MODEL.md).

The upgrade path: SignPath Foundation (free for open source, readiness in
[CODE_SIGNING_POLICY.md](../CODE_SIGNING_POLICY.md), the application tracked in
#837), Azure Trusted Signing, or an OV certificate once a legal
entity exists. **The signing workflow picks up only a PFX certificate**
(`WINDOWS_CERTIFICATE_*`). SignPath and Trusted Signing sign through their own
action without a PFX, so the workflow needs two signing steps — the sidecar
before the bundle, the installer after it — and the updater `.sig` must be
computed from the signed installer.

### The backend ships without a JEP 483 AOT class cache

**Decided 2026-11-08** (closes #839). Release builds stopped shipping the cache
on 2026-11-01 (#819): on JDK 25 a cache trained on the build machine stops the
backend from starting on any other machine (JDK-8376576, fixed in JDK 28 with
no JDK 25 update). #839 was to bring it back once the runtime had the fix. It
does not come back, even then.

The reason the cache was built has gone. It cut the start of the executable
jar from 4.1 s to 3.0 s, and the extracted layout that #819 moved to reaches
ready in about 2.7 s without one, on the same desktop machine
([PERFORMANCE_BASELINE.md](../backend/PERFORMANCE_BASELINE.md), 2026-10-17
and 2026-11-01). What a cache would add on top of the extracted layout was not
measured. Against that unmeasured gain stand known costs: a cache file of about
130 MB in the installation and in the installer every update downloads; a
training step in packaging, with file times rounded to the installer's two
seconds and checks that the cache maps; and two failures in two releases that
no green check caught — the 0.1.0 cache never mapped on an installation
(#756), and the first `0.2.0-beta.1` candidate's broke the start everywhere
but the build machine. The fix itself is in JDK 28, which is not an LTS
release, and moving the runtime off JDK 25 for it is not worth it.

What stays: `backend/scripts/generate-aot-cache-windows.ps1`, for measuring
against the system JDK, and `AotTrainingExitListener`, because packaging's
start check uses it to end its run once the backend is ready. What bringing the
cache back would take is in [OPERATIONS.md](../backend/OPERATIONS.md) "JEP 483
AOT class cache".

**Revisit when:** the start is reported as slow and the runtime the sidecar
ships has the JDK-8376576 fix, whether from a JDK 25 update or a move to a
later JDK for reasons of its own. Then measure the extracted layout with and
without a cache on one machine before building anything.

### A local `tauri:build` may go ahead over a stale sidecar, loudly

**Decided 2026-11-09** (#879). `npm run tauri:build` checks the sidecar before
it builds, through the function `npm run tauri:dev` uses (`requireUsableSidecar`
in `frontend/scripts/lib/sidecar-staleness.mjs`), and honours the same opt-out
by the same rule: `MAIL_ALLOW_STALE_SIDECAR=1` waves through a sidecar packaged
from other backend sources, never a missing sidecar or a misnamed launcher.

An installer is not a dev run. It outlives the shell it was built in, and a
mismatched pair inside it surfaces far from the cause, as a list that never
loads. The opt-out is honoured anyway. No installer that reaches a user is
built locally: `windows-signed-release.yml` packages and syncs the sidecar
from the checkout it builds, a few steps before the build, so the digest
matches there and the variable has nothing to do, and §3a of
[RELEASE_CHECKLIST.md](../backend/RELEASE_CHECKLIST.md) refuses a locally
assembled installer. A local one is built to try the installer, the updater or
the shell, where a backend carrying an unrelated edit is the same tradeoff it
is in a dev run. And refusing would not stop such a build, only move it to
`npm run tauri -- build`, which checks nothing. The opt-out costs noise
instead: the whole report before the build, and a warning after it, where the
build's own output has scrolled the first one away.

**Revisit when:** a locally built installer is handed to anyone but the person
who built it, or the release workflow stops packaging the sidecar in the job
that builds the installer. Then the build refuses the opt-out, at least where
`CI` is set.

### The Cargo license list counts what the build compiles, procedural macros included

**Decided 2026-11-03** (#846). `frontend/src-tauri/THIRD_PARTY_LICENSES.md`
and the Cargo part of the bundled `NOTICE.txt` list the crates
`cargo tree --target x86_64-pc-windows-msvc -e normal` shows: what the Windows
build compiles. Two other boundaries were measured on the lockfile of #844 and
rejected:

- **`cargo metadata`'s resolve graph, 303 crates**, which the list used
  before. It is the dependency resolver's graph, not the feature resolver's:
  it keeps an optional crate that a feature names weakly (`dep?/feature`), and
  its features are unified across platforms. 28 of the crates it listed are
  compiled by no build. Listing them was harmless, but it made the list's own
  header untrue.
- **Only what links into `voxrox-mail.exe`, 202 crates**
  (`-e normal,no-proc-macro`). Procedural macros and what they use run inside
  rustc and are not linked, but the code they generate is compiled into the
  binary — `serde_derive`, `thiserror-impl`, `tauri-macros` and what
  `tauri-codegen` writes. Whether that code carries the macro's license is a
  legal question, and leaving the crates out would answer it by omission. It
  would also drop four MPL-2.0 crates (`cssparser`, `cssparser-macros`,
  `selectors`, `dtoa-short`) and two license groups. Listing more than ships
  costs nothing; listing less is the omission a notice exists to prevent.

Two established tools were measured against it and not adopted:

- **cargo-about** 0.9.3, the usual generator of Rust license notices, with
  `targets` and `ignore-build-dependencies` / `ignore-dev-dependencies`. Its
  graph library, krates, re-resolves features and drops the 28 crates too, but
  it listed 273: `indexmap` 1.9.3 and `hashbrown` 0.12.3 are missing although
  the build compiles them. They sit under `schemars` 0.8 in the copy of
  `tauri-utils` that `tauri-codegen` runs, and the feature that pulls them in
  comes from `tauri-build`, a build dependency. Cargo builds that host-side
  copy once for both, while cargo-about drops the build edge before it
  resolves features. Its strength is attribution: it prints each crate's own
  license file, copyright line included, which it found for 240 crates. For
  33 others, every `windows*` and `webview2-com` crate and the six Tauri
  plugins among them, it printed the canonical text with a literal
  `<year> <copyright holders>`, although those crates ship a license file
  naming the holder. Neither its online mode nor a lower `--threshold`
  changed that, so it would need a hand-kept `clarify` entry per such crate.
  It also has no CycloneDX output, so it would be a second source beside the
  SBOM.
- **cargo-auditable**, which embeds the dependency list in the binary for
  `cargo audit bin`, Trivy, Grype and Syft. It records no licenses, and on
  stable it reads `cargo metadata`, so it over-lists the same way; its own
  schema says so. Precise data needs Cargo's unstable `-Z sbom`. It would also
  change the signed release build. It answers a vulnerability-scanning
  question, not this one.

The Cargo SBOM stays wider on purpose. cargo-cyclonedx is given `--target`,
keeps the build-script crates and marks them with the CycloneDX scope
`excluded`, which `generate-bundled-notice.mjs` honours. The 28 crates it
still marks `required` are its open issue
<https://github.com/CycloneDX/cyclonedx-rust-cargo/issues/766>, and the NOTICE
filters them out against `cargo tree`, as it already narrows the Maven SBOM to
the shipped jar.

**Revisit when:** a cargo-cyclonedx release fixes #766 there (then the NOTICE
filter against `cargo tree` goes, and the SBOM's scope alone narrows it); a
legal review says crates used only at build time need no notice (then
`no-proc-macro`); Cargo's SBOM output is stable (then cargo-auditable or
Cargo's own data is the precise source); or the shell ships for a second
target (then the list is the union of each target's `cargo tree`).

### Dependency manifests are not part of an audit's `Code paths`

**Decided 2026-08-31** while analysing the jsoup 1.23.2 bump (#349). The blind
spot is real: `check:audits` compares the object ids of the files under
`Code paths`, and neither `backend/pom.xml` nor `frontend/package.json` is
among them, so a library bump can change the behaviour of an audited component
while the gate stays green. With #349 it happened literally — jsoup is the
parser under `HtmlSanitizer.java`, which is a code path, while that file itself
did not change.

**Adding the manifests does not work, and that is measured:** in the 90 days
before the decision `backend/pom.xml` changed 20 times and
`frontend/package.json` 32 times, against a ledger cap of 8 entries in 90
days. The ledger would overflow in about five weeks, and the only way out of a
full ledger is re-verifying the whole audit — the gate would produce ceremony,
not attention. **The layer below it was strengthened instead:**
`HtmlSanitizerMalformedInputTest` asserts the security invariant over a corpus
of malformed markup, so a change in the parser's behaviour fails a test with a
diff in the log rather than a gate asking for thought. A cheaper shape, should
it be built after all: an `audit → artifacts` allowlist inside
`frontend/scripts/check-audit-freshness.mjs`, not the whole manifest; for jsoup
that comes to two events a quarter.

**Revisit when:** other audits turn out to rest on a library's behaviour as
directly as the content-rendering audit rests on jsoup. The OAuth audit (on
Spring Security) and the IMAP/SMTP audit (on jakarta.mail) do not: their claims
target our configuration, which tests cover directly.

### A change only in comments is not audit drift

**Decided 2026-11-08** (#867). `check:audits` does not count a path as drifted
when every file that changed under it differs from the reviewed state only in
comments and layout, as `frontend/scripts/lib/comment-only.mjs` reads it: Java
with a lexer, TypeScript and JavaScript with the TypeScript parser and printer,
`.properties` line by line. Before this, a javadoc sentence cost a dated
acknowledgement and counted against the caps like a code change. Three of the
twenty acknowledgements standing on the day were that. Replaying `main` from
2026-08-09 to 2026-11-08, 22 of 374 changes to a current `Code paths` entry
were comment-only, all of them in Java.

The library errs one way only. Anything it cannot read exactly counts as
drift: Svelte, Rust, YAML, HTML and JSON, a file added or deleted, a Java file
with a unicode escape that javac decodes into a quote, slash or line break, a
TypeScript file that does not parse or carries a compiler pragma. The scanner
in `frontend/scripts/lib/source-text.mjs` is not used, because it errs the
other way: it reads the `//` inside a regex literal as a comment.

YAML is left out on purpose. The release workflow's header comment cost one
acknowledgement in three months (#847, one of its 11 changes), and telling a
YAML comment from a `#` line inside a `run: |` block takes a YAML parser,
which `frontend/` has only as a transitive dependency.

**Revisit when:** comment-only changes in a language compared byte for byte
keep costing acknowledgements (then add that language with a parser, not a
scanner); or an audit's verdict turns out to rest on what a comment says
rather than on the code.

### Dependency install scripts are denied

**Decided 2026-11-03** (#847). In `frontend/`, an install runs a dependency's
install script (`preinstall`, `install`, `postinstall`) only if `allowScripts`
in `frontend/package.json` allows it, and `strict-allow-scripts=true` in
`frontend/.npmrc` fails the install on a script `allowScripts` does not name.
Install scripts are how the npm worms of 2025 spread: they run by themselves at
install time, on a developer's machine and in CI, for any package in the tree,
one that nothing imports included, before anyone has read the new version.
Deny by default with an allowlist is what pnpm 10 and Bun already do, and where
npm says it is going: in npm 11.20 the field is advisory, and a later release is
to block unreviewed scripts.

Measured on npm 11.20 (2026-11-03):

- Without `strict-allow-scripts`, a script `allowScripts` does not name still
  runs and npm only warns, so the field alone stops nothing new.
- A `false` entry skips the script, although npm's own `deny-scripts`
  documentation says deny entries take effect only in a later release.
- The project's own `prepare` (`svelte-kit sync`) still runs, unlike under
  `--ignore-scripts`.
- `--ignore-scripts` skips the check as well as the scripts, so the release
  workflow's publish job, which installs that way, is not affected. A
  lockfile-only install without it is checked.

Both packages with an install script at the time are denied: `es5-ext`, whose
`postinstall` prints a notice, and `esbuild`, whose `postinstall` checks the
binary that its platform package already provides. With it denied, esbuild's
API, its CLI and `npm run build` work. Denies are name-only (`npm deny-scripts`
writes them that way), so a version bump needs no new entry.

**When an install fails with `ESTRICTALLOWSCRIPTS`:** a dependency, usually
one a bump brought in, has an install script, and on a dependabot PR the
maintainer resolves it with a commit on that PR. Read the script, then run
`npm deny-scripts <pkg>` if the package works without it (most do: the script
builds or fetches what a prebuilt package already carries) or
`npm approve-scripts <pkg>` if it does not. An approval is pinned to the
version, so the next bump of that package asks again.
`--dangerously-allow-all-scripts` is not a way through.

What it does not cover: a compromised package's code still runs wherever the
build or the tests import it (vite and its plugins, vitest), and Maven plugins
and crate build scripts run as before. It closes the path that needs nothing to
import the package. The next entry narrows the other path for the updates
dependabot proposes.

**Revisit when:** npm blocks unnamed scripts by default (then check whether the
default fails the install or skips the script, and whether
`strict-allow-scripts` still adds anything); or a dependency turns out to need
its install script (then it gets a pinned approval, and its reason goes here).

### Dependabot proposes a release only once it is a week old

**Decided 2026-11-12** (maintainer, #903). Every ecosystem in
[dependabot.yml](../.github/dependabot.yml) carries `cooldown` with
`default-days: 7`: a version update is proposed only once the release is seven
days old. Security updates are not held back; the cooldown applies to version
updates only.

Why: the entry above leaves open the code that a build or a test imports, and
a compromised release reaches that path on the first `npm ci` of its dependabot
branch: in CI, and on the maintainer's machine when the branch is checked out
for its record commit. The compromised npm releases of 2025 were found and
pulled within hours to days, so a release that has stood for a week has been
looked at by more people than this repository. The same holds for crates,
Maven artifacts and actions, which run in the build as well.

The cost is a week on every ordinary update: on a weekly schedule a release
was proposed within six days, now within thirteen. An update that cannot wait
is bumped by hand, which the cooldown does not hold back, so that is the moment
to look at how old the release is.

**Revisit when:** an update the project needs is held back by it twice (then
exclude that package or shorten the window), or the package managers refuse a
release younger than a set age on install, which would cover the hand bumps
too.

### No `distributionSha256Sum` in the Maven wrapper

**Decided 2026-08-17** while reviewing #279; **reasoning rewritten
2026-08-19** — the conclusion stands, but two of the three original premises
were false. The one load-bearing argument: the pin would guard a single
artifact (`apache-maven-*-bin.zip`, which CodeQL's autobuild downloads), and
that comes over HTTPS from `repo.maven.apache.org` — **the same origin as every
other dependency of the pom**, none of which is pinned either. It would protect
a sliver of the surface.

What does not hold from the original reasoning: (1) "it is not verified that
dependabot updates the hash on a bump" — it does: dependabot-core's
`wrapper_updater.rb` removes the checksums before running the wrapper and
recomputes them afterwards, in files that already had them. (2) "the value
would have to be computed from our own download" — true (Maven Central
publishes no `.sha256`, re-checked on 2026-08-19), but that is exactly what
dependabot does for you, so the argument against became one for feasibility.
What remains is a one-off cost for the first value. The premise was false
already when written: Maven Wrapper support landed in dependabot-core on
2026-08-11 (dependabot/dependabot-core#14812), six days before the decision.

**Revisit when:** pinning starts systematically, for the whole pom rather than
this one file.

## Repository

### `main` takes squash merges only, and no push, the maintainer's included

**Decided 2026-11-12** (maintainer, #902). The repository allows only squash
merges, and the `main` ruleset lists only `squash` among its merge methods. The
ruleset's one bypass actor, the admin role, bypasses in `pull_request` mode:
the maintainer can still merge a pull request without waiting for its checks,
but cannot push to `main` directly. The release tag ruleset keeps its bypass,
since tags are pushed, not merged.

Why: the process already rested on both and the settings enforced neither.
Squash is what the audit ledger's object ids, the changelog's "the squash merge
keeps" and the release runbook assume, while merge commits and rebase merges
stayed one click away. [CODE_SIGNING_POLICY.md](../CODE_SIGNING_POLICY.md) says
changes reach `main` through pull requests, which held only by habit: the
bypass was `always`, and the last direct push was on 2026-08-17. A signing
policy that holds by habit is a weak thing to put in front of SignPath.

**Revisit when:** a second maintainer joins (then a required review is the
question), or a workflow has to commit to `main` itself.
