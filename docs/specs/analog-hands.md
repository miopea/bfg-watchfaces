# Analog hands

Scoped 2026-09-02. Hands as a second way to tell the time, alongside the digital
clock the app has always had.

`backlog.md` is everything not built. This file is only what was scoped, in the
order it was scoped. Decisions taken are in `DECISIONS.md`.

## 0. The constraint that shaped every other answer

Watch Face Format hands are **images**, not shapes. From the official XSD,
`clock/hourHand.xsd`:

```xml
<xs:element name="HourHand">
  <xs:complexType>
    <xs:choice maxOccurs="unbounded">
      <xs:element ref="Variant" minOccurs="0" maxOccurs="unbounded"/>
    </xs:choice>
    <xs:attribute name="resource" type="xs:string" use="required"/>
    <xs:attributeGroup ref="geometricAttributesRequired"/>
    <xs:attributeGroup ref="pivot2D"/>
    <xs:attribute ref="alpha"/>
    <xs:attribute name="tintColor" type='colorAttributeType'/>
```

`resource` is required and there is no `PartDraw` child. A hand cannot be
described as geometry in the face definition — it must be a PNG in the APK.

That is not a limitation here, it is a fit. `DialRenderer` already rasterizes
`dial_bg.png`, `FaceBuilder` already packs `drawable-nodpi`, and quantization to
64 colours is already measured. Hands are three more entries on a path that
exists.

## 1. What gets built, in one line each

| Piece | Where | What it is |
| --- | --- | --- |
| `HandStyle` | `:generator` | Enum: `BATON`, `DAUPHINE`, `SYRINGE`, `SKELETON` |
| `Hands` | `:generator` | `HandStyle` → `List<Polyline>` per hand, in dial space |
| `ClockMode` | `DialParams` | `DIGITAL` or `ANALOG`, exclusive |
| Chapter ring | `Hands` | Indices, owned by the style |
| Analog layout | `SlotGeometry` | Sub-dials, replacing the top/row/bottom stack |
| Hand rasterizing | `DialRenderer` | Three PNGs per built face |
| `AnalogClock` | `WffEmitter` | The element, its hands, and their ambient variants |

`Hands` is shaped exactly like `PatternEngines`: pure Kotlin, dial space,
`List<Polyline>` out, stroked by `EngravedStroke`, version-branched. It runs in
seconds with no device, and the workbench judges it.

## 2. The look: baked, not tinted

**Decided: hands are rendered per face by `DialRenderer`, not shipped as white
PNGs tinted by WFF.**

`tintColor` can paint a hand one colour. The engraved look is three passes in
three different colours — light `-relief`, dark `+relief`, thin mid — so tinting
cannot reproduce it. A tinted hand would be flat against an engraved dial, which
is the exact mismatch `EngravedStroke` exists to prevent.

Cost is three more PNGs in every built face. Against a 520KB APK that is small,
and it buys one definition of the house style rather than two that drift.

## 3. The art: full canvas, centre pivot

**Decided: every hand PNG is the full 456×456 dial, with the hand drawn in
place, and `pivotX="0.5" pivotY="0.5"` on all three, always.**

The alternative — crop to the bounding box and store a pivot per style — makes
smaller files and introduces a class of bug this project should refuse: a wrong
pivot is a hand that wobbles as it sweeps, which is subtle enough to ship and
maddening to diagnose from a photograph.

Mostly-transparent PNGs quantize to very little, and the full canvas is the same
coordinate space the engines and `SlotGeometry` already use, so there is nothing
to convert.

## 4. The rim: two rings, not one

**Decided: `RingSource` keeps the outer rim. The chapter ring is drawn just
inside it.**

`RingSource` is a *data* ring — steps, battery, chance of rain, each a
percentage swept round the rim. It is not decoration and analog faces have no
reason to give it up. The chapter ring goes inboard, costing a few pixels of
radius.

**The indices belong to the hand style.** Baton gets thick batons, Dauphine fine
lines, Skeleton dots. Choosing a style therefore produces a coherent watch
rather than a parts bin, and there is no third control to mismatch.

No numerals in v1. Numerals need a font, per-index rotation and twelve
placements, and they are the part most likely to look wrong; they are a later
style axis, on evidence.

## 5. The layout: sub-dials, and the 6 o'clock collision

**Decided: sub-dials at 9 and 3, with 6 as a third — unless the date is on, in
which case 6 is the date window.**

Hands sweep the whole dial, so `SlotGeometry`'s digital assumption — reserve a
centre band, stack complications above and below it — stops meaning anything. A
complication at centre-left is under the minute hand twice an hour.

```text
date ON                    date OFF
  ╭──────────────╮           ╭──────────────╮
  │      12      │           │      12      │
  │   ╲      ╲   │           │   ╲      ╲   │
  │ (9)   ●   (3)│           │ (9)   ●   (3)│
  │    [ 01 ]    │           │     (6)      │
  ╰──────────────╯           ╰──────────────╯
```

The layout adapts to what is switched on, which is what `SlotGeometry` already
does for the digital row. No new control, and the collision cannot occur.

**Sub-dials draw the value only — no ring around them.** That reuses the
existing box and text fitting wholesale, including the v11 widening work, and
keeps the dial pattern visible, which is the point of the app.

### Switching modes must not destroy anything

**Decided: `DialParams` keeps all five slots; the analog layout renders the ones
it has room for.** Switch to hands and back, and the face is exactly as it was.
Trying a mode out is not allowed to silently delete two of someone's choices.

## 6. The digital readout, and an accepted cost

**Decided: a small digital time may appear with hands, at a fixed position under
the 12.**

This was chosen against the recommendation, deliberately, and the cost is real
and recorded here so nobody rediscovers it as a bug: **the hour hand crosses that
readout for roughly two hours in twelve**, around 11:00–1:00, and it sits near
the 12 index. The alternative offered was to spend a sub-dial slot on it, where
nothing can collide.

If it reads badly on a wrist, the fix is to move it to a sub-dial, not to add a
placement control.

## 7. Seconds

**Decided: the second hand ticks at 1Hz and obeys the existing `showSeconds`
control.** No new control and no new battery story. Off means no second hand;
ambient hides it either way.

Smooth sweep needs `[SECOND_MILLISECOND]` and a much higher frame rate on an
always-on display. That is a battery decision and nothing here has measured it,
so it would ship as a guess. Later, on evidence.

**The second hand may take its own colour**, one nullable field in `DialParams`
falling back to the ink. The red seconds hand is the most recognisable analog
convention there is and it costs almost nothing.

## 8. Ambient

**Decided: second hand hidden, hour and minute drawn as outlines.**

Ambient is a black low-power screen. The second hand goes to `alpha 0` via
`<Variant mode="AMBIENT" target="alpha" value="0"/>`, the pattern the emitter
already uses for the step ring and the drawn date. Hour and minute switch to
outline artwork, which means **two renders per hand style** — solid and outline
— not one.

That is consistent with the dial already lifting its ink in ambient from v3.

## 9. Where people find it

**Decided: analog presets in the Designs gallery, plus mode and style controls
in Studio.**

Presets are how this app has always introduced a look, and a feature only
reachable from a settings screen is a feature most people never learn exists.
Studio is where someone tuning a face changes it.

Hands are **not** an `Engine`. An engine is the dial pattern; conflating the two
would break what the word means and every engine test with it.

## 10. Order of work

1. `HandStyle` + `Hands` in `:generator`, `BATON` only, with golden tests.
2. `DialRenderer` renders a hand to a PNG; workbench shows it.
3. `ClockMode` on `DialParams`, gated at the next `generatorVersion`.
4. `WffEmitter` emits `AnalogClock` — **`WffSchemaTest` is the gate.** A
   schema-invalid face installs, signs, and silently never appears in the
   carousel. There is no runtime error.
5. Analog branch in `SlotGeometry`, with the date/6 rule.
6. Chapter ring, per style.
7. Ambient outlines.
8. `DAUPHINE`, `SYRINGE`, `SKELETON`.
9. Studio controls and Designs presets.
10. On a real wrist: hands sweep true, pivot does not wobble, ambient behaves.

All four styles ship together in the first release. The pipeline risk is
identical whether there is one style or four, and step 1–4 proves it before any
of the extra art is drawn.

## 11. Consequences to remember

- **`generatorVersion` bumps**, so `PatternEngines` needs a delegating branch —
  no engine changes for this — and `GeneratorVersionTest` will fail until the
  assertion is updated.
- **`params-contract.json` goes stale**, and the catalog Worker rejects any face
  above its `currentGeneratorVersion`. Regenerate with
  `./gradlew :workbench:contract` and **redeploy the Worker**, or every analog
  face is refused on submit. This has now caught the project twice.
- **The `:appcore` slug and Push package rules are unchanged.** An analog face
  is still a face.
