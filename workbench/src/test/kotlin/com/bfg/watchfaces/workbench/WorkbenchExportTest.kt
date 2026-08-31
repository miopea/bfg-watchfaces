package com.bfg.watchfaces.workbench

import com.bfg.watchfaces.appcore.FaceCodec
import com.bfg.watchfaces.appcore.FaceLibrary
import com.bfg.watchfaces.generator.DialParams
import com.bfg.watchfaces.generator.Engine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * What is left of the old FaceStoreTest after the store itself moved to
 * :appcore: the two things that are genuinely the workbench's, not the
 * library's -- exporting a named face, and the local texture store.
 */
class WorkbenchExportTest {

    private val sample = DialParams(
        engine = Engine.KNOTWORK, scale = 26.0, depth = 3.0, freq = 7,
        dialColor = "#2B2E33", inkColor = "#FCF9F1", lens = false, lensAmount = 12.5
    )

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
        val saved = FaceLibrary.save(tmp, "Photo Dial", p)
        assertEquals(id, FaceLibrary.load(tmp, saved.slug)!!.params.texture)
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

