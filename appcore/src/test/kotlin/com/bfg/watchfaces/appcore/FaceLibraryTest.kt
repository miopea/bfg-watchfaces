package com.bfg.watchfaces.appcore

import com.bfg.watchfaces.generator.CURRENT_GENERATOR_VERSION
import com.bfg.watchfaces.generator.DialParams
import com.bfg.watchfaces.generator.Engine
import com.bfg.watchfaces.generator.WffEmitter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * A saved face IS a catalog entry (docs/SPEC.md). These tests hold that format
 * to the promises the architecture makes about it: parameters only, reproducible
 * on someone else's machine, and a slug Watch Face Push will actually accept.
 */
class FaceLibraryTest {

    companion object {
        @JvmStatic @BeforeAll fun headless() { System.setProperty("java.awt.headless", "true") }
    }

    private val sample = DialParams(
        engine = Engine.KNOTWORK, scale = 26.0, depth = 3.0, freq = 7,
        dialColor = "#2B2E33", inkColor = "#FCF9F1", lens = false, lensAmount = 12.5
    )

    /**
     * Saving your own face brings it up to date; nothing else does.
     *
     * The v11 fix for clipped weather text could not reach a face saved at
     * v10 — every send rebuilt it at v10 and reproduced the bug faithfully.
     * Freezing a stored face is the right default for one you installed from
     * the gallery and the wrong one for your own work.
     */
    @Test
    fun `saving stamps the face with the current generator version`(@TempDir tmp: File) {
        val old = DialParams(generatorVersion = 10)
        val saved = FaceLibrary.save(tmp, "Old Face", old)
        assertEquals(CURRENT_GENERATOR_VERSION, saved.params.generatorVersion)
        assertEquals(
            CURRENT_GENERATOR_VERSION,
            FaceLibrary.load(tmp, "old_face")!!.params.generatorVersion
        ) { "the stamp did not survive the round trip to disk" }
    }

    /**
     * The RETURNED face carries the stamp, not just the file.
     *
     * The phone kept its own copy of the params and saved from it, so the stamp
     * landed on disk while the screen went on holding the old version — and a
     * face re-saved for v13 was then SENT at v12. Measured by pulling the APK
     * off the watch, which still read "generator, v12".
     *
     * Returning the stamped face is what lets a caller take back what was
     * actually written instead of assuming it wrote what it sent.
     */
    @Test
    fun `save returns the face it actually stored`(@TempDir tmp: File) {
        val old = DialParams(generatorVersion = 10)
        val returned = FaceLibrary.save(tmp, "Old Face", old)
        assertEquals(CURRENT_GENERATOR_VERSION, returned.params.generatorVersion) {
            "the returned face still carries the version the caller passed in"
        }
        assertEquals(
            FaceLibrary.load(tmp, "old_face")!!.params.generatorVersion,
            returned.params.generatorVersion
        ) { "what was returned differs from what was written" }
    }

    /** Everything else leaves a stored version alone. */
    @Test
    fun `reading a face does not move its version`() {
        val json = FaceLibrary.toJson(
            FaceLibrary.StoredFace(
                "kept", "Kept", "2026-01-01T00:00:00Z", DialParams(generatorVersion = 10)
            )
        )
        assertEquals(10, FaceLibrary.fromJson(json).params.generatorVersion) {
            "reading a face silently upgraded it; only saving may do that"
        }
    }

    @Test
    fun `a saved face round trips through the catalog format`(@TempDir tmp: File) {
        val saved = FaceLibrary.save(tmp, "Midnight Knot", sample)
        val back = FaceLibrary.load(tmp, saved.slug)
        assertNotNull(back)
        assertEquals("Midnight Knot", back!!.name)
        // The params are the whole point. If these drift, the author's face
        // renders differently than they saw it, with no error anywhere.
        assertEquals(sample, back.params)
    }

    @Test
    fun `stored faces contain parameters, never rasters`(@TempDir tmp: File) {
        val saved = FaceLibrary.save(tmp, "Midnight Knot", sample)
        val text = File(FaceLibrary.dir(tmp), "${saved.slug}.json").readText()
        assertTrue(text.contains("\"engine\": \"KNOTWORK\""))
        // Parametric-only is the IP shield AND what keeps the catalog ~5KB/face.
        assertFalse(text.contains("data:image")) { "a raster leaked into the catalog format" }
        assertFalse(text.contains("base64")) { "a raster leaked into the catalog format" }
        assertTrue(text.length < 5000) { "a face should be a few KB, not ${text.length} bytes" }
    }

    @Test
    fun `slugs obey the Watch Face Push package rules`() {
        // Push rejects anything that is not lowercase alphanumeric/underscore,
        // and the slug becomes <app>.watchfacepush.<slug>. Getting this wrong is
        // a rejected install, so the rules are Google's, not ours.
        val cases = mapOf(
            "Midnight Knot" to "midnight_knot",
            "  Harbour  Steel  " to "harbour_steel",
            "Rosette Noir!!" to "rosette_noir",
            "Café Crème" to "caf_cr_me"
        )
        for ((input, expected) in cases) assertEquals(expected, FaceLibrary.slugify(input)) { "slugify($input)" }

        for (name in cases.keys) {
            val slug = FaceLibrary.slugify(name)
            // The real contract: the emitter must accept it.
            WffEmitter.pushPackageName("com.bfg.watchfaces", slug)
        }
    }

    @Test
    fun `a slug that would start with a digit is still valid`() {
        val slug = FaceLibrary.slugify("1970 Chronograph")
        WffEmitter.pushPackageName("com.bfg.watchfaces", slug)  // must not throw
        assertTrue(slug.first().isLetter()) { "package segments cannot start with a digit, got '$slug'" }
    }

    @Test
    fun `an unnamed face is refused rather than silently stored`(@TempDir tmp: File) {
        assertThrows(IllegalArgumentException::class.java) { FaceLibrary.save(tmp, "   ", sample) }
    }

    @Test
    fun `list and delete behave`(@TempDir tmp: File) {
        FaceLibrary.save(tmp, "One", sample)
        FaceLibrary.save(tmp, "Two", sample.copy(engine = Engine.CLOUS))
        assertEquals(2, FaceLibrary.list(tmp).size)
        assertTrue(FaceLibrary.delete(tmp, "one"))
        assertEquals(1, FaceLibrary.list(tmp).size)
        assertFalse(FaceLibrary.delete(tmp, "does_not_exist"))
    }

    @Test
    fun `a corrupt face file does not take the whole library down`(@TempDir tmp: File) {
        FaceLibrary.save(tmp, "Good", sample)
        File(FaceLibrary.dir(tmp), "broken.json").writeText("{ not json at all")
        // One bad file must not make every other saved face unreachable.
        assertEquals(1, FaceLibrary.list(tmp).size)
    }

    @Test
    fun `json parser handles the shapes the catalog uses`() {
        val v = Json.obj(Json.parse("""
            {"s":"a\"b\n","n":-12.5,"i":7,"t":true,"f":false,"z":null,
             "nested":{"k":"v"},"arr":[1,2,3]}
        """.trimIndent()))
        assertEquals("a\"b\n", Json.str(v, "s"))
        assertEquals(-12.5, Json.num(v, "n", 0.0))
        assertEquals(7.0, Json.num(v, "i", 0.0))
        assertTrue(Json.bool(v, "t", false))
        assertFalse(Json.bool(v, "f", true))
        assertEquals("v", Json.str(Json.obj(v["nested"]), "k"))
        assertEquals(3, (v["arr"] as List<*>).size)
    }

    @Test
    fun `json quote and parse are inverses for awkward names`() {
        for (s in listOf("plain", "with \"quotes\"", "tab\there", "new\nline", "back\\slash", "emoji ✦")) {
            val parsed = Json.parse(Json.quote(s))
            assertEquals(s, parsed)
        }
    }
}
