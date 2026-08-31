# The watch app, and the road to production

Scoped 2026-08-31, after the community catalog closed. This is what the WATCH
app needs before `com.bfg.watchfaces` leaves internal testing, and what it needs
after.

`backlog.md` is everything not built. This file is only the part that was
scoped, in the order it was scoped in. Decisions taken are in `DECISIONS.md`.

## 0. The finding that reordered everything

`setWatchFaceAsActive` is **not** an undocumented quota that might reset. From
Google's own reference, verbatim:

> The active watch face can be set by this means *only once*. Should the user
> move to a watch face from another developer, calling this API to set the
> active watch face back to your watch face throws an exception. Once this means
> has been used, your phone app should instead offer guidance on how to manually
> set the active watch face.

`DECISIONS.md` 2026-08-30 recorded it as "an undocumented and finite allowance
which this session exhausted". That reading was too kind. It is once per
install, by design, and there is no way to buy another.

That matters because of what the same decision changed. `FaceInstaller` now
REMOVES the installed face and ADDS a fresh one, so that a slot with nothing
assigned to it lets `DefaultProviderPolicy` apply. Removing the active face
deactivates it — so every send needs an activation, and only the first one can
have it.

**The current install path therefore works once and then leaves every wearer on
a fallback face, from their second face onward.** Not a rare edge; the normal
case, one send later. It has not been seen yet only because the operator's own
watch has not sent enough faces since the change.

### The fix is in the same document

> If the provided APK has a **different package name** than the watch face
> currently identified with the given slot ID, then the operation is considered
> a full replacement of the watch face. The user style settings of the previous
> watch face, as well as all the instances for watch faces that support multiple
> instances, **will be lost**. If the previous watch face was set as active,
> meaning it was the current watch face on the device, then the **provided watch
> face will be set as active**.

That is exactly what remove-then-add was reaching for — style settings cleared,
so `DefaultProviderPolicy` applies — and it arrives with two properties
remove-then-add cannot have:

- **No activation is spent.** The replacement inherits active status.
- **No window with no face.** Remove-then-add has a moment where the old face is
  gone and the new one is not in yet; `FaceInstaller` already has a comment
  calling that "the dangerous moment in the system".

Our packages are `com.bfg.watchfaces.watchfacepush.<slug>`, so a differently
NAMED face is already a different package and already takes the full-replacement
branch.

### The reconciliation, which is a hypothesis and not yet a fact

The 2026-08-30 note says the stale-assignment bug was proven on a watch: "a face
declaring battery/heart/steps/day-of-week rendered the assignments of a build
before it, unchanged across three different faces."

Google's documented behaviour says that can only happen on the SAME package —
the iterative-update branch, where preserving settings is the specification
rather than a bug. And the phone makes that easy to hit without noticing:
`sendingName` defaults to the last name used, so sending repeatedly from the
Studio without renaming is the same package every time.

**This is a hypothesis, and it is being tested before anything is rewritten.**
The riskiest path in the system does not get rebuilt on a documentation reading.

### The test, on the operator's watch

1. Design a face, name it **A**, send it. Note its complications.
2. Change the complications, name it **B** — a genuinely different name — send.
3. Read what B renders.

- B shows **its own** complications → Google's documented behaviour holds,
  remove-then-add is unnecessary, and step 4 below proceeds.
- B shows **A's** complications → the documentation does not describe this
  device, remove-then-add was right, and the activation problem needs a
  different answer. Record that, because it contradicts a published reference.

On a pass, `FaceInstaller` drops the remove-then-add branch and always calls
`updateWatchFace`. `resetComplications` stops meaning "remove and add" and
starts meaning nothing at all — the package name already decides.

### What the wearer is told when activation is gone

Decided: **say it plainly, once, at the moment it happens.** Not a dialog, not a
settings screen. The send still succeeded; the face is on the watch. The result
line says so and says the one thing that fixes it — press and hold the face on
the watch and pick it from the list.

The wearer is already looking at their watch when this happens. A page in the
phone app explaining it later is a worse version of a sentence now.

## 1. Play Store readiness

All four, fully filled out and ready to submit for review. These are gates, not
features: the app does not leave internal testing without them.

### Data Safety declaration — the highest risk

It is now **wrong**. The saved declaration predates both sign-in and the
catalog. As of 2026-08-31 the app:

- collects a Google account at sign-in, and stores an **anonymous hash** of it,
  never an address (verified by reading the queue after a real submission)
- transmits face parameters — engine, scale, colours, layout, complication
  choices — to a service the operator runs
- reads an install count, anonymously

Play rejects releases whose declaration does not match observed behaviour, and
this one currently understates what the app does. It is the single item most
likely to fail review.

### Store listing and screenshots

Saved, never submitted. Screenshots come from the workbench renderer rather than
hand-captured phone grabs — the renderer is the same one that bakes `dial_bg.png`,
so a screenshot is reproducible and cannot drift from what the app draws.

### Content rating and target audience

Questionnaires, and the answers changed today: the Community tab means the app
carries **user-generated content**, which must be declared. `MODERATION.md` is
the evidence that a policy and a complaint path exist.

### A rights-holder route that is not the app

`backlog.md` already calls this a release gate and `MODERATION.md` admits the
gap in writing:

> Getting a reply back to us is the part that is not finished… a rights holder
> who is not an app user has no route at all.

A published contact address is the minimum, and the document says so. It is a
condition of the catalog being open to the public, not something to sort out
afterwards.

## 2. The app must not offer a share it cannot make

The catalog service refuses `Engine.TEXTURE` — `unpublishableEngines` in the
generated contract — because a face built on an imported photo cannot be shared
as parameters. The app does not know: `canShare` is one global flag from
`/config`.

Harmless today, because `Engine.TEXTURE` has no way to get an image. It stops
being harmless the moment imported images exist.

**Decided:** `/config` gains `unpublishableEngines`, `CatalogService.Config`
carries it, and the Share button is hidden on those faces with one line saying
why. The generator already emits the list into the contract; the app reading it
keeps one source of truth, which is the drift this project keeps recording.

Imported images themselves are **out of scope** and stay in `backlog.md`.

## 3. Weather that is not there

`backlog.md` #3: `IS_AVAILABLE` and `IS_ERROR` are in the schema and unused. The
decided behaviour when weather is unavailable is to fall back to the slot's
system provider, and that is not built.

Today a weather slot on a watch with no weather draws whatever the format draws
for a missing value — which is the failure mode that once reached a wrist as
`° Unknow`. A slot that silently shows nothing is worse than a slot showing the
step count it would have shown anyway.

Uses `Condition`, the same element the seconds now use.

## 4. Tilt-reactive faces

`backlog.md` #4: `ACCELEROMETER_ANGLE_X/Y/Z/XY`, guarded by
`ACCELEROMETER_IS_SUPPORTED`.

Scoped because it is a visibly different KIND of face rather than another
number in a slot, and because it costs nothing on the device: it is pure Watch
Face Format, evaluated by the watch, with no new permission and no new provider.

`ACCELEROMETER_IS_SUPPORTED` is not optional. A face that reads an absent sensor
is a face that renders wrong on the watches that do not have one, and there is
no way to find that out except on such a watch.

## 5. Companion parity — the watch stops being a dead end

Two behaviours, both about a watch screen that currently says nothing useful.

### "Open on your phone"

`RemoteActivityHelper.startRemoteActivity`, the standard Wear handoff. The watch
app offers a button that opens BFG Watch Faces on the paired phone.

This is what makes the watch app a companion rather than a leaf. Everything the
wearer can actually DO — design, name, send — is on the phone, and the watch
currently does not say so.

### A watch screen that says what is wrong and how to fix it

Instead of blank or generic: **not paired**, **permission missing**, **no faces
sent yet** — each with the one action that fixes it. Replaces "nothing
happened", which is what an operator had to work out for themselves after an
evening of sends that all reported success.

## 6. Being stuck, and getting unstuck

The watch app needs `PUSH_WATCH_FACES`, and `ActivationConsent` enforces a
one-shot per install. A wearer who denied something has no prompt coming back.

Decided: all three, in this order.

1. **Say the exact path.** Settings › Apps › BFG Watch Faces › Permissions.
   Android has no API to re-prompt a permanently denied permission, and a vague
   "grant permission" message when no prompt will ever appear again is precisely
   the failure this project keeps recording.
2. **Deep-link where it works.** `ACTION_APPLICATION_DETAILS_SETTINGS` lands one
   tap on the permission page. **Verify on a real Wear build first** — not every
   Wear OS image carries that activity, and an intent that resolves to nothing
   is a button that does nothing.
3. **Offer to start over** for the state we own: clear `ActivationConsent` and
   local state so our own one-shot prompts can be asked again.

**Point 3 has a limit that must be said out loud in the UI:** it cannot restore
the `setWatchFaceAsActive` one-shot. That is held by the system, not by this
app, and no amount of clearing our data brings it back. A "reset" that implies
otherwise would be a lie told at the exact moment somebody is already frustrated.

## Not in scope

- **Imported images** (`backlog.md` #9). Creates an un-shareable second class of
  face and an IP surface the parametric catalog exists to avoid.
- **Recovering a full slot** (`backlog.md` #5). Still has no API out; still
  needs something from the platform.
- **Testing the transport without hardware** (`backlog.md` #10). Still the
  biggest gap in this project's ability to test itself, and still unsolved.
