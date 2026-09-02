# DECISIONS.md — BFG Watch Faces

## 2026-09-02 — Hands: analog faces exist, and the layout does not know yet

`ClockMode` (v12) makes a face analog, `WffEmitter` writes `AnalogClock`, both
renderers cut the hands, and Studio can switch a face over. Steps 1 to 4 of
`docs/specs/analog-hands.md`, plus the picker out of order so the feature is
reachable rather than provable.

### What the format decided

`clock/hourHand.xsd` requires a `resource` attribute and permits no `PartDraw`
child. A hand cannot be geometry in the definition — it is a PNG. So the shape
lives in `Hands`, the renderers cut it, and the emitter only says where the
pictures go.

### The gate, and why it needs more than validation

A schema-invalid face installs, signs and silently never appears. But validation
alone is not enough here: **every child of `AnalogClock` is `minOccurs="0"`**, so
an `AnalogClock` containing no hands at all is perfectly valid XML. It would
install and render a bare dial — indistinguishable from a face that failed to
load. So four tests assert the hands are actually present and in schema order,
alongside the schema run over every style with seconds on and off.

### Two mistakes worth keeping

**RGB_565 would have shipped a black square.** `dial_bg.png` is squeezed to 565
before packing, and applying that to a hand would have been the obvious
consistency — but 565 has no alpha channel, and a hand canvas is almost entirely
transparent. The result would be an opaque black square rotating over the face.
Hands pack as ARGB_8888.

**A test regex failed on correct XML.** Its own quoting was ambiguous inside a
Kotlin raw string. It counts occurrences now. Second time this week a test has
been wrong about correct output, and both times the tell was the same: the
assertion was harder to read than the thing it was checking.

### What is deliberately still wrong

**Complications sit in the digital stack, under the sweeping hands.** The
preview shows the minute hand crossing "8,412". `SlotGeometry` has no analog
branch yet — that is step 5, and the interview named it as the real work of this
feature, more than the hands themselves.

Shipping it visibly wrong beats shipping it invisible: the last two hand
releases changed nothing the operator could see, and the second one drew that
complaint. A face that is obviously half-finished can be judged; one that looks
identical cannot.

### Two styles, not four

`DAUPHINE` and `SYRINGE` throw rather than defaulting to a baton, and
`Presentation.OFFERED_HANDS` lists only what is drawn. `OneVocabularyTest` now
asserts every style is either offered or explicitly withheld, and that every
offered one can actually be drawn — otherwise the picker puts a crash behind a
button.

## 2026-09-02 — Two reports, and only one of them was a regression

Both arrived together after re-saving a face at v11: **"the spacing option no
longer works and the UI has all three selected"**, and **"weather and conditions
now just shows weather by itself"**. It was tempting to treat them as one thing
the version bump had broken. They are not related, and one of them is years
older than the other.

### The spacing control — NOT a v11 change

Measured at both versions before touching anything:

```text
size=26 v10 spreadRange=111..128   size=26 v11 spreadRange=111..128
size=31 v10 spreadRange=121..121   size=31 v11 spreadRange=121..121
size=36 v10 spreadRange=121..121   size=36 v11 spreadRange=121..121
```

Identical. At complication size 31 and above the range genuinely collapses:
the complications fill the row and there is nowhere to spread them. The geometry
was right the whole time.

The fault was `spreadOptions` returning `listOf(first, mid, last)`
unconditionally, so the UI was handed `[121, 121, 121]`, zipped it to
Tight/Normal/Wide, and every one of them matched the current value — three
buttons, all drawn as selected, none of them able to change anything.

`.distinct()` now, and where that leaves fewer than two the Studio drops the
button row and says why: *"Your complications are too big to space apart. Make
them smaller to get the spacing back."* The cause is named, and the control that
fixes it is the one directly above.

This was found only because the report was checked against v10 instead of
assumed to be the new thing. The re-save was a coincidence of timing.

### The dropped condition — this one was v11's doing

v11 measures a weather value honestly at seventeen characters. In the top slot
that no longer clears `LEGIBLE_SHRINK`, so `drawnText` did what it is designed to
do and dropped the word: "4° Partly cloudy" became "4°".

The 0.85 floor is about COMPARISON — three slots side by side, one at 56% of the
others reads as broken, which is what came back from a wrist as unreadable. TOP
and BOTTOM sit alone on their rows. There is no neighbour to be out-shouted by,
so the comparison that floor protects does not exist there, and holding them to
it deletes a word for a benefit nobody can see.

The floor is now 0.70 for a slot alone on its row. Measured across sizes, the
full wording survives at 100%, 96%, 79% and 79% of base — legible on its own
line, and the whole reading is there.

### What ties them together

Neither was a geometry error. Both were a control or a rule applying a
comparison that did not exist in the case at hand — three choices where there
was one, and a side-by-side legibility floor on a slot with nothing beside it.

## 2026-09-02 — Saving is what moves a face forward

v11 widened the top complication box because a long weather value was clipped
("4° Partly cloud"). The fix could not reach the face the operator was actually
wearing: it had been saved at v10, so every send rebuilt it at v10 and
reproduced the bug faithfully. Verified by pulling the installed APK off the
watch —

```text
Generated by the BFG Watch Faces generator, v10
<PartText x="132" y="50" width="192" ...>
```

Short of building the design again from scratch there was no way forward. A fix
nobody can receive is not a fix.

### The rule

**Saving stamps the current version. Reading, sending and installing do not.**

Freezing a stored face is right for one you installed from the gallery — it must
not change under you — and wrong for your own work, where it locks you out of
every later fix. Saving is the deliberate act that separates the two: it is you
saying *this is my design*, and it takes the current version with it.

The guarantee that actually earns its keep survives: a face you did not make
does not change on its own. What goes is the part that was only ever collateral.

### On the ceremony that preceded this

v11 was gated carefully — `widestValueFor(version)`, a guarded widening, and a
test walking every source in every slot to prove v10 boxes had not moved. None
of that was wrong, but the operator's note is worth recording: nothing has been
released, so there were no faces in the world to protect. The gate was built to
a standard the project has not reached yet, and the cost was a day-old bug
staying on a wrist.

The versioning machinery stays; it will matter the moment faces are public. The
lesson is narrower: **know which of your guarantees are load-bearing today**,
and do not pay for the others with a fix somebody is waiting on.

## 2026-09-01 — v11: the top slot takes the room the dial already had

Reported from a wrist, with a photo: the weather at the top read
**"4° Partly cloud"**. The last letter was gone.

### Measured before touching anything

```text
TOP box=(x=165 y=76 w=126 h=47)  base font=21
chord available at that height   = 339px
"4° Partly cloudy" at 21pt needs = 208px
```

The box was using **126 of 339 available pixels** — 37% of the room the circle
offers at that height — while the value needed 208. So it was drawn at full size
into a box a third too small and clipped.

Two separate errors, both of them under-measurement:

- **`widestValue` was 10**, taken from "72° Cloudy". Real
  `[WEATHER.CONDITION_NAME]` values are longer: "Partly cloudy" is thirteen on
  its own, seventeen with a temperature in front. The fitting logic was sound
  and was being fed a lie, so it concluded the string fit at 20pt.
- **The box was 1.7x a row slot**, a guess at "wider than a third" made when the
  top slot stopped being held to the middle row's width. Nobody checked it
  against the chord.

Under-measuring is the worst of the three possible errors. Too wide only wastes
space; too narrow silently removes a letter, and **a reader cannot tell a
clipped word from a short one** — which is why this shipped and was only caught
by someone looking at their own wrist.

### The fix is room, not shrinking

The operator asked whether it could scale in the space. It turns out it does not
have to: given the room that was already there, the full wording renders at 21pt
— the same size as its neighbours. The box now widens to what its own value
needs, capped by the chord at 92% so text never touches the rim.

Demand-driven rather than simply a bigger constant, because the old cap existed
for a reason recorded at the time: *a slot that ran the width of the dial would
stop reading as one of a set.* A short value keeps the old width; only a long
one takes the room. Measured: 126px to 222px for weather, unchanged for
everything else.

`drawnText` still shortens and then shrinks. That fallback is untouched — this
widens the box before any of it is needed.

### Gated at v11, and what that costs

Both changes move text on faces people already have, so `widestValueFor(version)`
and the widening are gated, and a test walks every source in every slot asserting
v10 boxes are identical. `PatternEngines` gains `11 -> v4(p)`: no engine changed.

The version bump also stales `params-contract.json`, which the catalog Worker
bundles at build time and uses to reject faces above `currentGeneratorVersion`.
Regenerated here; **the Worker must be redeployed or every v11 face is refused
on submit.** This is the second time a version bump has carried that
consequence, and it is now the second entry saying so.

## 2026-09-01 — The phone opens the watch app, because the watch cannot ask

A fresh install could not ask for activation AT ALL, and nothing anywhere said
so. The chain dead-ends in one line from the watch's own log:

```text
W/BfgActivationPrompt: notifications are not enabled;
                       the activation ask cannot be shown
```

`SET_PUSHED_WATCH_FACE_AS_ACTIVE` can only be requested by the app on the watch.
The way that request reaches a wearer is a notification. A fresh install holds
no notification permission. So the first face installed, the send reported
success, the watch went on showing something else, and there was **no prompt, no
error, and nothing on either device suggesting an action**.

The only way through was to know, unprompted, to open an app on a watch. That is
not a thing to expect of anybody, and it is why this only ever worked for
installs that had been hand-driven over adb.

### The phone is the right side to fix it from

It is where the person already is — they just pressed Send — and it is told what
it needs to know, because the watch reports its consent state back on the reply.
So `WatchLink.Report.needsActivation` decides, and the phone uses
`RemoteActivityHelper` to open `bfgwatchfaces://setup` on the watch.
`WatchActivity` then requests notifications and offers the activation route, as
it already did for anyone who found it by hand.

`needsActivation` is deliberately two conditions, both required. A face already
on the wrist needs nothing. And a wearer who said **no** must never be asked
again — Android will not carry a second request, and re-opening a refusal is
exactly how the one shot gets spent on somebody who already answered. A test
pins all four cases.

### It fails quietly, on purpose

This is a convenience over a path that still works by hand. Out of range, or a
refused launch, must never turn a successful send into a reported failure — a
mistake this project has already made in both directions.

### The one sentence added

"Your watch is asking permission to switch to it." That is what is HAPPENING,
not a chore being delegated: a dialog has just appeared on their wrist and this
is what makes it make sense. It is shown only when the launch actually
succeeded, which is the difference between this and the instruction text that
was rightly rejected twice.

## 2026-09-01 — Spending the activation on sends that did not need it

With the watch on adb for the first time — Wi-Fi debugging, once the operator
was home — the watch's own log answered in one send what the reply line could
not:

```text
Caused by: WatchFacePushManager$SetActiveException:
    Failed to set watch face as active with error code 2
...
I/BfgFaceReceiver: face installed in slot 55c1e952-... (replaced=true)
I/BfgFaceReceiver: free=0 ours=1 pkgs=[...default] apk=512630 push=true activate=true
```

Code 2 was read out of `wear-sdk.jar`'s constant pool rather than assumed:

```text
SET_ACTIVE_UNKNOWN_ERROR              = 1
SET_ACTIVE_MAXIMUM_ATTEMPTS_REACHED_ERROR = 2   <- this one
SET_ACTIVE_INVALID_SLOT_ID_ERROR      = 3
```

### The bug

`setWatchFaceAsActive` has a hard attempt limit per app install, and
`onFaceInstalled` called it on EVERY send — including the overwhelming majority
where our face was already on the wrist and the call could not change anything.

A scarce, non-renewable resource was spent once per send until it ran out. After
that no face this install pushed could be switched on again. The face installed
correctly every time, the send reported success, and the watch stayed on a
Google face. From the wrist: **"I send it from my phone and nothing happens."**

**The limit is not the problem. Spending it with nothing to buy is.** The call is
now made only when `isWatchFaceActive` says our face is not the one being worn.

### What the same log ruled out

`ours=1` killed the orphaned-slot theory that the previous entry's reinstall
advice was built on — the app sees its slot perfectly. And `pm list packages`
showed `...watchfacepush.botanic_bravo` present at `versionCode=1788303545`, so
the epoch-versioning fix works and faces really are installing. Two things were
wrong at once, and the visible symptom belonged entirely to the second.

### Why the recommended recovery had not been tried

`firstInstallTime=2026-08-30`, `lastUpdateTime` today: the watch app had been
UPDATED, never uninstalled. Play offers no uninstall in the update flow, so
"reinstall it" quietly became "update it" and the attempt counter was never
reset. A recovery instruction whose success cannot be verified from this side is
not a recovery instruction — which is why the next attempt is driven over adb,
where the install time can be read back.

### The diagnostic that should have existed

The activation failure went to `Log.e` on a device with no reachable log. It is
now named on the reply line beside the slot picture. Every diagnostic added this
week has paid for itself within one send; this one was missing for days while
five theories were argued in its absence.

## 2026-09-01 — Never destroy the face somebody is wearing

The fallback shipped in watch 1.27 cost the operator his watch face. He was left
on the system default, and the app could not put it back.

### Why it was unrecoverable rather than merely wrong

`updateWatchFace` refused, so the fallback removed the installed face and added
the new one. Removing the ACTIVE face deactivates it — and
`setWatchFaceAsActive` is spendable once per app install, so once it has been
used there is nothing left that can switch anything back on. The app had
destroyed the only thing it was allowed to fix.

**That trade is the bug**: a send that does not land is recoverable — try again.
A deleted active face with the one-shot spent is not, short of reinstalling the
watch app. A fallback that turns the first into the second is worse than no
fallback, and it looked like a success while doing it.

So the rule is now explicit: **if our face is the one on the wrist, the update
failing is reported and the face is left alone.** Remove-and-add stays, for a
face nobody is wearing, where the worst case is a face missing from a list.

### The text, rejected twice, now gone

"Long-press your watch face and pick it" was rejected. It was replaced with
"Choose it from your watch faces to wear it", which is the same instruction in
politer words, and was rejected again — correctly:

> "It should be appearing automatically. That's the whole point of what we're
> doing. We don't need that text. You keep bringing back stupid text."

He is right, and the reason is not tone. Watch Face Push preserves active status
across an in-place update, so a face that installs without switching is a **bug
in this app**. Describing it politely is the app failing and then delegating the
failure to the person who asked for it. Both outcomes now read
`"<face>" is on your <watch>.` and a test asserts the two strings are identical,
so the instruction cannot come back a third time by being reworded.

### The progress message stays up

It was `SnackbarDuration.Short`, so it vanished after a few seconds while the
build and the Bluetooth transfer carried on. Somebody was left watching an idle
screen, which is indistinguishable from a send that silently failed. Each stage
is now `Indefinite` and is replaced by the next, and the outcome replaces the
last of them.

### Recovery for an install that already lost it

Nothing in the app can restore a spent one-shot. Reinstalling the watch app can:
a fresh install holds neither the permission nor — since 1.26 excluded
`activation.txt` from backup — a stored answer claiming it was already asked. The
next face to land prompts, and switches on.

## 2026-09-01 — `versionCode="1"`, and the message that blamed the wrong thing

Driven from adb against a Pixel Watch 5, the fallback shipped in watch 1.27
returned the whole answer in one send:

```text
updateWatchFace refused (UpdateWatchFaceException: Unknown error while updating
a watch face. Typically this means that the Watch Face Push service on the watch
could not be accessed.); removed and re-added
 | free=0 ours=1 pkgs=[com.bfg.watchfaces.watchfacepush.default]
 | apk=524918 push=true activate=true
```

**`push=true activate=true`.** Both permissions held. The service was reachable —
`listWatchFaces` had just succeeded on the same manager. And remove-and-add
worked immediately afterwards, on the same APK, the same token, the same slot.

So the only thing left that distinguishes the two calls is what an UPDATE
requires that an ADD does not: something to update to. The APK's manifest said
`android:versionCode="1"`, hardcoded, every build, and the slot already held
`...watchfacepush.default` at version 1. Android does not install a package over
itself at an equal version. Watch Face Push reports that through its catch-all,
code 1, whose message names the service being unreachable.

It was reachable. **Five explanations were argued from that sentence and all
five were wrong.** Not one of them would have been proposed if the error had
said "same version". The lesson is not about watch faces: a catch-all error
whose text asserts a specific cause is worse than one that says nothing, because
it recruits everyone who reads it into the same wrong search.

`versionCode` is now seconds since the epoch — monotonic, stateless, inside the
2100000000 ceiling until 2038, and invisible to the wearer, whose face is
identified by its package and its name.

### The regression this created, and why it is the same bug

Watch 1.27's fallback fixed sending and brought back the message the operator
had already rejected: "Long-press your watch face and pick it."

That was not bad luck. Remove-and-add **deactivates** the face — it deletes the
one on the wrist before adding its replacement — and `setWatchFaceAsActive` is
spent, once per install, so nothing can switch it back. The in-place update kept
the slot and the face never stopped being active.

So the message was previously a LIE that did no harm, and the fallback made it
true. Fixing `versionCode` removes both: the update path works, the face stays
on, and the sentence is never reached.

### The words, which were the operator's actual complaint

"This is developer speak and not what a normal user should understand."

Gone: `Long-press your watch face and pick it`. A person who sent a watch face
wants to know it arrived. If it is not the one showing, what they do is choose
it — in whatever way their watch chooses things, which is theirs to know and not
ours to dictate. A test now asserts that NO outcome names a gesture.

The send narrates three beats in the words somebody would use — building,
sending to the named watch, and whether it arrived — because packing on the
phone and a Bluetooth transfer are genuinely different waits, and one
"Sending..." covering both is why a slow build read as a stalled watch.

### And a bug found by changing the wording

The phone decided whether to record the face as current by testing whether the
message `startsWith("Sent ")`. That was true only in the branch where the watch
did NOT confirm, and false on both real successes — so the record of what is on
the watch was written in exactly the one case nobody could be sure of, and
skipped whenever it was known.

`Report.landed` now answers it. **Prose is not a return value**, and a wording
change should never have been able to alter behaviour.

## 2026-09-01 — Correcting the entry below it, and instrumenting instead

The entry dated the same day is **wrong about the cause**, and it was written
confidently enough to be worth correcting in place rather than quietly editing.

### What it got wrong

It claimed the restored `activation.txt` explained `updateWatchFace` failing
with `ERROR_UNKNOWN`. It cannot:

- **Binding demonstrably worked.** `slots{free=0 ours=1 ...}` is assigned only
  after `manager.listWatchFaces()` RETURNS — same manager, same service, same
  bind. A non-empty slot picture in a failure reply is proof the service was
  reachable. "Could not be accessed", which is how the library words code 1, is
  not what happened.
- **The two permissions are different things.** `PUSH_WATCH_FACES` is normal and
  install-time; it gates PUTTING a face on the watch and is held from the moment
  the app installs. `SET_PUSHED_WATCH_FACE_AS_ACTIVE` is the runtime, ask-once
  one; it gates SWITCHING to a face and is all `ActivationConsent` records.
  Stale activation consent cannot stop an install.
- **And the fix shipped inert.** `Activation.permissionHeld` read
  `PUSH_WATCH_FACES` — the always-granted one — so `reconcile` was handed `true`
  on every call and returned the stored state unchanged. Watch 1.26 changed
  nothing. The unit tests could not have caught it: they call `reconcile` with a
  boolean and never see which permission the caller asks about.

### What survives

The backup mechanism is real, and so is the bug — but it is a NARROWER bug than
claimed. A restored `activation.txt` leaves the app unable to ask for the
activation permission, so a face **installs and the watch never switches to it**.
From the wrist that is "I sent it and nothing happened" — which is what made it
so easy to mistake for the send failing. The manifest backup exclusion stands.

### Why the fix is a fallback and not another theory

The failing call is `updateWatchFace`, on the in-place branch. Rather than
propose a sixth cause, 1.27 tries the OTHER call when that one is refused.

That is not a guess dressed as a fix: remove-and-add is the path the operator
already chose on 2026-08-30, for the complication-assignment problem, and it has
been unreachable in practice ever since because no caller passes
`resetComplications = true`. Trying it on refusal gets the wearer their face AND
says which of the two calls is refused — one send instead of one release.

### The method note, which is the actual lesson

Five explanations were offered for this symptom and all five were wrong. Every
one was argued from a short string, and every one was cheaper to argue than to
measure.

What finally narrowed it was not a new theory. It was reading what the existing
reply already proved — that `listWatchFaces` had succeeded — which had been
sitting in the operator's message, unread, the whole time.

So 1.27 reports on SUCCESS as well as failure. Every diagnostic in `FaceInstaller`
so far was reachable only by failing, which means the healthy shape has never
once been observed and there has never been anything to compare a failure
against. That is the condition that made five guesses possible.

## 2026-09-01 — A one-shot that outlived the permission it recorded

Every send began failing after the watch app was reinstalled:

```text
FAILED UpdateWatchFaceException
 | Unknown error while updating a watch face...
 | <- UpdateException: Failed to update watch face with error code 1
 | slots{free=0 ours=1 pkgs=[com.bfg.watchfaces.watchfacepush.default]}
```

### Reading it, rather than guessing at it

`slots{ours=1}` killed the leading hypothesis immediately: this was NOT the
orphaned slot of `backlog.md` #5, which the reinstall would plausibly have
caused. The slot is ours. That hypothesis was never acted on, only tested.

`error code 1` was decided by reading the constants out of the AAR rather than
assuming they were zero-based — they are not:

```text
ERROR_UNKNOWN = 1          <- this one
ERROR_UNEXPECTED_CONTENT = 2
ERROR_INVALID_PACKAGE_NAME = 3
ERROR_MALFORMED_WATCHFACE_APK = 4
ERROR_INVALID_SLOT_ID = 5
ERROR_INVALID_VALIDATION_TOKEN = 6
```

Zero-based would have made it `ERROR_UNEXPECTED_CONTENT` and sent the whole
investigation into the APK contents, which are fine.

### The contradiction that named the bug

The same reply carried `GRANTED` and a bind failure. Both cannot be true, and
the manifest says why: the watch app never declared `android:allowBackup`, so
it defaults to true, and `activation.txt` lives in `filesDir` — which Android
Auto Backup **restores on reinstall**. The permission is not restored.

So a fresh install read GRANTED from a file written by an install that no
longer existed, held no permission, and could never ask for one, because the
record said the single ask was already spent.

**A one-shot that outlives the thing it records is worse than no record**: it
locks the wearer out of the only action that would fix their problem, and the
failure it produces names neither the permission nor the cause.

### Two fixes, because one of them cannot reach this watch

`ActivationConsent.reconcile` reads GRANTED-without-the-permission as UNASKED —
the state that allows asking again. Nothing is lost: that answer was void.
DENIED is returned unchanged, because a denial does not depend on holding
anything, and re-opening it would hand the app a second ask it was refused.

The manifest also excludes `activation.txt` from cloud backup and device
transfer, so this stops happening to new installs. That does not help an install
that has ALREADY restored the file, which is why the reconciliation exists and
why it is the fix that matters.

### On the method

The watch's reply used to be `cause.message ?: cause.javaClass.simpleName` —
one or the other, never both, never the cause chain. It produced "Unknown
error", which names nothing. The phone had the identical bug and it cost a day.

There is no second place to look on a Pixel Watch: Bluetooth debugging was
removed in Wear OS 3, there is no data port, and Wi-Fi debugging needs a network
the operator did not have. The reply line IS the log, and it was summarising.

Fixing that took one release and produced the answer in one send.

## 2026-09-01 — Five wrong diagnoses, and the one measurement that ended it

Sign-in from the Play build failed for a day. Five causes were proposed and
four were wrong. The fifth was right only because it stopped being a proposal.

### What was wrong, in order

1. **The wrong sign-in flow.** Swapped the bottom sheet for the button flow.
2. **Two flows racing.** Collapsed them to one.
3. **Activity recreation.** Made the share state survivable — a real bug, fixed,
   and not this one.
4. **The Play App Signing certificate.** Checked the registered fingerprint
   against Play's current key, found them equal, and declared signing exonerated.
5. **The account needing re-verification.** Written into the UI as a sentence
   naming a cause. The operator did not believe it and was right not to.

Every one was argued from a plausible reading of `[16] Account reauth failed`.
Fixes 1–3 were improvements shipped for the wrong reason. Fix 4 was the worst
kind of wrong: a real check that answered a question narrower than the one
being asked.

### The measurement

With the phone on adb:

```text
adb shell pm path com.bfg.watchfaces
adb pull .../base.apk
apksigner verify --print-certs base.apk
  Signer #1 certificate SHA-1 digest: a6bf4178c352a4edeb9b868912a3558a38d1b9cb
```

That is the **previous** Play app signing key. Play was still serving
previous-key artifacts, so no Android OAuth client matched the app GMS actually
saw. Registering that fingerprint fixed it on the first attempt, verified on the
device and then in the live moderation queue.

### Why check 4 failed

It compared the registered client against **Play's current key** and stopped.
The question that mattered was "what certificate is on the APK THIS PHONE IS
RUNNING", and those are only the same thing if Play is serving the current key —
which it was not. Two of the three certificates on that page were never
registered, and "the fingerprint matches" was reported as though all of them had
been.

A check that answers a narrower question than the one asked is worse than no
check, because it retires a hypothesis that is still true.

### The rule this earns

**Read the artifact, not the console.** The console describes what Play intends
to sign with. `apksigner` on the APK pulled off the device says what it did.
Every claim about signing goes through the second one now.

And: an instrument beats an argument. The diagnostic build that printed the full
exception took twenty minutes to write and should have been the first thing
built, not the fifth. It did not find the answer by itself — but it ended the
supply of plausible stories, which is what kept the wrong method alive.

## 2026-09-01 — The instrument worked, and the answer was not the app

`[16] Account reauth failed`, read off a phone, after three failed diagnoses of
the same symptom.

### What it was not

The obvious suspect was the Play App Signing certificate — Google's own
guidance names that mismatch as the usual cause of this exact error, and this
project had a KNOWN open gap there: the release build is signed by a key whose
fingerprint was registered but never exercised, and that was said out loud when
1.37 shipped.

Checked instead of assumed, both ends:

| | |
| --- | --- |
| Play's Classical app signing key | `EE:97:08:66:…:01:F7` |
| The registered Android OAuth client | `EE:97:08:66:…:01:F7` |

Package matches, consent screen is External and in production, and the client's
own "last used" shows Google matching it. **The config was right.** The most
plausible suspect, with documentation behind it and a standing admission of
risk, was still not the cause.

### What it was

The message means what it says. The Google account on the device needs
re-authentication, the picker's attempt at it failed, and nothing in this app
can clear that — it is the person's own account state.

### So the only useful thing this app can do is say so properly

`[16] Account reauth failed` tells somebody they did something wrong and gives
them nowhere to go. It now says which app to open and what prompt to finish,
and keeps the raw text in parentheses, because the next person debugging this
needs it and burying it would repeat the mistake that made this take three
attempts.

### The lesson is about the method, not the bug

Three diagnoses, two of them reasoned rather than measured, both wrong. The
fourth attempt started by fitting an instrument, and the instrument answered in
one round — then pointed away from the theory everybody, including the
documentation, would have bet on.

The instrument should have come first. It cost one release to fit and would
have saved two.

## 2026-09-01 — Landscape, and a branch that said nothing three times

### The preview was measured against the wrong edge

Studio's dial was `fillMaxWidth(0.62f)` with `aspectRatio(1f)`, so its HEIGHT
was 62% of the screen's WIDTH. Portrait, that is a pleasant two-thirds square.
Landscape, the width is the LONG edge, so the square grew taller than the
screen, the scrolling controls got what was left of the height, and there was
nothing left. Reported from a phone: "you cannot see past the watch face
preview."

Two changes. The preview is now bounded by the SHORT edge, so it can never
exceed the screen whichever way the phone is held. And landscape puts it BESIDE
the controls rather than above them, which is what the extra width is for.

Rotation itself already held, because `params` became saveable yesterday — the
operator confirmed "turn sideways and back, everything holds". Worth noting
that the two bugs looked identical from outside and were not related at all.

### A silent branch cost three diagnoses

Sign-in has now been reported broken three times with the same words: pick an
account, land back on the Share button. Three fixes shipped. The first was the
wrong flow (sheet instead of button). The second was two flows racing. The
third was Activity recreation. **Two of those three were diagnosed by reasoning
rather than evidence, and both were wrong.**

The reason the same wrong method kept getting used is in the code:
`Outcome.Cancelled` did nothing and said nothing. So a sign-in the person
cancelled, a sign-in that returned no token, and a sign-in that never ran all
looked identical from outside — which leaves reasoning as the only available
tool, and reasoning has now been wrong twice.

`Cancelled` carries a reason now, and the UI shows it. Not as an error, because
dismissing a picker on purpose is not one, but as a line that says something.
Failures name the Credential Manager `type` as well as the message, because
those messages are frequently null and the type is the part that distinguishes
an unregistered signing certificate from a misconfigured client id — two causes
that currently produce identical, empty text.

This is not a fix. It is the instrument that should have been fitted before the
first guess.

### Not verified

The landscape layout compiles and the arithmetic that caused the bug is
unambiguous, but it has not been SEEN: both emulators went away with the bridge
to the operator's laptop. Said plainly rather than implied.

## 2026-08-31 — The audit, and the shape the bugs kept taking

Three parallel audits of `:mobile` and the module boundaries, after "we're
making a mobile app larger and more complex than it needs to be". Twelve
findings fixed. They were not twelve different mistakes; they were four, each
made more than once.

### The measurement that explains the rest

| module | main | test |
| --- | ---: | ---: |
| `:generator` | 4,305 | 2,815 |
| `:appcore` | 1,957 | 1,768 |
| `:workbench` | 3,393 | 1,936 |
| **`:mobile`** | **5,285** | **0** |
| **`:wear`** | **1,001** | **0** |

`:mobile` is the largest module and one of two with no test wiring at all — not
zero tests, zero ABILITY to have them. Everything with a `Context` in reach
lands there, and then everything near it does too. Both bugs found earlier the
same day lived in that region, and so did most of what the audit turned up.

### Shape 1 — state that does not survive what it is used for

`ShareSheet` had already been rebuilt so its work outlived the SHEET, because
Google's account picker takes focus and a dismissed sheet cancels its own
coroutine. That fix was half of one. The state moved to the Activity, and the
ACTIVITY is what Android recreates under memory pressure while another app is
in front — which is exactly what the account picker is. Everything reset, the
sheet reopened on nothing, and tapping Share asked again. Reported from a
phone; never reproducible on an emulator, which had no reason to recreate
anything.

`params` was worse, because the comment above it promised the fix: *"rememberSaveable
so a rotation does not throw away a design"* — over a plain `remember`. Only the
engine name survived. Every slider, both colours, the layout, the ring and the
seconds reverted on rotation, silently.

Both now survive, through the format the app already trusts: a slug for the
face, and `FaceCodec`'s JSON for the design. `DialParams` does not become
`Parcelable`; that would drag Android into a class `:generator` owns, and
`:generator` is Android-free so the file format can be tested on the JVM.

`shareBusy` deliberately does NOT survive. The coroutine dies with the Activity,
so restoring `true` would show a spinner that can never finish, and
`SubmissionStore` is on disk — a retry cannot double-submit.

### Shape 2 — one thing, written twice, agreeing until it doesn't

The complication label and sample tables were deduplicated in the morning and
guarded. The audit found the identical duplicate over `SlotPosition` still
there, **because the guard named an enum instead of the shape.** And a layer
down, three hand-rolled JSON escapers with three behaviours — a tab produced
invalid JSON from two of them.

Worst of the three: `MissingAppsRuleTest` declared its own private copy of the
filter and asserted on THAT, because the real function lived in `:mobile` where
`:appcore` cannot reach it. So the rule deciding what warning appears on every
face row had no test touching it, under a test named after it. That is the
`SlotGeometry` failure — two implementations that matched while both were
wrong — with the drift hidden inside the assertion.

`OneVocabularyTest` now guards the SHAPE: source labels, samples, slot labels,
hand-rolled escaping, and that every engine is either offered or explicitly
withheld. Every red path was executed rather than assumed.

### Shape 3 — asserting what was never checked

`reportInstall` called blocking `HttpURLConnection` from `onClick`. On a device
that is `NetworkOnMainThreadException`, swallowed by its own `runCatching`, so
**the install counter has never incremented on real hardware** — and it is what
orders the gallery.

`SubmissionLog` documented its state as "a CACHE, refreshed from
`CatalogService.submissionState`". Nothing refreshed it; that function was
called only by tests. A shared face read "Waiting for someone to check it"
forever, including long after a moderator published it.

The phone read `ActivationConsent` from its own `filesDir` — a file only the
WATCH writes. Permanently `UNASKED`, so the screen built to explain a denial
was unreachable. The watch now reports its answer on the reply that already
carries two other lines, into a SEPARATE record: that state machine guards a
one-shot unrecoverable action, and a second writer meaning something subtly
different is how a guard stops guarding.

### Shape 4 — offering what does not work

`Engine.TEXTURE` was an ordinary chip labelled "Your image" that drew a plain
dial, because no image picker exists and `backlog.md` #9 scoped it out. Removed
from the offer, with `UNOFFERED` making the withholding explicit and a test
asserting the two lists cover the enum — so a new engine forces a decision
instead of defaulting to invisible.

### What moved to `:appcore`

`MissingAppsRule`, and `SubmissionLog.describe` — the sentences somebody is
told about their own work. Both now have tests, including one asserting a
refusal does not invent a reason it cannot give, which `MODERATION.md` says
does not exist.

`:appcore` grew 1,957 → about 2,100 lines and 121 → 128 tests. The app did not
get smaller by much. It got smaller in the place that could not be tested.

## 2026-08-31 — "Where else are we not following basic principles?"

Asked after noticing that sending from My faces behaved differently from
sending from the Studio. It was a fair question and the answer was: in two
places, both found by looking rather than by arguing.

### The send path was shared; the IDENTITY was not

All three callers already went through one `requestSend`. What differed was
what they passed. My faces passed the stored face's own name. The Studio passed
`sendingName` — ambient state, set when a face was opened, when one was saved,
or defaulted to whatever was last on the watch, and **tied to nothing on the
screen**.

So editing an open face and pressing Send sent the NEW design under the OLD
face's name. Same name, same slug, same `watchfacepush.<slug>` package — which
means it replaced a face on the watch the wearer had not touched. Two buttons
labelled the same, doing different things, exactly as reported.

`sendingName` is gone. The Studio sends the OPEN face's name, and an unsaved
design goes through the same naming sheet Save uses — because a design with no
name is not a face yet, which `CLAUDE.md` already said. Naming then sends,
rather than saving and stopping, so the button that was pressed is the button
that happens.

### Two vocabularies for one thing, already drifted

`:mobile` carried its own `sample()` and `label()` over `ComplicationSource`,
duplicating `Complications` in `:appcore`. They had already diverged:

| | `:appcore` | `:mobile` |
| --- | --- | --- |
| `DAY_AND_DATE` | `MAR 10` | `TUE MAR 10` |
| `TIME_AND_DATE` | `10:10` | `10:10 TUE` |

Not cosmetic. `SlotGeometry` decides whether a value fits its box from how many
characters it runs to, so two samples are two answers to "does this fit" — the
phone preview and the workbench preview drawing different faces from identical
parameters. That is the precise bug `SlotGeometry` exists to prevent,
reappearing one layer up: in the words rather than the boxes.

`label()` is worse in kind: it is written into the built face's `strings.xml`
as each slot's `displayName`, so a second table lets the app call a slot one
thing and the WATCH call it another.

Both deleted. `OneVocabularyTest` scans the other modules for a second table of
either, and **its red path was executed**: a duplicate was reintroduced, the
test failed, the duplicate was removed, the test passed. It also carries a
positive control, because a scan that silently finds no files passes every
assertion while measuring nothing.

### One sign-in flow, not two

The two-flow design from earlier today — sheet, then button on
`NoCredentialException` — produced a loop on a real phone: pick an account,
land back on the share sheet unchanged, tap Share, get asked again.

It deserved to fail. Two sign-in UIs behind one button is two chances to end
somewhere nobody asked for, and the sheet was buying nothing: this is an
EXPLICIT action. Somebody has read what sharing does and pressed Share. Nobody
needs a tap saved there; they need a picker that appears and works.

`GetSignInWithGoogleOption` alone. On an emulator it renders "Choose an
account — to continue to BFG Watch Faces" and offers **Add another account**,
which the sheet could not do and which is the whole reason the first version
told a phone with an account that it had none.

**Not verified end to end.** The emulator's Play services wedged on the account
selection and stayed wedged. The picker was reached and was correct; the token
was not observed coming back. Said plainly rather than implied.

## 2026-08-31 — Three things a wrist found that no test could

All three came back from a real phone and watch within an hour of 1.37, and
each is a case of the app asserting something it had not checked.

### "There is no Google account" — told to a phone that had one

Sharing failed with "there is no Google account on this device to sign in
with", and offered no way forward. The phone had an account.

`GetGoogleIdOption` is the bottom sheet, and Google's own description says what
it leaves out: it "excludes accounts that require re-authentication", and "if no
Google Accounts exist on the device, the bottom sheet UI does not appear". Both
throw `NoCredentialException`, and this app read that one exception as the
strongest of its possible meanings.

`GetSignInWithGoogleOption` — the BUTTON flow — is the one that reaches a
re-auth account and can add a new one. So the sheet is an optimisation and the
button is the answer: try the sheet, and on `NoCredentialException` fall
through rather than giving up. Only when BOTH are out of ideas is "no account
this phone can use" an honest thing to say.

Cancelling is deliberately not retried. Dismissing the sheet is a decision, and
answering it by opening a second sign-in UI is the app arguing with someone who
just said no.

### "Long-press your watch face and pick it" — for a face already on the wrist

Reported as "the text says something about long press and set it, which doesn't
need to be done", from someone who had sent faces repeatedly and watched every
one of them work.

`setWatchFaceAsActive` works ONCE per install; Google's reference says so
outright. From the second send it throws, `FaceInstaller` caught it, and
reported `OK_NOT_ACTIVE` — which `WatchLink.Report.describe` renders as the
long-press instruction. Meanwhile `updateWatchFace` with a different package
name inherits active status by itself, so the face had already switched.

The app was describing a failed call rather than the state of the watch.
`isWatchFaceActive` costs nothing and is the only thing in the room that
actually knows, so the verdict is now `asked || isWatchFaceActive(...)`.

**This also answers "does the watch send back an ack": it always has.**
`WatchLink.Report` writes `OK`, `OK_NOT_ACTIVE` or `FAILED <reason>` back on the
same channel, plus the provider catalog. The ack was right; the value it carried
was wrong.

### The send messages did not look like the app

A stock Material 3 `Snackbar` is `inverseSurface` — near-black, floating, and
indistinguishable from every system toast on the phone. It is also where the
LONGEST text this app shows appears, so the surface most likely to be read
carefully was the one that looked least like it belonged. Now `primaryContainer`
on the app's own palette, with the large shape.

### What the three have in common

Each was the app stating something it had not verified: that there was no
account, that the face had not switched, that a message was ours. The fixes are
the same shape — ask the thing that knows, and only assert what comes back.

## 2026-08-31 — Sharing works, and the bug was in the sheet that asked

A face designed on the phone reached the live catalog, was seen in the
moderation queue, and was taken back again. That is the last of the three
things `CLAUDE.md` listed as never tested.

### The bug only running it could find

The first attempt did nothing. The account picker appeared, the account was
chosen, and the queue stayed empty. No crash, no error, no log line.

`ModalBottomSheet` fires `onDismissRequest` when something else takes focus,
and Google's account picker is an activity — so choosing an account dismissed
the sheet, which removed the composable, which cancelled the
`rememberCoroutineScope` the submit was running in. Half a network call, thrown
away silently.

Two changes, and both are the same lesson: **work that outlives a sheet cannot
live in the sheet.** The share state and the coroutine moved up to the
activity, whose composition survives; and `onDismissRequest` now refuses while
busy, so the picker taking focus cannot close the thing waiting for its answer.

`ShareSheet` is a view now — it holds no state but the half-typed author name
and starts nothing. Every other sheet in this app is safe only because nothing
in it opens another activity.

### The words are for the person, not the system

No "submission", no "moderation queue", no "slug", no id. Somebody sharing a
watch face is not administering a service: they put a thing they made where
others can find it, someone checks it, and they can take it back. The row says
"Waiting for someone to check it", and the sheet and the row get that sentence
from ONE function so they cannot describe a state two different ways.

The sheet also refuses to say "your face is live", because it is not. Nothing
appears until a person looks. An app that said "shared!" and showed nothing in
the gallery would read as broken, and the author would send it again.

### An unknown state reads as "still waiting"

`SubmissionLog.State.of` maps any word it does not recognise to PENDING. The
alternative defaults all end with the app telling an author their face was
refused because the service learned a new word — bad news that never happened,
about something somebody made.

### What sharing keeps

An anonymous hash of the account, and nothing else. Verified by reading the
queue after a real submission: `author_key` is a hash, the author field was
empty because the field was left blank, and no address is stored anywhere. The
account exists so a face can be taken back, which is exactly what the sheet
promises and now demonstrably does.

## 2026-08-31 — Sharing has a client id, in a project of its own

The last blocker on the community catalog was an OAuth client, which is not
something a repository can create. It exists now.

### A separate Google Cloud project, and the reason is the consent screen

The obvious home was `budgetbug-495002`, where `play-publisher` already lives.
Rejected: **the consent screen is user-facing branding.** A wearer signing in to
share a watch face would have read "BudgetBug wants access to your Google
Account", which is the kind of thing that makes a person cancel and never come
back. Sculpt Studio already has its own project, so this follows the shape the
account was already using rather than inventing one.

`bfg-watch-faces`, audience External, app name "BFG Watch Faces". External is
required rather than chosen: Internal restricts sign-in to the organisation,
and the whole point is that anyone with a Google account can share a face.

### Four clients, because Google matches on signing key

An Android OAuth client is one package name and ONE SHA-1, so the same app needs
one per key it can be signed with: the debug key for a local build, the upload
key, and Play App Signing's key for anything a tester installs from Play. Miss
the last one and sign-in works on the developer's own build and fails for every
tester — the worst shape of bug, because the person who could debug it is the
one person who cannot reproduce it.

All four are registered. The Play App Signing fingerprint took a detour worth
recording: the Play Console only offers it through a copy-to-clipboard chip,
and both reading the clipboard and pasting into a field hung the renderer. The
answer was to stop trying to read the clipboard and instead WRAP
`navigator.clipboard.writeText` before clicking the chip, so the value was
caught on its way out. Its ancestor was checked for "Classical key" rather than
assumed, because the same card also carries a post-quantum key whose SHA-1
would have been silently wrong.

It differs from the upload key, which is the proof Play re-signs: registering
only the upload key would have given sign-in that works for the developer and
fails for every tester.

### No client secret was kept

The Web client came with one and it was discarded unread. This flow never uses
it: an ID token is verified by checking its signature against Google's JWKS and
its `aud` against the client id, and no secret is exchanged. Storing a
credential the system has no use for is a liability with no upside. If one is
ever needed it can be reset.

The client id itself is NOT a secret and lives in `wrangler.toml` rather than
1Password — it ships inside every APK, so hiding it would be theatre.

### Published to production, which needed a privacy policy and a domain

"Publish app" was disabled until Branding carried an application home page, a
privacy policy link, and the matching domain in Authorized domains —
`bfgsolutions.net`, `/privacy`, both checked for a 200 and a real heading
rather than pasted in hopefully. Terms of service was left blank because it is
optional, and the logo was left blank because it is worse than optional:
uploading one FORCES a verification review.

The result is an app in production with no verification requirement, because
it asks for no sensitive or restricted scopes at all — the Data Access page
lists none, and `openid`/`email`/`profile` are implicit. The 100-user cap that
applies in Testing does not apply here for the same reason.

### A test that was written to fail, and did

`CatalogLiveTest` asserted that submissions were switched off. That was
deliberate: it was the tripwire for this day. It failed on deploy and now
guards the other direction — if `acceptsSubmissions` ever goes false again, the
app would offer a Share button that cannot work.

Verified against the deployed service rather than assumed: a request with a
bogus token now returns "that sign-in could not be read", which is JWT
verification failing. Before today it could not have got that far.

## 2026-08-31 — v10: the numbers got bigger without the boxes moving

"It's almost impossible to read the numbers, they're so small. Perhaps they can
scale larger when we squeeze the spacing tighter?"

### Squeezing the spacing buys nothing, and that was measured first

Sweeping the requested spread on a five-slot face: 60, 70, 80 and 92 all give
the same ceiling, complication size **31**. Past 100 it gets WORSE — 29, then
28 — because a wider row pushes boxes toward the rim. The proposal was checked
before anything was built, and it does not work.

Neither does anything else structural. Turning off the bottom slot: still 31.
The top slot: still 31. The date: still 31. Narrowing the row box from 3.9x the
slot size to 3.2x moves the ceiling to 34 and the FONT only from 29 to 31 —
two points of text in exchange for reflowing every stored face. Rejected.

Only two things moved the number, and neither is the size control.

### The font was filling two thirds of the space already reserved for it

`fontSize` was 0.92x the slot size, inside a line box (`textHeight`) of 1.35x.
So a third of the room set aside for the value was empty, and the size control
had been asked to make up the difference by growing the whole box — which is
the request the geometry cannot honour.

1.10x takes the value from 29pt to 34 at the largest size, and the line box is
still 1.24x the font, an ordinary line height. **No box changes size and no
slot moves.** The thing that was measured as impossible was making the boxes
bigger; making better use of the boxes was never tried.

### Shorten before shrinking

"71° Cloudy" is ten characters in a 121px row box. `drawnFontSize` shrank the
font until it fit — 19pt beside neighbours at 29 — which is not clipped and is
also not readable. That is the wrong lever: the box is about four characters
wide, so the font has to fall by a third to buy room for a word you can get by
looking out of the window.

A drawn source now declares a `compact` form — the same reading with the
droppable part dropped, "72° Cloudy" to "72°" and "78° / 61°" to "78/61". The
order is: full wording at full size; if that does not fit, the compact wording
at full size; only then does the font come down, and it comes down on the
shorter string so it falls as little as possible.

**A little smaller beats a word missing**, so shortening needs a threshold
rather than a yes/no. `LEGIBLE_SHRINK` is 0.85: in the 121px row slots the full
string fits only at 56% of its neighbours and the condition goes, but in the
206px TOP and BOTTOM slots it fits at 97% and is KEPT at 33pt. Deleting a word
the wearer chose, to save one point of size, would be the fix overshooting.

`WffSchemaTest` asserts both halves — the expressions of whichever wording was
chosen do reach the XML, and the full form still reaches a slot somewhere. A
shortened form that still emitted the dropped expression would be reading a
sensor in order to display nothing.

### Not version-gated, for the second time today

Same reasoning as the seconds this morning, and it should be recorded as a
pattern rather than rediscovered a third time: the rule protecting stored faces
exists to protect OTHER PEOPLE's, the published catalog contains zero of them,
and every face the operator owns is v9. Gating on v10 would freeze the defect
into the only faces that exist in order to protect faces that do not.

The version still moved to 10, and `PatternEngines` gets a v10 branch
delegating to v4 — no engine changed, and saying so in the dispatch is how that
stays provable.

### Three preview goldens moved, and this time all five fixtures did

Including the glyph-less one, which is correct: every fixture draws at least one
value and every value grew. It moved by the same amount at v6 and v7, which is
the check that this was text and not geometry.

## 2026-08-31 — The seconds follow the clock, and the heart stopped being an outline

Two things came back from the wrist after the last build, on a photo showing
`7:56` with a character-wide hole before the seconds.

### There is no number that puts a fixed gap beside a centred clock

The gutter was arithmetic: measure the widest time, add a gap, put the seconds
there. That is exactly right for three hours a day and wrong for the other
nine — a 12-hour clock renders `7:56` from one o'clock to nine and `12:56`
after, the clock is CENTRED, and so its right edge moves a whole character
between those cases. Sizing for the wide one strands the seconds most of the
day. Sizing for the narrow one runs them into the time at ten, eleven and
twelve. There is no third number.

Watch Face Format cannot help with this the way a layout engine would: it
positions everything absolutely, cannot measure text, and `TimeText` takes only
`Variant` children, so no transform and no relative placement. Looked up rather
than tried: the format's answer is `Condition`, which holds an expression and a
set of branches and renders the first that matches. The seconds are emitted
TWICE, thirty points apart, and `[HOUR_1_12] < 10` picks. `HOUR_1_12` is in
the schema's own `sourceType` enumeration, so this is the format's vocabulary
rather than a guess at it.

Only 12-hour faces pay for it. `hh:mm` zero-pads — a 24-hour face, and an
automatic one on a 24-hour watch — so the width never changes and one element
is emitted, which matters on a file that crosses by Bluetooth.

The previews were the other half. They draw a specific time, so they KNOW the
width, and they now pass its character count to the same `SecondsBand`
function the `Condition` branches are built from. Before this they always asked
for the wide position and so reproduced the bug they were supposed to catch.

Rejected: shrinking the seconds further, which trades a visible gap for
illegible digits; and padding the hour to `hh:mm` on a 12-hour face, which
fixes the layout by changing the design the wearer chose.

### The heart is a construction, not a drawing

Three outline attempts, each rendered and looked at, and the third looked like a
heart in AWT and not on the watch. Arcs are the one shape whose geometry has to
be CONVERTED for this format — AWT counts counter-clockwise from three o'clock
with a bounding box, the format clockwise from twelve with a centre — so an
arc-built glyph carries a whole class of failure that nothing else does.

So it is built the way a heart is constructed rather than traced: a square
turned 45 degrees with a filled circle on each of its two upper edges. Two
`Ellipse` fills and one `RoundRectangle` fill, and the rotation is a real
attribute of the part (`angle` with `pivotX`/`pivotY` on `abstractPartType`)
rather than something the emitter has to bake into coordinates. No arcs, no
strokes, no joins to go wrong, and the same three shapes in both previews and
on the watch.

### Three preview goldens moved, and one deliberately did not

Repinned for the heart. The fixture with `iconSlots = emptySet()` is
byte-identical across the change, which is the thing that says this was the
glyph and not the renderer — a golden set where everything moves together
proves nothing.

## 2026-08-31 — The heart, and why the seconds drifted back

The glyphs came back monochrome, so drawing our own was right. Two things it
left behind, both reported from the wrist.

### An end-aligned run hangs its left edge off an estimate

The seconds were moved left by computing a RIGHT edge and letting the text be
`align="END"` to it. That reads as equivalent to moving them and is not: the
right edge was derived from an ESTIMATE of how wide two digits are, so every
pixel the estimate was wrong by pushed the text away from the clock and toward
the ring. "The seconds slipped back to the right" — after a change whose entire
purpose was to move them left.

They are anchored by their LEFT edge now, `align="START"` at a fixed gap past
the widest time. The gap to the clock is exact because it is the thing being
set rather than the thing being inferred, and any error in the width estimate
lands on the ring side, which has room for it.

### The heart took three attempts and a render each time

Approximating a cubic outline with arcs and lines is not arithmetic, it is
drawing, and it cannot be reasoned about from coordinates:

1. Two half-ellipses side by side. They OVERLAPPED and crossed, leaving a notch
   and a stray mark where the strokes met.
2. Made to meet exactly. Now a clean shape and still not a heart — a shield,
   because a 180 degree lobe leaves the sides as straight walls.
3. Lobes sweeping 218 degrees, so each comes over the top AND wraps down the
   outside, meeting at a shallow dip rather than plunging to the waist.

Each version was rendered at 160px and looked at. There was no version of this
that could have been got right by thinking harder about the numbers.

### Three preview goldens moved again

Deliberately, and for the second time today: the heart really did change. That
is what a golden is for — it made a change to a glyph shape impossible to ship
without noticing.

## 2026-08-31 — We draw the complication glyph ourselves now

Fifth and last attempt at the glyph colour, and the first that does not depend
on a provider behaving.

### The tint was correct and could never have worked

`tintColor` on `ComplicationSlot` IS the documented mechanism, and the previous
build had it in the right place. It still did nothing, and the documentation
says why: a tint can only recolour an image the provider ships WHITE-FILLED.
Google Fit sends a green steps glyph and a red heart. No watch face can override
that; the fix would be in Fit.

So the question stopped being "how do we tint their icon" and became "why are we
drawing their icon at all".

### We were never meant to be drawing it

Both previews have always drawn [ComplicationGlyphs] — our own shapes, in the
wearer's ink. That is why the preview showed a monochrome glyph while the watch
showed a green one. The emitter was the odd one out.

The reason it was the odd one out is now fixed rather than worked around: **two
glyphs could not be expressed in Watch Face Format at all.** Steps was a
`Rotated` pair and heart rate a cubic `Curve`, and `GlyphWff` drops what it
cannot express, so both produced an empty `PartDraw`. Falling back to the
provider's image was the only thing that could work.

- Steps is two footprints without the lean. A rotated ellipse is not an
  axis-aligned one and the format has no primitive for it.
- Heart rate is two half-ellipses and two lines. An outline rather than a fill,
  because an arc can only be stroked.

### And one that was quietly broken

The notification bell's dome was a `Curve` too. That glyph rendered — it just
rendered WITHOUT ITS DOME, and nothing failed. It had never been drawn on a
watch because that slot also fell back to the provider's icon, so the defect was
waiting rather than absent.

`GlyphDrawableTest` now asserts every enabled source's shapes survive the trip
into the format, counted in and counted out, and that no glyph uses a shape the
format cannot express. A glyph that loses a stroke on the way to the watch looks
right everywhere it is checked and wrong where it is worn.

### What this costs

A third-party provider's distinctive icon is replaced by ours. For a slot
pointed at an app, our shape still describes the SOURCE behind it, which is what
the wearer chose. The three preview goldens moved, deliberately, because the
glyphs really did change.

### The one thing still unproven

`PartDraw` inside a `Complication` element. `PartImage` and `PartText` render
there today and `PartDraw` is the same element family, which is a much better
footing than `renderMode="MASK"` ever had — that was a mode flag whose runtime
behaviour was opaque. If it fails, the glyph moves to scene level, where the
step ring already proves `PartDraw` renders.

## 2026-08-31 — The tint belongs on the ComplicationSlot

Fourth attempt at the glyph colour, and the first one grounded in documentation
rather than in reading the schema and guessing.

The operator settled it by observation: "I see this on other faces so I know it
is possible." That is worth more than three failed hypotheses — it turns "is
this achievable" into "where does the attribute go", which is answerable.

`tintColor` is on `ComplicationSlot`, and that is the documented place for it.
Everything tried before was on the wrong element:

| Where | What happened |
| --- | --- |
| `tintColor` on `PartImage` | Ignored. `renderMode` defaults to SOURCE. |
| `renderMode="MASK"` on `PartImage` | Slots with a provider glyph rendered NOTHING |
| Our own glyph instead | Impossible — see below |
| **`tintColor` on `ComplicationSlot`** | This |

### Why drawing our own glyph is not an option

It was the attractive fix — both previews already draw our shapes, so it would
have made the watch match the preview at the root instead of patching the
symptom. `GlyphWff` can only emit `Line`, `Oval`, `RoundRectangle` and `Arc`.
**Steps is a `Rotated` pair and heart rate is a `Curve`** — cubic béziers, which
Watch Face Format has no equivalent for. They produce an empty `PartDraw`, and
77 tests said so immediately.

That is also why the emitter reaches for the provider's image for these slots in
the first place. It was never a shortcut.

### The limit that is not ours

A tint can only recolour an image the provider ships WHITE-FILLED. A
black-filled icon cannot be changed by a watch face at all, and the fix for that
would be in the provider's app. Fit's glyphs are green and red rather than
black, so this should reach them.

### Why this one is safe in a way MASK was not

MASK changed a render MODE, and the failure took the whole slot's content with
it. This adds a colour attribute to an element that already renders correctly.
Verified the emitted face differs from the working build by exactly that
attribute moving, and nothing else. The worst realistic outcome is that the
glyphs stay coloured.

The tint follows the ink into ambient when a dark ink is being lifted for
contrast, for the same reason the text's colour does.

## 2026-08-31 — renderMode="MASK" made complications vanish, and is reverted

Reported from the wrist: the row of three complications was simply gone. Time,
date, weather and battery still there; steps, heart rate and world clock
missing.

### Finding it took ruling out the obvious suspect first

The previous change was per-slot complication sizing, which is exactly the kind
of thing that loses a slot. It was not that. Emitting the operator's face at the
commit before and after that change and stripping comments gave **byte-identical
XML** — at a stored size of 19 the new sizing rule changes nothing, because
nothing was being clamped.

That left one candidate. `renderMode="MASK"` shipped one release earlier and
this was the first face built with it.

### What it does, and what nobody could have caught

MASK is the schema's answer to tinting: SOURCE, the default, draws a
complication's image in the provider's own colours and ignores `tintColor`. MASK
should stencil it and fill with the tint.

On a real watch it made the slots that HAVE a provider glyph render nothing at
all. The battery slot survived, which fits — a slot whose provider supplies no
monochromatic image has nothing for MASK to fail on.

**Nothing here could have caught it.** The XSD validates that the attribute
exists, not what a runtime does with it, and neither preview draws a provider's
real icon — both draw their own glyph in the ink. The face validated, installed,
and lost three complications.

### Reverted, and the colour complaint stays open

`tintColor` stays (it shipped in a working build and does nothing on its own);
`renderMode` is gone. Verified the emitted XML now differs from the broken one
by exactly that attribute and nothing else.

So the glyphs are still the provider's colours. **A missing complication is
worse than a green one**, and the trade is not close. Whatever fixes the colour
next has to be tried on a watch before it ships — this is now the third
wrist-only defect in a row, and the pattern is that anything about how a
PROVIDER's content renders is unobservable from here.

### The comment rule bit again

Writing that explanation put `--` inside an emitted XML comment for the third
time this session, and 93 tests went red instantly. The guard works; the habit
is mine.

## 2026-08-31 — The date only constrains the slot it touches

Approved from a rendered before/after rather than described: the operator saw
both cases and said go.

### What was wrong

The top slot has to sit above the drawn date. When it could not, `fittedSize`
shrank EVERY slot until it did — so a date squeezed three row slots and a bottom
slot that are nowhere near it. Measured ceiling for a five-slot face:

```text
date LARGE   23        no TOP slot, date NORMAL   31
date NORMAL  27
date SMALL   30
date OFF     31
```

The last line is the tell. Remove the ONE slot the date touches and the ceiling
comes back — so the other four were being shrunk by a constraint that never
applied to them.

### What it does now

`fittedSize` answers for the row and the bottom; `fittedTopSize` answers
separately for the top and is never larger. On the operator's face the row goes
27 to 31; with a large date, 23 to 31.

All three renderers ask one question, `SlotGeometry.sizeAt(p, pos)`, inside
their own per-slot loops. They previously hoisted a single size out of the loop,
which would have drawn the top slot's text at the row's scale inside the smaller
box it was given — the emitter and both previews would have disagreed in exactly
the way this project keeps being bitten by.

### The trade, which was shown before it was taken

The top complication now renders SMALLER than the row when a date is in its way
— 21 against 31 at a large date, which is visible. It reads as hierarchy:
weather above, the row you scan below. The previous behaviour expressed the same
constraint by making everything small instead, which is not obviously better and
was never a choice anybody made.

Capped at the row's size. A bigger complication above three smaller ones would
read as a mistake rather than a hierarchy.

## 2026-08-31 — Two of those four fixes did not work, and why

Both were reported back from the wrist. Neither had failed a test.

### The tint did nothing, because `renderMode` defaults to SOURCE

`tintColor` on the `PartImage` was correct and insufficient. The schema has a
sibling attribute, `renderMode`, defaulting to **SOURCE** — draw the image in
its own colours. The tint is simply ignored in that mode.

**MASK** is the one that matters: it uses the image as a stencil and fills it
with `tintColor`. That is what "make the glyph match the text" means, and it is
one attribute away from what shipped.

Nothing caught this. The XSD validates the attribute, not whether the
combination does anything, and neither preview draws a provider's real icon.

### The seconds were anchored to the wrong thing

They were positioned from the RIM: `rightEdge = DIAL_SIZE - inset`. Shrinking
the font while pulling the inset in by the same amount left their LEFT edge in
exactly the same place — so every pixel saved went to the ring side and the
seconds appeared to drift toward the ring rather than toward the time. Reported
as "you moved the seconds to the right, CLOSER to the ring".

They are now anchored to the CLOCK: a fixed gap past the widest time, with the
smaller font turning directly into clearance at the rim. Right edge 421 instead
of 432, ring gap seven pixels to about eighteen.

### The version gate was the real defect

Both fixes were behind `generatorVersion >= 9`. **Every face the operator owns
is v8**, so the fixes they asked for could not reach the watch they asked about.

The rule that protects stored faces exists to protect OTHER PEOPLE's, and the
published catalog contains zero of them. Freezing a defect into the only faces
that exist, to protect faces that do not, is the rule being followed rather than
kept. The gate is removed; the version stays at 9 because rendering did change
and the format history should say so.

### Measured: the DRAWN DATE is what caps complication size

Not the constant, not the slot count, not the spread. `maxSize` for a five-slot
face:

```text
date LARGE   23
date NORMAL  27
date SMALL   30
date OFF     31
no TOP slot, date NORMAL   31
```

The top slot has to clear the date, and `fittedSize` shrinks **every** slot
until it does — so one slot's collision drags the other four down with it. A
large date costs eight points of complication size on slots nowhere near it.

The fix is to stop sizing all five together, which is a real layout change and
is NOT in this commit. It is what "fix the complication boxes" should mean, and
it wants to be seen before it is chosen.

## 2026-08-31 — Four things a wrist found, and generatorVersion 9

All four came from the operator wearing it. None could have been found any other
way, and two of them the previews actively hid.

### The glyphs were the provider's colours, not the ink

Reported: "steps are green and heart rate red" on the watch, monochrome in the
preview. The emitter asks for `[COMPLICATION.MONOCHROMATIC_IMAGE]`, and **that
name describes what is REQUESTED, not what arrives** — Google Fit ships a green
steps glyph and a red heart, and Watch Face Format drew them as sent.

Both previews draw the glyph in the ink, so nothing disagreed and nothing
failed. The built face simply looked different from the tool that designed it,
which is the failure mode this project keeps meeting.

Fixed with `tintColor` on the `PartImage`, which `abstractPartType` has carried
all along.

### The seconds could not simply move

Reported as "very tight to the ring", and the arithmetic agreed: the ring's
inner edge sits at x=439 at the seconds' height, and they ended at 432. Seven
pixels.

Moving them left to an inset of 34 was the first fix and **rendering it showed
it was wrong** — the gap to the CLOCK closed to about three pixels and the
picture just traded one crowded side for the other. There is not room in that
gutter for seconds at 0.35 of the clock beside a full-size time.

So with a ring they also shrink, to 0.30, at an inset of 32: about ten clear of
the time, fifteen clear of the ring. Faces with no ring are untouched — nothing
crowds them, and the old inset was measured against the clock in the first
place.

The lesson is the loop rather than the numbers: the first version passed every
test and looked wrong the moment it was drawn.

### Complication sizes went up, but not as far as asked

"Small is still way too small. Large should be medium and medium small" wants
the whole scale to shift up a notch, which needs a bigger Large to shift into.

**There is no bigger Large.** `sizeOptions` is a fraction of `maxSize`, which is
not the `MAX_SIZE` constant (40) but the largest size whose boxes still fit —
measured at 30 for a five-slot face. It is the dial being ROUND that binds:
`fits` requires every box corner inside the circle. Turning the date off buys
one pixel. Widening the spread makes it WORSE, 28, because it pushes boxes
toward the rim.

0.80/0.90/1.00 was tried and `ControlsAreNoticeableTest` refused it: three
options between 24 and 30 differ by about 2pt of text, and the operator's own
earlier instruction was that a size change must be noticeable. So the range is
0.70/0.85/1.00 — 21/26/30 instead of 18/24/30 — and a genuinely larger Large is
a geometry change with its own decision, not a number here.

### The hour picker had one label that wrapped

Three segments share a row and "Match my watch" was the only one needing two
lines, so its button grew taller than the others. It is "Automatic" now; the row
is labelled "Time", which carries the context the longer wording did.

### Version 9, and what it does NOT change

The seconds move and the glyph tint both alter how a stored face renders, so the
version bumped and `PatternEngines` gained a `9 -> v4(p)` branch — no engine
changed, so it delegates rather than copying. A v8 face keeps its seconds where
its author saw them.

Two guards fired on their own and both were right to: `GeneratorVersionTest`
refused the bump until the branch existed, and `ContractFileTest` caught that
the deployed catalog validator would have rejected v9 faces until the generated
contract was regenerated and the Worker redeployed.

## 2026-08-31 — A preview drew today's date beside a clock fixed at 10:10

`RenderPipelineTest`'s pinned preview hashes went red mid-session, on code that
had passed forty minutes earlier. The session had crossed midnight.

`DateStyle.sample()` defaults its argument to `LocalDate.now()`, and both
preview renderers called it with no argument — so a preview drew whatever day it
happened to be rendered on, next to a clock permanently set to 10:10.

### The flake was the smaller half

`preview.png` is baked into the APK by `Workbench.exportTo` and is the thumbnail
the watch face carousel shows. Every built face carried its BUILD DATE, frozen,
beside a clock saying something else. Nobody had noticed because you have to
look at a preview built on a different day from the one you are looking at it on.

### What was checked before changing anything

- **The emitter does not use `sample()`.** A real watch fills the date in from
  Watch Face Format's own sources, so nothing here changes what an installed
  face displays. Confirmed by grep, not assumed.
- **`widestSample()` was already pinned** to a fixed date, which is what feeds
  the emitted font size. Whoever wrote it had this exact problem in mind for the
  half that reaches the watch, and stopped one line short of the half that does
  not.
- Exactly two callers, both previews: the workbench renderer and the Android one.

### 10 March is not arbitrary

It is the moment the rest of the preview already uses. `Complications.sample`
renders `DAY_AND_DATE` as "MAR 10" and both renderers put the clock at 10:10.
`DateStyle.SAMPLE_DATE` names it once; the workbench derives it from the render's
own `time` so a live preview at a real moment still agrees with itself.

### The guard was wrong TWICE, and ablating is why both were found

The first version compared a default render against an explicit one at the
default moment — both go through the same path, so it could not fail. Ablating
the fix showed it passing. It now renders TWO DIFFERENT DAYS AT THE SAME CLOCK
TIME, so the drawn date is the only thing that can differ.

The second was a test asserting the EXPORTED `preview.png` equals
`FacePreview.render`. It was named as though it proved determinism, and it does
not: both sides go through the same renderer, so if that renderer read the wall
clock both would read it and still match. Ablation caught this one too. It is
kept — it does catch `exportTo` drifting onto a different renderer or
quantization — but renamed to claim only that.

**Twice on one bug is a habit, not an accident:** a guard that compares two
calls down one code path cannot fail, and reads exactly like one that can.

### What finally proves it, given there is no faketime on this machine

- **A source-level check that no renderer calls `sample()` bare or reads
  `LocalDate.now()`.** Blunt, and the only thing that covers
  `AndroidFacePreview` — which needs an Android runtime, so no JVM test
  exercises it, and which is half the bug because the phone preview and the
  baked preview are two renderers that can disagree. Ablated against BOTH
  renderers; it catches both.
- **Looking at an exported `preview.png`.** It reads "Tue Mar 10", its
  complication reads "MAR 10", and the clock reads 10:10 — three parts of one
  image describing one moment. Before the fix the first of those said today.

## 2026-08-31 — Sign in to publish, anonymous to complain

Proposed by the operator: a Google sign-in, required only to POST to the
community and never to view it. Written up in full in
`docs/specs/catalog-service.md`; this records why it is right and what it costs.

### It is not really about the bot check

Every awkward part of the catalog descends from one sentence in R7: **no account
means no ban.** That is what makes pre-moderation mandatory rather than chosen,
why a bot check is needed at all, and why withdrawing your own face rests on a
random per-install id the spec has to apologise for in place. An account gives
that handle back. Turnstile falling out is a side effect, not the point.

### The mistake that triggered it, recorded as a mistake

Turnstile was chosen in the interview because it is "a script tag and one
secret". **There is no script tag in a native Android app.** Turnstile has no
native mobile SDK; the documented pattern is a WebView loading a page you host.
The reasoning was web-shaped while the submission path is app-only, and nobody
noticed until the app came to use it.

Worth separating from the hosting decision, because the operator reasonably
asked whether Azure would have avoided it: no. Turnstile was chosen separately
and earlier, and the same WebView would have been needed either way.

### The asymmetry is the design, not a compromise

**Publishing needs an account. Complaining never does.** R2 exists because
requiring an account to report became intolerable the moment submitting did not
— "anyone could publish and only developers could complain" is what moved this
catalog off GitHub in the first place. Putting a sign-in in front of the
complaint path would reintroduce exactly that, and Play's UGC rules want a
reachable complaint path.

Reports are safe to leave open because a report is a MESSAGE, not an action.
Nothing auto-hides, so flooding buys an attacker a longer queue and nothing else.

### What is promised, and the part that cannot be

The service stores `sha256(salt + sub)` — Google's per-application subject id,
hashed — and nothing else about the person. The display `author` stays an
optional string somebody types, deliberately NOT taken from the Google profile:
a display name and an identity are different things, and conflating them would
put someone's real name on a gallery by default.

What must not be claimed: Google's ID token carries the person's email and name
whether the app asks or not. The true statement is that the service reads `sub`,
ignores the rest, and never stores or logs it — not that it never sees it. About
has to say the true one.

### Rejected: relaxing pre-moderation at the same time

Accounts make pre-moderation optional, and publishing immediately with removal
on report is how most communities work. Rejected for now: `MODERATION.md`'s
published promises assume review; harmful content would be visible until
reported, which buys nothing at zero volume; and it can be relaxed later, where
tightening afterwards is far worse.

### Accepted costs, none of them free

- **About changes**, and it is the app's only promotion. "No account" becomes
  "no account to browse, an account to share".
- **A deletion obligation.** Play requires an app with accounts to offer data
  deletion in the app and from the web. `DELETE /me` has to exist, and what it
  does to an already-PUBLISHED face is not decided — removing it takes something
  off other people's wrists, keeping it means "delete my data" does not delete
  the face, which has to be said rather than discovered.
- **Burner accounts still work.** This raises the cost of abuse a lot and is not
  a wall, so pre-moderation is still doing real work.

### Why now rather than later

The catalog is empty and nothing has ever been submitted, so there is no
migration. Adding accounts after real faces exist would mean reconciling install
ids with subject ids for faces owned by real people.

## 2026-08-30 — WffEmitter escapes, and a face called "Rock -- Roll" builds again

The question raised was narrow: `WffEmitter` interpolates `fontFamily` and every
ComponentName into XML attributes with no escaping. Now that strangers can
submit a face, should the emitter escape, or is refusing bad values at the
catalog boundary the right and sufficient place?

**Both, because they are not the same job.** The objection to doing both was
that this repo has been hurt four times by one rule living in two places —
`SlotGeometry`, `ControlInventory`, `EngravedStroke` and `PublishedSlug` each
exist to undo an instance of it. That objection does not apply here:

- **Validation is policy.** "Is `Roboto Flex` a legal font family?" has one
  right answer and must have one home, which is the generated contract.
- **Escaping is encoding.** "How do I write an arbitrary string into an XML
  attribute?" is not a question about watch faces at all. It belongs to whatever
  writes the XML, and its answer does not change when the policy does.

Those are different questions. Keeping both is the difference between knowing a
value is acceptable and knowing the file is well-formed.

### What actually settled it was a bug with nothing to do with the catalog

A face named `Rock -- Roll` did not build:

```text
The string "--" is not permitted within comments.
```

The name goes into the header comment, and it never passes through
`DialParams` — it is a separate argument to `emit` — so there was no upstream
seam that could have caught it even in principle. "Validate at the boundary"
would have left that in place, because the boundary is the catalog and this
breaks for someone who never touches it.

Two other things fell out of probing rather than reasoning:

- **A quote in `fontFamily` produces perfectly well-formed XML.** It just
  carries an attribute the emitter never wrote. So a test that only parses the
  result PASSES on a successful injection — the tests check for the injected
  content specifically.
- **A face name containing `-->` closes the comment.** ComponentName was
  already safe: `DialParams`' constructor refuses one that is not a
  ComponentName, which the probe confirmed rather than assumed.

### Comments cannot be escaped, only sanitized

XML expands no entities inside a comment, so there is no escape for `--`; it is
simply illegal, as is a trailing `-`. The only options are to change the text or
leave it out. `XmlSafe.comment` collapses hyphen runs, so `Rock -- Roll` becomes
`Rock - Roll` in the header — a comment is documentation, and a faithful-enough
rendering beats refusing to build. It also strips control and format characters,
because a bidirectional override makes the header render as something other than
what it says.

### Why this is safe for every face already saved

Community faces are stored as parameters, so the emitter IS the renderer for the
stored file format, and changing its output silently rewrites everything. It
does not change: escaping only alters a string containing a character that had
no business being there, and `escaping changes nothing about a value that was
already legal` pins that. All 438 generator tests passed unchanged, including
the schema validation and the v1-v2 geometry guard. No `generatorVersion` bump,
because no output moved.

### The ablation was run, not assumed

Stripping every `XmlSafe` call from the emitter fails 5 of the 10 new tests. The
5 that still pass are the helper's own unit tests and the "changes nothing
legal" checks, which do not depend on the emitter calling it — which is correct,
and worth stating so nobody later reads them as coverage they are not.

### One thing deliberately left alone, and then not

`Workbench.exportTo` hand-rolled its own escape for `strings.xml` — `&` and `<`
only. It stayed outside that change rather than widening it, and was filed
separately.

**Picked up afterwards, and the "it is not wrong" half turned out to be wrong.**
`&` and `<` are the only two characters that must ALWAYS be escaped in element
text, which is the reasoning everyone including me applied. But the XML
specification also requires `>` to be escaped when it falls in the sequence
`]]>`, and a face name is exactly the field where a sequence like that arrives.
Exporting a face called `Bracket ]]> Face` produced a `strings.xml` no parser
accepts — and that file is built into an APK by aapt2, so the symptom would have
been a link failure saying nothing about the name.

Established by writing the test first and watching it fail against the existing
line, rather than by reading the spec and assuming. `XmlSafe.text` escapes `>`
unconditionally, so substituting it fixed the case as a side effect of removing
the duplication.

One real difference is recorded rather than glossed: a name containing a bare
`>` now produces different BYTES than before, because the helper escapes it
whether or not it is part of `]]>`. It parses to the same string, so the
resource in the APK is identical. "No behaviour change" was the acceptance line
and blanket byte-identity would have been a claim too strong to make.

## 2026-08-30 — The catalog service runs on Cloudflare, and validation splits in two

### Cloudflare, decided on merit

The spec recommended Azure on the grounds that the sibling repos use it heavily,
then reversed itself on the grounds that the only reachable Azure subscription
belongs to the operator's employer. The operator rejected the framing outright —
they use both clouds, they do not care who owns the accounts, they want the best
solution — and chose Cloudflare once the comparison was redone on technical
merit alone.

What actually decided it, all three specific to this workload:

- **No cold start on the install counter.** `POST /faces/<slug>/installed` fires
  on every community install and a person is waiting on it. Workers are V8
  isolates; Static Web Apps' Free-plan Functions cold start in seconds.
- **D1 is real SQL, so "byte-identical submissions are rejected" is a partial
  unique index** enforced by the database. On Table Storage it becomes a
  read-then-write in application code with a race two simultaneous submissions
  can drive straight through. Cosmos would fix that, and its one free-tier
  account per subscription is already taken.
- **Turnstile is a same-platform call**, not a second service with a second
  credential to rotate.

Rejected, with the reasons named rather than dismissed: Azure's App Insights,
point-in-time restore and staging slots are real advantages, and they are
operational maturity for a project that has no on-call, no alerting and no
restore drill. If this ever needs those, that is when the argument changes.

One ceiling is recorded because it is where this design would first hurt: a
response served from `caches.default` still costs a Worker invocation, so
Workers Free's 100,000 requests/day applies to total requests, not cache misses.
The fix if it is ever approached is Pages or a zone Cache Rule, not a migration.

**The account-ownership argument is retired.** It was raised three times after
the operator had twice said it was not a factor. It stays in the spec's history
because it was made, not because it counts.

### The Worker does not know what a face is

A Cloudflare Worker is JavaScript. The thing it has to validate is defined in
Kotlin. The obvious move — write the ranges and enums out again in TypeScript —
is exactly the shape this repo has already been hurt by four times, and
`ControlInventory`'s header says why: "A test that two copies match cannot tell
you they are both correct."

So `CatalogContract` in `:generator` emits `params-contract.json`, and the
Worker reads it. Ranges from `ControlInventory`, layout bounds from
`SlotGeometry`, enums from `DialParams`, the field list from
`FaceCodec.toQuery`, the colour and ComponentName patterns from `DialParams`'
own regexes, and the font weights from Watch Face Format's XSD — with a test
that reads the XSD and fails when the transcription drifts.

It is committed, like the launcher icons and for the same reason: a
`wrangler deploy` must not depend on a JVM task having been run.
`ContractFileTest` is what makes the committed copy trustworthy.

**The test fixture is generated too**, from `FaceCodec` and `CatalogStore`, and
this immediately earned itself. It caught `dateSize` bounded by
`SlotGeometry.MAX_DATE_SIZE` (56) when the stored default is 64 — those
constants bound the DERIVED date size, not the stored field, which
`fittedDateSize` stopped reading when the date became fitted. The service would
have rejected every genuine submission on a public endpoint, and a hand-written
fixture would have passed every test while it did.

The general guard added for that class of bug: a test asserting the DEFAULTS
satisfy every bound the contract states. A validator stricter than the format
rejects real work and nothing else notices.

### Validation happens twice, in two languages, and that is deliberate

R3 said "submissions validate without a human", written when the catalog was
git and CI was a JVM. It cannot be satisfied as written — the emitter is Kotlin
and the schema validator is Xerces, and neither runs in a Worker.

Rejected: porting the emitter to JavaScript. That is a second implementation of
the file format, and every one of `SlotGeometry`, `ControlInventory`,
`EngravedStroke` and `FaceCodec` exists because a duplicated implementation had
already caused a real bug.

Chosen: the Worker checks what is cheap and structural at the POST — ranges,
enums, colours, unknown fields, and strings that would break out of an XML
attribute — and the moderation pass runs the real render and the real XSD on the
JVM before anything is published. Nothing reaches the public without an
automated verdict, which is what R3 was protecting; the verdict just no longer
arrives while the submitter waits.

Said plainly because it is the failure nobody can see: a schema-invalid face
installs cleanly and then never appears in the carousel. There is no error on
either side, so the automated check is the only signal that exists.

### A boundary that only mattered once strangers could reach it

`WffEmitter` writes `fontFamily` and every ComponentName straight into XML
attributes with no escaping. Harmless while a face is made on the machine that
renders it; an injection the moment anyone can submit one, since a quote closes
the attribute. The contract publishes patterns for both, `DialParams.HEX` and
`COMPONENT` became public rather than being described a second time, and the
ComponentName pattern is anchored on the way out — Kotlin's `Regex.matches`
requires a full match, `RegExp.test` does not, and an unanchored copy would have
accepted anything wrapped around a valid value.

### Two smaller things

The colour pattern was briefly written as uppercase-only, which reads as tidier
and would have rejected `#7d7369` on the public endpoint while the app went on
saving it happily. It is now `DialParams.HEX` itself. A validator stricter than
the format is a bug only strangers hit.

Duplicate detection no longer matches text in a database error message. It asks
the database. A driver's error string is not an interface, it differs between
SQLite builds, and getting it wrong turned every duplicate into five pointless
retries and a 503 — which is what it did, until the tests said so.

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

## 2026-08-30 — Two notes, each shown to exactly the people it is for

**"Complications are chosen here now."** Said once, and only to someone whose
PREVIOUS face predates v8 — until then the watch's own editor owned a slot once
it had touched one, so anything picked there survived every send, and now it
does not. Their choices changing with no explanation is indistinguishable from a
bug.

Nobody else hears it. To a person whose first face is v8 or later there is
nothing to migrate, and the note would be a warning about a world they never
saw. Appended to the send result rather than shown separately, because that is
what they are already reading.

**"Uses the app's name, which isn't on your watch."** A face can point a slot at a
provider app or at an app to open. Without it the provider falls back to the
slot's system source and a shortcut simply does nothing — so the face renders,
and renders DIFFERENTLY from its preview, with nothing anywhere saying why.

Only shown once the watch has actually reported a catalog. An empty cache means
"we do not know", and treating it as "nothing is installed" would put a warning
on every face someone owns. That distinction is the whole reason the note is
trustworthy.

Quiet rather than alarming: the face works, it just will not look the way its
preview does.

Verified by seeding a saved face naming an app the cached catalog does not
contain — and by getting the fixture wrong first. `FaceLibrary` nests the dial
under `"params"`, so a flat file parses as a face with default everything: the
name and slug appeared, the providers did not, and the note correctly stayed
away. The code was right and the test was wrong, which is worth knowing before
concluding the opposite.

## 2026-08-30 — Sources that validate and render nothing, and a ring you choose

**The ring shows what you pick.** Steps against the day's goal, the watch
battery, or the chance of rain — the only three PERCENTAGES Watch Face Format
offers, and a ring is a proportion of a circle. Heart rate is a number, not a
fraction of anything, which is why it is not on the list.

`stepRing` was a boolean for exactly one release. The codec still reads it, so a
face saved in that window opens with its ring intact rather than silently off.

**Two weather sources validate and render nothing.** `WEATHER.TEMPERATURE_HIGH`
and `WEATHER.TEMPERATURE_LOW` are in Google's enum and pass Google's XSD, and a
face using either goes BLACK on a watch — everything gone, not just that slot.
They only exist per day: `WEATHER.DAYS.0.TEMPERATURE_HIGH`. So does UV, and the
enum's `WEATHER.WEATHER.UV_INDEX` has a doubled prefix that is simply a typo in
the schema.

Found by installing each source on a watch ONE AT A TIME and looking: battery
ring renders, chance of rain renders, high/low blank, UV blank. Nothing else
would have found it — the XSD is the authority everywhere else in this project,
and here it is wrong twice.

A test now guards the three dead spellings. It cannot test the behaviour, only
that those strings never come back, and it says so.

The pattern is worth naming now that it has happened three times:
`[MONTH_DAY]` was a fractional month, `[WEATHER.TEMPERATURE_UNIT]` was a numeric
code, and these two do not exist at all. Every one validated. The schema
describes the SHAPE of a face, never what a source means or whether a watch
implements it, and this project has repeatedly treated validation as proof.

## 2026-08-30 — A step ring the watch keeps current, and a clock you can read

**The ring costs no slot.** A goal is a proportion, and a proportion reads
better as a shape than as "8,412 / 10,000" in a box four characters wide — so it
goes round the rim rather than into one of five slots that are already fought
over.

`[STEP_PERCENT]` is a first-class WFF source and `<Transform target="endAngle">`
binds an arithmetic expression to any attribute, so the sweep is
`clamp([STEP_PERCENT], 0, 100) * 3.6` and the WATCH keeps it current. Nothing
here recomputes it and nothing is re-sent as someone walks.

The clamp is not decoration: an emulator reporting 107,520 steps against a
10,000 goal asks for a sweep of 3,800 degrees. Clamped, exceeding the goal fills
the ring exactly once.

Verified in two steps, because the emulator could only ever show a finished
goal: the live ring renders as a complete circle, and pinning the sweep to 40%
by hand drew a bright arc from 12 o'clock to roughly five, over the faint track.
Direction and start point are right.

**12 or 24 hours, and no leading zero on 12.** WFF takes `hourFormat="12"` and a
`format` of `h:mm` — a single `h` is what drops the zero, and "06:10" on a
12-hour face reads as a mistake. `Match my watch` stays the default, because it
is the only setting that is still right after someone travels.

`ClockText` is shared, because the watch formats its own clock and a preview
that formats it differently is a preview of a different face.

**The top complication was not smaller — it was narrower.** Measured with the
same provider in all five slots: identical, 105x66 at font 25. The difference
only appears with a LONG value, because TOP and BOTTOM were held to a box built
for three-across while being alone on their rows, so a provider returning "Sat,
Aug 30" had its text shrunk to fit. They get 1.7x the width now. Capped rather
than given the whole chord: a slot spanning the dial stops reading as one of a
set.

## 2026-08-30 — A control has to change something a person can see

"It should be noticeable to a user and logical for what they expect." That is a
testable claim, and this project has failed it three times: "Large" clamped to
within four points of "Medium"; Tight, Normal and Wide all resolving to the same
number; and a date size that could not exceed the value already stored. Each
time the code did what it said and the face did not move, and each time a person
found it rather than a test.

`ControlsAreNoticeableTest` now asserts the RENDERED result, not the numbers:
each complication size is at least 3pt of text bigger than the one below it,
each spacing moves the row slots at least 6px further apart, wider spacing also
moves the row DOWN, and each date size differs by at least 4pt. Across four
faces whose available room differs a lot.

It failed immediately, which is the point.

**Spacing did nothing on the default face** — 84, 84, 84. `spreadRange` probed
the maximum by asking for `DIAL_SIZE`, and spacing also drives vertical air, so
an enormous request pushes the row down until the complications must shrink, and
a shrunk box permits a NARROWER spread than a moderate request did. Measured:
456 produced 47px where 160 produced 144. It scans now and keeps the widest that
still leaves the complications the size the face would otherwise have. The
options are 84 / 115 / 147, and the sizes step 15 / 19 / 25pt.

**Spacing pushes the row away from the clock**, not only the slots apart. The
gap that reads as crowding is the one between the time and the complications,
and spacing left it alone entirely.

That broke `effective()`'s clamp reporting, which asked whether a slot travelled
EXACTLY the air requested. The bottom slot now travels further — with the row,
then again below it — and further is not a refusal. It asks whether the travel
fell SHORT, which is direction-dependent because negative air pulls in.

**Weather can be temperature, conditions, or both.** "Both" is where the
assumption that there was room turned out to be wrong: "72° Cloudy" is ten
characters against a box about four and a bit wide, and it reached a watch with the
temperature gone and the condition cut off mid-word. A drawn value has no provider to shorten it, so it is now shrunk to
fit its box — floored, not rounded, because rounding up overflows by a fraction
of a character, which is a clipped last letter.

## 2026-08-30 — A slot that opens any app on the watch

`launchTargetType` is a union with `xs:string`, so a ComponentName is a legal
`Launch` target. A slot set to "Open an app" names one. Verified on a watch:
tapping it opened `com.google.android.contacts/…ContactsActivity`.

The app is per SLOT, not per source — one enum member for "some app" rather than
one per app — so it lives in `launchers`, the same shape as `providers`. They
are separate maps because they are different jobs: a provider FILLS a slot with
a reading, a launcher is what pressing it OPENS. A slot could sensibly have both
one day, and conflating them would make that impossible.

The token carries both, with different markers: `STEP_COUNT+app:pkg/cls` fills,
`SHORTCUT_APP+open:pkg/cls` opens. One string per slot still, and it has to be
able to say which it meant.

**What the watch reports is now two lists.** Which apps can FILL a slot and
which can be OPENED are different questions — an app can be either, both or
neither — so `launchable()` is a second query, for activities answering
MAIN/LAUNCHER, and it rides back as a second line of the same reply. A watch
that sends only the first line is not broken; the second is simply absent.

**An invalid ComponentName fails silently.** A first attempt used an invented
class name and tapping did nothing at all — no error anywhere. That is why the
picker only ever offers components the WATCH enumerated, rather than letting one
be typed.

`enabled` had to stop testing `launch != null`. SHORTCUT_APP has no fixed
target, so the field is null and the slot was dropped from the layout entirely —
it simply did not appear, and nothing failed.

## 2026-08-30 — Tap actions, and the format could draw all along

Backlog item 2. A slot can now hold a SHORTCUT: a glyph you press, with nothing
to read. Music, Alarms, Settings, Phone, Calendar and Messages, using Watch Face
Format's own `<Launch>` targets. Verified on a watch — tapping the alarm glyph
opened `com.google.android.deskclock/.AlarmGatewayActivity`.

`<Launch>` has been available on every part since the beginning of the format
and this app never used it, which is why a face here could show a step count and
not start a timer.

**The glyphs are vectors, not PNGs.** `PartDraw` has Line, Ellipse, Rectangle,
RoundRectangle and Arc, and every shape in `ComplicationGlyphs` except a cubic
maps onto one. Baking a PNG per shortcut would have meant rasterising the same
shapes in two more places — the workbench and the phone — and shipping bytes for
something the watch can draw. Two shortcut glyphs were redrawn without curves
for this: a shape a watch cannot draw is not a shape, and the converter dropping
one silently would ship a glyph missing a stroke that validates perfectly.

Three bugs, all caught by the schema test rather than by a watch:

`Arc` takes a CENTRE and clockwise angles from 12 o'clock; the glyphs are
authored with a bounding box and AWT's counter-clockwise from 3. That difference
was already written down in `ComplicationGlyphs` for the renderers, and it
caught this too.

An empty `PartDraw` is invalid, which is what a shortcut with no glyph produces.

And the worst: `launch` was added BEFORE the `vararg drawn`, so
`WEATHER_TEMPERATURE(null, "%s°", "[WEATHER.TEMPERATURE]")` passed its data
source as a LAUNCH TARGET. Weather quietly became a shortcut with no glyph. A
parameter in front of a vararg swallows positional arguments, and every existing
call site kept compiling.

## 2026-08-30 — Three spacings that were one number, and a date you can size

**"Tight and Normal are the same."** They were, and so was Wide. Measured at
complication size 27: 84, 92 and 110 all came out as 115, because `layoutAt`
widens any request so boxes cannot touch, and the minimum had passed all three.
Three controls, one result.

Fixed numbers cannot survive a layout whose minimum moves, and it moved twice
this week — v7 shrank the glyph and let complications grow, which grew the boxes
they have to clear. The options are DERIVED now, by asking the layout what it
would honour at its narrowest and widest and taking the ends and the middle.
At size 19 that is 84 / 121 / 159; at 27 it is 115 / 125 / 135. The range
narrows as the boxes grow, which is the truth rather than a control pretending
otherwise.

**The selected option is matched by nearest, not by equality.** The stored value
is a request the layout may not honour, so it rarely equals any option — nothing
looked selected, which reads as a broken control. The same was true of the size
options, derived since v7 and compared by equality ever since.

**The date has Small, Normal and Large**, in the dialog where the style is
chosen, because it is the same decision. A SCALE of the fitted size rather than
a point size: 0.72, 1.0 and 1.22 of whatever matches the clock, so it means the
same thing at every style and clock size. 35, 48 and 56pt for a weekday date.

A stored point size was tried for this and was wrong twice — right for one style
and wrong for the rest, then clamping the fit back down to the old value. The
lesson is the same as the spacing one: store the INTENT, derive the number.

## 2026-08-30 — The ceiling quietly undid the fitted date

"The date seems to be the same size, so no change there." It was: the fitted
size was clamped to `Layout.dateSize` as a ceiling, and a face SAVED before the
change carries the old small value. So the fit was computed and then clamped
straight back down to what it was replacing. Raising the default only ever
helped faces that did not exist yet — which is every face except the ones people
have.

Keeping the control "meaningful" is what caused it, and the control existed only
because the date used to be too small. Auto-fitting removes the reason for it,
so the control is gone and `dateSize` is no longer read when drawing. It stays
in `Layout` and in the file so faces from any build still parse.

Verified the way the report came in: a face baked with `--dateSize=30`, the old
stored value, now emits `size="48"`.

**The date is part of the size budget now.** It is sized against the CLOCK, not
against what is left over, so something else has to give when it grows — and
`fittedSize` did not know about it, so the top slot was clamped to the rim and
drawn through the date. Complications now shrink instead: with a top slot and a
weekday date, 28 becomes 27; with the short "30" form, which fits at 56, they
drop to 23.

Capped at 56 regardless. "30" alone would fit at 96 against a 104pt clock, which
is a date the size of the time. Matching the WIDTH was the ask.

## 2026-08-30 — The watch's provider list, carried by the reply

Backlog item 1, and it became cheap the moment the install report existed: the
catalog rides back on the SAME reply, behind the verdict line. No second
connection, no background job, no new path or listener.

A complication provider is a service on the WATCH, so the phone can never
enumerate them and its picker could only ever offer what the build knew — which
is why "Google Health isn't in the list" had no answer. `ProviderCatalog` finds
them with the LEGACY action; the AndroidX spelling returns nothing.

The phone caches what came back, so the list is exactly as fresh as the last
send. An app installed since will not appear until the next one, and that is the
accepted price of a picker that opens with the watch charging in another room
rather than one that needs Bluetooth.

Choosing one writes `primaryProvider` and keeps the system source as the
fallback, because `defaultSystemProvider` is required and a watch without that
app has to show something. Choosing a system or drawn source clears the app: a
slot holds one thing.

Verified on the phone emulator by seeding a catalog the way a watch would:
"Moon phase / Clock" and "Daily steps / Google Fit" appear under "From your
watch". The watch half is exercised by the same reply path that carries the
verdict, which is still first run on real hardware.

## 2026-08-30 — The date is sized to the clock, not stored

"Scale it close to the width of the time." A stored point size cannot do that:
measured with AWT at a 104pt clock, whose "10:10" is 299px wide, "Wed Sep 30"
fits at 49 and "Sep 30" at 85. One number is right for one style and wrong for
the rest, which is why the control kept needing adjusting.

The size is DERIVED now, from how wide the style's longest form is, and
`Layout.dateSize` became the CEILING — still a control, now meaning "no bigger
than this".

The width estimate is exactly that, and has to be: the emitter runs on the
phone, where `java.awt` does not exist, and the emitter and both previews must
agree about how big the date is or they draw different faces. 0.575 per digit
and 0.62 per mixed character reproduced the AWT measurements within a point
across every style.

Sized against "HH:MM", not the seconds. Seconds sit in the gutter beside the
clock rather than on its line, so matching the time means matching the part
that IS the line.

The widest form is computed from a fixed date — Wednesday 30 September — not
today's. Sizing to today would resize the face on the 1st of the month.

**The v6 and v7 golden hashes were re-recorded for this, deliberately**, and the entry
in this file is the justification the rule asks for. The change was applied to
every version rather than gated: a date too small to read beside the clock is a
defect, reported twice, and preserving it for older faces would be preserving
the complaint. Only the drawn-date entry moved in either golden set, which is the
check that nothing else came with it.

## 2026-08-30 — Units the provider does not supply, and "782"

Two from the wrist, both about a bare number with no unit.

**Weather rendered "782".** `[WEATHER.TEMPERATURE_UNIT]` returns a numeric CODE,
not a symbol, so concatenating it appended the enum value: 78, then 2. It is
gone. The temperature carries a literal degree sign instead, which is right in
either scale — and the scale is the wearer's system setting, not something a
face should assert. Confirmed on a watch.

**The battery had no per cent sign** because the provider supplies "72" with no
title, which was measured days ago and reported twice since. It now carries one.

The reason that is safe ONLY NOW is worth recording: while `isCustomizable` was
TRUE the wearer could swap a slot's provider on the watch, so a hardcoded "%%"
could have ended up after a step count. From v8 the definition is authoritative,
so a slot holds what the designer chose and a unit belongs to it.

`%%` is the escape for a literal per cent in a WFF Template — tested on a watch
rather than assumed, because the alternative renders "%s" or swallows the sign,
and neither shows up in schema validation. Verified: battery "64%", weather "0°"
on an emulator with no weather data.

One `format` field now serves both a drawn source and a complication, because
the need is identical: the provider hands over a bare number and the unit is
ours to add.

## 2026-08-30 — The watch says what it did, on the channel it already has

"Sending does nothing. It says sent but nothing happens on my watch" — after
three fixes that were all real and none of which were the cause.

The reason each round cost an evening is that the phone could not tell the
difference between the outcomes. `FaceSender` resolved when the BYTES were
across. Installed, refused, installed-but-not-switched, never-picked-up: one
message for all four. Every diagnosis this week was inference from a symptom
that could not distinguish them, and inference kept being wrong.

The watch now writes a single line back on the SAME channel before closing it,
and the phone reads it with a timeout. `OK`, `OK_NOT_ACTIVE`, or `FAILED
<reason>` — turned into a sentence naming the one action that works:

```text
"My Face" is on Pixel Watch and switched on.
"My Face" is on Pixel Watch. Long-press your watch face and pick it.
Pixel Watch could not install "My Face": the watch has no free watch face slot.
```

The same channel, not a message or a second connection: it is already open,
already correlated with this exact face, and needs no new path, listener or
manifest entry.

**Silence is reported as unknown, not as either outcome.** A watch on an older
build writes nothing, and the read falls back to what the phone used to say.
Claiming success or failure on no evidence is the whole bug being fixed here, so
the degraded path must not do it either.

`FaceInstaller.Result.Installed` carries `active` now, because whether the watch
SWITCHED is the fact the wearer cares about and it was never captured — the
activation result was logged and dropped.

**Unverified here, deliberately said:** the reply needs a paired phone and
watch, and this project's emulators cannot pair. The wording is tested, both
apps build, and the fallback is safe — but the round trip itself is first
exercised on the operator's own hardware. That is the same gap that has made
every transport bug this week expensive, and it is not closed by this change.

## 2026-08-30 — The reset could delete the face, and was never needed

Audit of what the last three builds introduced, prompted by "fix whatever issue
we introduced". Two more regressions, both mine, both in the path the operator
was actually using.

**The phone asked the watch to REBUILD the slots on every complication change.**
Resetting removes the installed face and then adds it again. Between those two
calls the watch has no face from this app, and if the add fails it stays that
way — which is exactly "I sent it several times and mine is not even in the
list". It was requested on the single most common edit anyone makes.

Worse, it bought nothing. The reset existed because `isCustomizable="TRUE"` let
the watch's editor own a slot forever, so only a fresh slot would take the
design's complications. v8 made the definition authoritative in the same
release. Measured now: swap two complications, send with NO reset, and the face
shows the new arrangement — left 64 battery, right 105100 steps. A plain
`updateWatchFace` applies the design.

So the phone no longer asks. The watch still understands the request and the
debug receiver can still make it; nothing in the normal path does.
`ComplicationChange` is deleted rather than left as a decision nobody consults.

**A normal update stopped trying to activate.** Restructuring the branches lost
`onFaceInstalled` from the update-in-place path, so from v8 a face sent to a
watch wearing something else could never switch to itself. Every install used to
try. Restored.

**And the reset branch is loud now.** If the remove succeeds and the add fails,
the log says so in those words, rather than surfacing a generic failure with no
hint that something was deleted.

The pattern across all three of this week's regressions is the same: a
restructure that kept the happy path and quietly dropped a guard —
`remainingSlotCount`, `onFaceInstalled`, and the reason the reset existed at
all. Each was invisible because the phone reports success as soon as the bytes
land.

## 2026-08-30 — A dropped slot check, and a failure the phone cannot see

Follow-up: the face is not in the watch's list at all. So it is not merely
unselected, it is not installing — and the phone said "Sent" every time.

**A rewrite dropped the free-slot check.** The original refused to call
`addWatchFace` when `remainingSlotCount` was zero and replaced the oldest of our
own faces instead. Restructuring the branches for the reset path lost that
guard, leaving a bare `else -> addWatchFace`. With a full slot that fails with
`ERROR_SLOT_LIMIT_REACHED`, and the phone — which only learns whether the BYTES
arrived — still reports success.

It needs a slot that is FULL and holds nothing this install can attribute to
itself. `listWatchFaces` reports the faces THIS install pushed, so a reinstall
can leave a face occupying the only slot with nothing left to claim it. Every
emulator test had a face of ours present, so `ours` was never null and the
branch was never taken.

Restored, and it now returns a Failed with a sentence a person could act on
rather than throwing away the reason.

**The real hole is that "Sent" cannot fail.** `FaceSender` resolves when the
transfer completes. An `addWatchFace` failure, a slot limit, a rejected token —
all invisible to the phone, all indistinguishable from success. Two bugs this
week hid behind that, and both cost hours of the operator's time before anyone
could even tell which half was broken. The install result has to come back over
the same channel. That is the next thing to build, ahead of the provider
catalog.

## 2026-08-30 — "It says it sent okay" and the watch does not change

Reported after 1.12: the watch shows a black background with a time, several
sends in a row, the app reporting success every time.

Reproduced the whole path rather than guessing. A face built by the PHONE's own
`pack` — pulled straight out of the app's cache and pushed to a watch — renders
correctly: dial, complications, date, time. So neither v8 nor the on-device build
is broken, and updating the watch app over a live face does not break it either.

What "black background with a time" actually is: Wear's OWN default face. The
face is installed and simply not the one being worn.

**"Sent" was claiming too much.** `FaceSender` resolves when the TRANSFER completes.
Whether the watch then switched to the face is decided on the watch and never
reported back — and it usually does not switch, because `setWatchFaceAsActive`
succeeds once per app install and is refused afterwards. So from the second send
onwards a face installs perfectly, the wrist does not change, and the phone says
"Sent". That reads as "nothing happened", which is exactly how it was reported.

The message now says what this side actually knows, and names the one action
that works: long-press the face and pick it.

**Still claiming too much, and worth naming:** "Sent" also does not mean INSTALLED.
The phone reports success when the bytes are across; an `addWatchFace` failure
on the other side is invisible to it. A face that fails to install and a face
that installs without switching produce the same message. The fix is a reply
over the same channel, which needs both apps again.

## 2026-08-30 — v8: the definition wins, weather is drawn, and two bugs only a watch found

Built from `docs/specs/slot-content.md`. Everything below was watched on a Wear
OS 6 emulator before release, because the last round proved that neither the XSD
nor any test here can see how a face actually renders.

**`isCustomizable="FALSE"`, and it fixes the reported bug.** A face declaring
`day+date / battery / heart / steps / day-of-week` rendered exactly that on the
watch: left 99990, middle 66, right 64, bottom "Sun". The same face at TRUE had
rendered the assignments of a build before it. The complication picker now does
what it says.

Deliberately NOT version-gated for rendering. Every other branch preserves how
an old face looked; here the old behaviour IS the bug, and a face someone is
wearing should stop ignoring them.

**Weather is drawn, not a complication**, and it lives in the same list as Steps
and Heart rate because that is where a person looks for it. A drawn slot emits a
`PartText` in the slot's own box: no `ComplicationSlot`, no provider, no glyph.
`SlotGeometry` needed no new geometry.

**Two bugs that only running it could show.**

`[WEATHER.TEMPERATURE][WEATHER.TEMPERATURE_UNIT]` in ONE `<Parameter>` is
schema-valid and renders the whole face BLACK. WFF fills one `%s` per parameter;
juxtaposing two sources is not concatenation. This is the same mistake as
`[MONTH_DAY]` in the date, one day later, in a place the existing guard could not
see: `each Template placeholder has one Parameter` PASSED, because one `%s` and
one parameter holding `[A][B]` balance perfectly. The guard now also asserts that
no single expression names two sources, and was confirmed to fail (7 failures)
with the old form restored.

A drawn slot skipped the glyph's height, so its value sat visibly higher than the
numbers beside it. `hasIcon` now decides only whether the GLYPH IS DRAWN; the
LAYOUT still asks `iconSlots`, so a row shares one baseline whatever fills it.

**One namespaced string per slot**, as specified: a bare name is a system
provider, `NAME+app:pkg/cls` names an installed provider app. The `+app:` half
is not decoration — a slot has to carry BOTH the chosen app and what shows on a
watch without it, because `defaultSystemProvider` is required by the schema.
Dropping the fallback made round trips lossy, turning every app slot into the
same arbitrary source; the round-trip test caught it.

A provider for a slot that is off is now rejected rather than silently dropped:
the slot's content is one value in the file, so there is nowhere to put one.

**The dial preview is pinned.** Every control changes the dial, and judging a
change meant scrolling up to look and back down to adjust. It is also smaller
than it was: at full width the dial and one toggle filled the screen, which is
what made the scrolling cost so much.

**Not done, and worth naming.** The picker's "More" section lists what this
build knows, not what the watch has: `ProviderCatalog` enumerates installed
providers but nothing carries that list to the phone yet. Until it does, `app:`
slots can be stored and rendered but not chosen in the UI.

## 2026-08-30 — The activation allowance is ONE per app install, and WFF has weather

Gating the reset was built as decided: a separate channel path
(`/bfg-watchfaces/face-reset/`), the watch honouring it, and `ComplicationChange`
on the phone deciding when the complications actually changed. All of it works
and was watched working on an emulator: `updated slot ... in place` when nothing
changed, `replacing slot ... (active=true)` when it did.

**And then measuring killed the design.** After a clean reinstall of the watch
app the first activation succeeded, and the very next one was refused:

```text
switched to the new face (slot 55c1e952)      <- install
installed, but could not switch to it         <- next reset
SetWatchFaceAsActiveException: The maximum number of attempts
```

One successful `setWatchFaceAsActive` per app install. Not a large budget, not a
daily budget — one. So "remove and re-add whenever the complications change"
buys the wearer exactly ONE complication change per reinstall, and every change
after that leaves them on a default face with their design uninstalled. That is
worse than the bug. The gate is worth having and is kept, but it cannot be the
answer on its own.

**The answer is that the face definition should be authoritative.** Proven
earlier the same way: the identical face with `isCustomizable="FALSE"` and a
plain `updateWatchFace` rendered exactly what the XML declared, with no reset,
no deactivation and no activation spent.

The reason that looked unacceptable was that the watch's editor is the only
place a wearer can reach a third-party provider. That reason is now gone:
`primaryProvider` names any provider by ComponentName from inside the face, and
`ProviderCatalog` enumerates what is installed on the watch. The app can offer
weather and Google Health directly, in the app, where the design is being made.

**WFF has first-class weather sources.** `[WEATHER.TEMPERATURE]`,
`TEMPERATURE_UNIT`, `TEMPERATURE_HIGH`, `TEMPERATURE_LOW`, `CONDITION`,
`CONDITION_NAME`, `CHANCE_OF_PRECIPITATION`, `UV_INDEX`, `IS_DAY`,
`IS_AVAILABLE`, `IS_ERROR`. Confirmed schema-valid in an emitted face. Weather
needs no complication slot and no provider at all — it is drawn like `[DAY]` is.
The earlier note that "there is no weather in WFF" was about the SYSTEM
COMPLICATION PROVIDER list, and stands, but it was not the whole picture and
read as though it were.

Also in the schema and unused here: `STEP_GOAL` and `STEP_PERCENT` (so a goal
ring needs no complication), `HEART_RATE_Z`, and the whole accelerometer family
`ACCELEROMETER_ANGLE_X/Y/Z/XY` with `ACCELEROMETER_IS_SUPPORTED`, which is what
tilt-reactive faces are built from.

## 2026-08-30 — Remove and re-add, and the activation budget it spends

Operator decision on the `isCustomizable` trade-off: the APP's design wins.
`FaceInstaller` now removes the installed face and adds a fresh one rather than
calling `updateWatchFace`, because a fresh slot has nothing assigned to it and
`DefaultProviderPolicy` therefore applies. `isCustomizable` stays TRUE, so a
wearer can still pick weather or Google Health on the watch — those choices are
simply reset by the next send, which was accepted deliberately.

**The cost is not theoretical.** Tried on the emulator immediately afterwards:

```text
replacing slot 55c1e952 (active=true)
installed, but could not switch to it
SetWatchFaceAsActiveException: The maximum number of attempts to set the
watch face as active has been reached.
```

Removing the active face deactivates it, so every send now needs a
`setWatchFaceAsActive`, and that call has an undocumented and finite allowance
which this session exhausted. The failure mode is worse than the bug it fixes:
the old face is gone, the new one is not active, and the watch falls back to a
default. `isWatchFaceActive` is checked before removing so the call is not spent
on a face that was only sitting in the picker, but that does not help the normal
case, which is replacing the face you are wearing.

**The fix is to spend it only when it buys something** — reset the slots when
the complication configuration actually CHANGED, and use `updateWatchFace`
otherwise. The phone is what knows this: it keeps the last face it sent. But the
watch is what installs, and the only channel between them is the channel path,
which already carries the validation token and is parsed by the SHIPPED watch
app. Changing its shape breaks sends from a new phone to an old watch outright,
so it needs a watch release alongside, and that is a decision rather than a
detail.

Not released. Left here with the evidence.

## 2026-08-30 — isCustomizable is why the app's complication choices never applied

The operator said to check on the emulator before releasing. Doing it found two
bugs no test in this repo could have caught, and disproved a fix that had
already shipped.

**`[MONTH_DAY]` is not the day of the month.** The drawn date rendered
"Sun Aug 8.935" on a watch. `MONTH_DAY` sits in WFF's CONTINUOUS source group,
next to `MINUTE_SECOND` and `HOUR_1_12_MINUTE` — fractional composites for
smooth hand movement. 8.935 is month 8 plus 29/31 of the way through it. The
day of the month is `[DAY]`. The XSD validates both, so only running it on a
watch could show this.

**`isCustomizable="TRUE"` makes the watch ignore `DefaultProviderPolicy`
forever.** This is the real cause of "the right complication is always the same
as the bottom one, no matter what I check".

Established by experiment, not reasoning. A face was built with slot 1=battery,
2=heart rate, 3=steps, 4=day of week, and the watch rendered left=steps,
right=battery — assignments from an EARLIER build, unchanged across three
different faces. Rebuilding the same face with `isCustomizable="FALSE"` rendered
exactly what the XML said. The policy only supplies a default for a slot nothing
has been assigned to, and once the wearer's editor owns a slot it owns it
permanently.

The earlier fix — position-keyed slot ids and display names — was correct and
necessary, and it was NOT this bug. Both were shipped as the cure for the same
symptom; only one of them was. Recording that plainly because the release notes
for 1.11 claim more than they delivered.

The trade-off is genuine and unresolved: `TRUE` is what lets a wearer pick
weather or Google Health on the watch, which is the only way to get them at all
— there is no weather in WFF's fourteen system providers. `FALSE` makes the
app's choices authoritative and removes on-watch editing entirely.
`removeWatchFace` exists in the Push API, so a third path is possible: remove
and re-add so the app wins, at the cost of the wearer's own edits and an
`addWatchFace` call each send. That last one is not free — `setWatchFaceAsActive`
has an undocumented attempt limit this project has already hit.

Left for the operator to decide rather than picked silently.

**Also found on the watch:** appending `[COMPLICATION.TITLE]` to `TEXT` renders
"Aug 30Sun", "0180Step" and "70BPM" run together, and does not produce the per
cent sign it was tried for — the battery provider supplies "100" with no title.
Reverted to `TEXT` alone. And `DateStyle.sample()` was uppercasing in both
previews while the watch draws "Sun Aug 30" from its own sources, so the preview
was shouting a word the face never renders.

## 2026-08-30 — The slot name was the bug, and generator v7

More wrist reports. The important one: "whatever's in the right position is
always the exact same as the bottom one".

**Slot names were keyed by the SOURCE, not the POSITION.** `WffEmitter` wrote
`displayName="@string/slot_${source}"`, so a slot was named after whatever
happened to be in it. Two slots holding the same source got the SAME name, which
is what the watch's own editor uses to tell slots apart. `displayName` is the
name of the SLOT, so it has to say where the slot is.

**And the phone never emitted those strings at all.** `FaceBuilder.strings()`
emitted `watch_face_name` and nothing else, while the WFF referenced a
`@string/slot_*` per slot. Every face built on the device shipped dangling
resource references for its complication names.

Nothing caught it, and the reason is worth recording: the workbench builds with
aapt2, which FAILS on an unresolved `@string`, so that path was safe by
accident. The phone builds with `pack`, which does not fail. The face compiled,
signed, installed and ran with no name for any slot. This is the same shape as
the schema tests — a check that exists on one path and not the one that ships.

Both are now position-keyed, both builders emit all five names from one place in
`:appcore`, and a test walks every configuration asserting that every `@string`
the emitter references is one a builder supplies. It was confirmed to fail with
the old keying restored.

**v7 shrinks the complication glyph** from 1.25x the slot size to 0.85x. The
symbol was larger than the number it labels, which is backwards, and the height
was being paid for by the size control: v6 still ran out at 29.

**The size options are now derived from the layout** rather than fixed at
14/20/28. The ceiling moves with the face — five slots and a 104pt clock is a
tight budget, and turning off a slot or a glyph frees real room — so a fixed
"Large" was sometimes a number the layout refused and silently clamped. "Large"
now means as large as this face allows.

Honest limit: a five-slot face tops out around 31 (font 29), against v5's 25
(font 23). "Make Small equal today's Large and scale up from there" is not
reachable while five slots and a full-size clock share a 456 dial. Said plainly
rather than papered over.

**TIME_AND_DATE was missing.** Google's `defaultProviderType` has fourteen
members; this app offered twelve plus NONE. There is no weather provider in that
list at all, and third-party providers (Google Health and the like) cannot be
named at build time — they are assigned by the wearer in the watch's editor,
which is why the slot-name bug mattered more than it looked.

**Seconds go THIN to LIGHT.** At a third of the clock's size THIN stopped
reading as type. Both previews draw a normal weight because AWT has no light
face, so this also narrows a gap where the preview was heavier than the face.

**The drawn date goes to 40.** 30 was still a subtitle beside a 104pt clock.

Two fixture lessons, one repeated. The v5 golden broke when `TIME_AND_DATE` was
added to the enum, because a fixture used `ComplicationSource.entries.take(5)`
-- a golden must not depend on the length of an enum expected to grow. And the
v6 golden silently became a v7 golden the moment the version moved, exactly as
the v5 one had. Every version now pins its own fixture explicitly.

## 2026-08-30 — Four bugs from a real wrist, and generator v6

Reported after using the published build: turning seconds on shrank everything;
the seconds sat below the time; "Large" complications were barely larger than
Medium; and the last complication was always Notifications whatever was picked.
All four were real. None had a test.

**The clock no longer shrinks.** It was scaled to 82% whenever seconds were on,
to open a gutter. Measured instead: at full size the widest time runs to x=377
on a 456 dial, so there are 79 points of gutter and the seconds need 46 at a
third of the clock's size. They did not fit only because they were held 48 from
the rim. Inset 24 and they fit beside a full-size clock. Turning a feature on
must not resize the rest of the face.

**The seconds share the clock's element box**, so both centre on one line,
instead of sitting 0.72 of the clock's size below its origin.

**Slot ids are now the POSITION, not a running count of the enabled slots.**
This was the Notifications bug, and it is the worst of the four. Wear stores the
wearer's complication choice against the slotId, and that choice OVERRIDES
`DefaultProviderPolicy` permanently — the policy only fills a slot nothing has
been assigned to. While ids were a running count, turning any slot off
renumbered every slot after it, so the watch's memory reattached to a different
position and no amount of choosing in the app could dislodge it. Ids are
therefore not contiguous, which is fine: slotId is an identifier, not an index.

The old test asserted they were "unique and contiguous" and passed throughout,
because it emitted `DialParams()` with all five slots on and never turned one
off. Same blind spot as the schema tests: a fixture that never exercises the
case cannot see the bug.

**Generator v6: the complication BOX changed, not the dial.** A slot whose glyph
is off no longer reserves the icon's height or the offset that cleared it, and
the value's own box drops from 1.7x the slot size to 1.35x — it was 1.85x the
FONT, half a line of air under every value. That slack was not free: the
vertical stack of top, clock, row and bottom is what caps complication size, so
"Large" (28) was silently clamped to 25 and looked like Medium. At v6 it fits,
and the font goes 18 -> 26 rather than 18 -> 23.

Gated on `generatorVersion` because it moves every slot on a stored face.
`PatternEngines.v6` delegates to v5; a test asserts every engine renders
identically across the two, and another pins the v5 box numbers so saved faces
keep the layout their author saw.

**The metrics used the REQUESTED size while the boxes used the FITTED one.** The
emitter and both previews derived glyph, text and font from
`layout.complicationSize` while `SlotGeometry.boxes` used `fittedSize`. At a
clamped size the text was drawn to a scale its own box was never built for — at
"Large", a font of 26 inside a box laid out for 25.

**The date had no size control at all**, only a position one, and defaulted to
21 next to a 104pt clock. It read as a caption. Default 30, and `dateSize` is
now in `ControlInventory`. Changing the default touches no saved face: the size
is stored per face.

Two guards earned their keep. The v5 golden broke and was NOT re-recorded: it
was correct, and the failure was real — the previews called `textHeight(fitted)`
without a version, so they used v6 metrics to draw a v5 face. Threading
`p.generatorVersion` through fixed it and the golden passed byte-for-byte. The
label test caught `dateSize` shipping as a raw parameter name.

Rejected: re-recording the v5 golden's hashes. A golden that is updated whenever it
fails is a record of what the code does, not of what it should do. It is now
pinned to `generatorVersion = 5` explicitly rather than riding the default, so
it cannot drift onto a new version again, and a separate v6 golden covers the
new box, the glyph-less slot, the drawn date and the seconds.

## 2026-08-30 — SecondsBand, and seconds that were the wrong colour

The seconds shipped with `0.45` and `48` written out in `WffEmitter`, the
workbench preview and the Android preview. Three copies of one judgement, which
is the arrangement this file keeps recording as how two renderers drift apart.
They are now `SecondsBand` in `:generator`, alongside `SlotGeometry` and
`EngravedStroke`, for the same reason.

Consolidating them found a real disagreement. The emitter coloured the seconds
`inkDim` — the AMBIENT ink — on an element whose ambient alpha is 0, so it is
only ever seen awake. From v3 `inkDim` is lifted to clear a contrast floor
against black, so a pale dial with dark ink built a face with pale seconds on a
pale dial while both previews drew them dark at alpha 190.

Nothing failed. `WffSchemaTest` validated it happily — a colour is a colour to
the XSD — and the previews were right, so the only way to see it was to build
that specific face and look at a watch. The test now pins the seconds to the
awake ink, and was confirmed to fail (3 failures) with `inkDim` restored.

Rejected: keeping `inkDim` and dimming the previews to match. That is agreeing
on the wrong answer — the person picked an ink for a dial they can see, and the
ambient palette exists to solve a problem this element does not have.

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
