# Launch checklist

Two lists. **Before submit** is what has to be true to press "send for review".
**After submit** is everything else — real work, deliberately not blocking a
release that is otherwise ready.

The rule for which list something lands on: does shipping without it mislead
somebody, break a Play policy, or make the app look broken on first run? If not,
it waits.

Status as of 2026-09-03. Phone 1.67 (68) and watch 1.32 (1025) are on internal.

## Before submit

### 1. ~~A clean install performed by a human~~ — DONE

The operator has installed and uninstalled both apps manually many times,
including the fresh-install path that adb hid. This was the gate that mattered
most, because a fresh install holds no notification permission and that is how a
build shipped that could not ask for activation at all.

### 2. Data Safety declaration — OPERATOR

The saved declaration predates sign-in and the catalog, so it understates what
the app does, and Play refuses a declaration that does not match observed
behaviour. Answers are drafted from the code in `play-listing.md`, including the
Google ID-token judgement call flagged there.

Not something this repo can submit. It is a console form and a legal statement.

### 3. Privacy policy URL — OPERATOR

Must resolve on `bfgsolutions.net` before review. Play checks it.

### 4. Content rating questionnaire — OPERATOR

Console form. The app has user-generated content (the community catalog), which
changes the answers.

### 5. Store listing screenshots — OPERATOR TO REVIEW

Five are generated into `build/store/`. They contain the operator's own face
names and a personal photo, so they need a look before they are public.

### 6. The watch app is a single line of text — BUILD

Reported from the wrist 2026-09-03 with a photo. It shows the app name, a status
sentence and a hint, and nothing else. There is nowhere to see or change
permissions once granted, no way to reach the phone app, and no sign of what is
actually installed.

This is on the BEFORE list because it is a shipped surface that looks unfinished,
and a reviewer opens it.

### 7. ~~The About page has no logos, and links avoid the Play Store~~ — DONE 2026-09-03

Sibling products are listed as text with links to `bfgsolutions.net`. Two of them
are on Play and should link there, because that is where an Android reader can
act:

| Product | Play package |
| --- | --- |
| Sculpt Studio: Strength Coach | `com.bfg.sculptstudio` |
| BudgetBug | `live.budgetbug.app` |

Confirmed against the live developer listing 2026-09-03. VoiceBridge, Swarm and
Shotcraft are not on Play and keep their web links.

Shipped: both Android apps carry their own launcher icon, taken from their own
repositories, and their rows open `market://details?id=...`. The three web and
CLI products get a lettered tile in their own brand colour — they have no
launcher icon anywhere, and inventing one or stretching a marketing image would
be worse than a deliberate tile. "On Google Play" appears only on the two rows
where it is true.

Not done: the real marks for VoiceBridge, Swarm and Shotcraft. They exist only
as SVG on bfgsolutions.net, and nothing in this environment can rasterise SVG —
no rsvg, no Inkscape, no ImageMagick, no cairosvg, and the browser route returns
base64 that the tool blocks. Drop PNGs into `mobile/src/main/res/drawable-nodpi/`
as `logo_<name>.png` and set `logo =` on the product to swap a tile for a mark.

## After submit

### ~~A face package is named after the face, everywhere~~ — NOT A BUG, 2026-09-03

Reported with a screenshot of the Wear OS companion app's "On your watch". The
face there read "Default" rather than "BFG Watch Faces".

**Working as intended, and the label must not change.** Four pieces of evidence,
gathered from the device rather than reasoned about:

1. **The face is genuinely named "Default".** Pulled the APK off the watch:
   package `com.bfg.watchfaces.watchfacepush.default`,
   `application-label:'Default'`, WFF header "Default - Watch Face Format
   definition ... v13". Nothing in this codebase produces that name and
   `git log --all -S` finds no build that ever did, so it was typed when naming
   a face.

2. **That screen lists FACES, not apps.** Google ships its own faces from a
   single package, `com.google.android.wearable.watchface.rwf`, which declares
   84 watch-face components — and they appear individually in the same list.
   A per-app list could not do that. So every face there is shown under its own
   name, ours included.

3. **Watch Face Push has no attribution field.** `WatchFaceDetails` carries
   `slotId`, `packageName`, `versionCode` and a `getMetaData(String)` accessor,
   and nothing else. Nothing in `watchfacepush-1.0.0.aar` names a publisher,
   provider or developer. `meta-data` IS a permitted manifest tag and IS
   readable back through `getMetaData` — but that is the pushing app reading its
   own faces, not something system UI displays.

4. **The wear app's own label is already "BFG Watch Faces"** and appears
   correctly wherever apps are listed.

Relabelling the face packages would put "BFG Watch Faces" on every entry of the
watch face carousel, destroying the thing this project is built around — a face
carries the name its designer typed — in order to change a screen that is
behaving the same way it does for Google's own faces.

### `WEATHER.IS_AVAILABLE` fallback for drawn weather

`backlog.md` #3. Shape known: `<Condition>` accepts `Group`/`Condition`/clocks
but not `PartText`, so each branch needs a `<Group>` wrapper, which makes it a
change to every drawn weather slot and wants its own `generatorVersion` branch.

### The glare steps rather than sweeps

Runtime redraw cadence. The only fix is a sweeping second hand, rejected on
battery. Recorded as understood, not as a defect to chase.

### A full slot is unrecoverable

`backlog.md` #5. When the watch's one slot holds a face this install cannot
attribute to itself, `addWatchFace` fails forever and `removeWatchFace` needs a
slotId the app does not own. The error already names the only remedy. A real fix
needs something from the platform.

### `HEART_RATE_Z`

A schema source nothing here offers. `backlog.md` #4.

### `reskin.sh` has never been exercised

Written and read, not run since the workbench landed.

### The transport cannot be tested without hardware

`backlog.md` #10, and the single biggest gap in this project's ability to test
itself. The emulators cannot pair, so every Data Layer bug is first found on a
wrist.
