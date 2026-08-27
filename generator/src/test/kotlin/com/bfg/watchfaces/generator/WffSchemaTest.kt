package com.bfg.watchfaces.generator

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
