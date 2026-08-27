# BFG Watch Faces -- Specification

An open-source Wear OS watch face generator. Users design a dial on their phone
and push it to their watch. No server, no account, no ads, no cost to run.

## Why this is possible at all

Three pieces landed recently that together remove every reason this needed a backend:

1. **Watch Face Format (WFF)** -- watch faces are declarative XML plus resources,
   with no executable code. Required for all Wear OS installs since January 2026.
2. **Watch Face Push** (Wear OS 6+) -- an app can install watch faces on the watch
   at runtime, no Play Store round trip.
3. **`google/pack`** -- a Rust library that compiles and signs APKs on-device,
   with no JDK, no Android SDK, no `android.jar`. Written specifically for WFF
   packages. Google's own Androidify app uses it.

So the entire pipeline runs on the phone:

```
params -> pack builds APK -> validator issues token -> Data Layer -> addWatchFace()
```

No network call anywhere in that chain.

## The key architectural fact

**Community faces are parameter files, not images.**

A face is ~5KB of JSON. The phone regenerates the artwork locally. Consequences:

- A catalog of 10,000 faces is ~50MB of Git repo. Hosting is free via jsDelivr.
- **The generator is the file format.** `DialParams` + `PatternEngines` together
  define what a stored face means. Changing engine geometry silently rewrites
  every community face. This is why `generatorVersion` exists and why
  `GeneratorVersionTest` guards it.
- Parametric-only submissions are also the IP shield: you cannot encode a
  copyrighted logo as "clous engine, scale 34, pewter." Users import their own
  photos locally; those never enter the shared catalog.

## Modules

```
generator/           Pure Kotlin/JVM. No Android. Engines, params, WFF emitter.
                     35 tests, runs in CI without an emulator. Do work here first.
phone/               Compose app: design UI, pack integration, Data Layer client.
wear/                Thin Wear app: WatchFacePushManager, Data Layer listener.
watchface-template/  Standalone aapt2 WFF project. The Silver Sand reference face.
scripts/             bootstrap, emulator setup, device deploy.
```

`generator` is deliberately Android-free. It is the piece you will iterate on
most, and it must be testable without a device.

## Renderer split

Engines emit `List<Polyline>` in 456x456 dial space. Platform code strokes them.
One geometry implementation, two renderers:

- `phone`: Android `Canvas` -- drives both the live preview and the baked PNG,
  so what the user sees is what ships, by construction.
- CI/tests: geometry assertions only, no rasterization needed.

Stroke each polyline three times for the engraved look: light pass at
`-relief`, dark pass at `+relief`, thin mid pass at zero.

## Non-negotiable constraints

Every one of these was found the expensive way. Do not relax them.

### WFF
- Canvas is 456x456. Correct for Pixel Watch 4 and 5, both case sizes.
- Colours are `#AARRGGBB`. Eight digits, **alpha first**. Six-digit values are
  silently wrong, not rejected.
- Ambient is per-element `<Variant mode="AMBIENT" target="alpha" .../>`, not a
  second `<Scene>`.
- `DefaultProviderPolicy` takes `defaultSystemProvider` / `defaultSystemProviderType`.
- `BoundingArc` needs `centerX`/`centerY`; rectangular slots want `BoundingBox`.
- `res/xml/watch_face_info.xml` with a `<Preview>` is **required**.

A schema-invalid face compiles, links, signs, and installs -- then never appears
in the carousel, with no error anywhere. `WffSchemaTest` is the only defence.

### Watch Face Push
- Package names must be `<app package>.watchfacepush.<face name>`. Rejected otherwise.
  Here: `com.bfg.watchfaces.watchfacepush.<slug>`. `applicationId` is frozen at
  first Play release and can never be changed afterwards.
- APK may contain **only**: `/AndroidManifest.xml`, `/resources.arsc`, `/res/**`,
  `/META-INF/**`. Manifest tags limited to `manifest`, `uses-feature`, `uses-sdk`,
  `application`, `property`, `meta-data`. `minSdk >= 33`, `hasCode="false"`.
  (AGP adds `kotlin/` and `DebugProbesKt.bin`. Play accepts those; Push rejects
  them. This is why `watchface-template` uses aapt2 directly.)
- Every pushed face needs a validation token from the official validator.
  Tokens never expire -- cache by APK hash. Re-run occasionally; the tool updates.
- Memory footprint is 4 bytes/pixel/frame. A 456x456 RGBA dial is ~831KB.
  Budgets are roughly 10MB ambient, 100MB active.
- **`SET_PUSHED_WATCH_FACE_AS_ACTIVE` cannot be re-requested after denial, and
  `setWatchFaceAsActive()` is one-shot.** This is the only unrecoverable action
  in the system. Design the prompt before writing the code around it.
- Slots cap how many faces the app may have installed at once.

### Transfer
The APK crosses to the watch over Bluetooth. Quantize the dial PNG before
packing: measured 368KB -> 77KB at 64 colours, mean error 0.66/255, visually
identical on a soft low-contrast dial. Do this always.

## Build order

1. **Install `watchface-template` on a real watch.** Everything else assumes
   this works. Until a face appears in the carousel, nothing downstream matters.
2. **Design the permission flow.** On paper. Before code.
3. **`pack` via JNI.** Use Androidify's prebuilt `jniLibs` if the ABIs cover you.
4. **Data Layer.** `CapabilityClient` discovery, `ChannelClient` transfer.
5. **Compose design UI.** Ten sliders next to a 456px preview on a phone screen
   is a genuinely harder design problem than a desktop tool was.

## Community catalog (later)

- Repo of `faces/*.json`, one per face, plus a generated `index.json`.
- Served via jsDelivr -- free, no bandwidth limits, GitHub-integrated.
  Not `raw.githubusercontent.com`, which is not a CDN and is rate limited.
- Submissions are PRs. GitHub Actions runs the schema validator automatically,
  so invalid faces are rejected before human review.
- **Parametric submissions only.** No uploaded rasters.
- Play's UGC policy requires in-app reporting and moderation. Budget for it.

## Ongoing costs

Zero money. Two recurring time costs, forever:
- Annual target-API bumps, or Play delists the app.
- `validator-push` is at `1.0.0-alpha10`. The API will move.

## Market constraint

Watch Face Push is Wear OS 6+ only: Pixel Watch 4 and 5, recent Galaxy Watch.
Pixel Watch 1-3 cannot run this. The addressable market is small today and
growing.
