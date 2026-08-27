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
    fun `active slots are re-centred rather than leaving a hole`() {
        fun xs(vararg c: ComplicationSource): List<Int> =
            Regex("""<ComplicationSlot slotId="\d+" x="(-?\d+)"""")
                .findAll(WffEmitter.emit(DialParams(complications = c.toList())))
                .map { it.groupValues[1].toInt() }.toList()

        val three = xs(ComplicationSource.STEP_COUNT, ComplicationSource.HEART_RATE, ComplicationSource.DATE)
        val one = xs(ComplicationSource.NONE, ComplicationSource.HEART_RATE, ComplicationSource.NONE)
        assertEquals(3, three.size)
        assertEquals(1, one.size)
        // A single remaining slot must sit where the middle of three sat, not
        // where its original index would have put it.
        assertEquals(three[1], one[0]) { "a lone slot did not re-centre: $one vs middle-of-three ${three[1]}" }
    }
}
