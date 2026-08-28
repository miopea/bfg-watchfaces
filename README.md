# BFG Watch Faces

Open-source Wear OS watch face generator. Design a dial on your own device, push it
to your watch. No server, no account, no ads.

Requires Wear OS 6+ (Pixel Watch 4 / 5, recent Galaxy Watch).

The app is a generator. It ships a library of styles — knotwork, clous de Paris,
rosette, barleycorn, sunburst, botanical — and you name the faces you make.

Faces carry five complication slots (top, left, middle, right, bottom), and you
can import your own image as the dial. Generated faces are a few KB of
parameters and shareable; a face built on your own picture stays on your
machine.

## Quick start

```bash
scripts/bootstrap.sh              # fetch WFF schemas + XSD 1.1 jars + Gradle wrapper
./gradlew :generator:test         # 35 tests, no Android SDK or device needed
./gradlew :workbench:test         # 35 tests, rasterizer, quantizer, face store
./gradlew :workbench:workbench    # design loop at http://localhost:7777
```

That is the whole inner loop for pattern and layout work.

## The workbench

```bash
./gradlew :workbench:workbench    # then open http://localhost:7777
```

A four-tab app: browse styles, customise one in the Studio against a live
watch preview, name it, and build an installable APK. It validates the emitted
WFF against Google's XSD **on every change** — which matters more than it sounds,
because a schema-invalid face installs perfectly and then silently never appears
in the carousel.

Saved faces go to `faces/<slug>.json` — parameters only, a few KB each, which is
exactly the community catalog format.

The browser never draws the pattern itself. It requests PNGs rendered by the
same code that bakes the shipped file, so the preview cannot drift from the
artefact. The dial is exact; the text is positioned from `WffEmitter`'s own
arithmetic but drawn with a local font, so judge composition here and kerning on
the wrist.

The name you give a face becomes its carousel label, its
`com.bfg.watchfaces.watchfacepush.<slug>` package and its APK filename.

Headless, for scripts and CI:

```bash
./gradlew :workbench:bake                                   # first preset
./gradlew :workbench:bake --args="--preset=Knotwork Graphite"
./gradlew :workbench:bake --args="--engine=CLOUS --scale=22 --dialColor=#6E6A66"
```

`bake` refuses to write a schema-invalid face.

## Building a real APK

```bash
export ANDROID_HOME=~/Android/Sdk    # needs build-tools;34.0.0, platforms;android-34
./gradlew :workbench:bake            # generates dial_bg.png + preview.png
cd watchface-template && ./build.sh
adb install -r build/<your-face>.apk
```

On Windows, run the shell scripts from WSL or Git Bash.

## Layout

| path | what |
| --- | --- |
| `generator/` | Pure Kotlin. Engines, params, WFF emitter. **Start here.** |
| `workbench/` | Localhost design loop, rasterizer, quantizer, headless bake |
| `mobile/` | Compose design UI (scaffold) |
| `wear/` | Watch Face Push host (scaffold) |
| `watchface-template/` | Verified aapt2 WFF build — packaged per design |
| `docs/SPEC.md` | Architecture and constraints — read this first |
| `DECISIONS.md` | Dated record of why things are the way they are |
| `CLAUDE.md` | Notes for AI-assisted work |

## How it works

A watch face is Watch Face Format: declarative XML plus a background image, no
code. `generator` turns a small parameter set into that XML and the geometry for
the dial texture. On the device, `google/pack` compiles and signs the APK locally
and Watch Face Push installs it on the watch. Nothing touches a server.

Because faces are stored as parameters rather than images, a shared face is a
few kilobytes of JSON, and the generator is effectively the file format. See
`docs/SPEC.md` for why that matters and what it constrains.

## Naming

- **App / Play listing:** BFG Watch Faces
- **applicationId:** `com.bfg.watchfaces` — frozen at first release, never changeable
- **Pushed faces:** `com.bfg.watchfaces.watchfacepush.<slug>` — required shape
- **Kotlin package:** `com.bfg.watchfaces.generator`

## License

Apache 2.0.
