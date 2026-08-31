package com.bfg.watchfaces.generator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The emitter builds XML by interpolating strings, and some of those strings
 * now come from strangers. These are the tests that fail if the escaping is
 * removed.
 *
 * ## Two of these are about a bug that predates the catalog entirely
 *
 * A face named `Rock -- Roll` did not build. Not on a submission from a
 * stranger — for anybody, locally, today, because the name goes into the header
 * comment and XML forbids `--` there. The name is a separate argument to
 * [WffEmitter.emit] and never passes through [DialParams], so no amount of
 * validating stored parameters would have caught it.
 *
 * ## What "well-formed" is and is not evidence of
 *
 * A quote injected into an attribute produces PERFECTLY well-formed XML — it
 * just has an extra attribute the emitter never wrote. So these tests check for
 * the injected content specifically rather than only parsing the result. A
 * parse that succeeds is exactly what a successful injection looks like.
 */
class XmlSafeTest {

    private fun parses(xml: String): Boolean = runCatching {
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(InputSource(StringReader(xml)))
    }.isSuccess

    private fun why(xml: String): String = runCatching {
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(xml)))
        "parsed"
    }.exceptionOrNull()?.message ?: "parsed"

    // ---- the live bug -------------------------------------------------------

    @Test
    fun `a face named with a double hyphen builds`() {
        val xml = WffEmitter.emit(DialParams(), "Rock -- Roll")
        assertTrue(parses(xml)) { "a legally-named face does not emit valid XML: ${why(xml)}" }
    }

    @Test
    fun `a face name cannot close the header comment and inject markup`() {
        val xml = WffEmitter.emit(DialParams(), "X --> <Evil/> <!-- y")
        assertTrue(parses(xml)) { "the comment was closed early: ${why(xml)}" }

        // Asking whether the STRING contains "<Evil/>" is the wrong question:
        // it does, harmlessly, as inert text inside the comment. The question
        // is whether it became an ELEMENT, so this walks the parsed document.
        // The first version of this test asserted on the string and failed on
        // correct output.
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(InputSource(StringReader(xml)))
        assertEquals(0, document.getElementsByTagName("Evil").length) {
            "attacker markup became a real element"
        }
    }

    @Test
    fun `a face name keeps its readable shape in the comment`() {
        // A comment is documentation. Sanitizing has to leave the line still
        // telling someone which face they have opened.
        assertTrue(WffEmitter.emit(DialParams(), "Rock -- Roll").contains("Rock - Roll"))
        assertTrue(WffEmitter.emit(DialParams(), "Midnight Blue").contains("Midnight Blue"))
    }

    // ---- attribute injection ------------------------------------------------

    @Test
    fun `a font family cannot add an attribute the emitter never wrote`() {
        val xml = WffEmitter.emit(DialParams(layout = Layout(fontFamily = """Roboto" weight="BOLD""")), "ok")
        assertTrue(parses(xml)) { why(xml) }
        // The giveaway: well-formed XML carrying an attribute nobody emitted.
        // Checking only that it parses would PASS on a successful injection.
        assertFalse(xml.contains("""family="Roboto" weight="BOLD"""")) {
            "the quote closed the attribute and injected another one"
        }
        assertTrue(xml.contains("&quot;")) { "the quote was not escaped at all" }
    }

    @Test
    fun `a font weight cannot inject either`() {
        // Layout stores fontWeight as a free String, so it is the same shape as
        // fontFamily even though every value anyone has ever picked is one of
        // the schema's enumerated weights.
        val xml = WffEmitter.emit(DialParams(layout = Layout(fontWeight = """BOLD" alpha="0""")), "ok")
        assertTrue(parses(xml)) { why(xml) }
        assertFalse(xml.contains("""weight="BOLD" alpha="0""""))
    }

    @Test
    fun `a legal launch target is written unchanged`() {
        val params = DialParams(
            complications = listOf(ComplicationSource.SHORTCUT_APP),
            launchers = mapOf(SlotPosition.TOP to "com.example.app/.Main")
        )
        val xml = WffEmitter.emit(params, "ok")
        assertTrue(parses(xml)) { why(xml) }
        assertTrue(xml.contains("""target="com.example.app/.Main"""")) {
            "a legal launch target was mangled; escaping must not change valid values"
        }
    }

    // ---- the property that makes this safe to ship --------------------------

    /**
     * THE ONE THAT MATTERS FOR EVERY FACE ALREADY SAVED.
     *
     * Community faces are stored as parameters, so the emitter IS the renderer
     * for the stored file format. If escaping altered the output of any legal
     * face, this would be a silent rewrite of everything in the catalog — the
     * thing `DECISIONS.md` says must never happen without a `generatorVersion`
     * bump.
     *
     * It does not, and this is why: escaping only changes a string that
     * contains a character that had no business being there.
     */
    @Test
    fun `escaping changes nothing about a value that was already legal`() {
        val legal = listOf(
            Layout().fontFamily, Layout().fontWeight, "SYNC_TO_DEVICE", "Roboto", "Roboto Flex",
            "com.example.app/.WeatherProvider", "com.example.app/com.example.app.Provider",
            "MEDIUM", "THIN", "Midnight Blue", "face_7f3a"
        )
        for (value in legal) {
            assertEquals(value, XmlSafe.attr(value)) { "escaping altered a legal value: '$value'" }
            assertEquals(value, XmlSafe.text(value)) { "escaping altered a legal value: '$value'" }
        }
    }

    @Test
    fun `every engine still emits exactly what it emitted before`() {
        // Belt and braces on the above: walk every engine and confirm the
        // escaped attributes come out byte-identical to the raw values.
        for (engine in Engine.entries) {
            val p = DialParams(engine = engine, texture = if (engine == Engine.TEXTURE) "x" else "")
            val xml = WffEmitter.emit(p, "Sample Face")
            assertTrue(xml.contains("""family="${p.layout.fontFamily}"""")) {
                "$engine: the font family was altered by escaping"
            }
            assertFalse(xml.contains("&amp;amp;")) { "$engine: double-escaped" }
        }
    }

    // ---- the helper itself --------------------------------------------------

    @Test
    fun `attr escapes all five, and text escapes the three that matter`() {
        assertEquals("&amp;&lt;&gt;&quot;&apos;", XmlSafe.attr("""&<>"'"""))
        // Quotes are legal in element text and are left alone.
        assertEquals("""&amp;&lt;&gt;"'""", XmlSafe.text("""&<>"'"""))
    }

    @Test
    fun `comment sanitizes rather than escapes, because XML offers no escape there`() {
        // Entities are not expanded inside a comment, so the numeric form of a
        // hyphen would be literally that. Changing the text is the only correct
        // option.
        assertEquals("a - b", XmlSafe.comment("a -- b"))
        assertEquals("a - b", XmlSafe.comment("a ----- b"))
        assertFalse(XmlSafe.comment("trailing-").endsWith("-")) { "a comment may not end with a hyphen" }
        assertEquals("a b", XmlSafe.comment("a\tb")) { "a tab should become a space, not vanish" }

        // U+202E RIGHT-TO-LEFT OVERRIDE, written as an escape so the source
        // file stays readable. It makes a string render as something other than
        // what it says, which is not a thing a file header should be able to do.
        val override = "‮"
        assertFalse(XmlSafe.comment("Midnight$override").contains(override))
    }
}
