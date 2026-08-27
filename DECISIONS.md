# DECISIONS.md — BFG Watch Faces

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
