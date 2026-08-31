# BFG Watch Faces -- Specification

An open-source Wear OS watch face generator. Users design a dial on their own
device and push it to their watch. No server, no account, no ads, no cost to run.

## Why this is possible at all

Three pieces landed recently that together remove every reason this needed a backend:

1. **Watch Face Format (WFF)** -- watch faces are declarative XML plus resources,
   with no executable code. Required for all Wear OS installs since January 2026.
2. **Watch Face Push** (Wear OS 6+) -- an app can install watch faces on the watch
   at runtime, no Play Store round trip.
3. **`google/pack`** -- a Rust library that compiles and signs APKs on-device,
   with no JDK, no Android SDK, no `android.jar`. Written specifically for WFF
   packages. Google's own Androidify app uses it.

So the entire pipeline runs on the device:

```text
params -> pack builds APK -> validator issues token -> Data Layer -> addWatchFace()
```

No network call anywhere in that chain.

### Why there are two apps, and why that is not a choice

The Data Layer hop above is the part people expect to be able to skip, so it is
worth saying plainly: **a small app on the watch is mandatory.**

`WatchFacePushManager` is the only way to install a face, and its entire surface
runs on the watch — the library declares `<uses-library android:name="wear-sdk"
android:required="true" />`, so an app linking it cannot install on a phone or
tablet at all. Its install call is:

```kotlin
addWatchFace(apk: ParcelFileDescriptor, validationToken: String): WatchFaceDetails
```

A `ParcelFileDescriptor` is a handle to a LOCAL file. There is no "send this to
the paired watch" call anywhere in the API, so the APK has to already be a file
on the watch before anything can install it. Getting it there is the Data Layer's
job, and something on the watch has to be listening.

So the split is forced:

| Where | Does |
| --- | --- |
| `mobile/` | design, `pack` builds the APK, validator issues the token, sends the bytes |
| `wear/` | receives the bytes, `addWatchFace`, `setWatchFaceAsActive` |

**The user still installs one thing.** Both modules share
`applicationId = "com.bfg.watchfaces"`, which is how Wear OS delivers a watch
component alongside its handheld app — no separate download, no Play trip per
face. That shared id is also what makes the face package names legal:
`addWatchFace` rejects anything not starting with the Watch Face Push client's
own package, and the client is the WATCH app.

`setWatchFaceAsActive` is therefore also watch-side, which is where
`SET_PUSHED_WATCH_FACE_AS_ACTIVE` has to be granted. See `DECISIONS.md`
2026-08-28 on the activation prompt.

## The key architectural fact

**Community faces are parameter files, not images.**

A face is ~5KB of JSON. The device regenerates the artwork locally. Consequences:

- A catalog of 10,000 faces is ~50MB. That is why it can be hosted for nothing,
  and it is why an index of it is one request rather than a thousand.
- **The generator is the file format.** `DialParams` + `PatternEngines` together
  define what a stored face means. Changing engine geometry silently rewrites
  every community face. This is why `generatorVersion` exists and why
  `GeneratorVersionTest` guards it.
- Parametric-only submissions are also the IP shield: you cannot encode a
  copyrighted logo as "clous engine, scale 34, pewter." Users import their own
  photos locally; those never enter the shared catalog.

## Modules

```text
generator/           Pure Kotlin/JVM. No Android. Engines, params, WFF emitter.
                     35 tests, runs in CI without an emulator. Do work here first.
mobile/              Compose app: design UI, pack integration, Data Layer client.
wear/                Thin Wear app: WatchFacePushManager, Data Layer listener.
watchface-template/  Standalone aapt2 WFF project. Packaged per design.
scripts/             bootstrap, emulator setup, device deploy.
```

`generator` is deliberately Android-free. It is the piece you will iterate on
most, and it must be testable without a device.

## Renderer split

Engines emit `List<Polyline>` in 456x456 dial space. Platform code strokes them.
One geometry implementation, two renderers:

- `mobile`: Android `Canvas` -- drives both the live preview and the baked PNG,
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
5. **Compose design UI.** Ten sliders next to a 456px preview on a handheld screen
   is a genuinely harder design problem than a desktop tool was.

## Community catalog

**A service, not a repository.** <https://bfg-catalog.bfg-solutions.workers.dev>
— Cloudflare Workers and D1, on the BFG Solutions account. Deployed 2026-08-30.
`docs/specs/catalog-service.md` is the contract; this is the summary.

It used to be a public git repository of `faces/<slug>.json` with submissions
arriving as pull requests. That model is gone entirely, for one reason:
**GitHub has no anonymous write path.** Commits, pull requests, issues and gists
all need an account, and reading anonymously does not help when the thing you
need is for a stranger to be able to complain. Requiring an account to report
became indefensible the moment submitting did not.

- **Reads are anonymous and cheap.** `GET /index.json` carries name, author,
  engine, colours and an install count, so a gallery of a thousand faces is ONE
  request. Full parameters live behind `/faces/<slug>` and are fetched when
  somebody opens one. Served from the edge cache.
- **Publishing needs a Google sign-in. Reporting never does.** That asymmetry is
  the design, not a compromise: publishing is a privilege, complaining is not.
  The service stores a hash of the Google subject id and nothing else — no name,
  no email.
- **Nothing is public until a person approves it.** With little or no identity
  behind a submission, pre-moderation is the abuse control; rate limiting only
  slows a flood that still lands.
- **Parametric submissions only.** `TEXTURE` faces are refused at the boundary.
- **`GET /export`** emits every published face as the same `faces/<slug>.json`
  and `index.json` the git catalog used. That is deliberate: it is what buys
  back the portability a repository gave for free, and it means the on-disk
  format stays the interchange format rather than becoming legacy.

### The service does not know what a face is

`catalog-service/params-contract.json` is GENERATED from `CatalogContract` in
`:generator` by `./gradlew :workbench:contract`. Ranges come from
`ControlInventory`, bounds from `SlotGeometry`, enums from `DialParams`, the
field list from `FaceCodec.toQuery`. The Worker reads that file; it never
decides what a legal face looks like. Writing those ranges out again in
TypeScript would be a second definition of the file format.

### Where a face meets Google's XSD

`./gradlew :workbench:moderate`. A Worker is JavaScript and cannot run Xerces,
so the schema check moved to a JVM pass that runs before publication — and it is
the ONLY place it happens. A schema-invalid face installs cleanly and then never
appears in the carousel, with no error on either side.

Moderation policy lives in the catalog repository's `MODERATION.md`: what is
disallowed, how to report it, and how fast a report is acted on. Play's UGC
policy requires a working complaint path for any app surfacing user content.

Still open:

- **Sharing from the app**, which needs a Google OAuth client id. The service
  fails closed without one, so publishing is off rather than open.
- **A route for rights holders who are not app users**, and a way to answer an
  appeal. `MODERATION.md` states this gap rather than hiding it. It is a
  condition of the catalog being opened to the public.
- **Retiring `miopea/bfg-watchfaces-catalog`.** Authorised; the repository is
  already inert.

## Ongoing costs

Zero money. Two recurring time costs, forever:

- Annual target-API bumps, or Play delists the app.
- `validator-push` is at `1.0.0-alpha10`. The API will move.

## Market constraint

Watch Face Push is Wear OS 6+ only: Pixel Watch 4 and 5, recent Galaxy Watch.
Pixel Watch 1-3 cannot run this. The addressable market is small today and
growing.
