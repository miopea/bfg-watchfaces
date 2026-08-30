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
        val xml = WffEmitter.emit(DialParams(dateStyle = style))
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
