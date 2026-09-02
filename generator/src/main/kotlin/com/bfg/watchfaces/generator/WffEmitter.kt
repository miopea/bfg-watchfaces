package com.bfg.watchfaces.generator

import kotlin.math.roundToInt

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
    /**
     * The glyph above a complication's value: OURS, not the provider's.
     *
     * ## Why this stopped being `[COMPLICATION.MONOCHROMATIC_IMAGE]`
     *
     * A monochromatic image is supposed to be a single colour the watch face
     * tints. Google Fit ships a GREEN steps glyph and a RED heart, and no watch
     * face can override that: a tint can only recolour an image the provider
     * ships white-filled. Four attempts confirmed it, one of them destructively
     * — see `DECISIONS.md` 2026-08-31.
     *
     * The provider's icon was never the design anyway. Both previews have
     * always drawn [ComplicationGlyphs], which is why they showed a monochrome
     * glyph while the watch showed a green one. Drawing the same shapes here
     * makes the three renderers agree, and puts the colour under our control by
     * construction rather than by asking a runtime nicely.
     *
     * What it costs: a third-party provider's distinctive icon is replaced by
     * ours. For a slot pointed at an app, the shape still describes the SOURCE
     * behind it, which is what the wearer picked.
     *
     * A source with no shapes of ours keeps the provider's image rather than
     * losing its glyph. `GlyphDrawableTest` is what guarantees the shapes
     * actually survive into the format; two of them did not until today.
     */
    private fun glyphElement(
        source: ComplicationSource,
        box: SlotGeometry.Box,
        iconW: Int,
        iconH: Int,
        ink: String
    ): String {
        val shapes = ComplicationGlyphs.shapes(source)
        val x = (box.w - iconW) / 2
        if (shapes.isEmpty()) {
            return """
        <PartImage x="$x" y="0" width="$iconW" height="$iconH">
          <Image resource="[COMPLICATION.MONOCHROMATIC_IMAGE]"/>
        </PartImage>"""
        }
        return GlyphWff.parts(shapes, x, 0, iconW, ink)
    }

    /** One seconds element, at a given left edge. */
    private fun secondsText(p: DialParams, left: Int): String {
        val l = p.layout
        return """<TimeText format="ss" align="${SecondsBand.alignFor(p)}" alpha="255"
                x="$left" y="${SecondsBand.offsetY(l)}" width="${SecondsBand.boxWidthFor(p)}" height="${SecondsBand.height(l)}">
        <Variant mode="AMBIENT" target="alpha" value="0"/>
        <Font family="${XmlSafe.attr(l.fontFamily)}" size="${SecondsBand.fontSizeFor(p)}" weight="${SecondsBand.WEIGHT}" color="${argb(p.inkColor, SecondsBand.ALPHA)}"/>
      </TimeText>"""
    }

    /**
     * The seconds, in two positions, with the watch choosing between them.
     *
     * ## Why this is not just a number
     *
     * A 12-hour clock renders "7:56" for nine hours out of twelve and "12:56"
     * for the other three. The clock is CENTRED, so its right edge moves by a
     * whole character between those two cases — and Watch Face Format
     * positions everything absolutely and cannot measure text. There is no
     * arithmetic that puts a fixed gap beside a centred string of varying
     * width. Sizing the gutter for the wide case is what left the seconds
     * stranded most of the day; sizing it for the narrow case would run them
     * into the time at ten, eleven and twelve.
     *
     * `Condition` is the format's answer: emit both, evaluate an expression,
     * render the first branch that matches. `[HOUR_1_12] < 10` is the question,
     * and it costs one duplicated element.
     *
     * Only 12-hour faces need it. A `hh:mm` clock is always five characters.
     */
    private fun secondsCondition(p: DialParams): String {
        if (!p.showSeconds || !SecondsBand.twoPositions(p)) return ""
        val narrow = secondsClock(p, SecondsBand.leftEdgeFor(p, SecondsBand.NARROW_TIME))
        val wide = secondsClock(p, SecondsBand.leftEdgeFor(p, SecondsBand.WIDE_TIME))
        return """
    <Condition>
      <Expressions>
        <Expression name="shortHour">[HOUR_1_12] &lt; 10</Expression>
      </Expressions>
      <Compare expression="shortHour">$narrow
      </Compare>
      <Default>$wide
      </Default>
    </Condition>"""
    }

    /**
     * The digital clock, byte for byte as it has always been emitted.
     *
     * Lifted out of the scene UNCHANGED when hands arrived, so the scene shows
     * one decision -- numerals or hands -- rather than a conditional wrapped
     * around forty lines of template. Moving it is the whole of the change
     * here; if this ever renders differently from the version before v12, that
     * is a bug in the move and not a deliberate restyling.
     */
    private fun digitalClock(p: DialParams): String {
        val l = p.layout
        val clockSize = l.timeSize
        val ink = argb(p.inkColor)
        // Identical to the scene's own expression, deliberately: v3 lifts the
        // ambient ink to clear a contrast floor, and an older face must keep
        // the flat alpha it was written with.
        val inkDim = if (p.generatorVersion >= 3) argb(AmbientPalette.forAmbient(p.inkColor))
                     else argb(p.inkColor, 160)
        return """
    <DigitalClock x="0" y="${l.timeY - l.timeSize / 2}" width="$DIAL_SIZE" height="${(l.timeSize * 1.4).toInt()}">
      <TimeText format="${p.hourFormat.pattern}" hourFormat="${p.hourFormat.wff}" align="CENTER"
                x="0" y="0" width="$DIAL_SIZE" height="${(l.timeSize * 1.4).toInt()}" alpha="255">
        <Variant mode="AMBIENT" target="alpha" value="0"/>
        <Font family="${XmlSafe.attr(l.fontFamily)}" size="$clockSize" weight="${XmlSafe.attr(l.fontWeight)}" color="$ink"/>
      </TimeText>
      <TimeText format="${p.hourFormat.pattern}" hourFormat="${p.hourFormat.wff}" align="CENTER"
                x="0" y="0" width="$DIAL_SIZE" height="${(l.timeSize * 1.4).toInt()}" alpha="0">
        <Variant mode="AMBIENT" target="alpha" value="255"/>
        <Font family="${XmlSafe.attr(l.fontFamily)}" size="${l.timeSize}" weight="THIN" color="$inkDim"/>
      </TimeText>${if (!p.showSeconds || SecondsBand.twoPositions(p)) "" else """
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
      ${secondsText(p, SecondsBand.boxLeftFor(p))}"""}
    </DigitalClock>
${secondsCondition(p)}"""
    }

    /**
     * Hands, and the three images Watch Face Format rotates for them.
     *
     * ## Why this is short
     *
     * `clock/hourHand.xsd` requires a `resource` attribute and permits no
     * `PartDraw` child, so a hand CANNOT be described as geometry here. The
     * shape lives in `Hands` and is rasterized by `DialRenderer`; this only
     * says where the pictures go. See `docs/specs/analog-hands.md`.
     *
     * ## The pivot is 0.5/0.5 on all three, always
     *
     * Every hand image is the full dial with the hand drawn in place, so the
     * centre of the image IS the centre of the dial and there is no per-style
     * pivot to get wrong. A wrong pivot is a hand that wobbles as it sweeps --
     * subtle enough to ship, and very hard to see in a photograph of a wrist.
     *
     * ## Ambient
     *
     * The second hand goes to alpha 0. A hand moving once a second on an
     * always-on display is the most expensive thing a watch face can draw, and
     * it is exactly what `showSeconds` already means for the digital seconds.
     * Hour and minute DIM rather than disappear, because a watch you cannot
     * read with your wrist down is not a watch.
     *
     * Element order is fixed by the schema: hour, then minute, then second.
     */
    private fun analogClock(p: DialParams): String {
        val second = if (!p.showSeconds) "" else """
      <SecondHand resource="hand_second" x="0" y="0" width="$DIAL_SIZE" height="$DIAL_SIZE"
                  pivotX="0.5" pivotY="0.5" alpha="255">
        <Variant mode="AMBIENT" target="alpha" value="0"/>
      </SecondHand>"""
        return """
    <AnalogClock x="0" y="0" width="$DIAL_SIZE" height="$DIAL_SIZE">
${handPair("HourHand", "hand_hour")}
${handPair("MinuteHand", "hand_minute")}$second
    </AnalogClock>
    <!--
      The hub goes AFTER the clock so it covers the hands' pivots, which is
      where a real watch puts it. It does not rotate, so it is a plain image
      rather than a fourth hand.
    -->
    <PartImage x="0" y="0" width="$DIAL_SIZE" height="$DIAL_SIZE" alpha="255">
      <Variant mode="AMBIENT" target="alpha" value="0"/>
      <Image resource="hand_hub"/>
    </PartImage>
    <PartImage x="0" y="0" width="$DIAL_SIZE" height="$DIAL_SIZE" alpha="0">
      <Variant mode="AMBIENT" target="alpha" value="255"/>
      <Image resource="hand_hub_ambient"/>
    </PartImage>${analogReadout(p)}"""
    }

    /**
     * TWO of each hand: one awake, one ambient.
     *
     * The schema allows exactly this — `<xs:element ref="HourHand" minOccurs="0"
     * maxOccurs="2"/>` — and this is what the second one is FOR. A hand carries
     * a single `resource`, so swapping artwork between modes cannot be done with
     * a `Variant`; it needs a second element that is invisible until ambient.
     *
     * Identical in shape to how the digital clock ships two `TimeText`
     * elements, one fading out as the other fades in. Same problem, same answer,
     * already proven on a wrist.
     *
     * The ambient artwork is an OUTLINE. Ambient is a black low-power screen and
     * a filled hand there is a slab of ink where a watch should show a line —
     * and it lights far fewer pixels, which is the point of the mode.
     */
    private fun handPair(element: String, resource: String): String = """
      <$element resource="$resource" x="0" y="0" width="$DIAL_SIZE" height="$DIAL_SIZE"
                pivotX="0.5" pivotY="0.5" alpha="255">
        <Variant mode="AMBIENT" target="alpha" value="0"/>
      </$element>
      <$element resource="${resource}_ambient" x="0" y="0" width="$DIAL_SIZE" height="$DIAL_SIZE"
                pivotX="0.5" pivotY="0.5" alpha="0">
        <Variant mode="AMBIENT" target="alpha" value="255"/>
      </$element>"""

    /**
     * The small digital time under the twelve, when an analog face asks for one.
     *
     * Its box comes from [SlotGeometry.analogDigitalBand] rather than being
     * computed here, so the two previews cannot draw it anywhere else. Hidden
     * in ambient like the rest of the numerals: ambient updates once a minute
     * and the hands already say the time.
     */
    private fun analogReadout(p: DialParams): String {
        val box = SlotGeometry.analogDigitalBand(p) ?: return ""
        val size = SlotGeometry.analogDigitalSize(p)
        return """
    <DigitalClock x="${box.x}" y="${box.y}" width="${box.w}" height="${box.h}">
      <TimeText format="${p.hourFormat.pattern}" hourFormat="${p.hourFormat.wff}" align="CENTER"
                x="0" y="0" width="${box.w}" height="${box.h}" alpha="255">
        <Variant mode="AMBIENT" target="alpha" value="0"/>
        <Font family="${XmlSafe.attr(p.layout.fontFamily)}" size="$size" weight="${XmlSafe.attr(p.layout.fontWeight)}" color="${argb(p.inkColor)}"/>
      </TimeText>
    </DigitalClock>"""
    }

    /**
     * How far the highlight travels at full tilt, in dial units.
     *
     * Small on purpose. This is meant to read as material rather than as an
     * effect, and the tasteful range is narrow — a slider set to maximum is how
     * this kind of thing looks cheap, which is the same reasoning that keeps
     * hand proportions fixed. There is no control.
     */
    private const val TILT_TRAVEL = 2.5

    /**
     * The engraved relief under the time, and the light that moves across it.
     *
     * ## What was wrong before this
     *
     * The dial is engraved and the TIME WAS NOT. `EngravedStroke` cuts the
     * pattern in three passes and the numerals were drawn as flat ink on top of
     * it — an inconsistency nobody noticed until a tilt effect was proposed and
     * the plan said "reuse the relief layers the clock already has". It had
     * none. This adds them, and the tilt is what falls out.
     *
     * ## Why a Group
     *
     * `TimeText` accepts only `Variant`, `Font` and `BitmapFont` — no `Gyro`, so
     * a clock cannot tilt by itself. `Group` accepts both a `DigitalClock` and a
     * `Gyro`, so each relief copy is a group that carries the offset and the
     * motion, and the clock inside it just draws.
     *
     * ## Opposite directions, and the text never moves
     *
     * The light copy sits up-left and the dark copy down-right, the offsets
     * `EngravedStroke` already uses. Their gyros pull OPPOSITE ways, so tilting
     * slides the highlight across the letterforms as though light were moving
     * over cut metal. The ink copy on top has no gyro at all: the time is the
     * one thing that must always be readable, and a drifting clock reads as a
     * bug on a small screen.
     *
     * ## No IS_SUPPORTED branch
     *
     * On a watch with no accelerometer the expression rests at zero and the face
     * renders exactly as if the effect were off. A `Condition` would add schema
     * surface and a branch only ever exercisable on hardware nobody here has.
     */
    private fun reliefClock(p: DialParams): String {
        if (p.generatorVersion < 13) return ""
        // Sized for the TYPE, not for a hairline. See EngravedStroke.textPasses.
        val passes = EngravedStroke.textPasses(p, p.layout.timeSize)
        return reliefLayer(p, "clock_relief_light", passes[0], 1.0) +
            reliefLayer(p, "clock_relief_dark", passes[1], -1.0)
    }

    /**
     * One relief copy: offset, tinted, tilting, and gone in ambient.
     *
     * Ambient drops it entirely rather than dimming it. Ambient is a black
     * low-power screen with its own THIN clock, and relief on a black ground is
     * two extra elements lighting pixels to say nothing.
     */
    private fun reliefLayer(p: DialParams, name: String, pass: EngravedStroke.Pass, dir: Double): String {
        val l = p.layout
        val h = (l.timeSize * 1.4).toInt()
        val y = l.timeY - l.timeSize / 2
        // Rounded because Group geometry is integral; the sub-pixel part of the
        // offset is smaller than the travel and would only add noise.
        val ox = pass.dx.roundToInt()
        val oy = pass.dy.roundToInt()
        return """
    <Group name="$name" x="$ox" y="${y + oy}" width="$DIAL_SIZE" height="$h" alpha="255">
      <Variant mode="AMBIENT" target="alpha" value="0"/>
      <Gyro x="${gyro("ACCELEROMETER_ANGLE_X", dir)}" y="${gyro("ACCELEROMETER_ANGLE_Y", dir)}"/>
      <DigitalClock x="0" y="0" width="$DIAL_SIZE" height="$h">
        <TimeText format="${p.hourFormat.pattern}" hourFormat="${p.hourFormat.wff}" align="CENTER"
                  x="0" y="0" width="$DIAL_SIZE" height="$h" alpha="255">
          <Font family="${XmlSafe.attr(l.fontFamily)}" size="${l.timeSize}" weight="${XmlSafe.attr(l.fontWeight)}" color="${argbOf(pass.argb)}"/>
        </TimeText>
      </DigitalClock>
    </Group>"""
    }

    /** A linear map from a tilt angle to a small offset, clamped to the sensor's range. */
    private fun gyro(source: String, dir: Double): String =
        "(${dir * TILT_TRAVEL}/90) * clamp([$source], -90, 90)"

    private fun secondsClock(p: DialParams, left: Int): String {
        val l = p.layout
        return """
        <DigitalClock x="0" y="${l.timeY - l.timeSize / 2}" width="$DIAL_SIZE" height="${(l.timeSize * 1.4).toInt()}">
          <TimeText format="ss" align="START" alpha="255"
                    x="$left" y="${SecondsBand.offsetY(l)}" width="${DIAL_SIZE - left}" height="${SecondsBand.height(l)}">
            <Variant mode="AMBIENT" target="alpha" value="0"/>
            <Font family="${XmlSafe.attr(l.fontFamily)}" size="${SecondsBand.fontSizeFor(p)}" weight="${SecondsBand.WEIGHT}" color="${argb(p.inkColor, SecondsBand.ALPHA)}"/>
          </TimeText>
        </DigitalClock>"""
    }

    private fun providerAttrs(p: DialParams, pos: SlotPosition): String {
        val component = p.providers[pos]?.trim().orEmpty()
        if (component.isEmpty()) return ""
        return " primaryProvider=\"${XmlSafe.attr(component)}\" primaryProviderType=\"SHORT_TEXT\""
    }

    /**
     * The rim ring: a faint full circle with a bright arc over it.
     *
     * The sweep is bound with `<Transform>` rather than baked in, so the WATCH
     * keeps it current from `[STEP_PERCENT]`. Nothing here recomputes it and
     * nothing has to be re-sent as someone walks.
     *
     * Angles run clockwise from 12 o'clock, so the ring fills the way a person
     * reads a dial. Clamped at 100: someone who walks twice their goal gets a
     * complete ring, not a sweep of 720 degrees. Seen on an emulator reporting
     * 107,300 steps. Hidden in ambient: it is decoration, and ambient is a black
     * screen with the time on it.
     */
    private fun ringElement(p: DialParams, ink: String): String {
        val expression = p.ring.expression ?: return ""
        val b = StepRing.box()
        val track = argb(p.inkColor, StepRing.TRACK_ALPHA)
        return """
    <PartDraw x="0" y="0" width="$DIAL_SIZE" height="$DIAL_SIZE" alpha="255">
      <Variant mode="AMBIENT" target="alpha" value="0"/>
      <Arc centerX="${DIAL_CENTER}" centerY="${DIAL_CENTER}" width="${b.w}" height="${b.h}"
           startAngle="0" endAngle="360">
        <Stroke color="$track" thickness="${StepRing.THICKNESS}" cap="ROUND"/>
      </Arc>
      <Arc centerX="${DIAL_CENTER}" centerY="${DIAL_CENTER}" width="${b.w}" height="${b.h}"
           startAngle="0" endAngle="0">
        <Stroke color="$ink" thickness="${StepRing.THICKNESS}" cap="ROUND"/>
        <Transform target="endAngle" value="clamp($expression, 0, 100) * ${StepRing.DEGREES_PER_PERCENT}"/>
      </Arc>
    </PartDraw>"""
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
        <Font family="${XmlSafe.attr(l.fontFamily)}" size="${SlotGeometry.fittedDateSize(p)}" weight="NORMAL" color="${argb(p.inkColor)}">
          <Template><![CDATA[$placeholders]]>$parameters</Template>
        </Font>
      </Text>
    </PartText>"""
    }

    /** Seconds are just under half the clock, which reads as a secondary value. */


    /** How far in from the rim the seconds sit, so they clear a round bezel. */

    private fun argb(rgb: String, alpha: Int = 255): String =
        "#%02x%s".format(alpha, rgb.removePrefix("#").lowercase())

    /**
     * An [EngravedStroke.Pass] colour as WFF's AARRGGBB.
     *
     * The passes carry ALPHA already — that is how the relief stays subtle —
     * so this keeps it rather than forcing 255, which would turn a highlight
     * into a second solid clock sitting behind the first.
     */
    private fun argbOf(packed: Int): String = "#%08x".format(packed)

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
        val ring = ringElement(p, ink)

        // Numerals or hands. Exclusive: hands sweep the whole dial, so the two
        // want genuinely different layouts and a face trying to be both would
        // need a third. See docs/specs/analog-hands.md.
        val clockBlock =
            if (p.clockMode == ClockMode.ANALOG) analogClock(p)
            // The relief goes BEFORE the clock so the ink reads on top of it.
            else reliefClock(p) + digitalClock(p)

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
        // PER SLOT, not once for the face. The top slot can now be smaller
        // than the row when a drawn date is in its way, and hoisting these out
        // of the loop would draw its text and glyph at the row's scale inside
        // the smaller box it was actually given.

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
            val fitted = SlotGeometry.sizeAt(p, pos)
            val iconH = SlotGeometry.iconHeight(fitted, p.generatorVersion)
            val textH = SlotGeometry.textHeight(fitted, p.generatorVersion)
            val fontSize = SlotGeometry.fontSize(fitted)
            val iconW = iconH
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
                // A fixed system target, or the app this SLOT was pointed at.
                val target = source.launch ?: p.launchers[pos]
                // A slot set to "open an app" with no app chosen yet would emit
                // Launch with no target, which the schema requires. Draw
                // nothing rather than refuse to build the face.
                if (target.isNullOrBlank()) return@map ""
                val g = (minOf(box.w, box.h) * 0.78).toInt()
                return@map """
    <PartDraw x="${box.x + (box.w - g) / 2}" y="${box.y + (box.h - g) / 2}" width="$g" height="$g" alpha="255">
      <Variant mode="AMBIENT" target="alpha" value="$ambientAlpha"/>${
            // The tint follows the ink into ambient for the same reason the
            // text's colour does. From v3 a dark ink is lifted so it clears a
            // contrast floor against black; a tint pinned to the AWAKE ink
            // would drag the glyph back down to the colour the lift exists to
            // avoid, and only on the faces that need it most.
            if (p.generatorVersion >= 3 && inkNeedsLift)
                "\n      <Variant mode=\"AMBIENT\" target=\"tintColor\" value=\"$inkDim\"/>"
            else ""
        }
      <Launch target="${XmlSafe.attr(target)}"/>
          ${GlyphWff.elements(ComplicationGlyphs.shapes(source), g, ink)}
    </PartDraw>"""
            }

            // A DRAWN source is not a complication at all -- there is no
            // provider to fill it, so there is no ComplicationSlot and no
            // glyph. It is a PartText in the slot's own box, which is what puts
            // weather in the same list as Steps without a second layout.
            if (source.isDrawn) {
                // Which wording and what size are ONE question, asked of
                // SlotGeometry so the previews give the same answer. It
                // shortens before it shrinks; see SlotGeometry.drawnText.
                val drawn = SlotGeometry.drawnText(source, box, fontSize, p.generatorVersion, pos)
                return@map """
    <PartText x="${box.x}" y="${box.y}" width="${box.w}" height="${box.h}" alpha="255">
      <Variant mode="AMBIENT" target="alpha" value="$ambientAlpha"/>
      <Text align="CENTER">
        <Font family="${XmlSafe.attr(l.fontFamily)}" size="${drawn.fontSize}" color="$ink">$ambientColorVariant
          <Template><![CDATA[${drawn.format}]]>${drawn.expressions.joinToString("") { """<Parameter expression="$it"/>""" }}</Template>
        </Font>
      </Text>
    </PartText>"""
            }

            """
    <ComplicationSlot slotId="$id" x="${box.x}" y="${box.y}" width="${box.w}" height="${box.h}"
                      tintColor="$ink"
                      displayName="@string/${pos.resource}"
                      supportedTypes="SHORT_TEXT MONOCHROMATIC_IMAGE EMPTY" alpha="255"
                      isCustomizable="FALSE">
      <Variant mode="AMBIENT" target="alpha" value="$ambientAlpha"/>
      <DefaultProviderPolicy${providerAttrs(p, pos)} defaultSystemProvider="${source.wff}" defaultSystemProviderType="SHORT_TEXT"/>
      <BoundingBox x="0" y="0" width="${box.w}" height="${box.h}" outlinePadding="2.0"/>
      <Complication type="SHORT_TEXT">${if (!p.hasIcon(pos)) "" else glyphElement(source, box, iconW, iconH, ink)}
        <PartText x="0" y="${SlotGeometry.textOffset(fitted, pos in p.iconSlots, p.generatorVersion)}" width="${box.w}" height="$textH">$ambientColorVariant
          <Text align="CENTER">
            <Font family="${XmlSafe.attr(l.fontFamily)}" size="$fontSize" color="$ink">
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
  ${XmlSafe.comment(faceName)} - Watch Face Format definition.
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
  <Metadata key="CLOCK_TYPE" value="${if (p.clockMode == ClockMode.ANALOG) "ANALOG" else "DIGITAL"}"/>
  <Metadata key="PREVIEW_TIME" value="10:10:00"/>

  <Scene backgroundColor="#ff000000">

    <PartImage x="0" y="0" width="$DIAL_SIZE" height="$DIAL_SIZE" alpha="255">
      <Variant mode="AMBIENT" target="alpha" value="0"/>
      <Image resource="dial_bg"/>
    </PartImage>

$ring
$dateLine
${clockBlock}
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
