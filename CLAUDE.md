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

**The app icon is generated, never hand-drawn.** `BrandMark` in `:workbench`
describes the mark once; `./gradlew :workbench:brand` writes the Android adaptive
icon for both apps, the 512px Play PNG and the SVGs under `docs/brand`. All of it
is checked in — the Android build must not depend on a JVM task having been run.
Judge any change with `--sheet=`, which crops the way a launcher does: the mask
takes the middle 72dp of the 108dp layer, not all of it. See `DECISIONS.md`
2026-08-29.

**Shared app rules live in `:appcore`, not `:workbench`.** `Presets`, the face
JSON (`FaceCodec`), the saved-face library (`FaceLibrary`) and its slug rule are
there because both shipped apps need them and `:workbench` is never shipped. The
slug is the Watch Face Push package suffix — a second implementation that
disagreed would install faces under a different package and silently stop
replacing them.

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
./gradlew :workbench:brand              # launcher icons, Play icon, docs/brand SVGs
./gradlew :workbench:brand --args="--sheet=/tmp/i.png"   # icon as a launcher masks it

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

- `:generator` — 343 tests green, including validation against Google's official
  XSD, a v1↔v2 guard proving the version bump changed no existing geometry, and
  every complication source checked against the schema's own provider list.
- `:appcore` — 38 tests green. Rules and words the shipped apps share, pure
  JVM. Not `:generator` (that is the file format) and not `:workbench` (never
  shipped). Holds `ActivationConsent`, whose one-shot rule guards the only
  unrecoverable action in the system.
- `:workbench` — 118 tests green. Serves the app at localhost:7777; bakes
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

- `:mobile` — four screens behind a bottom bar, matching the localhost app:
  Designs (styles gallery), Studio, My faces, About. Naming, local save in the
  catalog format, the Fine tune bottom sheet, complication size and spacing, and
  a Material 3 `ExposedDropdownMenuBox` for each slot. Seen on an SDK 36 phone
  emulator; a face saved and came back on the list. The Community tab reads the
  live catalog. Share, Report and imported images are still absent — Share and
  Report because Turnstile is not configured yet.
- `:wear` — installed on a Wear OS 6 emulator, and `addWatchFace` works from it.
  See below.

The phone builds a face on the device: `google/pack` runs through `PackBridge`,
`ApkSigning` signs it, and the validator issues the token — measured at 2.7s for
a 520KB APK.

Building the native library needs a toolchain, once:

```bash
scripts/build-pack.sh          # clones + patches pack, builds the desktop CLI
scripts/build-pack-android.sh  # libpack_java.so for four ABIs, into jniLibs/
```

Needs rustup, `cargo install cargo-ndk`, protoc and an NDK; the script says so
and names the install lines. The `.so` files are gitignored build output, and
deliberately not Androidify's prebuilt ones — see `docs/THIRD-PARTY.md`.

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

On Google Play (2026-08-29):

- `com.bfg.watchfaces` is live on **internal testing** in the BFG Solutions org
  account — phone `versionCode 1` on `internal`, watch `versionCode 1001` on
  `wear:internal`. Opt-in:
  `https://play.google.com/apps/internaltest/4701563329381059441`
- Publish with `scripts/play-release.py`, not the console. It reads the service
  account from 1Password, uploads and commits in one command. **A Wear bundle
  cannot go on the phone track** — Play rejects the commit — so phone and watch
  are two releases.
- Release signing: upload keystore in `~/.keystores` (outside the repo),
  password in 1Password, read from the environment at build time. A release
  build fails loudly rather than emitting an unsigned bundle.

On real hardware (2026-08-30):

- **A face designed on a Pixel 11 Pro XL reached a Pixel Watch 5 over Bluetooth
  and installed.** Built on the phone, sent over `ChannelClient`, installed by
  `FaceInstaller`. That closes the transport, sending a face, and the activation
  permission all at once — the three things this section listed as never tested.
- Finding it cost three bugs no emulator could have shown: v1 signing was off
  and Watch Face Push needs it; `sendFile(Uri.fromFile(...))` sends nothing and
  reports success; `receiveFile` completes when the transfer is SET UP rather
  than finished. See `DECISIONS.md` 2026-08-30.

The community catalog is live (2026-08-30):

- **The service runs at `https://bfg-catalog.bfg-solutions.workers.dev`** —
  Cloudflare Workers, D1, on the BFG Solutions account. Anonymous submit,
  anonymous report, pre-moderation, and an export endpoint so the catalog
  survives the service. `catalog-service/` holds it; `docs/specs/catalog-service.md`
  is the contract.
- **It does not know what a face is.** `params-contract.json` is GENERATED from
  `CatalogContract` in `:generator` by `./gradlew :workbench:contract`, and the
  Worker reads it. Never write those ranges out again in TypeScript.
- **The Community tab reads it**, verified on an SDK 36 phone emulator against
  the deployed service. `CatalogService` in `:appcore` is the one seam, and a
  test asserts the URL appears in exactly one file.
- **Moderation is `./gradlew :workbench:moderate`.** It is the only place a face
  meets Google's XSD — a Worker cannot run Xerces — so if it does not run before
  publication, nothing does.

Still never tested:

- **Submitting or reporting, end to end.** `TURNSTILE_SECRET` is not set, so the
  write endpoints fail closed with 403. That is the design working, and it is
  also why nothing can be shared yet. A live test asserts submissions are off
  and will FAIL when the widget lands.
- **There is no submit or report UI**, deliberately — a share button that cannot
  work is worse than no button.
- **Imported images.** `Engine.TEXTURE` still has nowhere on the device to
  resolve an image id from.
- **`reskin.sh`** — written and read, not exercised since the workbench landed.
