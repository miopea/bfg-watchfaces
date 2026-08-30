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
            DateStyle.DAY -> listOf("MONTH_DAY")
            DateStyle.MONTH_DAY -> listOf("MONTH_S", "MONTH_DAY")
            DateStyle.WEEKDAY_MONTH_DAY -> listOf("DAY_OF_WEEK_S", "MONTH_S", "MONTH_DAY")
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
        <Font family="${l.fontFamily}" size="${l.dateSize}" weight="NORMAL" color="${argb(p.inkColor)}">
          <Template><![CDATA[$placeholders]]>$parameters</Template>
        </Font>
      </Text>
    </PartText>"""
    }

    /** Seconds are just under half the clock, which reads as a secondary value. */
    private const val SECONDS_SCALE = 0.45

    /**
     * How much the clock shrinks to make room for seconds.
     *
     * At the default size the time already spans nearly the whole dial, so there
     * is no gutter to put seconds in -- rendered beside it they land on top of
     * the last digit. Giving the clock 82% of its size opens the space, which is
     * what a mechanical face does too: adding a subdial shrinks the main one.
     */
    private const val CLOCK_SCALE_WITH_SECONDS = 0.82

    /** How far in from the rim the seconds sit, so they clear a round bezel. */
    private const val SECONDS_INSET = 48

    private fun argb(rgb: String, alpha: Int = 255): String =
        "#%02x%s".format(alpha, rgb.removePrefix("#").lowercase())

    fun emit(p: DialParams, faceName: String = "Untitled"): String {
        // There is no default face. The name comes from whoever designs it and
        // becomes the carousel label; "Untitled" is a placeholder for callers
        // that have not been given one, not a product name.
        val l = p.layout
        // The clock gives up room when seconds are shown; see
        // CLOCK_SCALE_WITH_SECONDS.
        val clockSize =
            if (p.showSeconds) (l.timeSize * CLOCK_SCALE_WITH_SECONDS).toInt() else l.timeSize
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
        val iconH = SlotGeometry.iconHeight(l.complicationSize)
        val textY = SlotGeometry.textOffset(l.complicationSize)
        val textH = SlotGeometry.textHeight(l.complicationSize)
        val fontSize = SlotGeometry.fontSize(l.complicationSize)
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

        val slots = boxes.entries.mapIndexed { id, (pos, box) ->
            val source = p.slot(pos)
            // TOP keeps a dim ambient variant: it is the always-readable
            // position, and the ambient design deliberately keeps that one line
            // visible while everything else goes dark.
            val ambientAlpha = if (pos == SlotPosition.TOP) 140 else 0
            """
    <ComplicationSlot slotId="$id" x="${box.x}" y="${box.y}" width="${box.w}" height="${box.h}"
                      displayName="@string/slot_${source.name.lowercase()}"
                      supportedTypes="SHORT_TEXT MONOCHROMATIC_IMAGE EMPTY" alpha="255"
                      isCustomizable="TRUE">
      <Variant mode="AMBIENT" target="alpha" value="$ambientAlpha"/>
      <DefaultProviderPolicy defaultSystemProvider="${source.wff}" defaultSystemProviderType="SHORT_TEXT"/>
      <BoundingBox x="0" y="0" width="${box.w}" height="${box.h}" outlinePadding="2.0"/>
      <Complication type="SHORT_TEXT">${if (pos !in p.iconSlots) "" else """
        <PartImage x="${(box.w - iconW) / 2}" y="0" width="$iconW" height="$iconH">
          <Image resource="[COMPLICATION.MONOCHROMATIC_IMAGE]"/>
        </PartImage>"""}
        <PartText x="0" y="$textY" width="${box.w}" height="$textH">$ambientColorVariant
          <Text align="CENTER">
            <Font family="${l.fontFamily}" size="$fontSize" color="$ink">
              <Template><![CDATA[%s]]><Parameter expression="[COMPLICATION.TEXT]"/></Template>
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
        Seconds sit in the right gutter beside the time, at just under half its
        size and in the lightest weight available.

        Not "hh:mm:ss" on the clock itself: that makes every digit the same size,
        so the seconds shout as loudly as the hour and the whole line grows wide
        enough to crowd the rim. The clock is centred, which leaves roughly a
        hundred points of empty dial on each side, and this uses the right one.

        Awake only. The ambient TimeText above deliberately has no seconds:
        ambient updates once a minute, so a second digit there would be wrong
        for most of the minute it was shown.
      -->
      <TimeText format="ss" align="END" alpha="255"
                x="0" y="${(l.timeSize * 0.72).toInt()}" width="${DIAL_SIZE - SECONDS_INSET}" height="${(l.timeSize * 0.6).toInt()}">
        <Variant mode="AMBIENT" target="alpha" value="0"/>
        <Font family="${l.fontFamily}" size="${(l.timeSize * SECONDS_SCALE).toInt()}" weight="THIN" color="$inkDim"/>
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
