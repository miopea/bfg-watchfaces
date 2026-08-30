package com.bfg.watchfaces.generator

/**
 * Emits the WFF definition for a set of params.
 *
 * Hard-won details, each of which cost a debugging session:
 *
 *  - Colours are #AARRGGBB. Eight digits, alpha FIRST. Six-digit colours are
 *    silently wrong, not rejected.
 *  - Ambient is per-element <Variant mode="AMBIENT" target="alpha" .../>,
 *    NOT a second Scene. Each element carries its interactive value as an
 *    attribute and its ambient value as a child.
 *  - DefaultProviderPolicy takes defaultSystemProvider / defaultSystemProviderType.
 *    NOT systemProvider / defaultType. Both compile, link, sign and install;
 *    the face then never appears in the carousel.
 *  - BoundingArc needs centerX/centerY. Rectangular slots want BoundingBox.
 *
 * WffSchemaTest validates the output of this class against Google's official
 * XSD on every build. Do not skip that test.
 */
object WffEmitter {

    /**
     * `[DAY_OF_WEEK_S] [MONTH_S] [MONTH_DAY]` and friends.
     *
     * These are Watch Face Format's own date sources, so the face decides the
     * shape rather than inheriting whatever a complication provider happens to
     * produce. Uppercased by the template, matching how the complication row
     * reads.
     *
     * Dimmed rather than hidden in ambient: a date does not change in the minute
     * ambient might be wrong about, so unlike the seconds it is safe to show.
     */
    /**
     * The wearer's chosen provider app for a slot, if any.
     *
     * `primaryProvider` is tried first and `defaultSystemProvider` — which the
     * schema requires — is what the watch falls back to when that app is not
     * installed. That ordering is the whole reason a face can name a weather
     * app and still render something sensible on a watch that lacks it.
     */
    private fun providerAttrs(p: DialParams, pos: SlotPosition): String {
        val component = p.providers[pos]?.trim().orEmpty()
        if (component.isEmpty()) return ""
        return " primaryProvider=\"$component\" primaryProviderType=\"SHORT_TEXT\""
    }

    private fun dateElement(p: DialParams): String {
        if (p.dateStyle == DateStyle.NONE) return ""
        val l = p.layout
        // One <Parameter> per %s, which is how WFF fills a Template. A single
        // parameter carrying "[DAY_OF_WEEK_S] [MONTH_S] [MONTH_DAY]" is not the
        // same thing and does not read as three sources -- the schema cannot
        // tell the difference, so this is a correctness rule the tests below
        // have to carry rather than the XSD.
        val sources = when (p.dateStyle) {
            DateStyle.NONE -> return ""
            // DAY, not MONTH_DAY. MONTH_DAY is in WFF's CONTINUOUS source
            // group, next to MINUTE_SECOND and HOUR_1_12_MINUTE -- fractional
            // composites for smooth hand movement. On a watch it rendered
            // "Sun Aug 8.935" on 30 August: month 8 plus 29/31 of the way
            // through it. The schema cannot catch this; only running it does.
            DateStyle.DAY -> listOf("DAY")
            DateStyle.MONTH_DAY -> listOf("MONTH_S", "DAY")
            DateStyle.WEEKDAY_MONTH_DAY -> listOf("DAY_OF_WEEK_S", "MONTH_S", "DAY")
            DateStyle.WEEKDAY -> listOf("DAY_OF_WEEK_F")
        }
        val placeholders = sources.joinToString(" ") { "%s" }
        val parameters = sources.joinToString("") { """<Parameter expression="[$it]"/>""" }
        // SlotGeometry owns where this goes; the top slot is laid out around it.
        val band = SlotGeometry.dateBand(p) ?: return ""
        return """
    <PartText x="${band.x}" y="${band.y}" width="${band.w}" height="${band.h}" alpha="255">
      <Variant mode="AMBIENT" target="alpha" value="140"/>
      <Text align="CENTER">
        <Font family="${l.fontFamily}" size="${SlotGeometry.fittedDateSize(p)}" weight="NORMAL" color="${argb(p.inkColor)}">
          <Template><![CDATA[$placeholders]]>$parameters</Template>
        </Font>
      </Text>
    </PartText>"""
    }

    /** Seconds are just under half the clock, which reads as a secondary value. */


    /** How far in from the rim the seconds sit, so they clear a round bezel. */

    private fun argb(rgb: String, alpha: Int = 255): String =
        "#%02x%s".format(alpha, rgb.removePrefix("#").lowercase())

    fun emit(p: DialParams, faceName: String = "Untitled"): String {
        // There is no default face. The name comes from whoever designs it and
        // becomes the carousel label; "Untitled" is a placeholder for callers
        // that have not been given one, not a product name.
        val l = p.layout
        // The clock is the same size with or without seconds. It used to shrink
        // to 82% to open a gutter, which made turning seconds on resize the
        // whole face -- the seconds fit beside a FULL-size clock once they stop
        // hugging the rim. See SecondsBand.INSET.
        val clockSize = l.timeSize
        val ink = argb(p.inkColor)

        // The date the FACE draws. dateY and dateSize have been in Layout since
        // the beginning and nothing emitted them -- the date was always a
        // complication, whose wording belongs to the system provider.
        val dateLine = dateElement(p)

        // Ambient is a black screen. From v3 the ambient ink is lifted to clear
        // a contrast floor while keeping its hue, so a dark ink chosen for a
        // pale dial does not render the time invisible when the watch dims.
        //
        // v1 and v2 keep the old behaviour EXACTLY -- the user's ink at alpha
        // 160, dark or not. A stored face must render as its author saw it, and
        // that is the whole job of generatorVersion.
        val inkNeedsLift = AmbientPalette.contrastOnBlack(p.inkColor) < 4.5
        val inkDim = if (p.generatorVersion >= 3) argb(AmbientPalette.forAmbient(p.inkColor))
                     else argb(p.inkColor, 160)

        // Slot geometry comes from SlotGeometry, which the preview also uses.
        // It sizes boxes to their content, widens the spread if they would
        // touch, pushes the bottom slot clear of the row and keeps everything
        // inside the rim -- the previous hand-placed numbers overlapped on both
        // axes and ran into the clock.
        val boxes = SlotGeometry.boxes(p)
        // The FITTED size, not the requested one. These four used to be derived
        // from l.complicationSize while the boxes came from fittedSize, so at a
        // clamped size the glyph and the text were drawn to a scale their own
        // box was never built for -- at "Large" a font of 26 inside a box laid
        // out for 25.
        val fitted = SlotGeometry.fittedSize(p)
        val iconH = SlotGeometry.iconHeight(fitted, p.generatorVersion)
        val textH = SlotGeometry.textHeight(fitted, p.generatorVersion)
        val fontSize = SlotGeometry.fontSize(fitted)
        val iconW = iconH

        // A complication has ONE Font colour for both modes, unlike the clock
        // which ships two TimeText elements. So the only way its ambient colour
        // can differ is a colour Variant.
        //
        // Schema-valid -- verified against Google's XSD, and asserted by a test.
        // RUNTIME support is NOT verified: no face from this repo has been
        // confirmed on a watch yet. If the runtime ignores an unknown Variant
        // target, this degrades to the previous behaviour rather than to
        // something worse. Confirm it during the first hardware test.
        val ambientColorVariant =
            if (p.generatorVersion >= 3 && inkNeedsLift)
                "\n          <Variant mode=\"AMBIENT\" target=\"color\" value=\"$inkDim\"/>"
            else ""

        val slots = boxes.entries.map { (pos, box) ->
            val source = p.slot(pos)
            // The slot id is the POSITION, not a running count of the enabled
            // ones. Wear stores the wearer's complication choice against the
            // slot id and that choice OVERRIDES DefaultProviderPolicy for good
            // -- the policy only supplies a default for a slot nothing has been
            // assigned to. So while ids were a running count, turning any slot
            // off renumbered every slot after it and the watch's memory landed
            // on the wrong position: the face showed notifications in the last
            // slot no matter what was picked in the app, and nothing the app
            // sent could dislodge it.
            //
            // Position ids are stable, so a slot keeps its identity when its
            // neighbours come and go. Ids are therefore NOT contiguous, which
            // is fine -- slotId is an identifier, not an index.
            val id = pos.ordinal
            // TOP keeps a dim ambient variant: it is the always-readable
            // position, and the ambient design deliberately keeps that one line
            // visible while everything else goes dark.
            val ambientAlpha = if (pos == SlotPosition.TOP) 140 else 0

            // A SHORTCUT is a glyph you press: no provider, no value, and a
            // <Launch> that opens the watch's own music, alarms or settings.
            // Drawn as WFF primitives rather than a baked PNG -- see GlyphWff.
            if (source.isShortcut) {
                val g = (minOf(box.w, box.h) * 0.78).toInt()
                return@map """
    <PartDraw x="${box.x + (box.w - g) / 2}" y="${box.y + (box.h - g) / 2}" width="$g" height="$g" alpha="255">
      <Variant mode="AMBIENT" target="alpha" value="$ambientAlpha"/>
      <Launch target="${source.launch}"/>
          ${GlyphWff.elements(ComplicationGlyphs.shapes(source), g, ink)}
    </PartDraw>"""
            }

            // A DRAWN source is not a complication at all -- there is no
            // provider to fill it, so there is no ComplicationSlot and no
            // glyph. It is a PartText in the slot's own box, which is what puts
            // weather in the same list as Steps without a second layout.
            if (source.isDrawn) return@map """
    <PartText x="${box.x}" y="${box.y}" width="${box.w}" height="${box.h}" alpha="255">
      <Variant mode="AMBIENT" target="alpha" value="$ambientAlpha"/>
      <Text align="CENTER">
        <Font family="${l.fontFamily}" size="$fontSize" color="$ink">$ambientColorVariant
          <Template><![CDATA[${source.format}]]>${source.drawn.joinToString("") { """<Parameter expression="$it"/>""" }}</Template>
        </Font>
      </Text>
    </PartText>"""

            """
    <ComplicationSlot slotId="$id" x="${box.x}" y="${box.y}" width="${box.w}" height="${box.h}"
                      displayName="@string/${pos.resource}"
                      supportedTypes="SHORT_TEXT MONOCHROMATIC_IMAGE EMPTY" alpha="255"
                      isCustomizable="FALSE">
      <Variant mode="AMBIENT" target="alpha" value="$ambientAlpha"/>
      <DefaultProviderPolicy${providerAttrs(p, pos)} defaultSystemProvider="${source.wff}" defaultSystemProviderType="SHORT_TEXT"/>
      <BoundingBox x="0" y="0" width="${box.w}" height="${box.h}" outlinePadding="2.0"/>
      <Complication type="SHORT_TEXT">${if (!p.hasIcon(pos)) "" else """
        <PartImage x="${(box.w - iconW) / 2}" y="0" width="$iconW" height="$iconH">
          <Image resource="[COMPLICATION.MONOCHROMATIC_IMAGE]"/>
        </PartImage>"""}
        <PartText x="0" y="${SlotGeometry.textOffset(fitted, pos in p.iconSlots, p.generatorVersion)}" width="${box.w}" height="$textH">$ambientColorVariant
          <Text align="CENTER">
            <Font family="${l.fontFamily}" size="$fontSize" color="$ink">
              <!--
                TEXT only, and not TEXT + TITLE.
                Tried on a watch: appending TITLE renders "Aug 30Sun",
                "0180Step" and "70BPM", run together, because the provider
                never meant them to be one string. And it does not buy the one
                thing it was tried for: the battery provider supplies "100"
                with no TITLE at all, so there is still no per cent sign.
                SHORT_TEXT is what the provider chose to show in a small slot.
              -->
              <Template><![CDATA[${source.format}]]><Parameter expression="[COMPLICATION.TEXT]"/></Template>
            </Font>
          </Text>
        </PartText>
      </Complication>
    </ComplicationSlot>"""
        }.joinToString("\n")

        // The emitted file is what ships, and since the workbench bakes it into
        // watchface-template it replaces what used to be a hand-annotated
        // reference. Carry that file's hard-won notes into the output so the
        // knowledge lives with the artefact instead of being overwritten by it.
        return """<?xml version="1.0" encoding="utf-8"?>
<!--
  $faceName - Watch Face Format definition.
  Generated by the BFG Watch Faces generator, v${p.generatorVersion}. Do not hand-edit:
  re-bake it instead, with ./gradlew :workbench:bake

  Canvas is 456x456: correct for Pixel Watch 4 and 5, both case sizes.
  Colours are AARRGGBB (8 digits, alpha FIRST). Six-digit values are silently
  wrong, not rejected.

  Ambient is handled by per-element Variant mode="AMBIENT", NOT by a second
  scene. Each element declares its interactive value as an attribute and its
  ambient value as a child.

  NOTE: the package in AndroidManifest.xml follows the Watch Face Push naming
  requirement, <app package>.watchfacepush.<face slug>. Push rejects anything
  else, and it lives in the binary manifest, so only pack can vary it at runtime.
-->
<WatchFace width="$DIAL_SIZE" height="$DIAL_SIZE">
  <Metadata key="CLOCK_TYPE" value="DIGITAL"/>
  <Metadata key="PREVIEW_TIME" value="10:10:00"/>

  <Scene backgroundColor="#ff000000">

    <PartImage x="0" y="0" width="$DIAL_SIZE" height="$DIAL_SIZE" alpha="255">
      <Variant mode="AMBIENT" target="alpha" value="0"/>
      <Image resource="dial_bg"/>
    </PartImage>

$dateLine
    <DigitalClock x="0" y="${l.timeY - l.timeSize / 2}" width="$DIAL_SIZE" height="${(l.timeSize * 1.4).toInt()}">
      <TimeText format="hh:mm" hourFormat="SYNC_TO_DEVICE" align="CENTER"
                x="0" y="0" width="$DIAL_SIZE" height="${(l.timeSize * 1.4).toInt()}" alpha="255">
        <Variant mode="AMBIENT" target="alpha" value="0"/>
        <Font family="${l.fontFamily}" size="$clockSize" weight="${l.fontWeight}" color="$ink"/>
      </TimeText>
      <TimeText format="hh:mm" hourFormat="SYNC_TO_DEVICE" align="CENTER"
                x="0" y="0" width="$DIAL_SIZE" height="${(l.timeSize * 1.4).toInt()}" alpha="0">
        <Variant mode="AMBIENT" target="alpha" value="255"/>
        <Font family="${l.fontFamily}" size="${l.timeSize}" weight="THIN" color="$inkDim"/>
      </TimeText>${if (!p.showSeconds) "" else """
      <!--
        Seconds sit in the right gutter beside the time, on the SAME line: they
        share the clock's element box, so both centre together. About a third of
        its size, in the lightest weight available.

        Not "hh:mm:ss" on the clock itself: that makes every digit the same size,
        so the seconds shout as loudly as the hour and the whole line grows wide
        enough to crowd the rim. The clock is centred, which leaves roughly a
        seventy-nine points of empty dial on each side at the widest time, and
        this uses the right one. The clock does not shrink to make room.

        Awake only. The ambient TimeText above deliberately has no seconds:
        ambient updates once a minute, so a second digit there would be wrong
        for most of the minute it was shown.
      -->
      <TimeText format="ss" align="END" alpha="255"
                x="0" y="${SecondsBand.offsetY(l)}" width="${SecondsBand.rightEdge()}" height="${SecondsBand.height(l)}">
        <Variant mode="AMBIENT" target="alpha" value="0"/>
        <Font family="${l.fontFamily}" size="${SecondsBand.fontSize(l)}" weight="${SecondsBand.WEIGHT}" color="${argb(p.inkColor, SecondsBand.ALPHA)}"/>
      </TimeText>"""}
    </DigitalClock>
$slots

  </Scene>
</WatchFace>
"""
    }

    /**
     * Watch Face Push requires: <app package>.watchfacepush.<face name>
     * The API rejects anything else. Package name lives in the binary manifest,
     * so pack must generate it - it cannot be swapped into a prebuilt APK.
     */
    fun pushPackageName(appPackage: String, faceSlug: String): String {
        require(faceSlug.matches(Regex("^[a-z][a-z0-9_]*$"))) {
            "face slug must be lowercase alphanumeric/underscore, got '$faceSlug'"
        }
        return "$appPackage.watchfacepush.$faceSlug"
    }
}
