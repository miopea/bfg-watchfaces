# DECISIONS.md — BFG Watch Faces

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
