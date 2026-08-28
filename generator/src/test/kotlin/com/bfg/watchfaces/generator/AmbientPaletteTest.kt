package com.bfg.watchfaces.generator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.math.abs

/**
 * Ambient is a black screen. A dark ink chosen for a pale dial renders the time
 * invisible when the watch dims -- a face that is schema-valid, installs fine,
 * and is simply unusable on the wrist. Nothing else in the build can see that.
 */
class AmbientPaletteTest {

    /** Every colour the app offers as ink, plus the awkward extremes. */
    private val inks = listOf(
        "#FCF9F1", "#FFFFFF", "#E8E6E1", "#C9A227", "#1A1A1A",
        "#000000", "#23306B", "#3E4A3F", "#7A4A3C", "#2B2E33"
    )

    @ParameterizedTest
    @ValueSource(strings = ["#FCF9F1", "#FFFFFF", "#E8E6E1", "#C9A227", "#1A1A1A",
                            "#000000", "#23306B", "#3E4A3F", "#7A4A3C", "#2B2E33"])
    fun `every ink is readable on black after conversion`(ink: String) {
        val out = AmbientPalette.forAmbient(ink)
        assertTrue(AmbientPalette.contrastOnBlack(out) >= 4.5) {
            "$ink -> $out gives only ${"%.2f".format(AmbientPalette.contrastOnBlack(out))}:1 on black"
        }
    }

    @Test
    fun `a colour already bright enough is returned unchanged`() {
        // A white-ink face must be byte-identical to before the change, or the
        // v3 bump would alter faces it had no business altering.
        for (ink in listOf("#FCF9F1", "#FFFFFF", "#E8E6E1")) {
            assertEquals(ink.uppercase(), AmbientPalette.forAmbient(ink))
        }
    }

    @Test
    fun `hue survives the lift`() {
        // The whole point of this over "just use white": a navy ink should still
        // read as navy in ambient, not as grey.
        val navy = "#23306B"
        val lifted = AmbientPalette.forAmbient(navy)
        assertNotEquals(navy, lifted)

        fun hueOf(hex: String): Double {
            val v = hex.removePrefix("#")
            val r = v.substring(0,2).toInt(16)/255.0
            val g = v.substring(2,4).toInt(16)/255.0
            val b = v.substring(4,6).toInt(16)/255.0
            val mx = maxOf(r,g,b); val mn = minOf(r,g,b); val d = mx-mn
            if (d < 1e-9) return -1.0
            val h = when (mx) { r -> ((g-b)/d + if (g<b) 6 else 0); g -> (b-r)/d + 2; else -> (r-g)/d + 4 } * 60
            return h
        }
        val before = hueOf(navy); val after = hueOf(lifted)
        assertTrue(abs(before - after) < 6.0) { "hue moved from $before to $after" }
    }

    @Test
    fun `pure black becomes grey rather than throwing`() {
        // Black has no hue to preserve, so there is nothing to be faithful to.
        val out = AmbientPalette.forAmbient("#000000")
        assertTrue(AmbientPalette.contrastOnBlack(out) >= 4.5)
    }

    @Test
    fun `v3 emits a readable ambient ink where v2 emitted an invisible one`() {
        val dark = DialParams(inkColor = "#1A1A1A", generatorVersion = 2)
        val v3 = dark.copy(generatorVersion = 3)

        // v2 emitted the raw ink: nearly black text on a black screen.
        assertTrue(WffEmitter.emit(dark).contains("color=\"#a01a1a1a\"")) {
            "v2 ambient ink changed; stored v2 faces must render exactly as before"
        }
        // v3 lifts it.
        val xml3 = WffEmitter.emit(v3)
        assertTrue(!xml3.contains("#a01a1a1a")) { "v3 still emits the invisible ink" }
        val lifted = AmbientPalette.forAmbient("#1A1A1A")
        assertTrue(xml3.contains("color=\"#ff${lifted.removePrefix("#").lowercase()}\"")) {
            "v3 did not emit the lifted ambient ink"
        }
    }

    @Test
    fun `v3 changes no geometry`() {
        // The bump is about colour. If geometry moved too, the version would be
        // hiding a second change and the guarantee would be worthless.
        // Only engines that existed at v2 can be compared across v2 and v3.
        // TEXTURE and the generated surfaces have no geometry, and the surfaces
        // did not exist until v4.
        val comparable = Engine.entries.filter {
            it != Engine.NONE && it != Engine.TEXTURE && !TextureField.isProcedural(it)
        }
        for (e in comparable) {
            val v2 = PatternEngines.paths(DialParams(generatorVersion = 2, engine = e))
            val v3 = PatternEngines.paths(DialParams(generatorVersion = 3, engine = e))
            assertEquals(v2, v3) { "$e geometry differs between v2 and v3" }
        }
    }
}

/**
 * The clock ships two TimeText elements so its ambient colour can differ. A
 * complication has ONE Font colour for both modes, so the only way its ambient
 * colour can differ is a colour Variant. That the schema accepts one is not
 * obvious -- Variant's `value` is an arithmetic expression type -- so it is
 * asserted rather than assumed.
 */
class AmbientComplicationColourTest {

    private fun validate(xml: String): List<String> {
        val errors = mutableListOf<String>()
        val factory = javax.xml.validation.SchemaFactory.newInstance(
            "http://www.w3.org/XML/XMLSchema/v1.1",
            "org.apache.xerces.jaxp.validation.XMLSchema11Factory", null
        )
        val schema = java.io.File(
            WffSchemaTest::class.java.classLoader.getResource("wff-schema/watchface.xsd")!!.toURI()
        )
        val v = factory.newSchema(schema).newValidator()
        v.errorHandler = object : org.xml.sax.ErrorHandler {
            override fun warning(e: org.xml.sax.SAXParseException) {}
            override fun error(e: org.xml.sax.SAXParseException) { errors += "line ${e.lineNumber}: ${e.message}" }
            override fun fatalError(e: org.xml.sax.SAXParseException) { errors += "FATAL: ${e.message}" }
        }
        v.validate(javax.xml.transform.stream.StreamSource(xml.reader()))
        return errors
    }

    @Test
    fun `a dark ink gets an ambient colour variant on every slot, and it validates`() {
        val xml = WffEmitter.emit(DialParams(inkColor = "#1A1A1A"))
        val variants = Regex("""target="color"""").findAll(xml).count()
        val slots = Regex("""<ComplicationSlot """).findAll(xml).count()
        assertEquals(slots, variants) { "expected one ambient colour variant per slot" }
        assertTrue(validate(xml).isEmpty()) { validate(xml).joinToString("\n") }
    }

    @Test
    fun `a light ink gets no variant at all`() {
        // Emitting a no-op variant on every face would be noise in a format
        // people read, and would imply a change that is not happening.
        val xml = WffEmitter.emit(DialParams(inkColor = "#FCF9F1"))
        assertTrue(!xml.contains("""target="color""""))
        assertTrue(validate(xml).isEmpty())
    }

    @Test
    fun `v2 faces get no variant, dark ink or not`() {
        val xml = WffEmitter.emit(DialParams(inkColor = "#1A1A1A", generatorVersion = 2))
        assertTrue(!xml.contains("""target="color"""")) {
            "a v2 face gained a v3 feature; stored faces must render as their authors saw them"
        }
    }
}
