package com.bfg.watchfaces.generator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXParseException
import java.io.File
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory
import kotlin.math.sqrt

/**
 * The controls are now defined once, so the interesting question is no longer
 * "do the two UIs agree" — they cannot disagree. It is whether every value a
 * person can actually reach produces a face that works.
 *
 * That was unanswerable before. `min`/`max`/`step` lived only in the HTML, so
 * `:generator` had no idea what was reachable, and a range wider than the
 * geometry tolerates stayed invisible until somebody dragged a slider into it.
 * Now the ranges are enumerable and this walks them.
 *
 * It matters more than usual here: a schema-invalid face installs cleanly and
 * then never appears in the carousel, with no error anywhere.
 */
class ControlInventoryTest {

    companion object {
        @JvmStatic
        fun controls(): List<ControlInventory.Control> = ControlInventory.CONTROLS

        /**
         * Every control's extremes and midpoint. Walking every step of every
         * control would be ~1,900 schema validations; the ends are where the
         * geometry actually breaks, and the midpoint catches an inverted range.
         */
        @JvmStatic
        fun extremes(): List<Array<Any>> = ControlInventory.CONTROLS.flatMap { c ->
            listOf(c.min, (c.min + c.max) / 2, c.max).map { arrayOf<Any>(c, it) }
        }
    }

    // ---- the inventory describes real things ---------------------------------

    @ParameterizedTest
    @MethodSource("controls")
    fun `every control names a parameter that exists`(c: ControlInventory.Control) {
        // with() throws for an unknown id, so this also proves the two halves of
        // the contract agree: a UI that knows the id can set the value.
        val p = ControlInventory.with(DialParams(), c.id, c.min)
        assertNotNull(p)
    }

    @ParameterizedTest
    @MethodSource("controls")
    fun `every range is usable`(c: ControlInventory.Control) {
        assertTrue(c.max > c.min) { "${c.id} has an inverted or empty range: ${c.min}..${c.max}" }
        assertTrue(c.step > 0) { "${c.id} has a non-positive step" }
        assertTrue(c.step <= c.max - c.min) { "${c.id}'s step is bigger than its whole range" }
        assertTrue(c.values().size >= 2) { "${c.id} can only take one value; it should not be a slider" }
    }

    @ParameterizedTest
    @MethodSource("controls")
    fun `a control actually changes the parameters`(c: ControlInventory.Control) {
        // Guards the with() mapping: a copy/paste that set the wrong field would
        // leave one control silently doing nothing, or two doing the same thing.
        val base = DialParams()
        val moved = ControlInventory.with(base, c.id, c.max)
        assertFalse(base == moved) { "moving ${c.id} to its maximum changed nothing" }
    }

    @Test
    fun `no control is listed twice`() {
        val ids = ControlInventory.CONTROLS.map { it.id }
        assertEquals(ids.size, ids.toSet().size) { "duplicate control ids: $ids" }
    }

    @Test
    fun `integral controls only ever offer whole numbers`() {
        // freq, timeSize and the layout anchors are stored as Int. A UI handing
        // back 7.5 is not a finer setting, it is a parse failure.
        for (c in ControlInventory.CONTROLS.filter { it.integral }) {
            for (v in c.values()) {
                assertEquals(v, Math.floor(v)) { "${c.id} offers the fractional value $v" }
            }
        }
    }

    // ---- and every reachable value still produces a usable face ---------------

    private fun schemaFile(): File? =
        javaClass.classLoader.getResource("wff-schema/watchface.xsd")?.let { File(it.toURI()) }

    private fun schemaErrors(xml: String): List<String> {
        val schema = schemaFile() ?: return emptyList()
        val errors = mutableListOf<String>()
        val factory = SchemaFactory.newInstance(
            "http://www.w3.org/XML/XMLSchema/v1.1",
            "org.apache.xerces.jaxp.validation.XMLSchema11Factory",
            null
        )
        val validator = factory.newSchema(schema).newValidator()
        validator.errorHandler = object : ErrorHandler {
            override fun warning(e: SAXParseException) {}
            override fun error(e: SAXParseException) { errors += "line ${e.lineNumber}: ${e.message}" }
            override fun fatalError(e: SAXParseException) { errors += "FATAL line ${e.lineNumber}: ${e.message}" }
        }
        validator.validate(StreamSource(File.createTempFile("wff", ".xml").apply {
            writeText(xml); deleteOnExit()
        }))
        return errors
    }

    @ParameterizedTest
    @MethodSource("extremes")
    fun `every reachable value emits a schema-valid face`(c: ControlInventory.Control, value: Double) {
        val p = ControlInventory.with(
            DialParams(
                complications = listOf(
                    ComplicationSource.DAY_AND_DATE, ComplicationSource.STEP_COUNT,
                    ComplicationSource.HEART_RATE, ComplicationSource.UNREAD_NOTIFICATION_COUNT,
                    ComplicationSource.WATCH_BATTERY
                )
            ),
            c.id, value
        )
        val errors = schemaErrors(WffEmitter.emit(p, "Range Test"))
        assertTrue(errors.isEmpty()) {
            "${c.id} = $value emits WFF the schema rejects — that face would install " +
                "and then never appear in the carousel:\n" + errors.joinToString("\n")
        }
    }

    @ParameterizedTest
    @MethodSource("extremes")
    fun `every reachable value keeps the slots apart and on the dial`(
        c: ControlInventory.Control,
        value: Double
    ) {
        // The reason the ranges belong in :generator at all. A layout slider
        // whose range exceeds what SlotGeometry tolerates used to be invisible
        // until someone dragged it.
        val p = ControlInventory.with(
            DialParams(
                complications = listOf(
                    ComplicationSource.DAY_AND_DATE, ComplicationSource.STEP_COUNT,
                    ComplicationSource.HEART_RATE, ComplicationSource.UNREAD_NOTIFICATION_COUNT,
                    ComplicationSource.WATCH_BATTERY
                )
            ),
            c.id, value
        )
        val boxes = SlotGeometry.boxes(p)
        assertFalse(SlotGeometry.hasOverlap(boxes.values)) {
            "${c.id} = $value makes complication slots overlap"
        }
        for ((pos, b) in boxes) {
            for (cx in listOf(b.x, b.right)) for (cy in listOf(b.y, b.bottom)) {
                val dx = cx - DIAL_CENTER
                val dy = cy - DIAL_CENTER
                assertTrue(sqrt(dx * dx + dy * dy) <= DIAL_RADIUS) {
                    "${c.id} = $value pushes $pos off the edge of the dial"
                }
            }
        }
    }
}
