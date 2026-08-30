# DECISIONS.md — BFG Watch Faces

## 2026-08-30 — The transport works, and the three bugs that hid inside it

A face designed on a Pixel 11 Pro XL reached a Pixel Watch 5 and installed:

```text
phone:  BUILT wrist.apk  520822 bytes  1837ms
        TARGET Ready(Pixel Watch 5)
        wrote 520822 bytes to the channel
watch:  channel opened: /bfg-watchfaces/face/YkxoNUNH...
        received 520822 bytes
        slots: 0 free, 1 used   -> installed
```

Three separate bugs, each of which required a real watch to find.

### 1. v1 signing was off, and Watch Face Push needs it

`ApkSigning` had `setV1SigningEnabled(false)`, reasoned as "the face declares
minSdkVersion 33, so a JAR signature is dead weight". That is true of the ANDROID
INSTALLER and false of Watch Face Push, which reads `META-INF/MANIFEST.MF`
itself.

Rejected with `ERROR_MALFORMED_WATCHFACE_APK` — "The provided watch face is not
a valid Android APK" — while `adb install` of the identical file succeeded and
the Push validator issued a token for it. The Wear OS 6 emulator accepted it;
Wear OS 7 does not.

Isolated by pushing the SAME face built two ways: `watchface.apk` (aapt2)
accepted, `packface.apk` (pack) rejected, then the pack one re-signed with
`--v1-signing-enabled true` accepted. Content held constant, one variable moved.

### 2. `sendFile(Uri.fromFile(...))` sends nothing, successfully

Google Play services opens that URI from ITS process and uid. Under scoped
storage neither `cacheDir` nor `getExternalFilesDir` is readable there — and the
send Task resolves anyway. The watch received 0 bytes and `addWatchFace`
rejected the empty file as malformed, which is an error about packaging that was
really about transport.

The bytes now go to `ChannelClient.getOutputStream`, which needs no cross-process
file access at all.

### 3. `receiveFile` completes when the transfer is SET UP, not finished

The watch awaited it, read a 0-byte file, failed to install, and closed the
channel — aborting the phone mid-write with `ChannelIOException: Channel closed
unexpectedly before stream was finished`. **Each end was reporting the other
end's fault**, which is why the phone's "sent" and the watch's "malformed" were
both true and neither was the cause.

The receiver now reads `getInputStream` to EOF, which is the only unambiguous
signal that a face is complete, and does the work inline: launching into a
service-scoped coroutine got it cancelled on teardown with "Job was cancelled".

### 4. And then it installed and did not switch

`setWatchFaceAsActive` was only ever called from `ActivationRequestActivity`, at
the instant permission was granted. So the FIRST face switched and every one
after it installed silently into the picker — which from outside is "I sent a
face and nothing happened", indistinguishable from the transport failing, and
exactly what it looked like once the transport finally worked.

`FaceInstaller` now switches whenever `ActivationConsent.canActivate` is true.

Confirmed on the watch: `DeclarativeWatchFaceRuntime` rendering
`com.bfg.watchfaces.watchfacepush.on`.

### What this cost, and why

Four wrong diagnoses before the first log was read: `minSdk`, `standalone`, a
native arm64 crash, 16 KB page alignment. Each was a plausible mechanism reasoned
from what I knew. `adb logcat` on the failing device named every real cause in
one command.

The emulator pair cannot pair, so this entire path had never run. "Verified on
the emulator" was doing far less work than it sounded like all week.

## 2026-08-30 — The crash on Send, and why every emulator run missed it

Tapping "Send to watch" on a real phone killed the app. Not the native crash I
expected — I had assumed the arm64 `libpack_java.so`, on the reasoning that only
the x86_64 one had ever executed. It was a Compose layout crash:

```text
java.lang.IllegalStateException: Vertically scrollable component was measured
with an infinity maximum height constraints, which is disallowed.
```

`MainActivity` wrapped the handoff branch in a `Column(Modifier.verticalScroll())`,
and `ActivationHandoffScreen` already scrolls itself — it was written as a
standalone screen before the Scaffold existed. A scrolling container inside a
scrolling container gives the inner one infinite height, which Compose refuses
with a hard crash rather than a layout warning.

Introduced on 2026-08-29 in the four-screen rebuild, and it fires only when that
screen is actually opened.

### Why it survived

Every attempt to reach the handoff screen on the emulator missed. The taps hit a
segmented control, a system pairing dialog stole focus, the page scrolled between
the screenshot and the tap. Each time I moved on, because the thing I was checking
at that moment was something else. **The screen was never once rendered on an
emulator**, and the only thing that ever reached it was a person with a real phone.

The status line and the persistent denied note moved into a `footer` slot on
`ActivationHandoffScreen`. Outside it they either sat in the second scrolling
container that caused this, or fell off the bottom where the one line saying what
happened would never be read.

### The diagnosis was wrong twice before it was right

`minSdk`, then `standalone`, then `native arm64` — three plausible mechanisms
reasoned out from what I knew, none of them checked. The 16 KB page alignment
check was the same instinct and came back clean at `0x4000`. What actually solved
it was `adb logcat -b crash` on the device that was failing.

The pattern across today: every wrong answer came from reasoning about a system
instead of reading what it said. Every right answer came from running the real
thing and looking at the output.

## 2026-08-30 — Glyphs are per slot, and the drawn date sits against the clock

Two features asked for together, and the second one moved the first.

**Per-slot glyphs.** `showComplicationIcons` was one switch for the whole face.
The reason to hide a glyph is almost always about ONE complication — a date
reads as a date without a calendar above it, while a bare number wants the
footprint that says it is a step count — so a single switch forces that
judgement on all five. It is now `iconSlots: Set<SlotPosition>`, and the toggle
lives inside each slot's own dialog, where someone is already deciding about
that slot. The row's glyph dims rather than vanishing when it is off: the row
still has to say which complication it is.

`showComplicationIcons` is still READ by the codec, because faces were saved
with it — false meant no glyph anywhere, true meant all of them. Only the new
key is written.

**The drawn date does not go at `dateY`.** It did at first, and it printed
straight through the top complication on a phone. Despite the name, `dateY` has
always been the TOP SLOT's anchor — `layoutAt` reads it for exactly that — so
putting the date there put two things in one place. Clamping the slot above the
date does not work either: at complication size 28 there is no room, and the
existing floor pushes it back down into the date.

The date now takes the band directly above the clock, which hands the whole
upper dial back to the top slot and fits at every size. `SlotGeometry.dateBand`
owns that, because the emitter and BOTH previews need it and the first version
computed `dateY - dateSize / 2` in all three — the duplicate-geometry mistake
`SlotGeometry` was created to end.

Rejected: hiding the top complication automatically when the drawn date is on.
It is a reasonable guess and it is still a guess, made by silently discarding a
choice someone made. Turning the slot off, or turning its glyph off, are both
one tap and both visible.

Also rejected: leaving the previews alone. `dateStyle` reached the emitter
first, so the control changed the built face and nothing on screen — the exact
complaint ("nothing really changes") that the complication-size fix answered a
day earlier.

## 2026-08-30 — A schema test that only tests defaults is not a schema test

`showSeconds` and `dateStyle` shipped to a real phone emitting an XML comment
containing `--`, which XML forbids. The face built, signed and validated locally
in every test we had, and the validator on the device rejected it with a
`SAXParseException` the person saw raw.

`WffSchemaTest` is described in `CLAUDE.md` as the only signal between a refactor
and a face that silently never appears. It has 20 tests. Every one of them
emitted `DialParams()` with the new features **off**, so the elements under test
were never in the document being validated. The test was not weak; it was
looking somewhere else.

Fixed by validating the feature matrix — every `DateStyle` crossed with seconds
and complication icons on and off — plus a direct check that no emitted comment
contains `--`. Both were confirmed to FAIL with the bug reintroduced (8 failures)
and pass with it fixed, rather than merely added and observed green.

Rejected: escaping `--` in a comment helper. That fixes one occurrence and
leaves the next author to rediscover the rule. The test makes the rule
enforceable, and the comment can stay prose.

## 2026-08-30 — The query form silently upgraded every stored face

`FaceCodec.fromQuery` reads `generatorVersion`, defaulting to
`CURRENT_GENERATOR_VERSION`. `FaceCodec.toQuery` never wrote it. So any face
round-tripped through the query form came back claiming to be current, and
`PatternEngines` — which IS the renderer for the stored file format — drew it
with today's geometry.

That is exactly the silent rewrite the "never change engine geometry in place"
rule exists to prevent, arriving through the codec instead of through an engine.
It reached the workbench's saved-face list (`Workbench.kt:361,491`) and
`bake --preset` (`Bake.kt:57`).

Found by a completeness test, not by reading the code: `FaceCodec` had **no test
at all** despite being what a saved face IS. The new test derives its field list
from `DialParams`'s own `toString`, so a field added to the params and forgotten
in the codec fails without anyone remembering to update the test. That is why it
found `generatorVersion` while looking for `dateStyle`.

Rejected: asserting a hand-maintained list of field names. That is the same
failure one level up — it passes until someone updates it, which is precisely
what does not happen.

## 2026-08-30 — The app does not show people the validator's stack trace

A build or validation failure had no user-facing cause and no user action, and
the app printed the validator's own output: `CheckFailure(name=Watch Face Format,
category=WATCH_FACE_FORMAT, failureMessage=... org.xml.sax.SAXParseException ...)`.

That names our bug in our vocabulary and reads, to the person holding the phone,
like they broke something. They cannot act on any of it.

The detail now goes to logcat under `BfgStudio`, where it is useful, and the
person gets a sentence saying it is our fault and their design is safe. The
APK size in kilobytes and the internal slug came out of the success and failure
messages for the same reason: they are ours, not theirs.

## 2026-08-29 — The Wear OS track has its own tester list

The opt-in link returned "Your account hasn't yet been invited to participate in
this app's internal testing program" for an account that was already a tester
and already had the phone app installed.

**Internal testing is two tracks, not one, and each carries its own testers.**
The Play Console's track pages are scoped by a form-factor selector at the top
right — "Phones, Tablets, Chrome OS, Android XR" and "Wear OS only". They are
separate rows with separate release histories and separate tester lists.

The `Internal Testers` list (2 users) was ticked on the phone track and unticked
on the Wear OS one, so the Wear track had no testers at all. Its summary read
**Inactive** while the phone track read Active — which is the tell, and it is
easy to miss because both pages are titled "Internal testing" and look identical.

Ticking the list flipped the Wear track to Active.

Worth writing down because nothing about it is visible from the API. Reading the
tracks back with `play-release.py` shows `wear:internal versionCodes=['1004']
status=completed`, which is true and says nothing about whether a single human
can install it.

### The full set of gates, in order

For a Wear app to reach a tester's watch, all of these have to be true, and each
one fails silently in a different way:

1. A Wear bundle released to `wear:internal`. Visible in the API.
2. Wear OS screenshots on the store listing. Console only.
3. **Wear OS form factor opted in** under Advanced settings. Console only; until
   then Play says the app is not compatible with the watch.
4. **The tester list ticked on the WEAR track specifically.** Console only; until
   then the opt-in link says the account was never invited.

Three of the four are invisible to the tooling this project uses to verify
releases. That is the lesson, not the individual settings.

## 2026-08-29 — Why the watch app was released and still not installable

Released to `wear:internal`, opted in as a tester, and the Play Store on the
watch said **not compatible**. The cause was a Play Console setting, not
anything in the app:

**Test and release → Advanced settings → Form factors → Wear OS** has a
three-step checklist. Two were done — Wear screenshots uploaded, a bundle
released to a testing track — and the third, **"Opt-in to Wear OS and agree to
the review policy"**, had never been done. Until it is, Play does not consider
the app available for Wear OS devices at all, testers included.

`developer.android.com/training/wearables/packaging` is explicit:

> "In the Play Console for your app, click the Test and release menu... Choose
> Advanced Settings, select the Form factors tab, and click Add form factor.
> Click Wear OS..."

Opting in flips the form factor to **Active** immediately. It does NOT wait for
the review it warns about — the dialog says the app will be reviewed against the
Wear OS quality guidelines, and separately that the change can be sent for
review from Publishing overview, but the distribution setting itself takes effect
on the spot.

### Two things I got wrong on the way, both by guessing instead of reading

**`standalone` was not the cause.** I changed
`com.google.android.wearable.standalone` from `false` to `true` on the theory
that Wear OS 3 stopped delivering non-standalone apps. The documentation says
the opposite:

> "If the value of `com.google.android.wearable.standalone` is `false`, the app
> is still downloadable from the Play Store, but it requires its companion
> mobile app for it to be usable."

That is exactly this app: it installs and opens on its own, and needs the phone
app to do anything with a face. The value is left at `true` for now because it
is shipped in 1004 and churning it again proves nothing — but the honest reading
of the definition ("fully usable without a paired phone") is `false`, and it
should go back when there is another reason to touch the manifest.

**`minSdk 36` was not the cause either.** The operator's watch is a Pixel Watch 5
running Wear OS 7, comfortably above the floor.

The pattern in both: I reasoned from a plausible platform rule instead of opening
the page that states it. The operator's "look at the documentation and stop
guessing" was the correction, and it was right.

## 2026-08-29 — The permission nobody asked for

`POST_NOTIFICATIONS` was declared in `:wear`'s manifest and requested nowhere.
On Wear OS 6 that is a runtime permission, and the failure it produces is the
worst kind:

1. A face arrives and installs correctly.
2. `ActivationPrompt` checks `areNotificationsEnabled()`, finds false, and posts
   nothing — it is written to, precisely so `notify` is not a silent no-op.
3. Nobody is ever asked whether the watch may switch faces.
4. The face sits there installed and inactive, with no error anywhere.

Somebody testing this for the first time would conclude the whole pipeline was
broken, and every log line would say it had worked.

`WatchActivity` now asks on open, and shows a red line with a button if the
answer was no. This is the only screen the watch app has, and a person opening
it is the one moment when asking is not an ambush. The install path cannot ask —
it is a background context, the same wall that stops it raising the activation
dialog directly.

Found by auditing the receiving half before handing it to somebody with real
hardware, rather than by watching it fail on their wrist.

### And one that was fine, checked anyway

The channel encoding changed earlier today, so the `WearableListenerService`'s
`android:pathPrefix` was worth re-checking: it is `/bfg-watchfaces`, the constant
is `/bfg-watchfaces/face/`, and only the segment AFTER that prefix changed. It
still matches.

It is now a test. The prefix is a hardcoded manifest string and the constant is
Kotlin, and nothing else connects them — a mismatch means the device opens a
channel, the service is never invoked, the bytes go nowhere and nothing is
logged. Same class of silent failure as the capability name, which already had
a test for the same reason.

## 2026-08-29 — The channel path carried a token it could not carry

`WatchLink` put the validator's token in the Data Layer channel path raw, and
the test that covered it used `eyJhbGciOiJI.UzI1NiJ9-_==` — a URL-safe shape
somebody assumed. Running the real validator on a device produced this:

```text
EsHCFGgf0GIQjD5UfB61BgMka8ShjdykSmb1SVS+MmU=:MS4wLjA=
```

Standard base64, with a version suffix. Its alphabet includes `+`, `=` and
**`/`** — and a `/` in a path is a new path segment. The receiver strips a prefix
so it would have recovered the token anyway, but nothing promises the transport
hands the path back byte for byte once it contains separators, and the failure
would be intermittent: fine for most tokens, broken for the ones that happen to
contain a slash.

The segment is now URL-safe base64 of the token's bytes — `[A-Za-z0-9_-]` and
nothing else, whatever the validator emits. Three tests cover it, including a
token with a slash in it. Nothing has ever crossed this link, so there was no
wire compatibility to keep.

Worth naming the shape of this bug: a test existed, it passed, and it was
testing an invented value. The fix was not cleverness, it was running the real
thing once and looking at what came out.

## 2026-08-29 — How far the pipeline gets, exactly

Measured on the emulators, in one run:

```text
pack available: true
BUILT   cache/debug_face.apk   520631 bytes   3256ms
TOKEN   EsHCFGgf0GIQjD5UfB61BgMka8Shjdyk...
TARGET  NoWatch
```

Build, sign and validate all work on the device. `findTarget` executes the real
`CapabilityClient` and `NodeClient` calls without error and correctly reports
that there is no watch — which is the truth: the two emulators are not paired,
and `CLAUDE.md` already records that a Wear AVD self-provisions and never runs a
pairing wizard.

So `FaceSender.send` has still never run. Everything on both sides of it is
verified; the Bluetooth crossing needs real hardware.

One real bug fell out of trying: `Tasks.await` refuses to run on the main
thread, and a `BroadcastReceiver`'s `onReceive` IS the main thread. The app was
always fine — `MainActivity` calls this inside `withContext(Dispatchers.IO)` —
but `DebugPackReceiver` was not, and now uses `goAsync()` the way `:wear`'s
receiver already did.

## 2026-08-29 — pack on the device: the pipeline runs

The on-device build path is written and compiles: `PackBridge` (JNI onto
google/pack), `ApkSigning` (Android Keystore key, self-signed cert, apksig v2/v3)
and `FaceBuilder`, which turns `DialParams` into a signed APK carrying only the
four paths Watch Face Push permits.

**It runs.** With the operator's go-ahead the toolchain went on this box —
rustup with the minimal profile, the four Android targets, `cargo-ndk`, a
user-level `protoc`, and NDK r27c — and `scripts/build-pack-android.sh` produced
`libpack_java.so` for all four ABIs from the pinned, patched pack.

Measured on the SDK 36 phone emulator, not assumed:

```text
pack available: true
BUILT   /data/user/0/com.bfg.watchfaces/cache/debug_face.apk
package com.bfg.watchfaces.watchfacepush.debug_face
bytes   520631
took    2687ms
TOKEN   EsHCFGgf0GIQjD5UfB61BgMka8ShjdykSmb1SVS+MmU=:...
```

`aapt2 dump` on the APK the PHONE built shows exactly the permitted paths and
nothing else — no `kotlin/`, no `DebugProbesKt.bin` — signed `CN=BFG Watch
Faces`, and **`(nodpi)` read back out of the resource table**. That last line is
the patch earning its keep: Androidify's prebuilt binary would have recorded
those two drawables as mdpi.

That APK then installed on the Wear OS 6 emulator through the existing
`DebugInstallReceiver`, took the slot from the previous face, and
`DeclarativeWatchFaceRuntime` rendered it with live complications.

**What that does NOT prove.** The APK crossed from phone to watch by `adb`, not
over Bluetooth. `CapabilityClient` and `ChannelClient` are still the untested
link, and they are now the only thing between here and a face that a person can
send from their own phone.

### Why not Androidify's prebuilt .so, again

`scripts/build-pack.sh` already refused to scavenge them — "unversioned binaries
nobody here can audit" — and pointing at a device does not change that. There is
now a second, sharper reason: **theirs is built from UNPATCHED pack.** This repo
carries `scripts/pack-qualifiers.patch`, without which `res/drawable-nodpi` is
recorded as density 0 — mdpi, not "unspecified" — and the watch scales a dial
image that explicitly says do not scale me. That bug was found and fixed here on
2026-08-28. Taking their binary would silently reintroduce it on the device path
only, which is the hardest place to notice it.

A third reason emerged while writing the binding: **a JNI symbol encodes the Java
package.** Androidify's library exports
`Java_com_android_developers_androidify_watchface_creator_PackPackage_nativeCompilePackage`,
so using it forces the binding to live in their package. Building ours puts it in
`com.bfg.watchfaces.mobile.pack`, where it belongs.

### Absent is a state, not a crash

`PackBridge.isAvailable` wraps `System.loadLibrary` in `runCatching`, so a
checkout that has not run the build script gets a sentence it can show rather
than an `UnsatisfiedLinkError` at class-load time on someone's phone. The send
flow builds BEFORE it looks for a watch, deliberately: telling somebody "no watch
found" when the real problem is that this build cannot pack a face would send
them into their Bluetooth settings for an hour.

### Signing, and why a throwaway key is the right key

Nothing about a pushed watch face depends on who signed it — Watch Face Push
trusts the validation token the validator issues for the bytes. So the per-device
Keystore key is not a weaker version of a real signing key, it is the right shape
for the job. It also means two phones produce differently-signed APKs for the
same design, which is why the SLUG and not the signature decides whether a face
replaces an earlier one.

## 2026-08-29 — The phone app catches up with the workbench, and what did not

`DECISIONS.md` 2026-08-28 made the localhost app the specification for the phone
app. Measured against it on 2026-08-29 the phone app was one scrolling studio
against four screens, and the gap was not cosmetic: it could not save a face,
name one, or list one. Nothing downstream — sending to a watch, sharing to a
catalog — can be built on a screen with nowhere to keep anything.

Now at parity: bottom navigation, Designs (styles gallery), Studio, My faces,
About, the naming sheet, local save in the catalog format, the Fine tune bottom
sheet, complication size and spacing, and an Android-standard complication
picker.

### Three things worth keeping the reasoning for

**The picker was wrong in ways a screenshot cannot show.** It was a `Row` with a
coloured value and a raw `DropdownMenu` hung off it — no affordance that it
opened anything, a touch target the width of whatever the text happened to be,
no field semantics for TalkBack, and a menu anchored to the `Box` rather than the
control, so it opened over its own label on a short screen.
`ExposedDropdownMenuBox` is the standard answer and supplies all four.

**Fine tune had to become a sheet.** Inline, the dial had scrolled off the top by
the time you reached the lower sliders, so you were adjusting an engraving you
could not see. The localhost app marks that sheet `short` for exactly this
reason. Fine tuning is a feedback loop and a loop you cannot see is guessing.

**Dynamic colour went away.** Material You is the right default for a utility and
the wrong one for an app whose entire subject is choosing colours: the chrome
took its identity from the wallpaper and competed with the swatches, so a person
could not tell which purple was theirs and which was the phone's. The scheme is
now the brand's, the same plum and blush as the launcher icon.

### Still missing, deliberately

- **Community.** Needs the catalog service, which is its own task and is held
  for an operator interview. The tab exists and says honestly that nothing is
  shared yet, rather than inventing faces or pretending to load.
- **Share and Report.** Both are catalog actions. Same blocker.
- **Your image (`Engine.TEXTURE`).** `AndroidDialRenderer` has never supported
  imported bitmaps; that is a renderer gap, not a UI one.
- **The custom colour picker.** The swatches work; an arbitrary-colour sheet is
  additive rather than a gap in the flow.
- **The schema validity pill.** The workbench validates against Google's XSD on
  every change because it has Xerces and the schema on disk. The phone has
  neither, and validation belongs at the point a face is packed, not while
  someone drags a slider.

Verified on the SDK 36 phone emulator, not assumed: all four tabs render, and a
design named "Midnight Knot" saved to `files/faces/midnight_knot.json` in the
catalog format and came back on the My faces list with its slug.

## 2026-08-29 — How to put a local file into the Play Console from a headless box

The store listing is complete: icon, feature graphic, five phone screenshots,
name, short and full description. Verified by reading it back with
`scripts/play-listing.py --show`, not by looking at the page that wrote it.

The API was still refusing listing commits hours after the "Manage store
presence" grant, so this went through the browser — which has its own problem:
Chrome runs on the operator's Windows laptop and the assets are generated on this
Linux box.

**Play has no file input to fill.** `document.querySelectorAll('input[type=file]')`
returns nothing. Play creates one on demand and calls `.click()`, which opens a
native dialog — invisible to automation, and it FREEZES the session because
nothing can dismiss it.

So, in order:

1. Neutralise every route to a dialog first, before touching any button:
   override `HTMLInputElement.prototype.click` to a no-op for `type=file`, and
   make `showOpenFilePicker` throw. Do this BEFORE the click, not after.
2. Create your own `<input type="file">`, attach it to the document, and fill it
   with the file-upload tool — which can read this machine's paths.
3. Take `input.files`, build a `DataTransfer`, and dispatch
   `dragenter`/`dragover`/`drop` on Play's drop zone. Play's asset picker opens
   with the file already ingested; select it and press Add.

**Address the drop zone by its help text, not by index.** There are eight
identical "Add assets" elements. Their order CHANGES as slots fill, so an index
that meant "phone screenshots" before the icon was set means something else
after — the first attempt silently dropped five screenshots onto nothing.

**Screenshot order is drag-and-drop and it matters.** Play showed them in
roughly reverse upload order, which put the black always-on screen first — the
one that looks like a blank rectangle. A real click-drag between thumbnails
reorders them, where a synthetic drag event does not.

Not done: the listing is SAVED, not submitted. Sending it for review is a
separate, operator-level decision and there is no reason to make it before the
app can actually send a face to a watch.

## 2026-08-29 — Store listing assets upload by API too, and that needed a permission

`scripts/play-listing.py` does for the listing what `play-release.py` does for
tracks: reads it, sets the words, uploads the icon, feature graphic and
screenshots. Same reason — the assets are generated on this Linux box and the
console's file picker runs on a Windows laptop, so "drag the PNG in" means
copying every asset across the bridge for every revision. The file input is not
in the accessibility tree either, so browser automation cannot reach it: clicking
the button opens a native dialog with nothing on the other side of it.

### The permission that was missing, and how it fails

`play-publisher@budgetbug-495002` held "Release apps to testing tracks" and not
**"Manage store presence"**. The failure is worth writing down because it is
misleading in two ways:

- The image UPLOAD succeeds. Play accepts the PNG, returns an image id, and only
  the `edits:commit` fails.
- The commit fails with a bare `403 PERMISSION_DENIED / "The caller does not have
  permission"` that names neither the permission nor the listing.

An edit containing no changes at all commits fine under the same credentials,
which is how the block was localised to the listing content rather than to
committing edits. That two-line diagnostic is worth keeping in mind for the next
opaque 403.

Granted on 2026-08-29 with the operator's go-ahead, scoped to this app: the
console shows the app's permission count going 8 to 9. It does **not** include
production release.

**The grant is not immediate, and a careless probe will tell you it worked.**
The console showed 9 straight away and the API was still returning 403 twenty
minutes later. A probe that PUT the listing back with its existing values did
commit — but Play treats an unchanged listing as a no-op, so that commit
authorised nothing and was not evidence of anything. Any probe of this has to
change a value.

## 2026-08-29 — The app icon: H6 with an onion crown, generated from one description

The app shipped to internal testing with `icon=''` — no launcher icon at all.
The mark chosen to fix that is an open guilloché dial (two arcs, the gap toward
the lower left) with a domed "onion" crown at two o'clock, in the BFG light
palette: `#F4E6EB` ground, `#80475C` ink.

### Why a generator and not four drawn files

The same mark has to exist as an Android adaptive icon (two vector drawables on
a 108dp canvas), a 512px PNG for Play, and an SVG for the docs site. Drawn
separately those are four chances for the crown to sit at a different angle, and
nobody notices until they are side by side on a phone. So the shapes are data in
`BrandMark` and each format is an executor — the arrangement `ComplicationGlyphs`
already uses, for the same reason.

`./gradlew :workbench:brand` writes all of it. The outputs are **checked in**:
the Android build must not depend on a JVM task having been run.

Rejected: putting the mark in `:generator`. It is not part of the watch face file
format, and `:generator` is the definition of that format and nothing else.

### Three things only visible at size, and what they cost

**The hands read as the letter V.** Two hands of near-equal length, near-symmetric
about vertical, are a chevron and not a watch. The hour hand is now 11.5 units
against the minute's 18.5, and heavier. Obvious in hindsight; invisible at 96px.

**The crown read as a balloon on a string.** Its stem started at radius 29, inside
the outer ring at 30.5, so the ring covered the stem and left a floating dot. The
stem now starts *on* the ring.

**The strokes were sized for an artboard, not a launcher.** The design was judged
at 120px. Inside a 60dp keyline a 2.6-unit stroke lands on about 1.4 device pixels
at a 48px launcher icon, and the mark goes grey. Every weight went up roughly 30%.
This is a deliberate departure from the approved artboards: the alternative is an
icon that is correct in the file and a smudge on the phone.

### The safe zone is 60dp, not 72dp, and getting it wrong is silent

An adaptive icon's 108dp layer is not what anyone sees. The system reserves 18dp
on each side for parallax, so the mask applies to the middle **72dp**, and
Material's keyline for a round motif is **60dp** inside that. Sized to 72dp the
mark looks perfect in a square preview and loses its crown to the first circular
mask it meets — there is no error and no warning.

`./gradlew :workbench:brand --args="--sheet=<path>"` renders the icon cropped the
way a launcher crops it, at 192 down to 24px, in both palettes. That contact sheet
is what caught all four problems above; a 512px render caught none of them.

### Light, not dark

The dark palette is a near-black tile. On the dark wallpaper of a phone that asked
for dark mode it stops being an icon and becomes a hole. The dark mark stays in
`docs/brand` for the site and the README, where there is a page behind it.

### Not built: the Play feature graphic

The remaining store-listing asset is a 1024×500 feature graphic, which needs
typography. This machine has DejaVu, Liberation and FreeSerif and nothing else —
setting the BFG wordmark in Liberation Serif would contradict the brand it is
meant to carry. It waits for a real face rather than shipping a generic one.

## 2026-08-29 — On Play internal testing, published by script rather than by hand

`BFG Watch Faces` exists on Google Play as `com.bfg.watchfaces`, in the BFG
Solutions org account, with both artefacts live on internal testing:

```text
internal        versionCodes=['1']     status=completed
wear:internal   versionCodes=['1001']  status=completed
```

Tester opt-in: `https://play.google.com/apps/internaltest/4701563329381059441`

### The upload is a script, and that was not optional

The browser is the obvious route and it does not work from here. Chrome runs on
the operator's Windows laptop; the bundles are built on this Linux box; and the
upload tooling available to this session rejects both the path (not a shared
file) and the size (19MB against a 10MB cap). Copying a bundle across the bridge
and clicking through the console would have to happen for every release.

`scripts/play-release.py` does it instead: JWT bearer grant, create edit, upload,
set track, commit. No browser, no size limit, and it can move to CI unchanged.

### Two things Play refused, both worth keeping

**A Wear bundle cannot ride the phone track.** Uploading both and committing one
release fails:

```text
The APK or bundle with version code 1001 requires the Wear OS system feature
android.hardware.type.watch. To publish this release on the current track,
remove this artifact.
```

Play exposes a track per form factor — `internal` and `wear:internal` are
different tracks, and phone and watch are two releases. The script's `--track`
choices list both, with the reason in a comment so nobody merges them again.

**Release notes cap at 500 characters.** The honest version of "what does not
work yet" ran to 648 and was rejected with an HTTP 403.

### Credentials, and one correction to how they were stored

The upload key lives in `~/.keystores`, outside the repo, and its password is in
1Password. `.gitignore` now refuses `*.jks` and `*.keystore` outright.

Publishing uses `play-publisher@budgetbug-495002`, not the billing service
account beside it — those identities are separate on purpose. It had no
retrievable key, so a new one was minted and stored as a 1Password **document**.
That detail matters: the pre-existing `budgetbug-google-play` item holds its JSON
with **doubled quotes**, CSV-style, so `json.loads` fails on it and four attempts
were spent discovering that. A document stores the file byte-for-byte.

### What the testers are actually getting

The release notes say it plainly: the Studio designs faces, and the phone cannot
yet send one to a watch, because it cannot build a face on device. Activation can
also fail silently. An internal build that overstates itself is worse than no
build.

## 2026-08-29 — Emulator pairing does not work, and the netsim theory is disproven

Two emulators now run locally on KVM, and the pairing attempt was made properly.
It fails, and not for the reason previously recorded here.

### Every precondition was met

One `netsimd`, shared. Both guests attached to it — each log says "Activated
packet streamer for bluetooth emulation". Watch at
`SCAN_MODE_CONNECTABLE_DISCOVERABLE`, Bluetooth on both, nearby-devices
permission granted, companion app installed against a signed-in account. The
scan finds nothing, indefinitely.

So **the separate-`netsimd` explanation is disproven as sufficient.** It was a
real defect on the Windows pair — one emulator per account, two virtual networks
— and fixing it changed nothing. The most likely remaining reading is that
netsim carries BLE but not classic BR/EDR inquiry, which is what device
discovery needs. There is no `netsim` CLI shipped with the SDK on either
machine, only `netsimd`, so the virtual network cannot be inspected or linked by
hand to confirm it.

Google's documented route is Android Studio's Wear pairing assistant, which does
not use Bluetooth discovery. That is a GUI IDE, on a headless box, and it was not
installed.

### What was tried, so nobody repeats it

- Sharing one `netsimd` by starting both emulators from one session. Necessary,
  not sufficient.
- `REQUEST_DISCOVERABLE` on the watch, accepted, verified in `dumpsys`.
- Clearing `user_setup_complete` and `device_provisioned` and rebooting, to force
  a setup wizard. **A fresh Wear AVD self-provisions**, so there is no wizard to
  return to — see the correction earlier today.
- The classic `adb forward tcp:5601` bridge. The companion never offers an
  emulator as a target.

### What it costs, stated exactly

`CapabilityClient`, `ChannelClient` and the Bluetooth crossing remain untested.
Everything on the Push side is proven on two independent machines: parameters,
APK, validator token, `addWatchFace`, slot allocation, `updateWatchFace` on
re-push, the activation permission, `setWatchFaceAsActive`, face live.

The transport is **not** the last mile. The phone cannot yet BUILD a face to
send — that needs `google/pack` on-device through JNI and the validator wired in
— so there are two unbuilt pieces and the other one is larger. The Data Layer is
ordinary Android plumbing by comparison, and the honest place to prove it is the
first real watch.

## 2026-08-29 — KVM here, and I was wrong about why pairing fails

The operator ran `chmod 0666 /dev/kvm` on the host and emulators now run on this
box. `KVM_GET_API_VERSION` returns 12, a Wear OS 6 watch and an SDK 36 phone
both boot in about two minutes, and `adb devices` lists them locally.

**The fix had to be the device node, not group membership.** This session's
`/proc/self/gid_map` is `1000 1000 1` and `setgroups` is `deny`, so
`gpasswd -a <user> kvm` could never have reached it whatever group was named —
one gid is mapped and supplementary groups are refused outright. The `0666` mode
bits work because they do not depend on the owner being mapped. Worth writing
down because **the emulator's own error message tells you to fix the group**, and
that advice is wrong in a user namespace.

### The correction: a fresh Wear AVD does not run a setup wizard

Earlier today this file said pairing two emulators "costs a factory reset of the
watch emulator", reasoning that a Wear device only advertises while inside its
setup wizard and the Windows watch reported `user_setup_complete=1`.

That is wrong. A never-booted Wear OS 6 AVD comes up **already provisioned** —
`user_setup_complete=1`, `device_provisioned=1`, straight to a rendering watch
face. The image self-provisions and never runs a pairing wizard at all. So the
reset would have destroyed the verified carousel state and changed nothing, and
the earlier entry should not be trusted on this point.

The lesson is narrower than "test your assumptions": the Windows watch had been
provisioned by *something*, and I read that as evidence a wizard had run and
completed. It had not. A state that looks like the end of a process is not proof
the process happened.

### What did survive, and is free here

Two emulators started from one session share a single `netsimd`. On Windows they
had one each — one from a scheduled task as the bridge account, one from the
operator's own session — putting them on separate virtual Bluetooth networks by
construction. That half of the pairing problem does not exist locally.

### Still open

Pairing itself. The local phone has no Google account, so no Wear OS companion
app, and installing one needs a Play sign-in. Both emulators are headless here
(there is no display, and `qemu-system-x86_64` links `libpulse.so.0`, which is
not on this box — `-no-window` selects the headless binary whose libraries all
resolve). So seeing them at all means mirroring over adb through the existing
tunnel.

## 2026-08-29 — The generated surfaces render on the phone, and the last renderer decision moved

`AndroidDialRenderer` fell back to a plain dial for `GRAIN`, `BRUSHED`, `CARBON`
and `LINEN` — four of the thirteen styles, silently wrong rather than broken.
The file said so honestly and gave the reason: the per-pixel shading loop lived
in the AWT renderer, and porting it meant copying the lighting model.

So the lighting model left too. `ProceduralDial` in `:generator` returns raw
ARGB — no platform image type appears in it — and both renderers are now a blit.
That is the last of the four: `EngravedStroke` took the stroke passes,
`DialShading` the gradients, `ComplicationGlyphs` the icons, and this the
surfaces. **No renderer on either platform now decides anything about how a dial
looks.**

The golden preview test grew two procedural faces before the move, and all five
hashes were unchanged afterwards. The three pre-existing ones had not shifted
either, which is the part worth stating: the earlier icon extraction and this one
are both confirmed inert rather than assumed to be.

`RendererParityTest` covers it the same way as the rest — both renderers must
call `ProceduralDial.pixels`, and neither may contain `0.55`, `* 6.0` or
`255 - c`, the constants that would be the natural thing to copy back.

### What is still missing from the phone, and why

`Engine.TEXTURE` — an imported image — still falls back to a plain dial. That is
not a porting gap: a face references the image by id and the bytes live outside
the face, so there has to be somewhere on the device to resolve that id from
before the renderer has anything to draw.

## 2026-08-29 — The phone app is the demo now, and the icons moved to :generator

`:mobile` was 537 lines: a handoff screen and a sender, no design surface at
all. It now opens on the Studio screen from the localhost app — composite
preview, style chips, dial and ink swatches, and every slider — which is what
`DECISIONS.md` 2026-08-28 meant by making that app the exact specification.

Nothing about it is invented. Controls and their ranges come from
`ControlInventory`, the pixels from `AndroidDialRenderer` and
`AndroidFacePreview`, and the labels and order from a `Presentation` object that
holds only presentation. Where the phone and the demo disagree, the demo is
right and the phone has a bug.

### The complication icons are geometry, so they went where geometry lives

They existed only in the workbench, as ninety lines of AWT calls. Porting them
by hand into a Canvas renderer would have produced a second copy that drifts —
the failure this repo has already fixed three times, for stroke passes, shading
and slot boxes.

So they are `ComplicationGlyphs` in `:generator` now: primitives on a 24-grid,
with an AWT executor in `:workbench` and a Canvas executor in `:mobile`, neither
holding a decision. One convention had to be written down rather than inferred —
the arcs carry AWT angles, counter-clockwise from 3 o'clock, and Android's
`drawArc` measures clockwise, so it negates both. Getting that wrong draws the
sunrise glyph as a sunset.

**`preview.png` ships**, which is why this was done carefully rather than
quickly. It is the carousel thumbnail, built from these icons, and
`watch_face_info.xml` requires it. `ComplicationIcons` used to say the icons
"never reach the APK"; that was true of `dial_bg.png` and false of the preview.

The move is guarded by a new golden test that hashes the composite preview for
three faces. It passed unchanged across the extraction — byte for byte — which
is the only evidence worth having that a refactor of drawing code changed
nothing.

### Two bugs the screenshot found that no test would have

**It opened on Botanical.** `DialParams()`'s defaults are the FORMAT's defaults
and start there; the first chip in the curated list is Knotwork. An app whose
opening style is not the style it highlights is the same regression caught on
2026-08-28, arriving by a different route. `Presentation.STARTING_FACE` is the
studio's opening point — no name, no slug, no package, so it is not the
hardcoded face identity `CLAUDE.md` forbids.

**Every slider drew a tick per step.** Compose renders `steps` as tick marks,
and `scale` has 152 of them, so the track came out as a striped bar you could
not read a position off. The sliders are continuous now and the value is snapped
on the way in by `ControlInventory.snap`, which also fixes a quieter bug: `freq`
is stored as an `Int`, so an unrounded 6.9997 truncated to 6 and the slider
jumped backwards under the finger.

`valueOf` was missing too — the inventory could write a control but not read one,
so any UI building sliders from it needed its own hand-written map from id to
field. That is the duplicated list the inventory exists to delete.

## 2026-08-29 — The face activates itself, and the one-shot had a silent bug

The whole Push half now runs end to end: parameters, APK, validator token,
`addWatchFace`, notification, permission, `setWatchFaceAsActive`. The pushed
face is the active watch face on the emulator, rendering with live
complications. Nothing in this paragraph was true this morning.

### A notification is the only route to the ask

`startActivity` from the install path is refused — `Background activity launch
blocked`, and a `WearableListenerService` is a background context by the same
rule. [ActivationPrompt] posts a notification instead; the tap is a foreground
action, so the activity it launches is allowed to start. `dumpsys notification`
confirms the exemption is attached to the `contentIntent`:

```text
contentIntent=PendingIntent{... startActivity
  (allowlist: .../NOTIFICATION_SERVICE/NotificationManagerService)}
```

It is also better than what the design asked for. The old shape fired a
one-shot dialog cold on a wrist; this one fires because someone chose to look.

**`POST_NOTIFICATIONS` is not granted by install**, and `notify` is a silent
no-op without it — the ask would simply never happen, indistinguishable from
the bug this replaced. `ActivationPrompt` checks `areNotificationsEnabled()`
and says so rather than failing quietly. **How that permission gets granted in
production is still open**; it was granted by hand for this test.

### The doc described a safety property the code did not have

`ActivationRequestActivity`'s KDoc has said since it was written that the state
is "written down BEFORE the dialog is shown as well as after", because "a
process death mid-dialog must not leave this looking unasked". The code only
ever wrote in the result callback.

The manifest then made that theoretical hazard real. `android:noHistory="true"`
finishes an activity as soon as it stops being visible — which is precisely what
the permission dialog covering it does. Observed: the dialog appeared, the
activity died, no callback arrived, nothing was written, and the state read
UNASKED with the ask already spent. On the one action in this system that cannot
be retried.

Both halves fixed. `noHistory` is gone, with a comment saying why it must not
come back. `ActivationConsent` gained `ASKING`, written before the request goes
out; `settle()` recovers an interrupted ask from the only evidence left, which
is whether the permission actually landed. Absent settles to DENIED and never
back to UNASKED — Android will not show the dialog again either way, so
"unasked" would produce an app that believes it can still ask and silently
never does.

Nine tests cover it, including the full save/die/reload round trip.

### Two notes for anyone driving a headless Wear emulator

Input to a **dozing** screen is silently discarded. Taps looked like they were
landing on the permission dialog and nothing happened, twice, before
`dumpsys power` showed `mWakefulness=Dozing`. Send `KEYCODE_WAKEUP` first and
check, or you will debug your own code for an hour.

The Wear permission dialog is Compose: its text nodes report
`clickable=false` and carry no resource id. Find the clickable ancestor by
bounds instead of matching on the label.

## 2026-08-29 — `addWatchFace` works, and two shipped bugs only running could find

Watch Face Push has installed a face. Not validated, not sideloaded — pushed,
through `WatchFacePushManager`, with a token from Google's validator, into a
slot the system allocated.

```text
slots: 1 free, 0 used
OK slot=55c1e952-c49a-38f2-ae3f-0786a085b15d replaced=false
```

Run a second time, it reports `0 free, 1 used` and takes the `updateWatchFace`
branch — `replaced=true`, same slot. So both halves of the slot-limit logic are
exercised, and **this device's slot limit is 1**, which makes the
`ERROR_SLOT_LIMIT_REACHED` avoidance load-bearing rather than defensive.

### Two bugs in shipped code, both invisible to every test we have

**The manifest never asked for permission to use the API.** `wear/` declared
`SET_PUSHED_WATCH_FACE_AS_ACTIVE` and stopped. Every Push call needs
`com.google.wear.permission.PUSH_WATCH_FACES` as well, and nothing tells you:
the library's own manifest declares no permission at all, only a `<queries>`
entry, so the merged manifest looks complete. The failure is

```text
SecurityException: Not allowed to bind to service Intent {
  act=com.google.wear.ACTION_PUSH_WATCH_FACES ... }
```

which names `bindService` and never says "permission". Read off the watch
instead of guessed: `dumpsys package com.google.android.wearable.dwf.receiver`
shows `WatchFaceReceiverService` guarded by exactly that string. It is
`prot=normal`, so declaring it is enough. This was not a harness artefact — the
Data Layer path would have hit the identical wall on the first real face.

**`WatchFacePushManager` needs a context that can bind services.** A
`BroadcastReceiver`'s cannot, and throws `ReceiverCallNotAllowedException` from
inside `listWatchFaces`. `FaceInstaller` now normalises to
`applicationContext`, which is correct for every caller and costs nothing.

### The activation prompt cannot work the way the decision describes

Operator decision 01a049a1-390b-7b50-a5d3-cc082037bb55 puts the one irreversible
ask on the watch, the first time a face lands. The install does reach
`onFaceInstalled`, which does call `startActivity`. Android refuses it:

```text
Background activity launch blocked! goo.gle/android-bal
  cmp=com.bfg.watchfaces/.wear.ActivationRequestActivity
  callingUidProcState: RECEIVER
```

This is not about the debug harness. A `WearableListenerService` handling
`onChannelOpened` is a background context by exactly the same rule, so the
shipped path is blocked identically. **The permission has still never been
requested**, and it cannot be from where the design puts it.

Not fixed here, because the fix is a design choice and the thing being chosen
about is unrecoverable. The options, none free:

- **A notification on the watch that launches the activity when tapped.** Keeps
  the decision's intent — asked when a face lands, in context — and satisfies
  BAL, because the tap is a foreground action. Costs a notification channel and
  a second tap. This is the recommendation.
- **Ask when the watch app is opened.** Already rejected on 2026-08-28, and the
  reason still holds: a companion app is often never deliberately opened.
- **Ask from the phone.** Impossible. `androidx.wear.watchfacepush` declares
  `<uses-library android:name="wear-sdk" android:required="true" />`, so an app
  linking it will not install on a phone.

### `FaceInstaller`, and why the install left the service

The install logic lived inside `FaceReceiverService.onChannelOpened`, which
welded the only genuinely novel calls in the project to a transport that needs a
paired phone. Nothing about Push requires a channel: `addWatchFace` takes a
descriptor to a local file and does not care who wrote it.

So it moved to `FaceInstaller`, and `wear/src/debug` gained a receiver that
drives it from `adb`. In `src/debug` and not behind a `BuildConfig.DEBUG` check:
an exported receiver that installs an arbitrary APK must not exist in a release
build, and a source set is the only version of that guarantee the compiler
enforces.

**This proves the Push half and says nothing about the transport half.**
`CapabilityClient`, `ChannelClient` and the Bluetooth crossing remain untested.

### Pairing two emulators needs a factory reset, which is why it was not done

The phone paired far enough to matter: Play signed in, "Wear OS by Google"
installed, consent accepted, and `CompanionAssociationActivity` scanning. It
finds nothing, for two stacked reasons.

First, **each account gets its own `netsimd`** — the emulator's virtual
Bluetooth network. The watch ran from a scheduled task as the bridge account and
the phone from the operator's own session, putting them on separate networks by
construction. Both now run from the same principal and share one.

Second, and not fixable by configuration: the watch reports
`user_setup_complete=1` and `device_provisioned=1`. **A Wear device only
advertises for pairing while it is inside its setup wizard.** Pairing therefore
costs a factory reset of the watch emulator, which would destroy the verified
carousel result from earlier today. Not spent, because the shortcut above
reaches the code that mattered without it.

## 2026-08-29 — Both apps run, and the Data Layer needs a pairing nobody can automate

`:mobile` and `:wear` have been installed and launched. Two emulators on the
operator's laptop — a Wear OS 6 watch and an SDK 36 phone with Play — driven from
here over the adb bridge.

### What ran

- **`:wear` installs on Wear OS 6.** `FaceReceiverService` is registered with its
  intent filter, `SET_PUSHED_WATCH_FACE_AS_ACTIVE` shows in requested permissions
  (not granted, which is correct for a runtime permission nobody has asked
  for yet), and `com.google.android.wearable.dwf.receiver` is present, so Push is
  genuinely supported on this image.
- **`:mobile` installs and launches on the phone.** The Compose handoff screen
  renders: the three steps, the numbered markers, the copy straight out of
  `ActivationConsent`.
- **"Send to watch" behaves correctly when it cannot.** `FaceSender.findTarget`
  fails and the screen says "Could not reach your watch. Is Bluetooth on?" — no
  crash, no silence.

### A defect only a screen could find

The title was drawn UNDER the status bar, overlapping the clock.
`enableEdgeToEdge()` draws behind the system bars and nothing was applying the
insets. Fixed with `windowInsetsPadding(WindowInsets.safeDrawing)`.

Worth recording because no test in this repo could have caught it and none
realistically could: it is not a wrong string, a wrong colour or a wrong number.
It is a layout that only exists once a real window with real insets is on a real
screen. The unit tests were all green while it was broken.

### Where the emulator route stops

The Data Layer needs the two emulators PAIRED, and pairing needs
`com.google.android.wearable.app` — the Wear OS companion — on the phone. The
Play image ships Play Store and Play services but not that app, and installing it
requires signing into a Google account.

That is the operator's credential and not something this session should touch, so
the automated path ends here. Everything up to it is done: both emulators run,
both apps are installed, and the bridge drives them.

**What that leaves untested**: `CapabilityClient` discovery, the `ChannelClient`
transfer, `addWatchFace`, slots, and the one-shot activation permission. The
validator already says a face WOULD be accepted; nothing yet says the transport
works.

## 2026-08-29 — The official validator issues tokens for both our builders

`validator-push-cli` 1.1.0-alpha01, run against faces this repo produces. Ten
checks, all passing, a token issued for each. Three questions that were recorded
as untestable are now answered.

### Watch Face Push accepts a pack-built APK

The larger result. `google/pack` signs v3 only, in the zip signing block, with no
`META-INF` at all — where aapt2 plus apksigner also writes a v1 JAR signature.
DECISIONS 2026-08-28 flagged that as unresolved and "exactly the kind of thing
that fails silently at install".

It does not fail. The pack-built APK passes `APK signature validation` and gets a
token, identically to the aapt2 one. The on-device builder is viable.

### The `nodpi` memory worry was unfounded

`Memory footprint validation` passes. That check is the whole reason the
qualifier mattered: a dial scaled 2x would be 3.3MB a frame against a ~10MB
ambient budget. Both builders clear it, so `scripts/pack-qualifiers.patch` fixed
a real correctness bug and the ceiling was never close.

### Everything else Push asks of a face

`Package name`, `Minimum SDK version`, `Watch Face Format version property`,
`Watch face definition files presence`, `Watch Face Format validator run`, `APK
size`, `File contents`, `AndroidManifest`. All pass on both builders — so the
template, the emitter and the manifest are all correct by Google's own reckoning
rather than by ours.

### Tokens, and where they belong

A token is issued per APK for a named Push client — `-p com.bfg.watchfaces`,
which is `:wear`'s applicationId. That confirms the shape `WatchLink` already
assumes: the token describes one specific APK and travels with it. It is not a
device credential and not reusable across faces.

### What this still is not

Validation is not installation. `addWatchFace` has not been called, no face has
gone over a Data Layer channel, and the activation permission has not been
requested. This says a face WOULD be accepted, not that the flow works.

## 2026-08-29 — A face from this repo is on a watch face carousel

`docs/SPEC.md` build order, step one: "Install `watchface-template` on a real
watch. Everything else assumes this works. Until a face appears in the carousel,
nothing downstream matters."

It appears. Selected, active, rendering: the knotwork dial in pewter, the time,
and every complication populated with live system data — date, step count,
notifications, battery.

### What is actually proven

On a Wear OS 6 emulator (`sdk_gwear_x86_64`, release 16, SDK 36):

- an APK built by `watchface-template/build.sh` installs and reports Success
- it appears in the watch face picker **by name**, alongside the stock faces
- it can be selected and becomes the active face
- it renders correctly, with complications bound to real data
- `dumpsys wallpaper` confirms
  `com.google.wear.watchface.runtime/DeclarativeWatchFaceRuntime0` is rendering
  it — the WFF runtime, which is how a declarative face is supposed to be drawn

### What is NOT proven, and must not be read into this

- **This is an emulator, not a watch.** Much larger than anything this repo
  could claim before, and smaller than "it works on a wrist".
- **Watch Face Push was not exercised.** The face was sideloaded with
  `adb install`. `addWatchFace`, the validation token, slots and the Bluetooth
  transfer remain untested.
- **The activation permission was not requested.** `:mobile` and `:wear` have
  still never been installed or launched.
- The `nodpi` memory question is still unmeasured.

### A wrong turn worth recording

Midway through, `cmd package query-services -a WallpaperService` did not list the
face, and that was read as "the system has not registered it". Wrong: a WFF face
is not a wallpaper service. The shared runtime renders it, and only the runtime's
component appears in that list. The face was in the picker the whole time.

What actually answered it was walking the picker with `KEYCODE_DPAD_DOWN` and
reading labels back through `uiautomator dump`. Wear lists scroll by rotary, so
`input swipe` moved nothing and made the list look empty past the first row —
which is why earlier passes saw only "Abstract, Adventure".

### How it was reached

The build machine cannot run an emulator at all: `/dev/kvm` is unreachable from
its user namespace, and the arm64 escape is closed because the emulator refuses a
foreign architecture. So the emulator runs on the operator's Windows laptop and
is driven from here over an SSH reverse forward — `scripts/remote-adb.sh`. Build
here, run there.

## 2026-08-28 — The Android renderer, and why the emulator is not an option

`:mobile` can draw a dial. `AndroidDialRenderer` is the second rasterizer
`DECISIONS.md` 2026-08-27 warned about, built the way 2026-08-28 said to build
it: with nothing in it worth drifting.

### The defence is not a pixel comparison, because there cannot be one

One renderer is AWT on the JVM; the other is Android `Canvas` on hardware that
does not exist here. No byte comparison between them can ever be run on this
machine.

So the property being defended is different: **neither renderer decides
anything.** `EngravedStroke` owns the three stroke passes, `DialShading` now
owns the sheen and the vignette, `PatternEngines` owns the geometry.
`AndroidDialRenderer` contains no colour arithmetic, no offsets, no gradient
stops and no stroke widths — and `RendererParityTest` asserts that absence by
reading the source, because an absence cannot be asserted any other way. If a
dial ever looks wrong on device, the bug is a drawing call, not a judgement.

`DialShading` was extracted the same way the stroke passes were, and checked the
same way: twelve sheen × vignette combinations rendered byte-identically before
and after.

### Deliberately not ported

The generated surfaces and imported images fall back to a plain dial for now —
`TextureField` is pure and ports directly, but the per-pixel shading loop is real
work and deserves its own pass. The lens is absent on purpose: 2026-08-27 records
that it never reaches the emitted WFF, so drawing it on device would make the
preview differ from the installed face in the one direction that matters.

### The emulator: it is KVM, and there is no way round it

Worth recording because it looks like it should have an answer, and two were
tried.

`/dev/kvm` is owned by `nobody:nogroup` because the kvm group (gid 993) is not
mapped into this user namespace, which maps only 1000 and 65534. The emulator
refuses outright: "x86_64 emulation currently requires hardware acceleration!"

The obvious escape is an **arm64** system image, since the SDK ships
`qemu-system-aarch64` and full software emulation needs no KVM. Wear OS 6 arm64
exists, and it was installed and tried. The emulator rejects it:

> `FATAL | Avd's CPU Architecture 'arm64' is not supported by the QEMU2 emulator
> on x86_64 host. System image must match the host architecture.`

That binary is for arm64 HOSTS. There is no cross-architecture path. Both routes
are closed, and the x86_64 image was restored so that fixing KVM is the only
remaining gap.

A second, quieter blocker surfaced on the way: the disk is at 98%. Even a
workable image would not have fit without freeing space first.

## 2026-08-28 — The controls live in `:generator`, and a slider was lying

`ControlInventory` replaces the hardcoded control lists in the app. Both front
ends now build themselves from it, so they cannot disagree rather than being
checked for disagreement. `DemoIsTheSpecTest` is gone: asserting that two
generated things match is noise.

Timed deliberately. `:mobile` exists but its controls were not yet hand-written,
which is the one window where this costs nothing — before it there was no second
consumer to design against, after it there would have been three copies.

### The split is identifiers here, words there

Ids, ranges and ordering moved. **Labels did not**, and neither did the starting
design. `DialParams` already said presentation belongs to the UI, and a watch
screen may want shorter words than a phone; dragging the copy into `:generator`
to make a test easier would invent a constraint nobody asked for. The app opens
on a Knotwork face, which is a curated preset rather than `DialParams`' bare
defaults, and that distinction stays in the UI where it cannot be erased by
accident.

An unlabelled id falls back to showing the raw id, so a new engine appears as
`GUILLOCHE` rather than silently not existing. `ControlLabelsTest` stops that
shipping.

**Engine ORDER is presentation too**, which the first cut got wrong. Building
the chip row straight from `Engine.entries` opened the app on Lattice, because
enum declaration order is not a running order — Knotwork leads because it is the
flagship. The UI orders them and appends anything it does not recognise, so a
new engine is never unreachable. Caught by driving the app, not by a test.

### The slider that was lying

The point of moving ranges was that `:generator` could not previously see what
values a person could reach, so a range wider than the geometry tolerates stayed
invisible until somebody dragged it.

There was one. **`timeY` offered 80..380, and every value from 304 up makes
complication slots overlap** with all five switched on — a quarter of the slider
produced a visibly broken face. `SlotGeometry` cannot rescue it either: a clock
that low leaves nowhere for a row to go, and it already shrinks slots to
`MIN_SIZE` before giving up. Narrowed to 300, with the margin and the
measurement recorded next to the number.

The bound is the worst case, five slots. A face with none could sit the clock
lower, and a range that changes with the number of complications switched on is
a slider that moves under the user's finger.

## 2026-08-28 — google/pack builds our watch face, and is not the default yet

Operator: "try pack-cli in build.sh I don't see a reason we don't just do this
ourselves from source". Tried, from source, and it works — `USE_PACK=1
./build.sh` produces a signed, Push-allowlist-clean APK with **no Android SDK,
no aapt2, no apksigner, no zipalign and no Java**.

That matters beyond the desktop. `pack` is the same library that will build faces
ON THE DEVICE, so exercising it here is how that path gets de-risked before any
of it ships. `scripts/build-pack.sh` builds it reproducibly.

### Correcting what this repo assumed about pack

`docs/SPEC.md` said to "use Androidify's prebuilt `jniLibs` if the ABIs cover
you". That reads as though an official binary exists. **It does not** — `pack`
publishes no releases and no artifacts at all, so that route means scavenging
unversioned `.so` files out of a sample app nobody here can audit. Building from
source is the ordinary path, and it needs `cargo` plus `protoc` (the App Bundle
crate generates code from `.proto`, and the workspace will not build without it
even though only the APK is wanted).

`pack` is also more relevant than "an APK packer": its README says it is
"currently being developed for compiling Wear OS Watch Face Format packages",
which is exactly this.

### The qualifier gap, and the patch that closes it

**pack had no resource-qualifier support.** `read_res_dir` used the directory
name verbatim as the resource type, so `res/drawable-nodpi/` became a type
called `drawable-nodpi` and `@drawable/preview` failed to resolve. Flattening
the directory made it build, and dropped the instruction not to density-scale
the dial — 831KB a frame becomes 3.3MB at 2x, a 4x regression against a figure
this repo measured deliberately.

`scripts/pack-qualifiers.patch` closes it properly. Three changes: a resource
knows its TYPE (everything before the first `-`) separately from its directory;
the buckets and the `@type/name` resolver group by type rather than by
directory; and the density qualifier is written into `ResTable_config` instead
of the 60 zero bytes that were there.

Those zeroes were not an oversight. pack's own comment says "Luckily, we don't
care about any of the data for watch faces" — but an all-zero config means
density 0, which is *mdpi*, not "unspecified". The file that says "do not scale
me" was being recorded as "I am mdpi, scale me".

Verified rather than assumed: `aapt2 dump resources` reads `(nodpi)` back out of
pack's APK, and the resource table matches aapt2's own — same type ids, same
entry ids, same entry counts, same configuration. The only remaining difference
is that aapt2 annotates the path `-v4`.

`build-pack.sh` pins pack at `7b60931e4058` and applies the patch, so the build
is reproducible and a pack that has moved fails loudly rather than quietly
building something else.

**Not sent upstream.** google/pack is Apache-2.0 and actively developed for exactly
this use case, and `res_dir.rs` carries a "TODO: Use a better pattern here" at
the spot, so it would likely be welcome. Sending it is outward-facing and is the
operator's call, not this session's.

### Still opt-in, for a different reason now

aapt2 remains the default. Not because anything is wrong with pack any more, but
because **no APK from this repo, from either builder, has ever been installed on
a watch.** Switching what builds the shipped artefact on the strength of a
comparison that has never touched hardware would trade a known-untested path for
a differently-untested one.

### Found on the way: the manifest was not self-describing

`minSdkVersion`, `targetSdkVersion`, `versionCode` and `versionName` existed only
as `aapt2 link` flags. `pack` reads the manifest and takes no flags — and neither
will the on-device build — so an APK came out with no version and an implied
`targetSdk` below 4, which made `apksigner` demand a v1 JAR signature that a
v3-only signer never produces. The APK looked unsigned and was not: with a real
`minSdk` supplied, the v3 signature verified.

They are declared in the manifest now, which Push permits (`uses-sdk` is on its
tag list). Both build paths then produce identical package metadata, and the
pack APK verifies with no flags at all.

### What is not yet known

Whether Watch Face Push accepts a v3-only signature with no `META-INF`. The
allowlist permits `META-INF/**` rather than requiring it, and minSdk 33 is far
above the API 24 where v1 stopped mattering, so there is no reason to expect a
problem — but it is untested, like everything else that needs a watch.

## 2026-08-28 — One engraved look, described in `:generator`

`DECISIONS.md` 2026-08-27 left this open: the workbench's AWT renderer would
become a second rasterizer once `:mobile` existed, and the fix was "extracting a
small drawing interface into `:generator` that AWT and Android `Canvas` both
implement, leaving stroke order and compositing defined once." It said not to
guess that shape before the Android side existed.

`:mobile` exists now, so `EngravedStroke` is that extraction. Operator chose it
over porting `DialRenderer` and living with two.

### What is shared is a description, not a canvas

The tempting move is a lowest-common-denominator canvas interface that both AWT
and Android `Canvas` implement. That is a large surface — transforms, clips,
paints, gradients, image drawing — maintained forever, and most of it is
platform detail that could differ without anyone noticing or caring.

What actually matters is the part `CLAUDE.md` states as a rule: three passes per
polyline, light offset up-left by relief, dark down-right, thin mid pass last.
That is where a second renderer drifts into looking almost-right. So
`EngravedStroke.passes()` returns the passes AS DATA — offset, colour, width, in
order — and each platform executes them with its own API. Small, pure, and
tested on the JVM.

### It changed no pixels, and that was checked rather than assumed

All twelve engines were rendered to PNG before the change and after, and the
SHA-256 of every one is identical. That mattered because `DialRenderer`'s output
IS the shipped artwork: any drift would have restyled every face already saved,
which is what `generatorVersion` exists to prevent.

The check earned its keep immediately. The first draft of `mix` ROUNDED, which
is arguably better arithmetic — truncation biases every channel down. The
workbench has always truncated. Rounding would have shifted the colour of every
dial in the catalog as a side effect of a refactor advertised as a no-op, so the
truncation is preserved and pinned by a test that says why.

A second guard pins the exact ARGB of the default face's three passes. It exists
so the promise survives a year: someone improving this arithmetic gets a failing
test that names `generatorVersion` rather than a silent restyle.

## 2026-08-28 — The community catalog leaves GitHub

Operator decision `01a049a3-0a0c-7521-a6f3-f40510b81cf7`, in their own words:

> Submission path: "Move the catalog off GitHub entirely"
>
> Reporting: "We need another solution"

The contract the replacement has to meet is `docs/specs/catalog-service.md`,
written before the code because the thing being replaced is a Play-required
complaint path.

### The fact that forced it

GitHub has no anonymous write path. Commits, pull requests, issues, discussions,
comments and gists all require an authenticated account; reading is anonymous,
writing never is, and no repository setting changes that.

So "anyone can share a face without an account" and "the catalog lives on
GitHub" cannot both be true. The catalog repo was created five hours earlier on
the assumption that submissions arrive as pull requests, which is the assumption
this removes.

### Recommended against, and chosen anyway

Recorded plainly because the next reader will otherwise wonder. Three options
went up — keep GitHub and make the user sign in; keep GitHub as the store with a
small relay holding the token; leave GitHub entirely. The recommendation was the
middle one, and the argument against the third was that it throws away
properties that are currently free:

- removals stop being an auditable public commit
- anyone can clone a git repo of JSON, so the catalog is portable by
  construction; that stops being true
- free hosting stops being automatic, because jsDelivr over a public repo is
  free in a way a service is not

The operator read that and chose to leave anyway. Their call, and the
requirements now carry mitigations for each loss: an export endpoint that dumps
every published face as the same files the git catalog uses, a moderation log,
and a hard rule that everything in the store can be exported as files.

### The part that is not about storage

Anonymous submission removes the only handle moderation normally has. There is
no identity to ban, and a public endpoint writing to a public catalog will be
found. `MODERATION.md` also already promises Play a working complaint path, and
that path currently requires a GitHub account — an asymmetry that was tolerable
while submitting needed one too, and stops being tolerable the moment submitting
does not. Anyone could publish; only developers could complain.

Both are answered by the same requirement: **nothing is public until it is
approved.** Pre-moderation is what makes anonymous submission safe, because
flooding a queue that nobody sees costs the attacker effort and gains them
nothing. Rate limiting only slows a flood that still lands.

### Nothing is ripped out yet, deliberately

The GitHub report route works today, and it is what makes the app shippable
under Play's UGC rules. Removing it before a replacement is deployed would leave
the app with no complaint path at all — strictly worse than one that needs an
account.

So the order is: build the service, verify it, point the app at it behind one
seam, and only then remove the GitHub route and rewrite `MODERATION.md`. The
spec says so in as many words so that a later reader does not mistake the
surviving GitHub code for someone ignoring this decision.

### Cheaper than it looks: the read path was never built

Worth knowing before anyone budgets for this. `CDN_URL` is only ever *reported*
in JSON and printed by the catalog task — nothing fetches it. The Community tab
reads the local catalog directory. So there is no network read path to migrate;
the work is submit, report, and moderation.

## 2026-08-28 — The activation prompt: asked once, explained properly, and never again

Settled by operator decision `01a0495b-dc98-76d2-9e80-92aff51cdec6`, which
closes the question the 2026-08-26 entry left open. Their words, both parts:

> When to ask: "Should this be when they first open the app. Explained carefully
> and why it is important they approve it and what the approval limits the app
> to."
>
> After a denial: "Show a persistent short note on how to activate from the
> watch instead"

Four options were offered and they took none of them, so this is their placement
rather than the nearest button: ask EARLY, not gated behind a first push that
may never happen.

### Where it happens, settled

Operator decision `01a049a1-390b-7b50-a5d3-cc082037bb55`: **the watch puts the
dialog up the first time a face lands on it**, and the device app explains what
is coming beforehand, in clear steps. Their words: "It should be a clear
multi-step instruction on the device app, saying it is pushing to the watch,
needs approval."

The operator's original intent survives inside that. The explaining happens
where they wanted it — on the device, while someone is looking at it, with room
to say it properly — and only the system dialog happens on the watch, because
that is the only place it can. `ActivationConsent.HANDOFF` is those steps.

The watch app's own first open was rejected as the moment: a companion app
installed alongside a handheld app is often never deliberately opened, so the one
irreversible ask could sit unused for weeks or fire cold on a wrist.

### "I've never seen another app install anything on a watch. Like Facer."

A fair challenge, and worth answering in the record because it will be asked
again. Checked against Google's own documentation rather than argued.

`developer.android.com/training/wearables/watch-face-push` gives a table of use
cases, and ours is in it verbatim:

> "I want to create a phone app that allows users to select watch faces from a
> curated collection, or design and customize watch faces for installation
> directly on their Wear OS watch." → "**Create an app, for both watch and
> phone**, using the Watch Face Push API on the watch." Complexity: **High**.

And on what the watch half is for:

> "**Watch app**: The watch app may typically not have a significant user
> interface. It is primarily a bridge between the phone app and the Watch Face
> Push APIs... Using the Watch Face Push API to install/update or replace watch
> faces. **Requesting necessary permissions and prompting the user.** Providing a
> default watch face. Providing a minimal cache of watch faces."

So Google independently assigns the permission prompt to the watch app, which is
what reading the library had already shown. Two apps is the documented design,
not a workaround.

The reason it has never *looked* like an install is that it did not used to be
one. The older generation of design apps shipped a single native watch face that
re-rendered whatever design you picked, so switching "faces" sent data rather
than a package — nothing was installed per design, so nothing appeared to be.
That route is closed: **"As of January 2026, the Watch Face Format is required
for installing watch faces on all Wear OS devices."** Watch Face Push is the
sanctioned replacement for exactly what those apps were doing.

### A detail worth having found

The same page notes the phone app can detect the absence of the watch app
through `CapabilityClient` and "launch an intent to the Play store to install the
missing form factor". That is why the first handoff step is about the companion
app rather than about the face: without it nothing can be sent at all, and
"nothing happened" is the worst failure available here.

### What verifying it first turned up, and why it matters

The task required confirming the permission can actually be requested before
building around it. It cannot, where the answer assumes.

`androidx.wear.watchfacepush:watchfacepush:1.0.0` declares `<uses-library
android:name="wear-sdk" android:required="true" />`. That is a Wear OS system
library, so an app linking this cannot install on a phone or tablet at all. The
permission is checked with `checkSelfPermission` inside `setWatchFaceAsActive`,
on the watch. And `setWatchFaceAsActive` takes a slot ID which the library's own
error text says comes only from `listWatchFaces` or `addWatchFace`.

So the app the user opens to design a face can never hold this permission. There
are two different "first opens" — the design app, where the attention is, and
the watch app, where the permission lives — and choosing between them changes
what the operator's answer means. That went back to them as
`01a04987-6498-7820-b7c6-271471f39fb5` rather than being quietly resolved by
moving the prompt, which is what the task told us not to do. It was answered by
`01a049a1`, above.

### The rules, in `ActivationConsent`

A three-state machine — `UNASKED`, `GRANTED`, `DENIED` — with `DENIED`
deliberately terminal, because Android offers no transition out of it.

`canAsk` is true only from `UNASKED`. It is pointedly NOT "is the permission
missing": a denial also leaves it missing, and that reading is exactly how the
one shot gets spent on somebody who already said no. `record` throws rather than
quietly doing nothing when called from a settled state, because a silent second request is a
call that looks like it worked and reached nobody.

Same shape as `WatchDevices`: the judgement is here and tested, the Android call
is a thin thing at the edge that does not exist yet. When `:wear` is built this
class does not change, it gains a caller.

### The explanation is the deliverable, not the wrapper

The operator named two things and the second is the one usually omitted: why it
matters, AND what the approval limits the app to. A permission screen that only
sells the upside is the shape people have learned to distrust, so the boundary
gets equal room — it only ever switches to a face you made here, it cannot see
the face you are wearing, and it cannot change anything else.

Three specific choices, each pinned by a test:

- **It says a no still leaves a working app.** "Without it the face still
  arrives — you just switch to it yourself." Overstating the cost of declining
  is the cheapest way to get a yes and the fastest way to deserve distrust.
- **It admits there is only one chance, before they choose.** Someone making a
  permanent decision should learn that while they are making it.
- **The decline button is "No thanks", not "Not now".** "Not now" promises
  another ask that cannot happen. A button that lies about being reversible is
  worse here than anywhere else in the app.

### After a no: instructions, not a second pitch

Persistent, as asked — the note has to still be there next week when they have
sent a face and are wondering why nothing happened, which a toast would not be.

It carries the actual gesture (press and hold, then scroll) and a test forbids
the words "allow", "permission", "grant", "enable" and "settings" in it. Nothing
can reopen the choice, so anything persuasive there is nagging someone about a
locked door.

### Found on the way: two dependency versions that were never published

`gradle/libs.versions.toml` pins `watchfacePush = "1.0.0-alpha03"`, which does
not exist — that library has shipped `1.0.0` — and `wfp-validator =
"1.0.0-alpha10"`, which does not exist for either validator artifact. Nothing
has caught it because `:mobile` and `:wear` are commented out of
`settings.gradle.kts`; they would fail to resolve the first time anyone
uncomments them. Raised with the operator in the same decision; not changed
unilaterally, because choosing between `1.0.0-alpha09` and `1.1.0-alpha01` for
the validator is a real choice and not a typo fix.

## 2026-08-28 — The app you can open is the app that ships, and it is Compose

The localhost app is the SPECIFICATION for the shipped one, not a design toy
that an Android app later approximates. Every screen, control and flow in it is
one the shipped app has. Where the two disagree, the shipped app is wrong.

The operator settled the shape of it in the interview, and their answer was not
one of the options offered:

> "What is the best for the user. A full binary APK that matches the demo or a
> webview on the device. I want this 100% ON the device (except for the
> community faces)."

That is a constraint plus a delegation, not a choice between the two. So: a
native Compose app, everything on the device, community faces over the network
and nothing else.

### The WebView option does not avoid the second rasterizer, and that changes the argument

The framing this decision inherited was that Compose costs a second rasterizer —
`DialRenderer` is AWT `Graphics2D`, so `:mobile` would need an Android `Canvas`
port alongside it — and that a WebView over the same HTML would avoid paying it.

It would not. `java.awt` does not exist on Android either, so a WebView app has
exactly three ways to get a dial on screen:

- redraw the pattern in JavaScript, which `DECISIONS.md` 2026-08-27 already
  rejected as a second renderer that starts identical and drifts
- call back into Kotlin for the PNG — which still has to rasterize with Android
  `Canvas`, the same port
- talk to a server, which the whole design exists to avoid

The rasterizer port is owed either way. It is a consequence of shipping to
Android at all, not of choosing Compose, so it cannot be the tiebreaker. With it
removed from the scales the question is just which UI is better for the person
using it, and that is not close: native scrolling and fling, system back,
TalkBack, font scaling, haptics, and no JavaScript bridge between a slider drag
and a redraw.

`docs/SPEC.md` already planned for Android `Canvas` to drive both the live
preview and the baked PNG, so what ships is what was previewed by construction.
That plan stands; this decision adds that the localhost app defines what is
drawn around it.

### 100% on the device is reachable, and is already what the scaffold assumes

Worth writing down because "no server anywhere" sounds like an aspiration and is
not:

- `google/pack` is a Rust library that compiles and signs a WFF APK on-device,
  with no JDK, no Android SDK and no `android.jar`. Google's own Androidify app
  uses it, and it can vary the package name, which `reskin.sh` cannot.
- The Watch Face Push validation token is issued by a local validator —
  `mobile/build.gradle.kts` already pulls `wfp.validator.android` for exactly
  that, and the comment on it says "local token generation, no network".

Neither has been run here. They are the scaffold's stated assumptions, recorded
so the next reader knows the on-device claim rests on those two pieces and can
check them first rather than discovering the dependency late.

### Rejected: WebView over the same HTML

Tempting because it makes "the app is the spec" true by construction — one file,
no possibility of drift. Rejected because it pays that with a permanently worse
app: a bridge between every control and every redraw, hand-built sheets in place
of real Material ones, and accessibility that has to be re-earned rather than
inherited. The drift problem is real and is answered below by other means.

### Rejected: the demo as a design reference that :mobile is tested against

This is what "the demo is the spec" was in danger of quietly becoming. It is the
weakest option because it is the one that requires nobody to ever be careless.
This repo has been bitten twice by precisely that: `SlotGeometry` exists because
the slot arithmetic was written twice with a test asserting the copies agreed,
and they agreed while overlapping on both axes; the JavaScript dial was rejected
for the same reason before it was written.

### So the correspondence has to be enforced, not asserted

Added `DemoIsTheSpecTest`, which reads `index.html` and checks the app's control
inventory against `:generator`: every `Engine` is offered, every
`ComplicationSource` is offered, neither list invents anything the format cannot
store, and the slot labels are in `SlotPosition` order. The interesting
direction is the first one — an engine the app does not list is not a small
omission, it is a piece of the file format no user can reach, which is the app
having stopped being the spec. Each of the five assertions was ablated and
fails when its mechanism is removed.

It is a stopgap and says so. The real fix is one control inventory in
`:generator` that both UIs build themselves from, so the lists cannot disagree
rather than being checked for disagreement. That is not worth designing yet:
there is one consumer today, and inventing the abstraction before the second one
is real is how the wrong shape gets frozen.

### phone/ is now mobile/

The module was `:phone` for an app that runs on a tablet just as well, which is
the same narrowing the copy pass took out of the UI. Renamed now, while it is an
empty scaffold commented out of `settings.gradle.kts`, because the alternative
is renaming it once it has a codebase in it. Android's own Wear project layout
pairs `mobile/` with `wear/` for this reason. `:wear` keeps its name — that one
really is a watch.

## 2026-08-28 — Spacing is one control, and it works on both axes (v5)

"Spacing" moved the middle row and nothing else. Set it to Wide and three slots
spread sideways while the date and the battery stayed exactly where they were —
the dial got wider and no airier, which is not what the word means to anyone
using it.

From `generatorVersion` 5, `complicationSpread` also pushes the top slot up and
the bottom slot down, and widens the gap between the row and the bottom slot.
The clock does not move: it is the fixed thing everything else spaces away from.

### Why a version bump for a layout tweak

Complication placement IS the stored file format. A face in the catalog is
parameters, so changing what a given `complicationSpread` means silently
rewrites every face already saved with it. `PatternEngines.v5` delegates
straight to `v4` because no engine changed — the bump exists purely so
`SlotGeometry.verticalAir` returns 0 for anything saved before today, and
`VerticalSpacingTest` asserts a v4 face's top and bottom slots do not move at
any spacing.

### Vertical travel is 0.45 of horizontal, and nothing is clamped in the formula

A 456px dial holding five slots and a 104px clock has far less vertical slack
than horizontal. Matching the horizontal travel one-for-one spends the whole
slider against a limit, so the factor is under 1.

The first version also clamped the derived air to a fixed range inside
`verticalAir`. That was wrong for the reason this file keeps re-learning: a
clamp there is invisible, so the control stops responding at the ends of the
slider with nothing to explain it. Every limit now comes from the layout itself,
which is the only code that knows about the clock and the rim, and `effective()`
reports what was refused.

**Rejected: detecting refusal by comparing against the ideal arithmetic.** It
reports "adjusted" whenever a slot is clamped at all, which is most of the time
and true regardless of the spacing setting. The honest question is not "did it
land where the formula said" but "did moving this control move anything", so
`effective()` lays the face out twice — once with the air and once without — and
compares the travel. A slot that moved the full amount is not a refusal.

Tightening below Normal is genuinely refused for the bottom slot: at the default
layout it already sits at the minimum readable gap below the row, so there is
nothing to give. The readout says so rather than pretending.

## 2026-08-28 — Watch detection: the judgement is ours, the mechanism is not

The app could send a face to a watch without ever saying which watch, or whether
one was attached. "Save and Update Watch" was the first place anyone learned
nothing was connected — after a full aapt2 and apksigner build.

`WatchDevices` now reports what is attached, whether each thing can actually
take a pushed face, and why not when it cannot. Studio shows that BEFORE the
build, the install sheet offers a picker when more than one watch is eligible,
and the install targets the chosen serial rather than whatever adb saw first.

### The mechanism is a stand-in and is written to be replaced

On a real device this is the Wear Data Layer — `CapabilityClient` discovery over
Bluetooth. The workbench has no Bluetooth and no paired phone, so it asks `adb`.

Everything except the two `ProcessBuilder` calls is therefore pure and tested:
parsing the device list, reading properties into a `Device`, and judging
eligibility. When this is rebuilt on the Data Layer the discovery changes and the
JUDGEMENT does not — so the part carrying the rules survives the swap, and the
part that gets thrown away is the part with no tests.

### Wear OS 6 is stated as a floor, early

docs/SPEC.md has always said Watch Face Push is Wear OS 6+. Nothing enforced it.
An older watch now reports "Runs Wear OS 5. Sending faces needs Wear OS 6 or
newer" instead of failing at install, and a phone is never offered as a target.

### A parsing bug the test found

The first parser did `.drop(1)` to skip the `adb devices` banner. That looks
equivalent to skipping the header and is not: adb prints `* daemon not running`
and `* daemon started successfully` BEFORE the banner on a cold start, so
positional dropping leaves "List of devices attached" to be parsed as a device
named "List" in state "of". Rows are now recognised by their STATE.

### Verified against real hardware, unusually

Detection, the UI state, a targeted send and the resulting install were all
checked against a booting Wear OS 6 emulator rather than fixtures: it was seen
as `offline` while booting, then `device` with `isWatch=true`, `wearOs=6`,
`ready=true`; Studio read "Ready to send to sdk_gwear_x86_64 · Wear OS 6"; and a
Brushed Steel face built and installed, confirmed by `pm list packages` showing
`com.bfg.watchfaces.watchfacepush.brushed_steel`.

The failure paths were exercised on the way there and reported honestly — "could
not send to sdk_gwear_x86_64: ... device is still booting" — naming the device
and never claiming success.

### Deliberately NOT built

- **The activation prompt.** `SET_PUSHED_WATCH_FACE_AS_ACTIVE` cannot be
  re-requested after denial and `setWatchFaceAsActive()` may be called only once.
  The 2026-08-26 entry says the prompt placement must be settled on paper BEFORE
  the code around it exists, and that is still unresolved. Writing it now would
  be deciding it by accident.

  **Superseded 2026-08-28** — decided, and the rules built. See the entry at the
  top of this file.
- **Remaining slot count.** `WatchFacePushManager.listWatchFaces()` is a Wear
  API; adb cannot see it. It arrives with the real Data Layer implementation.

## 2026-08-28 — generatorVersion 4: generated surfaces, as a field not polylines

`GRAIN`, `BRUSHED`, `CARBON` and `LINEN`. They exist because of a gap with real
consequences: an IMPORTED image makes a face **local-only**, since the catalog is
parameters and a picture is not. Anyone wanting a textured dial had to give up
sharing it. A generated surface is parameters, so it can be published like any
other face.

### Why a scalar field rather than polylines

Every other engine emits `List<Polyline>` and the renderer strokes it. Grain
cannot be expressed that way honestly:

- it would take on the order of 100k strokes to read as grain at 456px, each
  stroked three times for the emboss — blowing the 400k point budget the other
  engines are held to, and making the preview crawl
- stroked lines produce **hatching, not isotropic noise**; the difference is
  visible immediately
- the three-pass emboss is meaningful for a cut line and meaningless for a field

So a surface engine emits no geometry — exactly as `TEXTURE` already did — and
`TextureField` supplies a height field the renderer shades. That precedent
existed, which is what made this the smaller change rather than a new concept.

### Why a field rather than a BufferedImage

`:generator` is deliberately free of Canvas, Graphics2D and Android; that is what
lets it be tested in CI without rendering anything. Returning an image would have
broken that for one caller's convenience. A pure `(x, y) -> Double` keeps
determinism directly testable and leaves `DialRenderer` the only rasterizer.

The renderer builds the field into an array ONCE and takes the surface normal
from neighbouring cells. Re-sampling for each gradient would be four extra fBm
evaluations per pixel — about five times the work for the same picture, and a
preview that stutters under a moving slider.

### Determinism, and why not a seeded Random

Integer hashing throughout. A seeded RNG is reproducible only as long as nobody
changes the call order; a hash of the coordinates is reproducible because it does
not depend on being called in any particular sequence. A stored face must
re-render identically on someone else's device years later.

### The quantization warning was right to make and did not bite

The task said noise quantizes badly and to measure rather than assume. Measured
at 64 colours: **GRAIN 0.28, BRUSHED 0.22, CARBON 0.86, LINEN 0.54** per 255 —
all far inside budget.

The reason is worth recording so nobody re-derives the fear: these are
low-contrast variations around a SINGLE dial colour, so the palette only has to
cover a narrow band rather than a full gamut. Noise over a photographic gamut
would have banded; noise over one hue does not.

They are heavier on the wire though — **112–132KB quantized against ~77KB for the
stroked engines** — because there are more distinct tones per pixel. Still an
acceptable Bluetooth transfer, and now pinned by a test at 200KB so a change that
doubles it fails rather than surprising someone on a slow link.

### Carbon needed a second pass

The first attempt read as **diamond plate**: the tows were too coarse and too
high-contrast, and the hard block boundary drew a grid the eye locked onto.
Finer tows, a softened seam and roughly half the amplitude turned it into
something recognisable as twill. Recorded because the fix was aesthetic
judgement, not a bug, and the numbers alone would not have found it.

## 2026-08-28 — The About screen is a promotion, and diverges from the site

The About tab is the only promotion in an app with no ads, no account and no
paid tier, so it leads with that rather than with a product list: a FREE badge,
"Every part of this app is free", and the attribution to BFG Solutions. The
products come second, because the claim is the point of the screen.

Visually it moved from five bordered cards to an editorial list — logo, name,
one line, platforms, hairline rule. Five boxed cards read as an advert, which is
the thing a free app's only promotion should least resemble.

### It is a curated list, not a mirror

The previous version copied bfgsolutions.net's ProductShelf and said so. It now
DELIBERATELY differs, on the operator's call:

- **Aria is omitted.** It is in beta, and this is a promotion rather than a
  directory.
- **Swarm comes before Shotcraft.**

Taglines are still the site's own words, so the site remains the source for
those: if a product ships or its description changes there, it changes here too.
The divergence is intentional and recorded so nobody "fixes" it back into a
mirror later.

### Logos are bundled, not fetched

Copied from bfg-solutions into the jar and served from `/logos/`. The app works
offline apart from the community catalog, and an About screen full of broken
images on a train is worse than no logos at all. The route accepts a bare
filename matching `^[a-z0-9_-]+\.(svg|png)$` and nothing else — the name arrives
in a URL, so anything path-like is refused rather than resolved.

### Found while looking at it

The schema-validity pill was rendering on every screen, including this one — it
was reporting "Valid" over a page of promotional copy. It describes the face
being edited, so it now appears only in Studio.

## 2026-08-28 — generatorVersion 3: ambient ink is lifted, not reused

Ambient is a black screen — the dial fades to alpha 0 and only text remains.
Nothing stopped someone choosing near-black ink (the palette offers `#1A1A1A`),
which looks deliberate on a pale dial and renders the time **invisible** the
moment the watch dims. Schema-valid, installs fine, unusable on the wrist. No
test or validator in the build could see it.

`AmbientPalette` keeps hue and saturation and raises only HSL lightness until
the colour clears **4.5:1 against black**. WCAG's large-text bar is 3:1, but
ambient is read at a glance, at an angle, often outdoors, on a panel the watch
has already dimmed, so the stricter floor is the honest one. Measured:
`#1A1A1A` becomes `#757575`, `#23306B` becomes `#5A6EC9` and is still navy,
`#FCF9F1` is returned unchanged.

Rejected: forcing white would work and would flatten a design dimension across
the whole catalog; warning the user would be honest and would still ship faces
whose time cannot be read.

### Why it is a version bump

The change alters what a STORED face renders as in ambient, which is precisely
what `generatorVersion` protects. v1 and v2 keep the old behaviour exactly — raw
ink at alpha 160, dark or not — and a test asserts a v2 face gains none of this.
`PatternEngines` v3 delegates wholesale to v2, with a test asserting every
engine's geometry is identical across the two, so the bump provably carries a
colour change and nothing else.

### The complication needed a mechanism the clock did not

The clock ships TWO `TimeText` elements, so its ambient colour can simply differ.
A complication has ONE `Font` colour for both modes, so the only way to vary it
is a colour `Variant`.

That this validates is not obvious — `Variant`'s value is
`arithmeticExpressionType`, which sounds numeric — so it was **tested against
Google's XSD rather than assumed**, and a test now asserts it. The variant is
emitted only when the ink actually needs lifting; emitting a no-op on every face
would be noise implying a change that is not happening.

**Runtime support is UNVERIFIED.** Schema-valid is not the same as honoured, and
no face from this repo has been confirmed on a watch yet. If the runtime ignores
an unknown Variant target this degrades to the previous behaviour rather than to
something worse — but it belongs on the list for the first hardware test,
alongside the carousel check that still gates everything.

### A side effect worth keeping

The catalog index recorded the generator version that BUILT it, so bumping the
app made every committed index stale — churn that says nothing. It now records
the highest version among the faces, which is what a client actually needs:
whether it is new enough to render everything in there.

## 2026-08-28 — An unanchored gitignore line published an index with no faces

Immediately after the catalog landed, CI went green having validated **nothing**.
The log read:

```text
catalog: catalog/faces
  no catalog directory yet -- nothing to validate
```

`.gitignore` carried `faces/` for the personal save directory. Unanchored, that
pattern matches a directory of that name **at any depth**, so it also matched
`catalog/faces/`. `git add catalog` therefore committed `catalog/index.json` —
which describes seven faces — and none of the faces. The pushed repo had an
index pointing at nothing.

Two things went wrong and both are worth keeping:

**The gitignore pattern.** Now `/faces/`, anchored to the repo root. This is a
general trap: `build/`, `faces/`, `textures/` and friends all match at any depth
unless anchored, and the failure is invisible because the file simply never
appears in `git status`.

**The gate treated absence as success.** "No catalog directory" exited 0, which
is right for a repo that has no catalog and wrong for one that does. Absence of
a signal was read as absence of a problem — the same defect class the CI standard
warns about, committed in the very check written to enforce that standard.

The fix is a consistency check rather than a louder message: `index.json`
declares a count, so if the index claims more faces than are present, the build
fails and names `.gitignore` as the likely cause. An index that disagrees with
the faces beside it is worse than no index.

Verified by reproducing it: moving `catalog/faces` aside makes the task exit 1
with "index.json describes 7 face(s) but only 0 are present", and restoring it
passes. That is the check the original push needed and did not have.

## 2026-08-28 — The catalog is real, and the gallery reads it

docs/SPEC.md described the community catalog as "later". It is built.

`catalog/faces/<slug>.json` plus a generated `catalog/index.json`, served in
production from jsDelivr. The index carries name, author, engine and the two
colours and nothing else, so **a gallery of a thousand faces is one request**.
Full parameters stay in the per-face files and are fetched only when someone
opens one — inlining them would make the index grow with the size of the
catalog rather than with its length.

### Validation is a build gate, because review cannot do this job

`./gradlew :workbench:catalog --args="--check"` runs in CI on every PR and fails
on a face that does not parse, does not render, emits schema-invalid WFF, has a
slug disagreeing with its name or filename, exceeds 8KB, or leaves `index.json`
stale.

That last one matters: a generated file that can drift from its source is a
generated file nobody trusts. `--check` rebuilds the index in memory and
compares, ignoring only the timestamp.

The reason this is a gate rather than a reviewer's checklist is the same reason
`WffSchemaTest` exists — **a schema-invalid face is invisible in a diff and
silent on the wrist.** It installs, reports success, and never appears in the
carousel. There is nothing for a human to notice.

Verified by injection rather than assertion: a TEXTURE face, a slug/name
mismatch, and a hand-staled index each fail the task, and it passes again once
reverted.

### Refusals are structural

TEXTURE faces are rejected automatically. Parametric-only is what keeps a face
~5KB (so 10,000 of them are ~50MB of Git and free to host) and it is the IP
shield: you cannot encode a copyrighted logo as "knotwork, scale 26, pewter",
but you can certainly upload one. The app says so where the image is chosen, and
the submit path refuses again and deletes the staged file rather than leaving an
invalid submission for someone to commit.

### The app stages; the human publishes

**Share** writes the file and validates it, then stops and prints the git
commands. It does not open a pull request. A design tool that pushes to a public
repo on a button press is a mistake waiting to happen, and the failure mode —
publishing something you did not mean to — is not one an undo button fixes.

### Removed a check that could never fire

The first version of the validator checked `generatorVersion` against what this
build supports. It was unreachable: `DialParams`' own constructor already
refuses an unknown version, so parsing throws first. Unreachable code that looks
like protection is worse than none, so it is gone and a comment says where the
guard actually lives. The test now writes raw JSON, which is the only way such a
face can reach us — from a newer client, in a pull request.

### Still open: where the catalog lives

It is in this repo. Before a public launch it should move to its own repository.
Strangers opening PRs against the app's source is a different risk profile from
strangers opening PRs against a folder of JSON, and the split is much cheaper
now than after the first outside contribution.

## 2026-08-28 — Complication spacing is a control, and clamping is visible

Size was selectable; spacing was buried in Fine tune. Both are now
Small/Medium/Large-style controls, with the slider still there for precise work.

The interesting part is what happens when the request cannot be honoured.
`SlotGeometry` clamps size and spacing against the rim, the clock and each
other, and a control whose value is silently overridden feels broken. So
`SlotGeometry.effective()` reports what was actually used, and the app says
"spacing adjusted to 100 — the dial ran out of room" instead of appearing inert.

The nearest preset keeps the highlight when the Fine tune slider lands between
two of them; leaving every button unlit reads as a bug.

## 2026-08-28 — SlotGeometry: one calculation, and the collisions it found

The five slots were cramped and overlapping. Measuring the defaults rather than
squinting at them found four separate faults:

- the three row boxes were 89 wide on an 86 spread — **overlapping by 3px**
- the bottom slot **overlapped the row by 14px**
- the top slot ran **28px into the clock**, and the row **25px** into it
- boxes were `size * 4.0` tall for `size * 3.15` of content — ~15px of dead
  space in every slot, which is what made five of them look crowded when they
  were merely mis-measured

None of it was caught because nothing asserted it. The tests checked that the
emitter and the preview AGREED on slot positions — and they agreed, on being
wrong. **A test that guards a copy-paste is the wrong shape.** The fix was to
delete the second copy.

`SlotGeometry` in `:generator` now computes every box once, and both the emitter
and the preview ask it. It sizes boxes to their content, widens the spread if
boxes would touch, narrows it if the outer ones would leave the circle, pushes
the bottom slot clear of the row, and keeps every CORNER inside the dial —
checked against the circle, not the bounding square, because the dial curves
away and a corner can escape while x and y both look fine.

### When it does not fit, it shrinks

Five slots plus a 104px clock genuinely runs out of room on a 456px dial above
size ~24. The first attempt clamped the bottom slot and pushed it off the edge
of the circle: a face that would have shipped with a complication in the bezel.

`boxes()` now steps the size down until the layout actually fits. Shrinking is
the only option that stays correct while keeping every slot the user switched
on — dropping one silently loses data they asked for, and overlapping is not a
layout. `fittedSize()` reports what was actually used.

### The clock band was measured from the wrong point

Even after all that, the row still touched the digits. The band was computed
around `timeY`, but the digits are centred inside the `DigitalClock` ELEMENT
BOX, which runs from `timeY - timeSize/2` for `timeSize * 1.4` — so its centre
sits `timeSize * 0.2` LOWER, 20px at the default size. Every slot was being
kept clear of a band 20px above where the numerals actually are.

This is the class of bug that only appears on the dial: schema-valid, no
overlap by the old measure, and completely wrong. `ClockBandTest` now derives
the band from the same box arithmetic the emitter uses and asserts the row
clears it at four clock sizes.

### Sizes are user-facing

Small / Medium / Large (16 / 19 / 23) in the Complications section, with slot
spacing, row position and the top and bottom anchors exposed in Fine tune for
precise work. Defaults retuned to the corrected geometry: `dateY` 118→99,
`complicationY` 286→273, `batteryY` 348→344, `complicationSpread` 86→92.

## 2026-08-28 — Five slots, provider icons, and an image engine

### Five positions, and the date and battery stop being special

The face had five information areas but only advertised three. The date line at
the top and the battery line at the bottom were hardcoded `PartText` elements:
not configurable, not removable, and not visible anywhere in the app as things a
user owned.

They are ordinary complication slots now — `SlotPosition` is TOP, LEFT, MIDDLE,
RIGHT, BOTTOM, and the enum's ORDER is the storage order, so it is append-only.
The row of three re-centres among itself; TOP and BOTTOM are centred singles.

TOP keeps a dim ambient variant (alpha 140) while the others go to 0. That is
not an arbitrary exception: it is the position that held the date, and the
ambient design recorded on 2026-08-26 deliberately keeps that one line readable
while everything else goes dark. Moving it to a slot should not have quietly
changed the ambient behaviour, so it did not.

`slotId`s are asserted unique and contiguous. Duplicates pass the schema and
then behave unpredictably on the watch, which is the worst place to find out.

### Icons

Complications render `[COMPLICATION.MONOCHROMATIC_IMAGE]` above the value, which
the WFF already emitted but nothing previewed. `ComplicationIcons` draws
stand-ins with the same silhouette and weight as the Wear/Material glyphs for
the same data, authored on a 24x24 grid so the weights stay consistent when
scaled.

They are stand-ins on purpose and never reach the APK. On the watch the SYSTEM
PROVIDER supplies the icon; nothing here is baked into `dial_bg.png`. Like the
sample values, their job is to answer "does the layout survive an icon this
size", which is the only question a preview can honestly answer about a provider
that is not running.

### Engine.TEXTURE — bring your own image

The original ask was to use the mockup PNG as the dial. The parametric KNOTWORK
engine answered the *design* question, but not the general one: people want
their own pictures on their watch.

`Engine.TEXTURE` emits **no geometry at all** — the dial is an imported image the
renderer centre-crops to fill the circle (cover, not contain: a dial with
letterboxing is not a watch face). Sheen and vignette still apply on top so a
photo sits on the same dial as the engines rather than looking pasted on, and
`contrast` fades it toward the dial colour, because pushing artwork back far
enough for the time to stay readable is the thing most photos get wrong on a
watch.

Images are content-addressed under `textures/<sha1>.png`, re-encoded on import
rather than stored verbatim — that proves the bytes decode before they can reach
the renderer, and strips whatever metadata the original carried. The face stores
only the hash. Ids are validated against `^[0-9a-f]{40}$` before touching the
filesystem, since they arrive from a query string on a tool that binds a port.

**A TEXTURE face is local-only, and that is structural rather than a policy we
could relax.** docs/SPEC.md's catalog is parametric-only for two reasons that
both still hold: a face has to stay a few KB of JSON, and parameters are the IP
shield — you cannot encode a copyrighted logo as "knotwork, scale 26, pewter",
but you can certainly upload one. The SPEC already carved out exactly this case:
photos are imported locally and never enter the shared catalog.
`DialParams.isLocalOnly` makes it checkable, and the app says so where the image
is chosen rather than at submission time.

TEXTURE is excluded from the geometry-coverage and point-budget tests alongside
NONE. "Produces polylines covering the dial" is not a property a raster engine
has, and asserting it would have meant weakening a real test for every other
engine.

## 2026-08-27 — Complications, and two ways to finish a face

### Complications are configurable, and the preview tells the truth about them

`WffEmitter` hardcoded three slots with fixed providers. They are now a stored
parameter: `DialParams.complications`, a list of `ComplicationSource`.

The provider tokens are **exactly** the schema's `defaultProviderListType`
enumeration, read out of the XSD rather than remembered — an invented token is
not a runtime error, it is a face that installs cleanly and never appears. All
thirteen are covered by a parameterized schema test.

Two behaviours worth stating because they are not obvious from the code:

- **A slot set to Off is not emitted at all**, rather than emitted empty. An
  empty slot still costs a tap target and frame budget on the watch.
- **The remaining slots re-centre.** Turning off the left slot must not leave a
  hole with two slots hanging right. `FacePreview` uses the *same* arithmetic as
  the emitter, and a test asserts a lone slot lands exactly where the middle of
  three did — if those two drift, the preview stops being evidence about layout,
  which is most of what it is for.

The preview draws each slot's selected source with a sample value of
representative WIDTH. That is the only honest thing a preview can say here: on
the watch a real provider fills the slot, and the question a designer needs
answered is whether the layout survives a value about that size.

`strings.xml` is generated with a `slot_<source>` entry for every active slot,
because the emitter references them by name and aapt2 link fails outright on an
unresolved `@string`. Presentation (labels, samples) lives in `:workbench`, not
`:generator`: the generator defines the stored format, and sample strings have
no business being versioned into it.

### The OS colour picker had to go

`<input type="color">` delegates to the operating system's own dialog — on
Windows a desktop colour chooser that cannot be styled, does not look like the
app, and breaks the illusion completely. Replaced with an in-app
saturation/brightness pad plus hue slider and a hex field.

Drawing gradients for the picker is not a breach of the one-rasterizer rule.
That rule is about watch-face PIXELS: no dial, no pattern, no preview is drawn
in the browser. Picker chrome is chrome.

### Save to Gallery, or Save and Update Watch

One button was doing two jobs badly. Saving a design and putting it on a wrist
are different intentions with very different costs — one writes 700 bytes, the
other runs aapt2, apksigner and adb.

The install path reports the build and the install **separately**, and an
`adb install` Success is deliberately NOT reported as "it is on your watch".
That is the oldest trap in this repo: a schema-invalid face installs cleanly and
never appears. With no watch attached it says so plainly and prints the command
to run later, rather than failing as though the face were broken.

## 2026-08-27 — KNOTWORK engine, and retiring the default face

### The mockup texture, generated instead of traced

The original ChatGPT mockup came back as the texture to use. The 2026-08-26
analysis still stands — it is an aperiodic tangle, ~6× over the noise floor,
untraceable and untileable — but it also contained the answer: "closer to Celtic
knotwork than engine-turning." That is a description of what to build.

`Engine.KNOTWORK` is a **Truchet tiling**: every lattice cell carries one of two
quarter-arc pairs, so the strapwork wanders and never visibly repeats, while the
grid keeps it regular enough to read as engine-turning rather than noise. A
diagonal cross-hatch runs underneath, because without it the tiling reads as
bubbles. Each arc is a PAIR of concentric edges, so the three-pass relief lifts a
ribbon with a groove rather than a wire.

The tile choice is a **hash of the cell coordinates, never a Random**: stored
faces are parameters and must re-render identically on someone else's phone
years later. `freq` seeds the hash, so it selects between whole arrangements
instead of counting waves.

Why not just ship the PNG, which is what was asked: baked lighting and paper
grain that cannot respond to the ambient variant; a colourway frozen forever
against a `dialColor` parameter that exists precisely to vary it; grain that
quantizes badly against a Bluetooth budget where the generated dials hit
0.51/255 at 64 colours; and it could never enter the catalog, where
parametric-only is the IP shield. Personal raster import stays supported by
docs/SPEC.md — it just cannot be the shipped default.

### generatorVersion 1 → 2, and how the bump was made safe

Adding an engine is exactly what the version is for. `v2` **delegates** to `v1`
for every pre-existing engine rather than copying their bodies — copying is how
geometry drifts, because one copy eventually gets a "small fix" and every face
pinned to v1 silently re-renders. `GeneratorVersionCompatibilityTest` asserts
v1 and v2 produce identical output for all seven original engines, and that
KNOTWORK is *rejected* at v1 rather than silently substituted.

### "Silver Sand" is gone

Retired on the operator's call, and the reasoning finishes what 2026-08-26
started. That entry kept Silver Sand as the name of the default face, having
removed it as the name of the app. The remaining half was the same category
error one level down: **a product like this has no default face.** It has
starting points, and faces that people name themselves.

So there is no hardcoded face identity anywhere. A saved name becomes the
carousel label (`strings.xml`), the Watch Face Push package
(`<app>.watchfacepush.<slug>`, rewritten into the manifest before aapt2 runs
since only `pack` can vary it afterwards) and the APK filename. `build.sh` takes
`FACE_SLUG` with a neutral default.

Slugs are ASCII-only on purpose. `Char.isLetterOrDigit()` is true for most of
Unicode, so "Café Crème" would pass a naive slugify and be rejected by Push at
install time — far too late. Caught by a test, not on a wrist.

### The workbench became the app

`faces/<slug>.json` is the docs/SPEC.md catalog format, written by the Save
button. Saving a design in the app and preparing a catalog submission are now
the same artefact; there is no second format to keep in sync and no export step
left to build. The JSON reader is hand-rolled for the same reason `:generator`
has no dependencies — when the catalog becomes real, that parser is what gets
replaced with a schema-validated reader in `:generator`.

The page is now a three-tab phone app — Designs, Studio, My faces — rather than
a slider panel. The rule that survived the rewrite: **the browser still never
draws the pattern.** Every dial on screen, including every gallery thumbnail, is
a PNG from the same `DialRenderer.render` call that bakes the shipped file.

## 2026-08-27 — Wear emulator: API 36, and what an install actually proves

`scripts/setup-emulators.sh` pinned `system-images;android-34;android-wear`.
That is **Wear OS 5**, and Watch Face Push is Wear OS 6+. A WFF face sideloads
and appears in the carousel there, so it was fine for step one, but
`WatchFacePushManager` does not exist on it — the emulator was silently capped
below the product, and the one feature the architecture depends on could never
have been exercised on it.

Now `system-images;android-36;android-wear-signed;x86_64`, which `avdmanager`
reports as **Wear OS 6.0**. `-signed` is the variant carrying Play services.
`WEAR_IMG` is overridable for testing the sideload path on an older platform.

The SPEC's caveat that "the emulator cannot exercise Watch Face Push end to end"
was written against the API 34 image and should be re-tested on API 36 before it
is trusted. It has not been.

### Measured: the APK installs on Wear OS 6

`adb install -r` returned **Success** against the API 36 emulator, and
`pm list packages` confirms `com.bfg.watchfaces.watchfacepush.silver_sand`
present, signed, minSdk 33. First time anything from this repo has been on a
Wear OS device.

**That is NOT the same as appearing in the carousel, and this repo has said so
from the beginning.** A schema-invalid face installs, reports Success, and never
appears. The install therefore proves packaging, signing and the Push path
allowlist — nothing about whether the face renders. Confirming the carousel
needs the watch face picker, and the emulator never got there: it sits in the
Wear setup wizard, and `device_provisioned` / `user_setup_complete` do not move
it past that.

### Blocked on KVM, which is why the above stops where it does

The emulator ran under pure software emulation (`/dev/kvm` is
`root:kvm 0660`, and the developer account is not in the `kvm` group).
Measured cost: **~5 min to adb-online, ~16 min to zygote, ~4 min for a single
`adb install`.** Completing a setup wizard at that speed is not a reasonable
verification path.

The fix is one line and needs root, so it is recorded here rather than applied:

```bash
sudo usermod -aG kvm "$USER"    # then log out and back in, or: newgrp kvm
```

With KVM the same image boots in well under a minute. Nothing else about the
pipeline is waiting on anything.

## 2026-08-27 — The workbench: a localhost design loop, and one rasterizer

`build.sh` referred to "the workbench" and `.gitignore` referred to "the
workbench or the generator", but no such thing existed. The gap it left was
structural, not cosmetic: **nothing in the repo could produce `dial_bg.png`.**
`:generator` emitted polylines and XML and stopped there. So the build that
`CLAUDE.md` listed as verified could not actually be reproduced from a clean
checkout — the artwork had been made somewhere else, correctly gitignored as
generated output, and the thing that generated it was never committed.

`preview.png` was missing the same way, and worse: `build.sh` did not check for
it, so the failure surfaced as an aapt2 link error about an unresolved
`@drawable/preview` rather than as the missing-artwork problem it was.

**Added `:workbench`** — pure JVM, depends on `:generator`, never the reverse:

- `DialRenderer` — the rasterizer. Strokes engine polylines three times for the
  engraved look, exactly as `CLAUDE.md` specifies, and keeps that in the
  renderer rather than the engines.
- `Quantizer` — median-cut to ≤64 colours, as `docs/SPEC.md` requires on every
  dial. Measured here: mean error 0.51/255, and the dial lands at 77,271 bytes
  in the APK against the spec's measured ~77KB.
- `FacePreview` — composites the dial with the text layers, interactive and
  ambient, mirroring `WffEmitter`'s arithmetic.
- An HTTP server on `localhost:7777` and a headless `bake` task.

### The browser does not draw the pattern, and that was the point

The obvious build is a JS canvas that redraws the guilloché client-side. It
would have been faster to write and much faster to drag a slider against. It was
rejected: `docs/SPEC.md` commits to one geometry implementation and to "what the
user sees is what ships, by construction," and a JS reimplementation is a second
renderer that starts identical and drifts. Every pixel in the browser is a PNG
produced by the same `DialRenderer.render` call that bakes the shipped file, so
the preview cannot disagree with the artefact. The cost is a round trip per
edit, which on loopback is imperceptible.

For the same reason the server and the `bake` CLI both call
`Workbench.exportTo`. There is no fast path that can emit different bytes than
the UI showed.

### Live schema validation is the feature that matters

A schema-invalid face compiles, links, signs, installs, reports Success, and
then never appears in the carousel with no runtime error anywhere. That failure
used to cost a full build-sign-sideload-squint cycle to notice. The workbench
runs the same XSD 1.1 validation `WffSchemaTest` runs, against Google's schema,
on every parameter change, and shows it as a banner. `bake` refuses to write a
schema-invalid face at all.

### Open: this is now a second rasterizer, by the SPEC's reckoning

`docs/SPEC.md` planned for `:phone`'s Android `Canvas` to drive both the live
preview and the baked PNG. `DialRenderer` is AWT `Graphics2D`, so when `:phone`
is built there will be two rasterizers unless they are deliberately unified —
most cleanly by extracting a small drawing interface into `:generator` that AWT
and Android `Canvas` both implement, leaving stroke order and compositing
defined once. **Not done now**, because writing that abstraction before the
Android side exists would be guessing at its shape. Recording it as the known
cost of unblocking hardware testing today.

### Found while building: `lens` and `lensAmount` are not in the file format

`DialParams.lens` documents itself as drawing the pattern *over* the numerals.
`WffEmitter` never reads either field. It cannot: in WFF the dial `<PartImage>`
sits below the `<DigitalClock>` in the scene, so a texture baked into
`dial_bg.png` is always behind the time.

It *is* expressible — a second transparent `<PartImage>` emitted after the clock
would do it — but that changes what a stored parameter set renders as, for every
existing face, which is exactly what `generatorVersion` exists to prevent. So
nothing was changed. `DialRenderer` implements the shippable half (a localised
lift in relief and brightness under the time, which reads as a lens), and the
decision about the other half is left open deliberately. Two parameters
currently affect the preview and the bake but not the emitted WFF.

## 2026-08-26 — Renamed from "Silver Sand" to "BFG Watch Faces"

The project inherited its name from the mockup that started it: her ChatGPT
image was headed "SILVER SAND • SUBTLE PATTERNS • TIMELESS STYLE", and that got
picked up as a working name and never revisited.

Wrong scope. "Silver Sand" names **one dial** — a warm taupe colourway with a
fixed palette. The project is a generator producing botanical, clous, rosette,
barleycorn and sunburst patterns in arbitrary colours. Naming the app after a
single preset is naming Photoshop "Blue Gradient".

Renamed while it was still free to do so. **`applicationId` can never be changed
after a Play release** — a new one is a new app with zero installs and zero
reviews — and Watch Face Push derives every pushed face's package from it, so it
is structural, not cosmetic. Eight files now; permanent later.

- App / Play listing: **BFG Watch Faces**
- `applicationId`: `com.bfg.watchfaces`
- Kotlin package: `com.bfg.watchfaces.generator`
- Pushed faces: `com.bfg.watchfaces.watchfacepush.<slug>`

**Silver Sand survives as the name of the default face**, which is what it was
always good at naming. It ships as the botanical preset and as the reference
face in `watchface-template/`.

(Orthography: "watch face" is two words in prose — Google is consistent about
this across Watch Face Format and Watch Face Push. The single-word form only
appears in code identifiers, where it is a compounding convention rather than
English.)

## 2026-08-26 — Initial architecture: parameters as the file format

The project started as "turn my wife's ChatGPT watch face mockups into something
she can wear." The mockups turned out to be the least useful part, and what
replaced them set the shape of everything else.

- **The mock's texture had no structure, and measuring it proved it.** At full
  resolution the "guilloché" is an aperiodic tangle — closer to Celtic knotwork
  than engine-turning, with a different random scribble in every lattice cell.
  A Fourier analysis put the dominant spatial frequency only ~6× above the noise
  floor; a generated clous pattern scores 12–26. It could not be traced, tiled,
  or cleaned up.
  - Two facts from that analysis survive and are encoded in the presets: the
    texture repeats on roughly a **30px cell at 456px**, and **A/B/C differ only
    in contrast, not scale**, despite variant C being labelled "larger pattern."
    The AI never changed the pattern size on the dials, only in the close-up crops.
  - This is why the engines are parametric rather than asset-based. It was not a
    purity preference — there was nothing to trace.

- **Faces are stored as parameters, not images. This is the load-bearing
  decision.** A face is ~5KB of JSON that the phone re-renders locally. A
  10,000-face catalog is ~50MB of Git, which is why the whole thing can be free
  to host. Three consequences, all of which constrain the code:
  - **The generator IS the file format.** `DialParams` + `PatternEngines`
    together define what a stored face means. Changing an engine's geometry
    silently rewrites every community face that was pinned to it — the author's
    face renders differently than they saw it, with no error. Hence
    `generatorVersion`, hence the branch in `PatternEngines.paths()`, hence
    `GeneratorVersionTest` failing loudly on a bump.
  - **Parametric-only submissions are the IP shield.** A community catalog
    attracts copyrighted logos and characters; hosting those under your own name
    is real exposure. You cannot encode a Disney character as "clous engine,
    scale 34, pewter." Users import photos locally; those never enter the
    catalog. The constraint that makes it cheap to host is the same one that
    makes it safe to host.
  - **Submissions validate in CI without a human.** Parameters can be
    deserialized, emitted to WFF, and schema-checked by a GitHub Action. Invalid
    faces are rejected before anyone looks.

- **`:generator` has no Android dependency, deliberately.** It is the module
  under most active development, and native's real cost is the deploy-and-squint
  feedback loop. Pure JVM means 35 tests in ~15 seconds on a laptop with no SDK
  and no emulator. Engines emit `List<Polyline>` in 456×456 dial space; platform
  code strokes them. One geometry implementation, and on Android the same
  function drives the live preview *and* bakes the shipped PNG — so what the
  user sees is what ships, by construction, rather than by discipline.

- **The watch face APK is built with aapt2 directly, never Gradle.** AGP injects
  `kotlin/` and `DebugProbesKt.bin` into the APK. Google Play accepts those;
  **Watch Face Push rejects them** — it enforces a strict allowlist of
  `/AndroidManifest.xml`, `/resources.arsc`, `/res/**`, `/META-INF/**` and
  nothing else. `watchface-template/build.sh` produces exactly those four paths
  and asserts it after signing. Do not "modernize" this to Gradle.

- **Schema validation is a build gate, not a nicety.** A schema-invalid WFF face
  compiles, links, signs, installs, and reports Success — then never appears in
  the watch face carousel, with no error in logcat. There is nothing to catch at
  runtime. Four real errors were found this way during the first build:
  `DefaultProviderPolicy` wants `defaultSystemProvider`/`defaultSystemProviderType`
  (not `systemProvider`/`defaultType`), and `BoundingArc` wants `centerX`/`centerY`
  where rectangular slots need `BoundingBox`. Every one of them would have
  shipped silently.
  - Validation needs **Xerces**, not the JDK's validator: the WFF schemas are
    XSD 1.1 and the built-in one only does 1.0. Maven's `xercesImpl` alone is
    *also* insufficient — it lacks the XPath2 processor that 1.1 assertions
    require, which cost a round of red tests. The four jars Google ships in
    `google/watchface` are the working set, and the factory must be named
    explicitly because JAXP service discovery fails with an opaque
    `IllegalArgumentException`.

- **Ambient is per-element `<Variant>`, not a second scene.** An earlier read of
  this was wrong and is worth recording as wrong: the assumption was that a
  separate ambient scene had to be maintained alongside the interactive one. WFF
  handles it with `<Variant mode="AMBIENT" target="alpha" .../>` on individual
  elements — each carries its interactive value as an attribute and its ambient
  value as a child. Cleaner, and one scene to keep honest instead of two.
  - The ambient design is deliberately *not* the interactive one dimmed. The dial
    image fades to alpha 0 entirely, because a full mid-tone dial is the most
    expensive thing you can leave lit on an OLED panel. Measured: the interactive
    face averages ~45% lit pixels; ambient lands at ~2% against Wear OS's ~15%
    ceiling.

- **Third-party artifacts are fetched, never committed.** `scripts/bootstrap.sh`
  clones `google/watchface` for the schemas and jars. They are Google's and
  Apache's to distribute, not ours, and a pinned copy silently goes stale — you
  end up validating against last year's spec and believing you are covered.
  `dial_bg.png` is likewise absent: it is generated output, not source, in a repo
  where the parameters are the real artifact.

- **No backend, and that is a supported path rather than a hack.** Watch Face
  Push (Wear OS 6+) installs faces at runtime, `google/pack` compiles and signs
  APKs on-device with no JDK or Android SDK, and the validator ships as an
  Android library that issues tokens locally. `reskin.sh` demonstrates the same
  idea in shell: `resources.arsc` stores resource *paths*, not bytes, so the dial
  PNG and `res/raw/watchface.xml` can be swapped in a built APK and re-signed.
  `pack` supersedes that trick in one respect — it can vary the package name,
  which the swap cannot, and Push requires `<app>.watchfacepush.<slug>`.

- **Quantize the dial PNG before packing, always.** The APK crosses to the watch
  over Bluetooth. Measured: 368KB → 77KB at 64 colours, mean pixel error
  0.66/255, visually identical on a soft low-contrast dial. The in-memory
  footprint is unchanged at 456×456×4 = 831KB — quantization buys transfer time,
  not memory budget.

### Open and deliberately unresolved

- **`SET_PUSHED_WATCH_FACE_AS_ACTIVE` is one-shot and unrecoverable.** It cannot
  be re-requested after denial, and `setWatchFaceAsActive()` may only be called
  once regardless. It is the only irreversible action in the system. The prompt
  placement is a design problem and should be settled on paper before the code
  around it exists. Nothing has been decided here yet.

  **Superseded 2026-08-28** — settled by operator decision
  `01a0495b-dc98-76d2-9e80-92aff51cdec6`. See the entry at the top of this file.

- **Market is Wear OS 6+ only** — Pixel Watch 4 and 5, recent Galaxy Watch.
  Pixel Watch 1–3 cannot run this at all. Small today, growing. Accepted.

- **Nothing has been tested on hardware.** Every claim above is verified against
  schemas, dumps, and unit tests. No face from this repo has been confirmed to
  appear on a real watch. That is step one and it gates everything else.
