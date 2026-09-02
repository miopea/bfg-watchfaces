package com.bfg.watchfaces.generator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXParseException
import java.io.File
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory

/**
 * Validates emitted WFF against Google's official XSD.
 *
 * This is the highest-value test in the repo. A schema-invalid face still
 * compiles, links, signs and installs -- and then silently never appears in the
 * watch face carousel. There is no runtime error to catch. This test is the
 * only thing between a refactor and a face that quietly vanishes.
 *
 * The WFF schemas are XSD 1.1, so this needs Xerces; the JDK's built-in
 * validator and libxml both only handle 1.0.
 *
 * Run scripts/bootstrap.sh first to fetch the schema and jars.
 */
class WffSchemaTest {

    private fun schemaFile(): File =
        File(javaClass.classLoader.getResource("wff-schema/watchface.xsd")!!.toURI())

    private fun validate(xml: String): List<String> {
        val errors = mutableListOf<String>()
        // Name the Xerces XSD 1.1 factory explicitly. JAXP service discovery
        // does not reliably find it, and the failure mode is an opaque
        // IllegalArgumentException from newInstance().
        val factory = SchemaFactory.newInstance(
            "http://www.w3.org/XML/XMLSchema/v1.1",
            "org.apache.xerces.jaxp.validation.XMLSchema11Factory",
            null
        )
        val validator = factory.newSchema(schemaFile()).newValidator()
        validator.errorHandler = object : ErrorHandler {
            override fun warning(e: SAXParseException) {}
            override fun error(e: SAXParseException) { errors += "line ${e.lineNumber}: ${e.message}" }
            override fun fatalError(e: SAXParseException) { errors += "FATAL line ${e.lineNumber}: ${e.message}" }
        }
        val tmp = File.createTempFile("wff", ".xml").apply { writeText(xml); deleteOnExit() }
        validator.validate(StreamSource(tmp))
        return errors
    }

    @Test
    fun `default params emit schema-valid WFF`() {
        val errors = validate(WffEmitter.emit(DialParams()))
        assertTrue(errors.isEmpty()) { "schema errors:\n" + errors.joinToString("\n") }
    }

    @ParameterizedTest
    @EnumSource(Engine::class)
    fun `every engine emits schema-valid WFF`(engine: Engine) {
        val errors = validate(WffEmitter.emit(DialParams(engine = engine)))
        assertTrue(errors.isEmpty()) { "$engine schema errors:\n" + errors.joinToString("\n") }
    }

    /**
     * Every optional feature, validated -- not just the defaults.
     *
     * `showSeconds` and `dateStyle` shipped to a real phone emitting an XML
     * comment containing "--", which XML forbids. Nothing here caught it,
     * because every other test in this file emits DialParams() with both
     * features off, so the elements under test were never in the document.
     *
     * A default-only schema test only ever proves the default face is valid. An
     * optional feature is exactly the code a person turns on later, alone, with
     * no way to tell a schema rejection from a bug in their design.
     */
    @ParameterizedTest
    @EnumSource(DateStyle::class)
    fun `every date style emits schema-valid WFF`(style: DateStyle) {
        val iconSets = listOf(
            emptySet(),
            SlotPosition.entries.toSet(),
            setOf(SlotPosition.TOP, SlotPosition.RIGHT)
        )
        for (seconds in listOf(false, true)) {
            for (icons in iconSets) {
                val p = DialParams(showSeconds = seconds, dateStyle = style, iconSlots = icons)
                val errors = validate(WffEmitter.emit(p))
                assertTrue(errors.isEmpty()) {
                    "date=$style seconds=$seconds icons=$icons is schema-invalid:\n" +
                        errors.joinToString("\n")
                }
            }
        }
    }

    /**
     * XML comments may not contain "--", and the emitter writes prose comments.
     *
     * The schema test above catches this only where a comment sits inside an
     * element some parameter combination emits. This catches it everywhere, in
     * one cheap string check, because the failure is silent on a watch: the
     * face installs and never appears.
     */
    /**
     * ANALOG FACES VALIDATE. This is the only signal there is.
     *
     * A schema-invalid watch face compiles, links, signs and installs, and then
     * silently never appears in the carousel — no runtime error, nothing in a
     * log, nothing on the watch. Every other kind of mistake in this repo
     * announces itself; this one does not.
     *
     * Every style, and with seconds both on and off, because the SecondHand
     * element is emitted conditionally and an element the schema rejects only
     * fails when it is actually present.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(HandStyle::class)
    fun `hands emit schema-valid WFF`(style: HandStyle) {
        for (seconds in listOf(false, true)) for (readout in listOf(false, true)) {
            val p = DialParams(
                clockMode = ClockMode.ANALOG,
                handStyle = style,
                showSeconds = seconds,
                analogDigital = readout
            )
            val errors = validate(WffEmitter.emit(p))
            assertTrue(errors.isEmpty()) {
                "$style seconds=$seconds readout=$readout: ${errors.joinToString("\n")}"
            }
        }
    }

    /**
     * The readout appears only when asked for, and only on an analog face.
     *
     * A DigitalClock on a face wearing hands is deliberate here and a bug
     * anywhere else, so both directions are worth pinning.
     */
    @Test
    fun `the small readout is emitted only when an analog face asks for it`() {
        val without = WffEmitter.emit(DialParams(clockMode = ClockMode.ANALOG))
        assertTrue(!without.contains("<DigitalClock")) { "an unrequested readout was emitted" }

        val with = WffEmitter.emit(DialParams(clockMode = ClockMode.ANALOG, analogDigital = true))
        assertTrue(with.contains("<DigitalClock")) { "the readout is missing" }
        assertTrue(with.contains("<AnalogClock")) { "the hands went away when the readout arrived" }

        // Ignored on a digital face: it has a clock already. Asserted as
        // "changes nothing" rather than by COUNTING clocks -- from v13 a digital
        // face legitimately carries three, the ink one and its two relief
        // copies, and a count would have to be revised every time the engraving
        // changes while saying less than this does.
        assertEquals(
            WffEmitter.emit(DialParams()),
            WffEmitter.emit(DialParams(analogDigital = true))
        ) { "analogDigital changed a digital face, which has a clock already" }
    }

    /**
     * The hands are actually THERE, and in the order the schema demands.
     *
     * Validation alone would pass an AnalogClock with no hands in it at all —
     * every child is minOccurs="0" — which installs, renders a bare dial, and
     * looks exactly like a face that failed to load.
     */
    @Test
    fun `an analog face emits three hands, a hub, and no digital clock`() {
        val xml = WffEmitter.emit(DialParams(clockMode = ClockMode.ANALOG, showSeconds = true))
        assertTrue(xml.contains("<AnalogClock")) { "no AnalogClock element" }
        assertTrue(!xml.contains("<DigitalClock")) { "an analog face still carries a DigitalClock" }
        assertTrue(xml.contains("CLOCK_TYPE\" value=\"ANALOG")) { "CLOCK_TYPE still says DIGITAL" }
        val hour = xml.indexOf("<HourHand")
        val minute = xml.indexOf("<MinuteHand")
        val second = xml.indexOf("<SecondHand")
        assertTrue(hour in 1..minute) { "hour hand missing or after the minute hand" }
        assertTrue(minute in 1..second) { "minute hand missing or after the second hand" }
        assertTrue(xml.indexOf("hand_hub") > second) { "the hub must be drawn after the hands" }
    }

    /** Seconds off means no SecondHand at all, not a hidden one still ticking. */
    @Test
    fun `an analog face without seconds emits no second hand`() {
        val xml = WffEmitter.emit(DialParams(clockMode = ClockMode.ANALOG, showSeconds = false))
        assertTrue(!xml.contains("<SecondHand")) { "a second hand was emitted with seconds off" }
    }

    /**
     * Every hand pivots on the CENTRE of its own image.
     *
     * The full-canvas decision is what makes that true, and it is what means no
     * style carries pivot data that can be wrong. A hand pivoting anywhere else
     * wobbles as it sweeps.
     */
    @Test
    fun `every hand pivots on the centre of the image`() {
        val xml = WffEmitter.emit(DialParams(clockMode = ClockMode.ANALOG, showSeconds = true))
        // FIVE, not three: hour and minute each ship twice — one awake, one
        // ambient with outline artwork — because a hand carries a single
        // `resource` and swapping it between modes needs a second element. The
        // schema allows exactly two of each, which is what that is for. The
        // second hand is hidden in ambient, so it needs only one.
        val hands = Regex("<(Hour|Minute|Second)Hand").findAll(xml).count()
        assertEquals(5, hands) { "expected five hand elements, found $hands" }
        assertEquals(hands, xml.split("pivotX=\"0.5\"").size - 1) { "not every hand pivots on centre X" }
        assertEquals(hands, xml.split("pivotY=\"0.5\"").size - 1) { "not every hand pivots on centre Y" }
    }

    /**
     * Awake and ambient artwork are BOTH referenced, and they differ.
     *
     * If the ambient element pointed at the awake resource the face would look
     * right in every screenshot and light a filled slab of ink on the wrist,
     * which is the one place nobody checks often.
     */
    @Test
    fun `ambient hands reference their own outline artwork`() {
        val xml = WffEmitter.emit(DialParams(clockMode = ClockMode.ANALOG))
        for (base in listOf("hand_hour", "hand_minute", "hand_hub")) {
            assertTrue(xml.contains("\"$base\"")) { "$base is not referenced" }
            assertTrue(xml.contains("\"${base}_ambient\"")) { "${base}_ambient is not referenced" }
        }
    }

    /** A digital face is untouched by any of this. */
    @Test
    fun `a digital face still emits a digital clock and no hands`() {
        val xml = WffEmitter.emit(DialParams())
        assertTrue(xml.contains("<DigitalClock")) { "the digital clock went missing" }
        assertTrue(!xml.contains("<AnalogClock")) { "a digital face emitted hands" }
        assertTrue(xml.contains("CLOCK_TYPE\" value=\"DIGITAL")) { "CLOCK_TYPE is wrong" }
    }

    /**
     * The time is ENGRAVED, and the light moves without the text moving.
     *
     * Two relief copies in the dial's own pass colours, pulling opposite ways,
     * with the ink copy on top carrying no gyro at all. If the ink copy ever
     * gained one the clock would drift as the wrist turned, which on a small
     * screen reads as a bug rather than as an effect.
     */
    @Test
    fun `the dial parallaxes and nothing else moves`() {
        val xml = WffEmitter.emit(DialParams())

        // ONE moving thing, and it is the dial.
        //
        // The first version tilted the TIME's relief. It ran -- the watch
        // powered up its accelerometer for the face -- and could not be seen,
        // because the relief sits behind the ink and only a hairline of it
        // shows: measured at 4,097 pixels, 2.51% of the dial. The dial is the
        // other 97%.
        val gyros = Regex("<Gyro ").findAll(xml).count()
        assertEquals(1, gyros) { "expected exactly one tilting element, found $gyros" }

        // On the dial image, not on a clock.
        val gyro = xml.indexOf("<Gyro")
        val dialImage = xml.indexOf("dial_bg")
        assertTrue(gyro < dialImage) { "the gyro is not on the dial image" }

        // The relief survives as a STATIC treatment.
        assertTrue(xml.contains("clock_relief_light")) { "the relief went away with the tilt" }
        assertTrue(xml.contains("clock_relief_dark")) { "the relief went away with the tilt" }
    }

    /**
     * The dial is emitted OVERSIZED, or tilting it would expose the rim.
     *
     * A dial that fills the screen exactly has nowhere to travel: moving it
     * leaves a black crescent on the side it came from. The bleed has to exceed
     * the travel at every angle.
     */
    @Test
    fun `the dial has bleed to travel into`() {
        val xml = WffEmitter.emit(DialParams())
        val m = Regex("""<PartImage x="(-?\d+)" y="(-?\d+)" width="(\d+)" height="(\d+)"[^>]*>\s*<Variant[^>]*/>\s*<Gyro""")
            .find(xml)
        assertTrue(m != null) { "the dial image is not positioned for parallax" }
        val x = m!!.groupValues[1].toInt()
        val w = m.groupValues[3].toInt()
        assertTrue(x < 0) { "the dial starts at $x, so it has no bleed on the left" }
        assertTrue(w > DIAL_SIZE) { "the dial is $w wide, no larger than the screen" }
        // Travel is 6; bleed must beat it with room to spare.
        assertTrue(-x >= 8) { "only ${-x}px of bleed; the rim would show at full tilt" }
    }

    /** Ambient has its own thin clock; relief on a black ground lights pixels to say nothing. */
    @Test
    fun `the relief disappears in ambient`() {
        val xml = WffEmitter.emit(DialParams())
        val light = xml.indexOf("clock_relief_light")
        val nextVariant = xml.indexOf("<Variant", light)
        assertTrue(nextVariant > 0) { "the highlight layer has no ambient variant" }
        assertTrue(
            xml.substring(nextVariant, nextVariant + 80).contains("value=\"0\"")
        ) { "the highlight survives into ambient" }
    }

    /** A v12 face renders exactly as it did; the relief is gated. */
    @Test
    fun `an older face gets no relief`() {
        val old = WffEmitter.emit(DialParams(generatorVersion = 12))
        assertTrue(!old.contains("clock_relief")) { "v12 grew relief it was not saved with" }
        assertTrue(!old.contains("<Gyro")) { "v12 grew a tilt effect" }
    }

    @Test
    fun `no emitted XML comment contains a double dash`() {
        for (style in DateStyle.entries) {
            val xml = WffEmitter.emit(DialParams(showSeconds = true, dateStyle = style))
            Regex("<!--(.*?)-->", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { m ->
                assertTrue(!m.groupValues[1].contains("--")) {
                    "an emitted comment contains \"--\", which XML forbids:\n${m.value}"
                }
            }
        }
    }

    /**
     * Every %s in a Template has exactly one <Parameter> to fill it.
     *
     * The XSD does not check this -- it counts neither, so a Template with one
     * "%s" and three sources crammed into a single expression validates
     * cleanly and then renders wrong on the watch. This is the correctness
     * rule the schema cannot carry.
     */
    @ParameterizedTest
    @EnumSource(DateStyle::class)
    fun `each Template placeholder has one Parameter`(style: DateStyle) {
        // Every DRAWN source is in here too, not just the date. Weather shipped
        // with "[WEATHER.TEMPERATURE][WEATHER.TEMPERATURE_UNIT]" in ONE
        // parameter: schema-valid, and the whole face rendered BLACK on a
        // watch. This test existed and did not catch it, because it only ever
        // emitted the default complications.
        val xml = WffEmitter.emit(DialParams(
            dateStyle = style,
            complications = listOf(
                ComplicationSource.WEATHER_TEMPERATURE, ComplicationSource.WEATHER_CONDITION,
                ComplicationSource.STEP_COUNT, ComplicationSource.HEART_RATE,
                ComplicationSource.WATCH_BATTERY
            )
        ))
        val templates = Regex("<Template>(.*?)</Template>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
        var seen = 0
        for (t in templates) {
            seen++
            val body = t.groupValues[1]
            val placeholders = Regex("%s").findAll(body).count()
            val parameters = Regex("<Parameter\\b").findAll(body).count()
            assertEquals(placeholders, parameters) {
                "$style: $placeholders placeholder(s) but $parameters parameter(s) in:\n$body"
            }
            assertTrue(parameters > 0) { "$style: a Template with no Parameter renders nothing" }

            // And no single expression may name TWO sources. Counting
            // placeholders against parameters does NOT catch this -- one "%s"
            // with one Parameter holding "[A][B]" balances perfectly, and
            // renders the whole face BLACK on a watch. That is exactly how
            // weather shipped, past this very test.
            for (p in Regex("""<Parameter expression="([^"]*)"""").findAll(body)) {
                val sources = Regex("""\[[A-Z_0-9.]+\]""").findAll(p.groupValues[1]).count()
                assertTrue(sources <= 1) {
                    "$style: one Parameter names $sources sources: \"${p.groupValues[1]}\" " +
                        "-- WFF fills one %s per Parameter, so this renders nothing at all"
                }
            }
        }
        if (style != DateStyle.NONE) {
            assertTrue(seen > 0) { "$style emitted no Template at all, so no date is drawn" }
        }
    }

    /** The drawn date is absent entirely when it is off, not an empty element. */
    @Test
    fun `no date style emits no date element`() {
        val xml = WffEmitter.emit(DialParams(dateStyle = DateStyle.NONE))
        assertTrue(!xml.contains("DAY_OF_WEEK") && !xml.contains("MONTH_S")) {
            "a date source was emitted with the date switched off"
        }
    }

    /**
     * The seconds are coloured from the AWAKE ink, never the ambient one.
     *
     * They were emitted with `inkDim` on an element whose ambient alpha is 0 --
     * so it is only ever seen awake. From v3 `inkDim` is lifted to clear a
     * contrast floor against BLACK, which on a pale dial with dark ink built a
     * face with pale seconds on a pale dial while both previews drew them dark.
     * Nothing failed. The two just disagreed, which is the failure this repo
     * keeps paying for.
     */
    @Test
    fun `seconds take the awake ink, not the ambient one`() {
        // A dark ink on a pale dial: exactly the case where the ambient lift
        // changes the colour, so the two are distinguishable.
        val p = DialParams(showSeconds = true, inkColor = "#231F1B", dialColor = "#C9C3B6")
        val xml = WffEmitter.emit(p)

        val seconds = Regex("""<TimeText format="ss".*?</TimeText>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)!!.value
        val color = Regex("""color="#([0-9a-fA-F]{8})"""").find(seconds)!!.groupValues[1]

        assertEquals("231f1b", color.substring(2).lowercase()) {
            "the seconds are not the ink the person chose: #$color"
        }
        assertEquals(SecondsBand.ALPHA, color.substring(0, 2).toInt(16))
    }


    /**
     * A slot can name a specific provider APP, which is how weather gets on a
     * face at all.
     *
     * WFF's system provider list has fourteen members and no weather in it.
     * Third-party sources — weather, Google Health, anything installed — are
     * named by ComponentName through `primaryProvider`, with the required
     * `defaultSystemProvider` left as the fallback for a watch that does not
     * have that app. This is the check that the arrangement is legal, which is
     * the part that cannot be assumed.
     */
    @Test
    fun `a named provider app is schema-valid and keeps a system fallback`() {
        val p = DialParams(
            complications = listOf(
                ComplicationSource.DAY_AND_DATE, ComplicationSource.STEP_COUNT,
                ComplicationSource.HEART_RATE, ComplicationSource.DATE,
                ComplicationSource.WATCH_BATTERY
            ),
            providers = mapOf(
                SlotPosition.RIGHT to "com.google.android.apps.weather/.complications.WeatherProvider",
                SlotPosition.LEFT to "com.google.android.apps.fitness/.ComplicationProviderService"
            )
        )
        val xml = WffEmitter.emit(p)
        val errors = validate(xml)
        assertTrue(errors.isEmpty()) { "a named provider is not schema-valid:\n" + errors.joinToString("\n") }

        // The chosen app is tried first; the system provider is still there.
        val right = Regex("""<ComplicationSlot slotId="3"[\s\S]*?</ComplicationSlot>""").find(xml)!!.value
        assertTrue(right.contains("""primaryProvider="com.google.android.apps.weather/.complications.WeatherProvider"""")) {
            "the chosen provider is missing:\n$right"
        }
        assertTrue(right.contains("""primaryProviderType="SHORT_TEXT"""")) {
            "primaryProviderType defaults to EMPTY, which supplies no data"
        }
        assertTrue(right.contains("""defaultSystemProvider="DATE"""")) {
            "the system fallback was dropped; the slot is empty on a watch without that app"
        }
    }

    /** A slot with no chosen app emits exactly what it always did. */
    @Test
    fun `no named provider means no primaryProvider attribute`() {
        val xml = WffEmitter.emit(DialParams())
        assertTrue(!xml.contains("primaryProvider")) {
            "a face with no chosen providers gained an attribute it does not need"
        }
    }

    /**
     * Weather in a slot is schema-valid, and is NOT a complication.
     *
     * WFF's system provider list has fourteen members and no weather. The
     * format does have `[WEATHER.TEMPERATURE]` as a first-class source, so a
     * weather slot is drawn by the face: no ComplicationSlot, no provider, no
     * glyph. This is the check that the arrangement is legal.
     */
    @Test
    fun `a weather slot is drawn, not a complication, and validates`() {
        val p = DialParams(complications = listOf(
            ComplicationSource.WEATHER_TEMPERATURE, ComplicationSource.STEP_COUNT,
            ComplicationSource.WEATHER_CONDITION, ComplicationSource.HEART_RATE,
            ComplicationSource.WATCH_BATTERY
        ))
        val xml = WffEmitter.emit(p)
        val errors = validate(xml)
        assertTrue(errors.isEmpty()) { "a weather slot is not schema-valid:\n" + errors.joinToString("\n") }

        assertTrue(xml.contains("[WEATHER.TEMPERATURE]")) { "no temperature source emitted" }
        assertTrue(xml.contains("[WEATHER.CONDITION_NAME]")) { "no condition source emitted" }

        // Three complication slots, not five: the two weather slots are drawn.
        val slots = Regex("""<ComplicationSlot """).findAll(xml).count()
        assertEquals(3, slots) { "weather was emitted as a complication slot" }
    }

    /**
     * The face definition wins, always.
     *
     * `isCustomizable="TRUE"` lets the watch's editor assign a source to a slot,
     * and once it has, `DefaultProviderPolicy` is never consulted again -- so
     * nothing chosen in the app could change what the watch drew. Measured on a
     * watch: a face declaring battery/heart/steps/day-of-week rendered the
     * assignments of a build before it, unchanged across three faces.
     */
    @Test
    fun `slots are not customizable on the watch`() {
        val xml = WffEmitter.emit(DialParams())
        assertTrue(xml.contains("""isCustomizable="FALSE"""")) {
            "a customizable slot ignores DefaultProviderPolicy forever once the " +
                "watch's editor has touched it"
        }
        assertTrue(!xml.contains("""isCustomizable="TRUE"""")) { "a slot is still customizable" }
    }

    /**
     * A shortcut slot is a tappable glyph, drawn by the format itself.
     *
     * `<Launch>` has been available on every part since the beginning and this
     * app never used it, which is why a face here could show a step count and
     * not start a timer. The glyph goes over as `PartDraw` primitives rather
     * than a baked PNG: the format has Line, Ellipse, Rect, RoundRect and Arc,
     * and shipping pixels for something it can draw would mean rasterising the
     * same shapes in two more places.
     */
    @Test
    fun `a shortcut slot launches and is schema-valid`() {
        val p = DialParams(complications = listOf(
            ComplicationSource.SHORTCUT_MUSIC, ComplicationSource.SHORTCUT_ALARM,
            ComplicationSource.SHORTCUT_SETTINGS, ComplicationSource.SHORTCUT_PHONE,
            ComplicationSource.SHORTCUT_MESSAGES
        ))
        val xml = WffEmitter.emit(p)
        val errors = validate(xml)
        assertTrue(errors.isEmpty()) { "a shortcut is not schema-valid:\n" + errors.joinToString("\n") }

        for (target in listOf("MUSIC_PLAYER", "ALARM", "SETTINGS", "PHONE", "MESSAGE")) {
            assertTrue(xml.contains("""<Launch target="$target"/>""")) { "no $target shortcut emitted" }
        }
        // No provider, so no complication slot and nothing to read.
        assertTrue(!xml.contains("<ComplicationSlot ")) { "a shortcut was emitted as a complication" }
    }

    /**
     * Every glyph the format has to draw is made of shapes it HAS.
     *
     * A cubic has no equivalent in `PartDraw`, and the converter drops what it
     * cannot draw -- which would ship a glyph missing a stroke and validate
     * perfectly. Two shortcut glyphs were redrawn because of this.
     */
    @Test
    fun `every shortcut glyph can be drawn by the format`() {
        for (source in ComplicationSource.entries) {
            if (!source.isShortcut) continue
            val shapes = ComplicationGlyphs.shapes(source)
            assertTrue(shapes.isNotEmpty()) { "$source has no glyph, so it is an invisible button" }
            assertTrue(GlyphWff.canDraw(shapes)) {
                "$source uses a shape Watch Face Format cannot draw; it would be silently dropped"
            }
        }
    }

    /**
     * The step ring is drawn by the format and kept current by the WATCH.
     *
     * `[STEP_PERCENT]` is a first-class source and `<Transform>` binds an
     * expression to an attribute, so the sweep updates as someone walks with
     * nothing re-sent. This is the check that the arrangement is legal — an
     * expression in the wrong attribute validates as a plain float and then
     * draws nothing.
     */
    @Test
    fun `the step ring binds its sweep and is schema-valid`() {
        val xml = WffEmitter.emit(DialParams(ring = RingSource.STEPS))
        val errors = validate(xml)
        assertTrue(errors.isEmpty()) { "the step ring is not schema-valid:\n" + errors.joinToString("\n") }

        assertTrue(xml.contains("""<Transform target="endAngle" value="clamp([STEP_PERCENT], 0, 100) * 3.6"/>""")) {
            "the sweep is not bound to the step count, so the ring never moves"
        }
        // A faint track and a bright arc: without the track a part-finished
        // goal reads as a broken circle rather than progress.
        //
        // Counted INSIDE THE RING'S OWN ELEMENT, not across the document. It
        // used to count every <Arc> in the face, which worked only while the
        // ring was the sole thing using one — the heart and the bell are drawn
        // with arcs now, and the count jumped to six without anything about the
        // ring changing. A proxy that broad is measuring the wrong thing.
        val ring = xml.substringAfter("<PartDraw", "")
            .let { rest -> rest.split("<PartDraw").first { it.contains("STEP_PERCENT") } }
            .substringBefore("</PartDraw>")
        assertEquals(2, Regex("<Arc ").findAll(ring).count()) {
            "the ring should be exactly a track and a progress arc"
        }
    }

    @Test
    fun `no step ring means no ring at all`() {
        assertTrue(!WffEmitter.emit(DialParams()).contains("STEP_PERCENT"))
    }


    /** Every ring source is a real percentage the format can bind. */
    @ParameterizedTest
    @EnumSource(RingSource::class)
    fun `every ring source emits schema-valid WFF`(source: RingSource) {
        val xml = WffEmitter.emit(DialParams(ring = source))
        val errors = validate(xml)
        assertTrue(errors.isEmpty()) { "$source: " + errors.joinToString("\n") }
        if (source.enabled) {
            assertTrue(xml.contains(source.expression!!)) { "$source did not reach the face" }
        }
    }

    /**
     * Every weather reading is drawn, valid, and names its own source.
     *
     * ## Why the expected expressions are asked for rather than assumed
     *
     * A value too wide for its box is SHORTENED before it is shrunk, so
     * "72° Cloudy" in a row slot emits only `[WEATHER.TEMPERATURE]` — the
     * condition is deliberately not there. Asserting the full list would fail
     * on the feature working. What must stay true is that whatever wording the
     * layout chose is what the XML actually asks the watch for; a shortened
     * form that still emitted the dropped expression would be reading a sensor
     * to display nothing.
     */
    @ParameterizedTest
    @EnumSource(ComplicationSource::class)
    fun `every weather reading is schema-valid`(source: ComplicationSource) {
        if (!source.name.startsWith("WEATHER")) return
        val p = DialParams(complications = List(5) { source })
        val xml = WffEmitter.emit(p)
        val errors = validate(xml)
        assertTrue(errors.isEmpty()) { "$source: " + errors.joinToString("\n") }
        for ((pos, box) in SlotGeometry.boxes(p)) {
            val drawn = SlotGeometry.drawnText(source, box, SlotGeometry.fontSize(SlotGeometry.sizeAt(p, pos)))
            for (expression in drawn.expressions) {
                assertTrue(xml.contains(expression)) { "$source at $pos is missing $expression" }
            }
        }
        // The full form still has to reach a slot wide enough for it, or
        // "shorten when it does not fit" has quietly become "always shorten".
        if (source.compact != null) {
            assertTrue(source.drawn.all { xml.contains(it) }) {
                "$source was shortened in EVERY slot, including the wide ones"
            }
        }
    }

    /**
     * Two weather sources are in Google's enum, validate against Google's XSD,
     * and render NOTHING on a watch — taking the whole face with them.
     *
     * `WEATHER.TEMPERATURE_HIGH` and `WEATHER.TEMPERATURE_LOW` only exist per
     * day: `WEATHER.DAYS.0.…`. And `WEATHER.WEATHER.UV_INDEX`, with the
     * doubled prefix, is a typo in the schema itself.
     *
     * Found by installing each source on a watch one at a time and looking. No
     * amount of validation would have caught either, so this guards the two
     * spellings rather than the behaviour.
     */
    @Test
    fun `no weather source uses a spelling the watch rejects`() {
        val dead = listOf(
            "[WEATHER.TEMPERATURE_HIGH]",
            "[WEATHER.TEMPERATURE_LOW]",
            "[WEATHER.WEATHER.UV_INDEX]"
        )
        for (source in ComplicationSource.entries) {
            for (expression in source.drawn) {
                assertTrue(expression !in dead) {
                    "$source uses $expression, which validates and renders a black face; " +
                        "high, low and UV are day-indexed (WEATHER.DAYS.0....)"
                }
            }
        }
    }

    @Test
    fun `colors are emitted as 8 digit AARRGGBB`() {
        val xml = WffEmitter.emit(DialParams(inkColor = "#FCF9F1"))
        assertTrue(xml.contains("#fffcf9f1")) { "expected alpha-first 8-digit colour" }
        assertTrue(!Regex("""color="#[0-9a-f]{6}"""").containsMatchIn(xml)) {
            "found a 6-digit colour -- WFF needs #AARRGGBB and fails silently otherwise"
        }
    }

    @Test
    fun `ambient is per element variants not a second scene`() {
        val xml = WffEmitter.emit(DialParams())
        assertTrue(xml.contains("<Variant mode=\"AMBIENT\""))
        assertTrue(xml.split("<Scene").size == 2) { "there must be exactly one Scene" }
    }

    /**
     * The seconds follow a 12-hour clock as it changes width.
     *
     * ## The bug this pins
     *
     * A `h:mm` clock is "7:56" for nine hours out of twelve and "12:56" for
     * the other three, and it is CENTRED — so its right edge moves a whole
     * character between those cases. The gutter was sized for the wide one,
     * which stranded the seconds a character-width from the time for most of
     * the day. Reported from a wrist, on a face showing 7:56.
     *
     * The format cannot measure text and cannot position relative to another
     * element, so the only fix available is to emit BOTH positions and let the
     * watch pick. That is what `Condition` is for, and this test is the only
     * thing that says the emitted `Condition` is well-formed: a schema-invalid
     * one does not fail on the watch, it makes the whole face never appear.
     */
    @Test
    fun `a twelve-hour clock gets its seconds in two positions`() {
        val p = DialParams(
            showSeconds = true,
            hourFormat = HourFormat.TWELVE,
            ring = RingSource.STEPS
        )
        val xml = WffEmitter.emit(p)
        val errors = validate(xml)
        assertTrue(errors.isEmpty()) {
            "the seconds Condition is not schema-valid:\n" + errors.joinToString("\n")
        }

        val narrow = SecondsBand.leftEdgeFor(p, SecondsBand.NARROW_TIME)
        val wide = SecondsBand.leftEdgeFor(p, SecondsBand.WIDE_TIME)
        assertTrue(narrow < wide) { "a shorter time should let the seconds sit further left" }
        assertTrue(xml.contains("[HOUR_1_12]")) { "nothing chooses between the two positions" }
        assertEquals(1, Regex("<Condition>").findAll(xml).count())
        assertEquals(2, Regex("format=\"ss\"").findAll(xml).count()) {
            "there should be exactly one seconds element per branch"
        }
        assertTrue(xml.contains("x=\"$narrow\"")) { "the short-hour branch is not at $narrow" }
        assertTrue(xml.contains("x=\"$wide\"")) { "the long-hour branch is not at $wide" }
    }

    /**
     * A clock that is always five characters wide does not pay for the
     * Condition.
     *
     * `hh:mm` zero-pads, whether it is a 24-hour face or an automatic one on a
     * 12-hour watch, so there is nothing to choose between and the second
     * element would be pure weight on a file that crosses by Bluetooth.
     */
    @Test
    fun `a fixed-width clock keeps a single seconds element`() {
        val xml = WffEmitter.emit(
            DialParams(showSeconds = true, hourFormat = HourFormat.DEVICE, ring = RingSource.STEPS)
        )
        assertTrue(!xml.contains("<Condition>")) { "a fixed-width clock does not need two positions" }
        assertEquals(1, Regex("format=\"ss\"").findAll(xml).count())
    }
}

/**
 * Complication slots are user-configurable, so every combination a user can
 * reach has to be schema-valid. The provider tokens come from the schema's own
 * enumeration; an invented one installs cleanly and never appears.
 */
class ComplicationSchemaTest {

    private fun validate(xml: String): List<String> {
        val errors = mutableListOf<String>()
        val factory = javax.xml.validation.SchemaFactory.newInstance(
            "http://www.w3.org/XML/XMLSchema/v1.1",
            "org.apache.xerces.jaxp.validation.XMLSchema11Factory",
            null
        )
        val schema = File(
            WffSchemaTest::class.java.classLoader.getResource("wff-schema/watchface.xsd")!!.toURI()
        )
        val validator = factory.newSchema(schema).newValidator()
        validator.errorHandler = object : org.xml.sax.ErrorHandler {
            override fun warning(e: org.xml.sax.SAXParseException) {}
            override fun error(e: org.xml.sax.SAXParseException) { errors += "line ${e.lineNumber}: ${e.message}" }
            override fun fatalError(e: org.xml.sax.SAXParseException) { errors += "FATAL: ${e.message}" }
        }
        validator.validate(javax.xml.transform.stream.StreamSource(xml.reader()))
        return errors
    }

    @ParameterizedTest
    @EnumSource(ComplicationSource::class)
    fun `every complication source emits schema-valid WFF`(source: ComplicationSource) {
        val errors = validate(WffEmitter.emit(DialParams(complications = listOf(source))))
        assertTrue(errors.isEmpty()) { "$source: ${errors.joinToString("\n")}" }
    }

    @Test
    fun `slots set to NONE are not emitted at all`() {
        val xml = WffEmitter.emit(
            DialParams(complications = listOf(
                ComplicationSource.STEP_COUNT, ComplicationSource.NONE, ComplicationSource.WATCH_BATTERY
            ))
        )
        // An empty slot would still cost a tap target and a frame budget.
        assertTrue(xml.split("<ComplicationSlot").size - 1 == 2) { "expected exactly 2 slots" }
        assertTrue(!xml.contains("defaultSystemProvider=\"null\"")) { "a disabled slot leaked into the output" }
        assertTrue(validate(xml).isEmpty())
    }

    @Test
    fun `a face with no complications at all is still valid`() {
        val xml = WffEmitter.emit(DialParams(complications = emptyList()))
        assertTrue(!xml.contains("<ComplicationSlot"))
        assertTrue(validate(xml).isEmpty())
    }

    @Test
    fun `row slots re-centre rather than leaving a hole`() {
        fun xs(vararg c: ComplicationSource): List<Int> =
            Regex("""<ComplicationSlot slotId="\d+" x="(-?\d+)"""")
                .findAll(WffEmitter.emit(DialParams(complications = c.toList())))
                .map { it.groupValues[1].toInt() }.toList()

        val N = ComplicationSource.NONE
        val S = ComplicationSource.STEP_COUNT
        val H = ComplicationSource.HEART_RATE
        val D = ComplicationSource.DATE

        // TOP, LEFT, MIDDLE, RIGHT, BOTTOM
        val fullRow = xs(N, S, H, D, N)
        val loneRow = xs(N, N, H, N, N)
        assertEquals(3, fullRow.size)
        assertEquals(1, loneRow.size)
        assertEquals(fullRow[1], loneRow[0]) {
            "a lone row slot did not re-centre: $loneRow vs middle-of-three ${fullRow[1]}"
        }
    }

    @Test
    fun `top and bottom are centred singles`() {
        val N = ComplicationSource.NONE
        val B = ComplicationSource.WATCH_BATTERY
        fun xs(vararg c: ComplicationSource): List<Int> =
            Regex("""<ComplicationSlot slotId="\d+" x="(-?\d+)"""")
                .findAll(WffEmitter.emit(DialParams(complications = c.toList())))
                .map { it.groupValues[1].toInt() }.toList()
        assertEquals(xs(B, N, N, N, N), xs(N, N, N, N, B)) {
            "TOP and BOTTOM should share the same centred x"
        }
    }

    @Test
    fun `all five positions can be filled and stay schema valid`() {
        val xml = WffEmitter.emit(DialParams(complications = listOf(
            ComplicationSource.DAY_AND_DATE, ComplicationSource.STEP_COUNT,
            ComplicationSource.HEART_RATE, ComplicationSource.UNREAD_NOTIFICATION_COUNT,
            ComplicationSource.WATCH_BATTERY)))
        assertEquals(5, xml.split("<ComplicationSlot").size - 1)
        assertTrue(validate(xml).isEmpty())
    }

    @Test
    fun `a slot keeps its id when other slots are turned off`() {
        // THE invariant, and the one this file used to get wrong.
        //
        // Wear stores the wearer's complication choice against the slotId, and
        // that choice overrides DefaultProviderPolicy permanently -- the policy
        // only fills a slot nothing has been assigned to. Ids used to be a
        // running count of the ENABLED slots, so turning one off renumbered
        // every slot after it and the watch's memory reattached to the wrong
        // position. On a real watch the last slot showed notifications no
        // matter what was chosen in the app, and nothing the app sent could
        // dislodge it.
        //
        // The old test asserted the ids were contiguous from 0, which was true
        // only because it emitted DialParams() with all five slots on. It never
        // turned one off, so it never saw the renumbering.
        val filled = ComplicationSource.STEP_COUNT

        for (off in SlotPosition.entries) {
            val comps = SlotPosition.entries.map { if (it == off) ComplicationSource.NONE else filled }
            val xml = WffEmitter.emit(DialParams(complications = comps))
            val ids = Regex("""slotId="(\d+)"""").findAll(xml)
                .map { it.groupValues[1].toInt() }.toList()

            assertEquals(ids.size, ids.toSet().size) { "duplicate slotId with $off off: $ids" }
            assertEquals(
                SlotPosition.entries.filter { it != off }.map { it.ordinal },
                ids
            ) { "turning $off off renumbered the others: $ids" }
        }
    }

}
