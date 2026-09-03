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

### 7. The About page has no logos, and links avoid the Play Store — BUILD

Sibling products are listed as text with links to `bfgsolutions.net`. Two of them
are on Play and should link there, because that is where an Android reader can
act:

| Product | Play package |
| --- | --- |
| Sculpt Studio: Strength Coach | `com.bfg.sculptstudio` |
| BudgetBug | `live.budgetbug.app` |

Confirmed against the live developer listing 2026-09-03. VoiceBridge, Swarm and
Shotcraft are not on Play and keep their web links.

## After submit

### A face package is named after the face, everywhere

In the phone's Wear OS companion app a pushed face appears under its own name
("Glow 100") rather than under "BFG Watch Faces". Reported 2026-09-03.

**Investigate before promising a fix, because the obvious fix is wrong.** Watch
Face Push installs one PACKAGE PER FACE, and that package has exactly one
`android:label`, currently `@string/watch_face_name`. That label is what the
watch face CAROUSEL shows — and a face carrying its own name there is a founding
decision of this project, not an accident. Relabelling every package "BFG Watch
Faces" would make the carousel useless to fix an app list.

So the question to answer first is what surface the operator actually saw, and
whether it reads a different field. If there is no second field, this is
inherent to Watch Face Push and the honest outcome is to record that.

The wear app's own label is already correct.

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
