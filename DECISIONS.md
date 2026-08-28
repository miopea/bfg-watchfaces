# DECISIONS.md — BFG Watch Faces

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

- **Market is Wear OS 6+ only** — Pixel Watch 4 and 5, recent Galaxy Watch.
  Pixel Watch 1–3 cannot run this at all. Small today, growing. Accepted.

- **Nothing has been tested on hardware.** Every claim above is verified against
  schemas, dumps, and unit tests. No face from this repo has been confirmed to
  appear on a real watch. That is step one and it gates everything else.
