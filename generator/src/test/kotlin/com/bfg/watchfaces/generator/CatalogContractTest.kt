package com.bfg.watchfaces.generator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The contract is what a Worker with no JVM uses to refuse a bad face, so
 * anything it gets wrong is a submission accepted or rejected for the wrong
 * reason — and neither shows up anywhere else.
 *
 * These tests check the parts that can go stale silently. The check that the
 * COMMITTED file is current lives in `:workbench`, because that is where the
 * codec supplying the field list lives.
 */
class CatalogContractTest {

    private fun contract(): String = CatalogContract.json(listOf("engine", "scale"))

    /**
     * The one hand-copied list in the whole contract, checked against its
     * source.
     *
     * `CatalogContract.FONT_WEIGHTS` is transcribed from Watch Face Format's
     * schema because `:generator`'s main source set must not read a fetched
     * test resource. This is what makes that transcription safe: the schema is
     * right here in the test source set, so a copy that drifts fails rather
     * than quietly letting a weight through that the XSD will reject — which
     * on a watch means a face that installs and never appears.
     */
    @Test
    fun `the font weights are the schema's own`() {
        val xsd = File(
            javaClass.classLoader.getResource("wff-schema/group/part/text/fontElement.xsd")!!.toURI()
        ).readText()

        // The `weight` attribute's enumeration, and nothing else in the file --
        // `slant` and `width` are enumerations too and are not this list.
        val weightBlock = xsd.substringAfter("""<xs:attribute name="weight"""").substringBefore("</xs:attribute>")
        val fromSchema = Regex("""<xs:enumeration value="([^"]+)"/>""")
            .findAll(weightBlock).map { it.groupValues[1] }.toList()

        assertTrue(fromSchema.isNotEmpty()) { "found no weight enumeration in the schema; the parse above is wrong" }
        assertEquals(fromSchema, CatalogContract.FONT_WEIGHTS) {
            "FONT_WEIGHTS has drifted from Watch Face Format's own schema"
        }
    }

    /**
     * Every slider is described, and described the way the slider actually
     * behaves.
     *
     * A control missing from the contract is a value the Worker cannot check at
     * all: unknown ids are refused, so the submission is rejected outright and
     * the app looks broken.
     */
    @Test
    fun `every control in the inventory reaches the contract`() {
        val json = contract()
        for (c in ControlInventory.CONTROLS) {
            assertTrue(json.contains(""""id": "${c.id}"""")) { "control '${c.id}' is not in the contract" }
        }
    }

    /**
     * `complicationSize`'s bound is [SlotGeometry]'s own — the same numbers
     * that clamp it at render time — so a submission cannot carry a value the
     * renderer would quietly move.
     */
    @Test
    fun `the complication size bound is the geometry's own`() {
        assertTrue(
            contract().contains(""""complicationSize": {"min": ${SlotGeometry.MIN_SIZE}, "max": ${SlotGeometry.MAX_SIZE}""")
        ) { "complicationSize bounds do not match SlotGeometry" }
    }

    /**
     * THE ONE THAT MATTERS: a face built from the defaults must satisfy the
     * contract.
     *
     * A validator stricter than the format rejects real faces on a public
     * endpoint, and nothing else notices. This caught exactly that — `dateSize`
     * had been bounded by `SlotGeometry.MAX_DATE_SIZE` (56), which bounds the
     * DERIVED date size, while the stored default is 64. Every genuine
     * submission would have been refused.
     *
     * The check is deliberately over the DEFAULTS rather than a chosen face:
     * defaults are what every new face starts from, so if they do not pass,
     * nothing does.
     */
    @Test
    fun `the defaults satisfy every bound the contract states`() {
        val p = DialParams()
        val l = p.layout

        val numbers = mapOf(
            "scale" to p.scale, "depth" to p.depth, "freq" to p.freq.toDouble(),
            "stroke" to p.stroke, "relief" to p.relief, "rotate" to p.rotate,
            "contrast" to p.contrast, "sheen" to p.sheen, "vignette" to p.vignette,
            "timeSize" to l.timeSize.toDouble(), "timeY" to l.timeY.toDouble(),
            "complicationSpread" to l.complicationSpread.toDouble(),
            "complicationY" to l.complicationY.toDouble(),
            "dateY" to l.dateY.toDouble(), "batteryY" to l.batteryY.toDouble()
        )
        for ((id, value) in numbers) {
            val c = ControlInventory.byId(id)
            requireNotNull(c) { "no control called '$id'" }
            assertTrue(value >= c.min && value <= c.max) {
                "the default $id ($value) is outside its own control's range ${c.min}..${c.max}"
            }
        }

        // The three with no slider, whose bounds are stated in CatalogContract
        // rather than derived -- which is precisely why they need checking.
        assertTrue(l.dateSize >= CatalogContract.DATE_SIZE_MIN && l.dateSize <= CatalogContract.DATE_SIZE_MAX) {
            "the default dateSize (${l.dateSize}) is outside the contract's bound"
        }
        assertTrue(l.complicationSize >= SlotGeometry.MIN_SIZE && l.complicationSize <= SlotGeometry.MAX_SIZE) {
            "the default complicationSize (${l.complicationSize}) is outside the contract's bound"
        }
        assertTrue(l.tracking >= CatalogContract.TRACKING_MIN && l.tracking <= CatalogContract.TRACKING_MAX) {
            "the default tracking (${l.tracking}) is outside the contract's bound"
        }
        assertTrue(p.lensAmount >= CatalogContract.LENS_MIN && p.lensAmount <= CatalogContract.LENS_MAX) {
            "the default lensAmount (${p.lensAmount}) is outside the contract's bound"
        }

        assertTrue(Regex(CatalogContract.FONT_FAMILY_PATTERN).matches(l.fontFamily))
        assertTrue(CatalogContract.FONT_WEIGHTS.contains(l.fontWeight)) {
            "the default fontWeight '${l.fontWeight}' is not a weight the schema allows"
        }
    }

    /**
     * TEXTURE is the engine the catalog exists to refuse. It is the IP shield
     * and the size guarantee, not a style preference.
     */
    @Test
    fun `TEXTURE is named as unpublishable and is still a real engine`() {
        assertTrue(contract().contains(""""unpublishableEngines": ["TEXTURE"]"""))
        assertTrue(Engine.entries.any { it.name == "TEXTURE" }) {
            "the engine was renamed; the contract now forbids nothing"
        }
    }

    /**
     * Every enum a face can name is enumerated, so an unknown member is a
     * rejection rather than a value the Worker passes through to a renderer
     * that has never heard of it.
     */
    @Test
    fun `every enum a face carries is in the contract`() {
        val json = contract()
        val expected = mapOf(
            "engine" to Engine.entries.map { it.name },
            "dateStyle" to DateStyle.entries.map { it.name },
            "dateScale" to DateScale.entries.map { it.name },
            "ring" to RingSource.entries.map { it.name },
            "hourFormat" to HourFormat.entries.map { it.name },
            "slotPosition" to SlotPosition.entries.map { it.name },
            "complicationSource" to ComplicationSource.entries.map { it.name }
        )
        for ((name, values) in expected) {
            val line = json.lines().firstOrNull { it.trimStart().startsWith(""""$name":""") }
            requireNotNull(line) { "the contract has no '$name' enum" }
            for (v in values) assertTrue(line.contains("\"$v\"")) { "'$name' is missing member '$v'" }
        }
    }

    /**
     * The contract's colour rule must be `DialParams`' rule exactly — not
     * stricter.
     *
     * It was briefly written out as uppercase-only, which reads as tidier and
     * would have rejected `#7d7369` on the public endpoint while the app went
     * on saving it happily. A validator that is stricter than the format is a
     * bug that only strangers hit.
     */
    @Test
    fun `the colour pattern is exactly what DialParams enforces`() {
        val re = Regex(CatalogContract.COLOR_PATTERN)
        assertTrue(re.matches(DialParams().dialColor))
        assertTrue(re.matches(DialParams().inkColor))
        assertTrue(re.matches("#7d7369")) { "DialParams accepts lowercase; the contract must too" }
        assertFalse(re.matches("#FF7D7369")) { "8-digit is WFF's form, not the stored one" }
        assertFalse(re.matches("7D7369")) { "the hash is part of the stored form" }
        // A face that DialParams would build must never fail the contract.
        assertTrue(re.matches(DialParams(dialColor = "#abcdef").dialColor))
    }

    /**
     * The ComponentName pattern has to be ANCHORED, because the thing reading
     * it is JavaScript.
     *
     * Kotlin's `Regex.matches` requires the whole string, so `DialParams` can
     * leave its pattern unanchored safely. `RegExp.test` does not. An
     * unanchored copy in the Worker would accept a provider with a quote around
     * it — and that string is written verbatim into a WFF attribute.
     */
    @Test
    fun `the component pattern is anchored and refuses anything around it`() {
        val re = Regex(CatalogContract.COMPONENT_PATTERN)
        assertTrue(re.matches("com.example.app/.WeatherProvider"))
        assertTrue(re.matches("com.example.app/com.example.app.Provider"))
        assertFalse(re.matches("""com.example.app/.P" onload="x"""))
        assertFalse(re.matches("x com.example.app/.P"))
        assertFalse(re.matches("no-slash"))

        // The anchoring is the point: check it against a JS-style unanchored
        // search rather than trusting the shape of the string.
        assertTrue(CatalogContract.COMPONENT_PATTERN.startsWith("^"))
        assertTrue(CatalogContract.COMPONENT_PATTERN.endsWith("$"))
    }

    /**
     * The font family goes straight into an XML attribute in [WffEmitter]'s
     * output with no escaping. That is harmless while faces are made on the
     * machine that renders them, and stops being harmless the moment a stranger
     * can submit one — a quote closes the attribute.
     */
    @Test
    fun `the font family pattern refuses anything that could close an XML attribute`() {
        val re = Regex(CatalogContract.FONT_FAMILY_PATTERN)
        assertTrue(re.matches(Layout().fontFamily)) { "the default family is not accepted by its own pattern" }
        assertFalse(re.matches("""a" onload="x"""))
        assertFalse(re.matches("a<b"))
        assertFalse(re.matches("a&b"))
        assertFalse(re.matches("")) { "an empty family would emit family=\"\"" }
    }

    /** Deterministic output, so the committed file only changes when the format does. */
    @Test
    fun `the same inputs produce the same bytes`() {
        val fields = listOf("scale", "engine", "depth")
        assertEquals(CatalogContract.json(fields), CatalogContract.json(fields))
        // and the field list is sorted, so the codec's own ordering cannot
        // churn the committed file
        assertEquals(CatalogContract.json(fields), CatalogContract.json(fields.reversed()))
    }
}
