# Slot content: providers, apps and drawn sources

What can fill a complication slot, how a face records it, and why the watch's
own editor is no longer part of the answer.

Decided by interview 2026-08-30. Every trade-off below was measured on a Wear OS
6 emulator, not reasoned about — the measurements are in `DECISIONS.md` for the
same date.

## The problem this solves

Three separate reports, one cause:

- "The right complication is always the same as the bottom one, no matter what
  I check."
- "There's no weather."
- "Apps on my phone that give complications aren't in the list."

## What was actually wrong

`isCustomizable="TRUE"` on a `ComplicationSlot` lets the watch's editor assign a
data source to that slot. Once it has, `DefaultProviderPolicy` is **never**
consulted again — it only fills a slot nothing has been assigned to. So after
the first install, nothing chosen in the app could change what the watch drew.

Proven: a face declaring `battery / heart / steps / day-of-week` rendered
`steps / heart / -- / battery` from an earlier build, unchanged across three
different faces. The same face with `isCustomizable="FALSE"` rendered exactly
what it declared.

`updateWatchFace` keeps the slot, so it keeps the assignment. Removing and
re-adding gives a fresh slot and the policy applies — but that deactivates the
face, and **`setWatchFaceAsActive` succeeds once per app install**. Measured:
one success, then `SetWatchFaceAsActiveException: The maximum number of attempts
has been reached`. So remove-and-re-add buys one complication change per
reinstall and strands the wearer on a default face after that.

## The decision

**The face definition is authoritative.** `isCustomizable="FALSE"`, plain
`updateWatchFace`, no reset, no activation spent. What a person picks in the app
is what the watch draws, every time.

The reason this looked unacceptable — the watch's editor being the only route to
a third-party provider — no longer holds:

- `DefaultProviderPolicy/@primaryProvider` is an `xs:string` ComponentName, so a
  face can name **any** installed provider directly, with
  `defaultSystemProvider` as the required fallback.
- WFF has **first-class weather sources**, so weather needs no provider at all.

## One list, three kinds

Everything that can fill a slot appears in one picker. A slot stores **one
namespaced string**:

```json
"complications": [
  "DAY_AND_DATE",
  "draw:WEATHER_TEMP",
  "HEART_RATE",
  "app:com.acme.aqi/.Provider",
  "WATCH_BATTERY"
]
```

| Prefix | Meaning | Emitted as |
| --- | --- | --- |
| *(none)* | A WFF system provider | `defaultSystemProvider` |
| `draw:` | Drawn by us from a WFF source | a `PartText`, no provider |
| `app:` | An installed provider app | `primaryProvider` + a system fallback |

One field and one parser. A new kind is a new prefix, not a new field — which
matters because a field added to `DialParams` and forgotten in `FaceCodec` has
silently vanished twice already (`dateStyle`, and `generatorVersion`, which was
re-rendering stored faces with current geometry).

Unprefixed values are what every existing face stores, so old faces parse as
system providers with no migration.

## Weather

Drawn from WFF sources, never a complication:

| Slot option | Sources |
| --- | --- |
| Weather | `[WEATHER.TEMPERATURE]` `[WEATHER.TEMPERATURE_UNIT]` |
| Conditions | `[WEATHER.CONDITION_NAME]` |

Confirmed schema-valid in an emitted face. High, low, chance of precipitation
and UV index exist and are deliberately **not** offered yet: they are near-
identical rows in a picker we are keeping short, and several are meaningless
without a label beside them.

**When weather is unavailable** — no location permission, no data yet, an error
— the slot falls back to its system provider. The face is never a hole and never
a placeholder. `[WEATHER.IS_AVAILABLE]` and `[WEATHER.IS_ERROR]` are what decide.

## The picker

Curated first, discovered second:

```text
COMMON
  Off
  Day and date
  Steps
  Heart rate
  Weather
  Battery

MORE FROM YOUR WATCH
  Clock — Moon phase
  Contacts — Favourite contact
  …
```

Roughly fourteen system sources, what we draw, and 37+ installed apps on a bare
emulator. Nine in ten people want one of the six at the top; the rest stays
reachable. The curated order is presentation and lives in the UI, not in
`:generator`.

## Discovering what is installed

Complication providers are services **on the watch**. A phone app that shows step
counts has nothing to do with it, which is why the phone cannot build this list
itself.

`ProviderCatalog` (`:wear`) queries
`android.support.wearable.complications.ACTION_COMPLICATION_UPDATE_REQUEST` —
the legacy action, deliberately: providers written against AndroidX register it
too, and querying the `androidx.` spelling returns nothing. Measured on Wear OS
6: 37 services for the legacy action, zero for the other.

Filtered to sources that can supply `SHORT_TEXT`, since that is the only type
these slots declare. A provider that declares no types is kept rather than
dropped — far more likely to be one this parser does not understand than one
that supplies nothing, and the slot falls back anyway.

**The list rides back on a successful send** and is cached on the phone. No
extra round trip, no new connection state, and the watch was reachable by
definition. The list is therefore as fresh as the last send: an app installed
since will not appear until the next one. That is the accepted cost of a picker
that still works with the watch charging in another room.

## Faces that name an app you do not have

The watch falls back to the system provider on its own, so the dial is never
broken. The app additionally shows a quiet note naming the missing app, because
a face that silently renders differently from its preview is exactly the
invisible-difference problem that made these bugs so hard to find.

## Migration

The first send after this ships resets the slots to the design, and the app says
so **once**: complications are chosen in the app now. Anyone who customised on
the watch loses those tweaks a single time, with an explanation, rather than
finding them revert silently and reasonably calling it a bug.

## Activation

The watch switches to a face once per app install and refuses afterwards. That
is normal, not an error, and must not read like one.

- Try once. On refusal, remember it and stop calling — no pointless IPC, and no
  error in the log on every send hiding a real failure.
- Retry after the watch app is reinstalled or updated, which is the only point
  the allowance is known to reset.
- What the person sees:

```text
“My Face” is on your watch.
Long-press your watch face to pick it.
```

## Still unused

In the schema, offered by nothing here yet: `STEP_GOAL` and `STEP_PERCENT` (a
goal ring needs no complication), `HEART_RATE_Z`, and the accelerometer family
`ACCELEROMETER_ANGLE_X/Y/Z/XY` with `ACCELEROMETER_IS_SUPPORTED`, which is what
tilt-reactive faces are built from.
