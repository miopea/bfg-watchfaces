package com.bfg.watchfaces.workbench

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
class FaceStoreTest {

    companion object {
        @JvmStatic @BeforeAll fun headless() { System.setProperty("java.awt.headless", "true") }
    }

    private val sample = DialParams(
        engine = Engine.KNOTWORK, scale = 26.0, depth = 3.0, freq = 7,
        dialColor = "#2B2E33", inkColor = "#FCF9F1", lens = false, lensAmount = 12.5
    )

    @Test
    fun `a saved face round trips through the catalog format`(@TempDir tmp: File) {
        val saved = FaceStore.save(tmp, "Midnight Knot", sample)
        val back = FaceStore.load(tmp, saved.slug)
        assertNotNull(back)
        assertEquals("Midnight Knot", back!!.name)
        // The params are the whole point. If these drift, the author's face
        // renders differently than they saw it, with no error anywhere.
        assertEquals(sample, back.params)
    }

    @Test
    fun `stored faces contain parameters, never rasters`(@TempDir tmp: File) {
        val saved = FaceStore.save(tmp, "Midnight Knot", sample)
        val text = File(FaceStore.dir(tmp), "${saved.slug}.json").readText()
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
        for ((input, expected) in cases) assertEquals(expected, FaceStore.slugify(input)) { "slugify($input)" }

        for (name in cases.keys) {
            val slug = FaceStore.slugify(name)
            // The real contract: the emitter must accept it.
            WffEmitter.pushPackageName("com.bfg.watchfaces", slug)
        }
    }

    @Test
    fun `a slug that would start with a digit is still valid`() {
        val slug = FaceStore.slugify("1970 Chronograph")
        WffEmitter.pushPackageName("com.bfg.watchfaces", slug)  // must not throw
        assertTrue(slug.first().isLetter()) { "package segments cannot start with a digit, got '$slug'" }
    }

    @Test
    fun `an unnamed face is refused rather than silently stored`(@TempDir tmp: File) {
        assertThrows(IllegalArgumentException::class.java) { FaceStore.save(tmp, "   ", sample) }
    }

    @Test
    fun `list and delete behave`(@TempDir tmp: File) {
        FaceStore.save(tmp, "One", sample)
        FaceStore.save(tmp, "Two", sample.copy(engine = Engine.CLOUS))
        assertEquals(2, FaceStore.list(tmp).size)
        assertTrue(FaceStore.delete(tmp, "one"))
        assertEquals(1, FaceStore.list(tmp).size)
        assertFalse(FaceStore.delete(tmp, "does_not_exist"))
    }

    @Test
    fun `a corrupt face file does not take the whole library down`(@TempDir tmp: File) {
        FaceStore.save(tmp, "Good", sample)
        File(FaceStore.dir(tmp), "broken.json").writeText("{ not json at all")
        // One bad file must not make every other saved face unreachable.
        assertEquals(1, FaceStore.list(tmp).size)
    }

    @Test
    fun `export names the face everywhere the watch will read it`(@TempDir tmp: File) {
        File(tmp, "watchface-template/res/raw").mkdirs()
        File(tmp, "watchface-template/AndroidManifest.xml").writeText(
            """<manifest package="com.bfg.watchfaces.watchfacepush.placeholder"></manifest>"""
        )
        Workbench.exportTo(tmp, sample, 64, "Midnight Knot")

        val strings = File(tmp, "watchface-template/res/values/strings.xml").readText()
        assertTrue(strings.contains("<string name=\"watch_face_name\">Midnight Knot</string>")) {
            "carousel label not written"
        }
        val manifest = File(tmp, "watchface-template/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("com.bfg.watchfaces.watchfacepush.midnight_knot")) {
            "package name not rewritten -- Watch Face Push would reject or mislabel this"
        }
        assertTrue(File(tmp, "watchface-template/res/raw/watchface.xml").readText().contains("Midnight Knot"))
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

/**
 * Imported images for Engine.TEXTURE. They are the one thing in this app that
 * is content rather than parameters, so the boundaries matter.
 */
class TextureStoreTest {

    companion object {
        @JvmStatic @BeforeAll fun headless() { System.setProperty("java.awt.headless", "true") }
    }

    private fun samplePng(w: Int = 40, h: Int = 40): ByteArray {
        val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, (x * 6 shl 16) or (y * 6 shl 8) or 0x40)
        val bos = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(img, "png", bos)
        return bos.toByteArray()
    }

    @Test
    fun `importing the same image twice is the same texture`(@TempDir tmp: File) {
        val bytes = samplePng()
        val a = TextureStore.save(tmp, bytes)
        val b = TextureStore.save(tmp, bytes)
        // Content-addressed: a face's reference can never later point at
        // different bytes, and re-importing costs nothing.
        assertEquals(a.id, b.id)
        assertEquals(1, TextureStore.list(tmp).size)
    }

    @Test
    fun `non-images are refused rather than stored`(@TempDir tmp: File) {
        assertThrows(IllegalArgumentException::class.java) {
            TextureStore.save(tmp, "this is not a PNG".toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) { TextureStore.save(tmp, ByteArray(0)) }
        assertEquals(0, TextureStore.list(tmp).size)
    }

    @Test
    fun `a texture id cannot escape the textures directory`(@TempDir tmp: File) {
        // The id reaches this from a query string, so path traversal is the
        // obvious attack on a tool that binds a port.
        assertNull(TextureStore.load(tmp, "../../etc/passwd"))
        assertNull(TextureStore.load(tmp, "..%2F..%2Fsecret"))
        assertNull(TextureStore.load(tmp, "not-a-hash"))
        assertFalse(TextureStore.delete(tmp, "../../something"))
    }

    @Test
    fun `a texture face is marked local-only`() {
        val plain = DialParams(engine = Engine.KNOTWORK)
        val textured = DialParams(engine = Engine.TEXTURE, texture = "a".repeat(40))
        assertFalse(plain.isLocalOnly)
        // The catalog is parametric-only, both to stay small and as the IP
        // shield. A face carrying an imported image cannot be published.
        assertTrue(textured.isLocalOnly)
        assertFalse(DialParams(engine = Engine.TEXTURE).isLocalOnly) { "no image means nothing to withhold" }
    }

    @Test
    fun `the texture id survives a face round trip`(@TempDir tmp: File) {
        val id = TextureStore.save(tmp, samplePng()).id
        val p = DialParams(engine = Engine.TEXTURE, texture = id)
        val saved = FaceStore.save(tmp, "Photo Dial", p)
        assertEquals(id, FaceStore.load(tmp, saved.slug)!!.params.texture)
    }

    @Test
    fun `quality note tells the truth about small images`(@TempDir tmp: File) {
        val small = TextureStore.save(tmp, samplePng(200, 200))
        assertTrue(TextureStore.qualityNote(small).contains("below 456")) {
            "a 200px image must be called out as too small for a 456px dial"
        }
        val big = TextureStore.save(tmp, samplePng(1000, 1000))
        assertTrue(TextureStore.qualityNote(big).contains("plenty"))
    }
}
