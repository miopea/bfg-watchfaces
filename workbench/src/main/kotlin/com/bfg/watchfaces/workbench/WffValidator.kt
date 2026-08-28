package com.bfg.watchfaces.workbench

import org.xml.sax.ErrorHandler
import org.xml.sax.SAXParseException
import java.io.File
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.Schema
import javax.xml.validation.SchemaFactory

/**
 * Runs the SAME XSD 1.1 validation that WffSchemaTest runs, but live, on every
 * keystroke in the workbench.
 *
 * This is the point of the whole tool. A schema-invalid face compiles, links,
 * signs, installs and reports Success -- and then silently never appears in the
 * carousel, with no runtime error anywhere. Catching that in a browser in
 * milliseconds, instead of after a build-sign-sideload round trip, is the
 * difference the workbench is meant to make.
 *
 * The schemas are XSD 1.1, so this needs Xerces plus the XPath2 processor; the
 * JDK validator only implements 1.0. The factory is named explicitly because
 * JAXP service discovery fails here with an opaque IllegalArgumentException.
 * scripts/bootstrap.sh puts the jars and the schema in place.
 */
object WffValidator {

    data class Issue(val line: Int, val fatal: Boolean, val message: String)

    /** Located relative to the repo root so the workbench needs no test classpath. */
    private fun schemaFile(root: File): File? = listOf(
        File(root, "generator/src/test/resources/wff-schema/watchface.xsd"),
        File(root, "watchface-template/tools/wff-schema/watchface.xsd")
    ).firstOrNull { it.isFile }

    @Volatile private var cached: Schema? = null

    private fun schema(root: File): Schema? {
        cached?.let { return it }
        val f = schemaFile(root) ?: return null
        val factory = SchemaFactory.newInstance(
            "http://www.w3.org/XML/XMLSchema/v1.1",
            "org.apache.xerces.jaxp.validation.XMLSchema11Factory",
            null
        )
        return factory.newSchema(f).also { cached = it }
    }

    /** null return means the schema is not installed -- run scripts/bootstrap.sh. */
    fun validate(root: File, xml: String): List<Issue>? {
        val s = schema(root) ?: return null
        val issues = mutableListOf<Issue>()
        val v = s.newValidator()
        v.errorHandler = object : ErrorHandler {
            override fun warning(e: SAXParseException) {}
            override fun error(e: SAXParseException) { issues += Issue(e.lineNumber, false, e.message ?: "") }
            override fun fatalError(e: SAXParseException) { issues += Issue(e.lineNumber, true, e.message ?: "") }
        }
        try {
            v.validate(StreamSource(xml.reader()))
        } catch (e: Exception) {
            issues += Issue(-1, true, e.message ?: e.toString())
        }
        return issues
    }
}
