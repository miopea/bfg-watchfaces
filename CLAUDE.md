# Working in this repo — BFG Watch Faces

The app is **BFG Watch Faces** (`com.bfg.watchfaces`). It has **no default
face**: it ships a library of styles, and people name the faces they make. The
project was renamed once for conflating an app with a colourway; the last of
that naming went away on 2026-08-27.

Read `docs/SPEC.md` for the architecture and `DECISIONS.md` for why things are
the way they are. Both contain constraints found the expensive way that are not
obvious from the code.

## First run

```bash
scripts/bootstrap.sh      # fetches WFF schemas, Xerces jars, Gradle wrapper
./gradlew :generator:test # 35 tests, ~15s, no Android SDK or device needed
```

`generator/libs/` and `generator/src/test/resources/wff-schema/` are gitignored
and fetched, not committed. They are Google's and Apache's files.

On Windows, run the shell scripts from WSL or Git Bash.

## Ground rules

**Work in `:generator` whenever you can.** Pure Kotlin/JVM, no Android
dependency, runs in seconds without an emulator. Geometry, params, and WFF
emission all belong there. Only reach for `:phone` or `:wear` when the task
genuinely needs Android APIs.

**Use the workbench instead of building an APK.** `./gradlew
:workbench:workbench` serves a live design loop on localhost with schema
validation on every change. Almost nothing about geometry, colour, layout or
ambient needs a device to judge. `:workbench` is also pure JVM and depends on
`:generator`, never the reverse — the generator stays the dependency-free
definition of the file format.

**There is one rasterizer, and it is `DialRenderer`.** The browser preview, the
`bake` task and the shipped `dial_bg.png` are all the same call. Do not add a
second one — in particular, do not "speed up" the preview by redrawing the
pattern in JavaScript. See `DECISIONS.md` 2026-08-27.

**There is no default face any more.** Presets are starting points; a face gets
its name when someone saves it, and that name becomes the carousel label, the
`watchfacepush.<slug>` package and the APK filename. Do not reintroduce a
hardcoded face identity — that is what "Silver Sand" was, and it went away on
2026-08-27.

**Never change engine geometry in place.** Community faces are stored as
parameters, so `PatternEngines` IS the renderer for the stored file format.
Changing an engine's output silently rewrites every existing face. Add a branch
keyed on `generatorVersion` and leave older branches untouched.
`GeneratorVersionTest` fails loudly if you bump the version.

**Never skip `WffSchemaTest`.** A schema-invalid watch face compiles, links,
signs, installs — and then silently never appears in the carousel. There is no
runtime error. That test is the only signal you get.

**Do not add AGP to the watch face APK build.** Gradle injects `kotlin/` and
`DebugProbesKt.bin`, which Play accepts but Watch Face Push rejects.
`watchface-template/build.sh` uses aapt2 directly for this reason and asserts
the APK contents afterward.

**Record decisions in `DECISIONS.md`**, dated, with the reasoning and what was
rejected — not a changelog.

## Commands

```bash
./gradlew :generator:test
./gradlew :generator:test --tests '*WffSchema*'
./gradlew :workbench:test

./gradlew :workbench:workbench          # design loop at http://localhost:7777
./gradlew :workbench:bake               # dial_bg.png + preview.png + watchface.xml
./gradlew :workbench:bake --args="--preset=Rosette Noir"

make docs-check                         # markdownlint + cspell over the docs

cd watchface-template && ./build.sh    # validates + builds build/$FACE_SLUG.apk
cd watchface-template && ./reskin.sh <template.apk> <bg.png> <wff.xml> <out.apk>

scripts/setup-emulators.sh             # create + pair phone and Wear AVDs
scripts/deploy.sh                      # build and install to whatever adb sees
```

`ANDROID_HOME` must be set for anything touching aapt2 or adb.

## Conventions

- Colours in params are `#RRGGBB`. The emitter converts to WFF's `#AARRGGBB`
  (8 digits, alpha first). Do not store 8-digit colours in `DialParams`.
- Dial space is 456×456, origin top-left. Engines emit `List<Polyline>` there.
- Renderers stroke three times (light `-relief`, dark `+relief`, thin mid) for
  the engraved look. Do not bake that into the engines.
- Quantize the dial PNG to ≤64 colours before packing. It crosses to the watch
  over Bluetooth. Measured: 368KB → 77KB, mean error 0.66/255, indistinguishable.

## State of play

Verified — built and run, not assumed:

- `:generator` — 43 tests green, including validation against Google's official
  XSD and a v1↔v2 guard proving the version bump changed no existing geometry.
- `:workbench` — 35 tests green. Serves the app at localhost:7777; bakes
  `dial_bg.png`, `preview.png`, `watchface.xml`, `strings.xml` and the manifest
  package from parameters. Quantization measured at 64 colours, mean error
  0.51/255. Saves designs to `faces/<slug>.json`, the catalog format.
- `watchface-template` — builds a signed APK containing exactly the four paths
  Watch Face Push permits, named and packaged after the design being built.
  Verified by `unzip -l`, `apksigner verify` and `aapt2 dump badging` on
  2026-08-27.
- `reskin.sh` — swaps resources into a built APK without recompiling.
  (Written and read, but not exercised since the workbench landed.)

Scaffolded, never built or run:

- `:phone`, `:wear` — build files and a manifest only. Commented out of
  `settings.gradle.kts`. Uncomment as you implement them.

Never tested on hardware:

- Everything. No watch face from this repo has been confirmed to appear on a
  real watch. That is step one and it gates all of the rest. The APK now exists
  and is installable; what is missing is a Wear OS device or emulator to install
  it onto.
