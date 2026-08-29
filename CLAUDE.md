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
emission all belong there. Only reach for `:mobile` or `:wear` when the task
genuinely needs Android APIs.

**Use the workbench instead of building an APK.** `./gradlew
:workbench:workbench` serves a live design loop on localhost with schema
validation on every change. Almost nothing about geometry, colour, layout or
ambient needs a device to judge. `:workbench` is also pure JVM and depends on
`:generator`, never the reverse — the generator stays the dependency-free
definition of the file format.

**The engraved look lives in `EngravedStroke`, in `:generator`.** Three passes
per polyline, described once as data and executed by each platform. Do not
recompute those colours or offsets in a renderer — `EngravedStrokeTest` pins the
exact ARGB, because changing them restyles every face already saved.

**There is one rasterizer, and it is `DialRenderer`.** The browser preview, the
`bake` task and the shipped `dial_bg.png` are all the same call. Do not add a
second one — in particular, do not "speed up" the preview by redrawing the
pattern in JavaScript. See `DECISIONS.md` 2026-08-27.

**There is no default face any more.** Presets are starting points; a face gets
its name when someone saves it, and that name becomes the carousel label, the
`watchfacepush.<slug>` package and the APK filename. Do not reintroduce a
hardcoded face identity — that is what "Silver Sand" was, and it went away on
2026-08-27.

**Controls come from `ControlInventory`.** Which sliders exist, their ranges and
their order live in `:generator`; both UIs build from it. Labels and the curated
engine order stay in the UI — those are presentation. Do not hardcode a control
list in a front end.

**Slot positions come from `SlotGeometry`, not from arithmetic you write.** The
emitter and the preview both call it. They used to compute boxes independently
with a test asserting they matched, and they matched while overlapping on both
axes and colliding with the clock. Do not reintroduce a second copy.

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
./gradlew :appcore:test
./gradlew :workbench:test

./gradlew :workbench:workbench          # design loop at http://localhost:7777
./gradlew :workbench:bake               # dial_bg.png + preview.png + watchface.xml
./gradlew :workbench:bake --args="--preset=Rosette Noir"
./gradlew :workbench:catalog            # validate catalog + rewrite index.json

make docs-check                         # markdownlint + cspell over the docs

./gradlew :wear:assembleDebug          # needs ANDROID_HOME + platform 36
./gradlew :mobile:assembleDebug

cd watchface-template && ./build.sh    # validates + builds build/$FACE_SLUG.apk
cd watchface-template && ./reskin.sh <template.apk> <bg.png> <wff.xml> <out.apk>

scripts/setup-emulators.sh             # create + pair phone and Wear AVDs
scripts/remote-adb.sh                  # check the bridge to a watch on ANOTHER machine
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

- `:generator` — 335 tests green, including validation against Google's official
  XSD, a v1↔v2 guard proving the version bump changed no existing geometry, and
  every complication source checked against the schema's own provider list.
- `:appcore` — 30 tests green. Rules and words the shipped apps share, pure
  JVM. Not `:generator` (that is the file format) and not `:workbench` (never
  shipped). Holds `ActivationConsent`, whose one-shot rule guards the only
  unrecoverable action in the system.
- `:workbench` — 98 tests green. Serves the app at localhost:7777; bakes
  `dial_bg.png`, `preview.png`, `watchface.xml`, `strings.xml` and the manifest
  package from parameters. Quantization measured at 64 colours, mean error
  0.51/255. Saves designs to `faces/<slug>.json`, the catalog format.
- `watchface-template` — builds a signed APK containing exactly the four paths
  Watch Face Push permits, named and packaged after the design being built.
  Verified by `unzip -l`, `apksigner verify` and `aapt2 dump badging` on
  2026-08-27.
- `reskin.sh` — swaps resources into a built APK without recompiling.
  (Written and read, but not exercised since the workbench landed.)

Installed and driven on emulators (2026-08-29):

- `:mobile` — the Studio screen from the localhost app: composite preview with
  clock and complications, ambient toggle, style chips, dial and ink swatches,
  complication slots, and every slider built from `ControlInventory`. All
  thirteen styles render. Seen running on an SDK 36 phone emulator.
- `:wear` — installed on a Wear OS 6 emulator, and `addWatchFace` works from it.
  See below.

Neither has run on real hardware, and the phone cannot yet BUILD a face to send:
that needs `google/pack` on the device and the validator wired in. The Studio
designs; nothing leaves the phone.

Confirmed on a Wear OS 6 emulator (2026-08-29):

- **A face from this repo appears in the watch face carousel, can be selected,
  and renders with live complications.** `sdk_gwear_x86_64`, release 16, SDK 36.
  `dumpsys wallpaper` shows `DeclarativeWatchFaceRuntime0` rendering it. This was
  step one in `docs/SPEC.md` and it gated everything else.
- The emulator runs on the operator's Windows laptop and is driven from here over
  an SSH reverse forward; this machine cannot run one (`/dev/kvm` unreachable).
  See `scripts/remote-adb.sh`.

Watch Face Push installs a face (2026-08-29):

- **`addWatchFace` works**, with a token from Google's validator, into a slot the
  system allocated. Run twice it takes the `updateWatchFace` branch instead —
  **the slot limit on that device is 1**, so the replace path is load-bearing.
- Doing it found two bugs no test here could: the manifest never requested
  `com.google.wear.permission.PUSH_WATCH_FACES` (the failure names `bindService`
  and never says "permission"), and `WatchFacePushManager` needs a context that
  can bind services, which a `BroadcastReceiver`'s cannot.
- Driven by `wear/src/debug`'s `DebugInstallReceiver`, which calls the same
  `FaceInstaller` the channel calls. **This proves the Push half only.**

Still never tested:

- **An actual watch.** The above is an emulator, which is a smaller claim.
- **The transport.** `CapabilityClient`, `ChannelClient` and the Bluetooth
  crossing. Pairing two emulators is blocked twice over: each Windows account
  gets its own `netsimd` (fixed — both now run from one), and a Wear device only
  advertises while inside its setup wizard, which `user_setup_complete=1` ends.
  Clearing the provisioning flags makes it discoverable and the phone still
  finds nothing; Google's documented route is Android Studio's pairing
  assistant. See `DECISIONS.md` 2026-08-29.
- **Sending a face.** The phone has no APK to send until `pack` runs on the
  device. `addWatchFace` was exercised by putting the APK on the watch directly.
- **The activation permission has still never been requested, and cannot be from
  where the design puts it.** `startActivity` from the install path is refused —
  `Background activity launch blocked`. A `WearableListenerService` is a
  background context by the same rule, so the shipped path is blocked too. This
  needs a design decision; see `DECISIONS.md` 2026-08-29.
