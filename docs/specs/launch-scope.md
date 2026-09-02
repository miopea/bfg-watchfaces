# Launch scope

Written 2026-09-02, from research into what the popular Wear OS watch face apps
actually ship, measured against what this repo has.

`watch-app.md` §1 holds the Play Store gates. `backlog.md` is everything not
built. This file is the answer to one question: **what has to be true before
`com.bfg.watchfaces` leaves internal testing**, and what is deliberately not on
that list.

## 0. A correction, before the recommendations

On 2026-09-02, asked what was missing, the answer given was **"on-watch
customization, it's the biggest gap"** — reasoning from the fact that
`UserConfiguration`, `ListConfiguration` and `ColorConfiguration` appear in the
XSD and are emitted in zero files.

That reasoning was backwards. Google's own guidance for Watch Face **Push** apps
says the opposite:

> The examples favor companion phone apps using `WearableListenerService` and the
> Data Layer to send information to watches… This suggests designing for
> phone-initiated changes rather than watch-side editing.
> — [Further explorations with Watch Face Push](https://android-developers.googleblog.com/2025/08/further-explorations-with-watch-face-push.html)

A pushed face gets **one slot** on Wear OS 6 and the app lives on the phone.
Configuration belonging on the phone is the intended architecture, not a gap in
this one. The schema elements are unused because they are for a different
distribution model.

Recorded because "it is in the schema and we do not emit it" felt like evidence
and was not. The schema describes what the FORMAT can do; it says nothing about
what our delivery route should.

## 1. What the competition actually is

| App | Scale | Model |
| --- | --- | --- |
| Facer | 500,000+ faces, brand tie-ins (SpongeBob, Star Trek, Barbie) | Free tier + subscription |
| WatchMaker | 130,000+ faces converted to WFF | Free + paid |
| Pujie | Building-block designer, real-time simulator | Paid |

**We cannot compete on library size and should not try.** Three orders of
magnitude separate 500,000 faces from a preset list. Anything shaped like
"more faces" is a losing race.

### What they are all struggling with, and we are not

WFF removed the effects the old format had:

> WFF "limits visual effects such as depth or shadow, rendering very
> artificial-looking visuals" — particularly affecting analog designs.
> — [Android Authority](https://www.androidauthority.com/pujie-watchmaker-watch-face-wear-os-6-support-3581417/)

**This repo's entire premise is the answer to that complaint.** `EngravedStroke`
fakes depth in three passes at BAKE time and ships a PNG, so the dial has relief
that WFF's own drawing primitives cannot express. The competition lost depth in
the migration; a generator that rasterizes never had to.

That is the differentiator, and nothing on the launch list should come at its
expense.

### What their users complain about

Battery drain, surprise subscriptions (£29.99 charged after a £6.99 face,
refunds refused), and faces that will not sync.
[Trustpilot](https://fr.trustpilot.com/review/facer.io),
[Samsung community](https://r2.community.samsung.com/t5/Tips/Wearable-app/m-p/12380183/highlight/true)

Free, open source, no account required to browse, install or report — the
positioning is already right. It only has to be **said** on the store listing,
which today says nothing about it.

## 2. Must-have before launch

Ordered. Every item is a gate, not an improvement.

### 2.1 The Play Store gates — `watch-app.md` §1

Unchanged and still the top of the list. The **Data Safety declaration is wrong**
and understates what the app does, which is the single item most likely to fail
review.

### 2.2 The first-run path has to work for somebody who is not the operator

Measured on 2026-09-02, a fresh watch install could not ask for the activation
permission AT ALL — the ask is a notification and a fresh install holds no
notification permission, so the first face installed and nothing appeared on
either device. Fixed by having the phone open the watch app
(`WatchSetup`), but **that fix has never been exercised by anyone but adb**.

**Gate: a genuinely clean install on hardware, driven by hand, start to finish.**
Nothing else on this list matters if the first face never switches on.

### 2.3 Say what the app is on the store listing

Free, open source, no account to browse or install, no subscription. Given what
the category's reviews look like, this is a feature, and it is currently unsaid.

### 2.4 Imported images — `backlog.md` #9

`Engine.TEXTURE` exists, has a control, and has nowhere on the device to resolve
an image from. A control that cannot work is the failure this project has been
told about three times. **Either finish it or hide it before launch** — I would
hide it, because photo dials are a whole feature and the launch does not need
one.

### 2.5 Recovering a full slot — `backlog.md` #5

The slot limit is 1. Every path that leaves a face stranded needs to end
somewhere a person can act on. Partly measured already: uninstalling the app
DOES remove its pushed face, so faces do not orphan the way that entry feared.
Worth re-reading against what is now known.

## 3. Worth building, in this order, after launch

**Colourways.** Free values for `dialColor`/`inkColor` and no curated pairs. The
competition ships dozens per design; we ship one per preset. Pure `Presets` data,
an afternoon, multiplies what exists by ten. **The highest ratio on this list.**

**Fonts.** `fontFamily` is `SYNC_TO_DEVICE` with no picker. Typeface is most of a
watch face's character and we offer none of it.

**Gyro / tilt.** In the schema, unused, and the one WFF effect that still reads as
expensive. An engraved dial catching light as the wrist turns is exactly what
this app's material is for. Already noted in `backlog.md` #4.

**A complication data source of our own.** The Watch Face Push guidance's actual
trick: a face reads values from a data source the phone updates, so things can
change **without a rebuild and a push**. That routes around the slot limit and
the activation one-shot for anything that is a value rather than a shape.

**Weather beyond now.** Forecast strip, sunrise/sunset as a drawn arc.

## 4. Deliberately not doing

**Face marketplace at scale.** 500,000 faces is not a target, it is a different
company.

**Subscriptions.** The category's worst reviews are about billing. Free is the
position.

**On-watch editing.** §0. Wrong architecture for a Push app.

**Animation.** `PartAnimatedImage` is unused and should stay that way for now.
Battery is the first thing reviewers complain about, and the smooth-seconds
question was already deferred pending measurement rather than opinion.

## 5. The honest summary

**The app is closer to launch than the feature list suggests, and further than
the code suggests.**

Closer, because the differentiator is already built and the competition cannot
easily copy it. Further, because the gates are unglamorous — a Data Safety form,
a store listing, and one clean install performed by a human rather than adb.

Nothing in section 3 should delay section 2.

Sources:
[Android Authority — Pujie and WatchMaker on Wear OS 6](https://www.androidauthority.com/pujie-watchmaker-watch-face-wear-os-6-support-3581417/) ·
[Android Developers — Further explorations with Watch Face Push](https://android-developers.googleblog.com/2025/08/further-explorations-with-watch-face-push.html) ·
[Android Developers — Watch Face Push](https://developer.android.com/training/wearables/watch-face-push) ·
[Android Developers — Watch Face Format](https://developer.android.com/training/wearables/wff) ·
[Facer — customizable complications](https://www.facer.io/collection/customizable-widgets) ·
[Geekflare — best Wear OS watch face apps](https://geekflare.com/consumer-tech/best-wear-os-watchfaces-apps/) ·
[Trustpilot — Facer reviews](https://fr.trustpilot.com/review/facer.io)
