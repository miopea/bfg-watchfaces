package com.bfg.watchfaces.workbench

import com.bfg.watchfaces.generator.DialParams
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * `strings.xml` carries the face name, and the face name is chosen by whoever
 * designs the face.
 *
 * This file gets built into an APK by aapt2, which fails outright on malformed
 * XML — so an escaping mistake here is a build failure with a confusing cause,
 * not a rendering oddity.
 */
class ExportStringsTest {

    /** `exportTo` writes into a `watchface-template` tree; make the bare bones. */
    private fun rootWithTemplate(dir: File): File {
        File(dir, "watchface-template/res/raw").mkdirs()
        File(dir, "watchface-template/res/values").mkdirs()
        File(dir, "watchface-template/res/drawable-nodpi").mkdirs()
        return dir
    }

    private fun exportName(dir: File, name: String): String {
        Workbench.exportTo(rootWithTemplate(dir), DialParams(), colors = 8, faceName = name)
        return File(dir, "watchface-template/res/values/strings.xml").readText()
    }

    private fun nameIn(xml: String): String {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(InputSource(StringReader(xml)))
        val nodes = document.getElementsByTagName("string")
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node.attributes.getNamedItem("name")?.nodeValue == "watch_face_name") {
                return node.textContent
            }
        }
        error("no watch_face_name in the exported strings.xml")
    }

    @Test
    fun `an ampersand and a less-than survive as themselves`(@TempDir dir: File) {
        val xml = exportName(dir, "Tom & Jerry <3")
        assertEquals("Tom & Jerry <3", nameIn(xml)) { "the name did not round-trip through the XML" }
    }

    /**
     * THE ONE THAT IS NOT MERELY TIDYING.
     *
     * `>` is usually legal in element text, which is why escaping only `&` and
     * `<` looks sufficient. It is not: the XML specification says `>` MUST be
     * escaped when it appears in the sequence `]]>` in content. A face name
     * containing that sequence produced a `strings.xml` that no parser accepts,
     * and therefore an aapt2 link failure whose message would say nothing about
     * the name.
     *
     * Obscure, and a face name is exactly the field where obscure input arrives.
     */
    @Test
    fun `a face name containing the CDATA terminator still produces valid XML`(@TempDir dir: File) {
        val xml = exportName(dir, "Bracket ]]> Face")
        assertEquals("Bracket ]]> Face", nameIn(xml))
    }

    /**
     * This file is built into an APK, so a change that rewrites every existing
     * export is not a tidy-up. An ordinary name must come out byte-identical.
     */
    @Test
    fun `an ordinary name is written exactly as it was before`(@TempDir dir: File) {
        val xml = exportName(dir, "Midnight Blue")
        assertTrue(xml.contains("""<string name="watch_face_name">Midnight Blue</string>""")) {
            "the ordinary path changed:\n$xml"
        }
    }

    /**
     * THE ONE THING THAT DID CHANGE, pinned rather than glossed.
     *
     * A bare `>` used to be written literally, which is legal. The shared
     * helper escapes it unconditionally rather than only inside `]]>`, because
     * a rule nobody has to remember is one nobody can forget. So a name
     * containing `>` produces DIFFERENT BYTES than before.
     *
     * It does not produce a different resource: both forms parse to the same
     * string, so what aapt2 puts in the APK is identical. Recorded here because
     * "no behaviour change" was the acceptance line, and blanket byte-identity
     * would have been an overclaim.
     */
    @Test
    fun `a bare greater-than is now escaped, and still means the same thing`(@TempDir dir: File) {
        val xml = exportName(dir, "Faster > Slower")
        assertTrue(xml.contains("Faster &gt; Slower")) { "expected the escaped form:\n$xml" }
        assertEquals("Faster > Slower", nameIn(xml)) { "the meaning changed, which would be a real break" }
    }

    @Test
    fun `the name is trimmed, which is not part of the escaping question`(@TempDir dir: File) {
        val xml = exportName(dir, "  Padded  ")
        assertEquals("Padded", nameIn(xml))
    }
}
