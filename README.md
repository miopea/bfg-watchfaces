# BFG Watch Faces

Open-source Wear OS watch face generator. Design a dial on your phone, push it
to your watch. No server, no account, no ads.

Requires Wear OS 6+ (Pixel Watch 4 / 5, recent Galaxy Watch).

**Silver Sand** is the default face it ships with — a warm-taupe botanical dial.
The app is the generator; Silver Sand is one preset it can make.

## Quick start

```bash
scripts/bootstrap.sh          # fetch WFF schemas + XSD 1.1 jars + Gradle wrapper
./gradlew :generator:test     # 35 tests, no Android SDK or device needed
```

That is the whole inner loop for pattern and layout work.

To build a real watch face APK:

```bash
export ANDROID_HOME=~/Android/Sdk    # needs build-tools;34.0.0, platforms;android-34
cd watchface-template && ./build.sh
adb install -r build/silver-sand.apk
```

On Windows, run the shell scripts from WSL or Git Bash.

## Layout

| path | what |
|---|---|
| `generator/` | Pure Kotlin. Engines, params, WFF emitter. **Start here.** |
| `phone/` | Compose design UI (scaffold) |
| `wear/` | Watch Face Push host (scaffold) |
| `watchface-template/` | Verified aapt2 WFF build — the Silver Sand reference face |
| `docs/SPEC.md` | Architecture and constraints — read this first |
| `DECISIONS.md` | Dated record of why things are the way they are |
| `CLAUDE.md` | Notes for AI-assisted work |

## How it works

A watch face is Watch Face Format: declarative XML plus a background image, no
code. `generator` turns a small parameter set into that XML and the geometry for
the dial texture. On a phone, `google/pack` compiles and signs the APK locally
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
