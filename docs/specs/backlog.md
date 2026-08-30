# Backlog

Everything discussed and not built, so it stops living in a chat thread.
Written 2026-08-30. Ordered by what unblocks the most.

Decisions already taken are in `DECISIONS.md`; the slot model is specified in
`slot-content.md`. This file is only the things that are NOT done.

## ~~1. Third-party complication providers in the picker~~ — DONE 2026-08-30

The watch enumerates its providers, the list rides back on a successful send,
the phone caches it, and the picker offers them under "From your watch" with the
owning app as a subtitle. Choosing one writes `primaryProvider` with the system
source kept as the fallback.

The cost, as decided: the list is as fresh as the last send, so an app installed
since will not appear until the next one. That is what keeps the picker working
with the watch out of range.

Original note follows.

**Half built.** `ProviderCatalog` (`:wear`) enumerates every complication data
source installed on the watch — 37 on a bare emulator — and filters to the ones
that can fill a SHORT_TEXT slot. The file format already carries a chosen app:
`NAME+app:pkg/cls`, schema-valid, round-trips.

**Missing:** nothing carries the list to the phone. Decided: it rides back on a
successful send and is cached, so the picker works with the watch out of range
and is as fresh as the last send.

This is what "Google Health isn't in the list" needs, and weather no longer
depends on it.

## ~~2. Tap actions~~ — DONE 2026-08-30

A slot can hold a SHORTCUT: a glyph you press, with no reading. Music, Alarms,
Settings, Phone, Calendar and Messages, using Watch Face Format's own
`<Launch>` targets. Verified on a watch: tapping the alarm glyph opened
`com.google.android.deskclock/.AlarmGatewayActivity`.

The glyphs go over as `PartDraw` vectors rather than baked PNGs.

Launching an arbitrary app by ComponentName is done too. The watch enumerates
what can be opened, the list rides back on the same reply, and a slot set to
"Open an app" names one. Verified on a watch: tapping the slot opened
`com.google.android.contacts/…ContactsActivity`.

## 3. More weather

`[WEATHER.TEMPERATURE_HIGH]`, `TEMPERATURE_LOW`, `CHANCE_OF_PRECIPITATION`,
`UV_INDEX`, `IS_DAY`. Deliberately not offered yet: several are meaningless
without a label beside them, which is a layout question, not a plumbing one.

`IS_AVAILABLE` / `IS_ERROR` are also unused — the decided behaviour when weather
is unavailable is to fall back to the slot's system provider, and that is not
built either.

## 4. Sources in the schema that nothing here offers

- `STEP_GOAL`, `STEP_PERCENT` — a goal ring with no complication involved
- `HEART_RATE_Z`
- `ACCELEROMETER_ANGLE_X/Y/Z/XY` with `ACCELEROMETER_IS_SUPPORTED` — what
  tilt-reactive faces are built from

## 5. Recovering a full slot

When the watch's only slot holds a face this install cannot attribute to itself,
`addWatchFace` fails forever. There is no API out: `removeWatchFace` needs a
slotId the app owns, and the problem is that it owns none.

The failure now names the only remedy — reinstall the watch app — because an
operator had to work that out themselves after an evening of sends that all
reported success. A real fix would need something from the platform.

## 6. The one-time migration note

Decided in the interview and not built: the first send after the definition
became authoritative should say once that complications are chosen in the app
now, so anyone who customised on the watch understands why their picks changed.

## 7. Faces that name an app you do not have

The watch falls back to the slot's system provider on its own, so nothing looks
broken. Decided: My faces should also show a quiet note naming the missing app,
because a face that silently renders differently from its preview is the
invisible-difference problem that made this week's bugs so hard to find.

## 8. Community catalog, Share, Report

Still blocked on the operator interview. `catalog-service.md` has the shape.

## 9. Imported images

`Engine.TEXTURE` needs a bitmap the face only references by id, and there is
nowhere on the device to resolve that id from yet. Both previews fall back to a
plain dial.

## 10. Testing the transport without real hardware

Every expensive bug this week lived between "the bytes left the phone" and "the
face is on the watch", and none of it can be exercised here: the emulators
cannot pair, so the Data Layer path — including the new install report — is
first run on the operator's own wrist.

That is the single biggest gap in this project's ability to test itself.

## 11. Production release

The store listing is saved and never submitted for review. Both apps are on
internal testing only.
