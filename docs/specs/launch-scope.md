# Launch scope

Written 2026-09-02 from research into what the popular Wear OS watch face apps
ship, then interviewed to decisions. `watch-app.md` §1 holds the Play Store
gates; `backlog.md` is everything not built. This file answers one question:
**what has to be true before `com.bfg.watchfaces` leaves internal testing.**

## 0. The position, in the operator's words

> We don't win on faces, we win on free features and hitting 90% of the goals.

That sentence decides most of what follows, and it is a better strategy than the
one this document started with. It rules out the race that cannot be won and
names the one that can.

**Everything is free. No advertising. Nothing is sold.** This is a free thing
BFG Solutions is handing out. Every item in §3 is free at launch, permanently,
and that is the product rather than a promotion.

## 1. Two corrections, recorded because both felt like evidence

**"On-watch customization is the biggest gap."** Reasoned from
`UserConfiguration` and `ColorConfiguration` being in the XSD and emitted
nowhere. Google's guidance for Watch Face **Push** apps says the opposite —
configuration belongs in the companion phone app, and a pushed face gets one
slot. Those elements are unused because they serve a different distribution
model. *The schema describes what the format can do; it says nothing about what
our delivery route should.*

**"The time already ships as a light and dark relief pair, so tilt can reuse
it."** It does not. The two `TimeText` elements at `WffEmitter.kt:151` are
**interactive versus ambient**, not light versus dark. The engraved relief exists
only in the baked dial PNG. The time is flat ink on an engraved dial, which is
its own inconsistency and had gone unnoticed until a feature was proposed on top
of it.

Both were stated confidently and both were wrong in the same way: a plausible
reading of something real, never checked against the thing itself.

## 2. What the competition is, and where it is weak

| App | Scale | Model |
| --- | --- | --- |
| Facer | 500,000+ faces, brand tie-ins | Free tier + subscription |
| WatchMaker | 130,000+ converted to WFF | Free + paid |
| Pujie | Building-block designer, live simulator | Paid |

**Library size is not a contest.** Three orders of magnitude. Anything shaped
like "more faces" is out of scope by strategy, not by capacity.

### The thing they all lost

> WFF "limits visual effects such as depth or shadow, rendering very
> artificial-looking visuals" — particularly affecting analog designs.
> — [Android Authority](https://www.androidauthority.com/pujie-watchmaker-watch-face-wear-os-6-support-3581417/)

`EngravedStroke` fakes depth in three passes at BAKE time and ships a PNG, so
this app never depended on the primitives WFF removed. **The competition lost
depth in the migration; a generator that rasterizes never had to.** That is the
differentiator and nothing here may come at its expense.

### What their users complain about

Battery drain, surprise subscriptions (£29.99 after a £6.99 face, refunds
refused), faces that will not sync.
[Trustpilot](https://fr.trustpilot.com/review/facer.io) ·
[Samsung community](https://r2.community.samsung.com/t5/Tips/Wearable-app/m-p/12380183/highlight/true)

Free, no account to browse or install, no ads. Already true, and currently said
nowhere.

## 3. Launch gates

Ordered. Each is a gate, not an improvement.

### 3.1 A clean install performed by a human

**Uninstall both apps, reinstall from the Play listing, and go start to finish
with no adb and no coaching**: open, make a face, send it, grant the prompt, see
it on the wrist.

This has never been done. Every install so far was driven over adb, which is
exactly how a fresh install that *could not ask for the activation permission at
all* survived into a release — the ask is a notification and a fresh install
holds no notification permission. **If any step needs explaining, that is the
bug.**

### 3.2 Play Store gates — `watch-app.md` §1

The **Data Safety declaration is wrong** and understates what the app does. It
should say exactly what is observed and nothing more:

- **Collected**: a Google account at sign-in, stored as an anonymous hash, only
  to allow withdrawing a published face.
- **Transmitted**: face parameters, only when somebody publishes.
- **Not collected**: photos, location, contacts.

Photos are not collected and must not be declared as though they were.
Over-declaring is still a mismatch, and here the code enforces it: a photo is
baked into a local face and `isLocalOnly` stops that face reaching the catalog.

### 3.3 Imported images, finished

Decided against the earlier recommendation to hide it: photo dials are table
stakes in this category, and "90% of the goals" includes this one.

- **The Android photo picker, with no permission at all.** `PickVisualMedia`
  returns the single chosen image and can see nothing else, so there is no
  prompt, no denial path, and nothing to declare.
- **Baked into `dial_bg.png`**, the path that already exists —
  `DialRenderer.drawTexture` crops, fades and quantizes it like any other dial.
  Nothing new crosses to the watch and a face costs what it already cost.
- **Copied into app storage keyed by the texture id.** The face JSON stores an
  id, not content, and copying means the face survives the original being
  deleted from the gallery — which is what happens to photos.
- **`contrast` protects the time**, already built and documented, defaulted low
  enough that a photo arrives pushed back far enough to read over.
- **Local only, said once and plainly.** Share is hidden on such a face with a
  line explaining the photo stays on the phone.

Uploading photos to the catalog is explicitly **not** in scope: it is image
hosting, storage cost, and moderating arbitrary user photographs with a queue of
one person.

### 3.4 Say what the app is

Free, open source, no account to browse or install, no subscription, no ads.
Given the category's reviews this is a feature, and the listing has never been
written.

## 4. Tilt, and the relief the text never had

The one WFF effect that still reads as expensive, and the right one for this
app's material. `Gyro` attaches to any part or group and drives `x`, `y`,
`scaleX`, `scaleY`, `angle` or `alpha` from `[ACCELEROMETER_ANGLE_*]`.

**The text gets real engraved relief, and the tilt moves the light.** A light
copy and a dark copy offset by ±`relief`, exactly as `EngravedStroke` already
does for the dial, given opposite `Gyro` offsets so the highlight slides as the
wrist turns. The text itself never moves.

- **The text never moves.** The time is the one thing that must always be
  readable, and a drifting clock reads as a bug on a small screen.
- **Subtle and fixed: about ±2.5px at full tilt.** No control. The tasteful range
  is narrow and a slider set to maximum is how this looks cheap — the same
  reasoning that fixed the hand proportions.
- **Off in ambient, always.** Continuous sensor reads and redraws are the most
  expensive thing a face can do, battery is the top complaint in the category,
  and ambient drops the dial image anyway so there is nothing to catch light.
- **No `ACCELEROMETER_IS_SUPPORTED` branch.** On a watch without one the
  expression rests and the face renders as though the effect were off. A
  `Condition` would add schema surface and a branch only exercisable on hardware
  nobody here owns.

This changes every face, so it is a `generatorVersion` branch, and it fixes the
flat-on-engraved inconsistency whether or not the tilt is ever noticed.

## 5. After launch, in order

1. **Colourways.** Named dial/ink pairs — Taupe, Graphite, Steel, Noir — chosen
   independently of the pattern. Eight engines × six colourways is forty-eight
   looks from about twenty lines of `Presets` data. **The best ratio available.**
2. **Fonts.** `fontFamily` is `SYNC_TO_DEVICE` with no picker. Needs checking
   against what typefaces a Wear OS watch actually ships before it is scoped.
3. **A complication data source of our own.** The Watch Face Push guidance's real
   trick: a face reads values the phone updates, so things can change *without* a
   rebuild and a push — routing around both the slot limit and the activation
   one-shot for anything that is a value rather than a shape.
4. **Weather beyond now.** Forecast strip, sunrise/sunset as a drawn arc.

## 6. Deliberately not doing

- **A face marketplace at scale.** 500,000 faces is a different company.
- **Subscriptions, paid tiers, ads.** Everything is free. The category's worst
  reviews are about billing.
- **On-watch editing.** §1. Wrong architecture for a Push app.
- **Animation.** `PartAnimatedImage` stays unused. Battery is the first thing
  reviewers complain about, and smooth seconds was already deferred pending
  measurement rather than opinion.
- **Hosting user photos.** §3.3.

## 7. The honest summary

**Closer to launch than the feature list suggests, and further than the code
suggests.**

Closer, because the differentiator is built and hard to copy. Further, because
the gates are unglamorous — a Data Safety form, a store listing, a photo picker,
and one clean install performed by a person rather than by adb.

Nothing in §5 delays §3.

Sources:
[Android Authority](https://www.androidauthority.com/pujie-watchmaker-watch-face-wear-os-6-support-3581417/) ·
[Further explorations with Watch Face Push](https://android-developers.googleblog.com/2025/08/further-explorations-with-watch-face-push.html) ·
[Watch Face Push](https://developer.android.com/training/wearables/watch-face-push) ·
[Watch Face Format](https://developer.android.com/training/wearables/wff) ·
[Facer](https://www.facer.io/collection/customizable-widgets) ·
[Geekflare](https://geekflare.com/consumer-tech/best-wear-os-watchfaces-apps/) ·
[Trustpilot](https://fr.trustpilot.com/review/facer.io)
