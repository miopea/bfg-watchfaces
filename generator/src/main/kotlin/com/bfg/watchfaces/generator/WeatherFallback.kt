package com.bfg.watchfaces.generator

/**
 * What weather shows when the watch has none: a drawn slot, and the rain ring.
 *
 * Two bumps, two years apart in version numbers and the same idea. v14 gated
 * the drawn TEXT slots and left [WffEmitter] emitting the rim ring
 * unconditionally; v16 closes that, because an arc is the worse of the two
 * places to state a figure nobody measured. See `DECISIONS.md` 2026-10-12.
 *
 * ## The hole this fills
 *
 * `WEATHER.IS_AVAILABLE` and `WEATHER.IS_ERROR` have been in the schema since
 * the beginning and nothing here read either. A drawn weather slot asked for
 * `[WEATHER.TEMPERATURE]` unconditionally, so a watch with no location, or with
 * weather not set up, got whatever the runtime does with an absent value — which
 * is not documented anywhere and which this project has never observed.
 *
 * ## The behaviour that was recorded, and why it is not this one
 *
 * `backlog.md` #3 recorded the decision as "fall back to the slot's system
 * provider". **That is not expressible.** `ComplicationSlot` appears in exactly
 * one place in Google's whole schema — `sceneElement.xsd`, as a direct child of
 * `Scene`, `maxOccurs="8"`. It cannot go inside a `Group` and it cannot go
 * inside a `Condition`. A drawn slot emits no `ComplicationSlot` at all, and the
 * format offers no way to conditionally introduce one, so there is no provider
 * to fall back to. The decision was made without checking the format allowed it.
 *
 * What IS expressible is a `Condition`: draw the value when weather is there,
 * and draw a placeholder when it is not.
 *
 * ## No `Group` wrapper, contrary to the note this was filed with
 *
 * `Condition`'s `_CompareChild` includes `<xs:group ref="PartElementGroup"/>`,
 * and that group is `{PartText, PartImage, PartAnimatedImage, PartDraw}`. So a
 * `PartText` is a legal child of `Compare` and `Default` directly. The extra
 * `Group` this was expected to need does not exist.
 *
 * ## Why a placeholder rather than nothing
 *
 * Same reason [com.bfg.watchfaces.appcore.PhoneNote] uses one: a slot that
 * renders nothing looks like a face that is broken, and somebody who chose to
 * put weather there needs to see that the slot is theirs and simply has no
 * reading yet. An em dash is a value, not a sentence.
 */
object WeatherFallback {

    /**
     * Shown in place of a reading when the watch has no weather.
     *
     * The same glyph [com.bfg.watchfaces.appcore.PhoneNote] shows for an empty
     * note, and for the same reason. Deliberately not "N/A" or "no data": those
     * are a system talking about itself, and this is a watch face.
     */
    const val PLACEHOLDER = "—"

    /**
     * The first version that emits the fallback.
     *
     * Gated rather than applied in place, because it changes the XML every
     * drawn weather slot produces. A face saved before this keeps rendering the
     * way its author saw it, which is the rule the whole `generatorVersion`
     * mechanism exists to hold — and the cost is real and accepted: those faces
     * never get the fallback.
     */
    const val SINCE_VERSION = 14

    /** Whether this face and this source get a fallback at all. */
    fun appliesTo(p: DialParams, source: ComplicationSource): Boolean =
        p.generatorVersion >= SINCE_VERSION && source.isDrawn && source.readsWeather

    /**
     * Wrap a drawn value so it is shown only when there is weather to show.
     *
     * [available] is the `PartText` exactly as it would have been emitted
     * without this — unchanged, so a watch that HAS weather renders precisely
     * what it rendered before, which is what keeps this a fallback rather than
     * a redesign.
     *
     * The `Compare` expression is `[WEATHER.IS_AVAILABLE]` alone.
     * `WEATHER.IS_ERROR` gets no branch of its own: an error and an absence are
     * the same thing to somebody looking at their wrist — there is no reading —
     * and a second branch would be a distinction this project has never observed
     * on a device and could not test.
     */
    // The placeholder is LITERAL text inside <Font>, not a <Template>.
    //
    // Every other piece of text this emitter writes goes through a Template,
    // so a Template is the obvious thing to reach for -- and it is invalid
    // here: the schema requires at least one <Parameter> inside one, and a
    // fixed string has nothing to parameterise. Xerces says "The content of
    // element 'Template' is not complete. One of '{Parameter}' is expected."
    // <Font> is mixed="true" (group/part/text/fontElement.xsd:22), so text
    // sits in it directly -- and INLINE, with no surrounding newlines: mixed
    // content keeps whitespace, so a placeholder on its own indented line is
    // the string "\n            —\n            " and centres accordingly.
    fun wrap(
        available: String,
        box: SlotGeometry.Box,
        fontSize: Int,
        family: String,
        ink: String,
        ambientAlpha: Int,
        ambientColorVariant: String
    ): String = """
    <Condition>
      <Expressions>
        <Expression name="hasWeather">[WEATHER.IS_AVAILABLE]</Expression>
      </Expressions>
      <Compare expression="hasWeather">${available.trimEnd()}
      </Compare>
      <Default>
        <PartText x="${box.x}" y="${box.y}" width="${box.w}" height="${box.h}" alpha="255">
          <Variant mode="AMBIENT" target="alpha" value="$ambientAlpha"/>$ambientColorVariant
          <Text align="CENTER">
            <Font family="$family" size="$fontSize" color="$ink">$PLACEHOLDER</Font>
          </Text>
        </PartText>
      </Default>
    </Condition>"""

    /**
     * The first version whose RAIN RING is gated on there being weather.
     *
     * Two versions after [SINCE_VERSION] because it is a second bump and not
     * the same one: v14 wrapped the drawn text and left the ring out, and a
     * face stored at v15 or earlier keeps the ring its author saw. Only
     * [RingSource.RAIN] is affected -- steps and battery are always there to
     * read, and gating them would withhold a reading that exists.
     */
    const val RING_SINCE_VERSION = 16

    /** Whether this face's ring reads weather and is new enough to be gated. */
    fun appliesToRing(p: DialParams): Boolean =
        p.generatorVersion >= RING_SINCE_VERSION && p.ring == RingSource.RAIN

    /**
     * Wrap the rain ring so it is drawn only when there is weather to draw.
     *
     * **There is no `<Default>`, and that is the decision rather than an
     * omission.** Operator ruling, 2026-10-12: draw nothing when there is no
     * weather. `Default` is `minOccurs="0"` in `conditionElement.xsd` and the
     * schema's own documentation says it is what shows when no `Compare`
     * succeeds -- so leaving it out is legal and means precisely that: no
     * track, no arc, nothing on the rim.
     *
     * Two alternatives were considered and declined. The faint TRACK alone
     * keeps the composition, and is pixel for pixel what 0% looks like, so it
     * would state a figure instead of withholding one -- the same bug, drawn
     * quieter. A non-percentage MARK invents a vocabulary the dial has nowhere
     * else. A drawn slot can say "no reading yet" with an em dash because text
     * has words for absence; an arc reads as a measurement at every length,
     * including zero.
     *
     * [ring] is the `PartDraw` exactly as it would have been emitted without
     * this, so a watch that HAS weather renders what it rendered before.
     */
    fun wrapRing(ring: String): String = """
    <Condition>
      <Expressions>
        <Expression name="hasWeather">[WEATHER.IS_AVAILABLE]</Expression>
      </Expressions>
      <Compare expression="hasWeather">${ring.trim()}
      </Compare>
    </Condition>"""
}
