package com.bfg.watchfaces.appcore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The images a photo dial refers to.
 *
 * Pure JVM on purpose: every rule here would otherwise only be exercised on a
 * phone, and the one that matters most — an id becoming a file path — is a
 * security property rather than a look.
 */
class TextureStoreTest {

    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)

    @Test
    fun `an image round trips`(@TempDir dir: File) {
        val id = TextureStore.save(dir, png)
        assertTrue(TextureStore.has(dir, id))
        assertEquals(png.toList(), TextureStore.load(dir, id)!!.toList())
    }

    /**
     * Content-addressed: the same photo twice is one file.
     *
     * Somebody picking the same picture for a second face should not double the
     * storage, and two faces sharing an image is the normal case rather than an
     * edge one.
     */
    @Test
    fun `importing the same image twice stores it once`(@TempDir dir: File) {
        val a = TextureStore.save(dir, png)
        val b = TextureStore.save(dir, png)
        assertEquals(a, b)
        assertEquals(1, TextureStore.dir(dir).listFiles()!!.size)
    }

    @Test
    fun `different images get different ids`(@TempDir dir: File) {
        val a = TextureStore.save(dir, png)
        val b = TextureStore.save(dir, png + 9)
        assertTrue(a != b) { "two different images collided on $a" }
    }

    /**
     * THE ONE THAT IS NOT ABOUT LOOKS.
     *
     * A texture id becomes a file PATH, and it arrives from a stored face —
     * which, on the catalog side, is JSON somebody else wrote. Rejecting by
     * SHAPE rather than sanitising is the check that cannot be worked around:
     * there is no traversal that is also forty hex characters.
     */
    @Test
    fun `an id that is not forty hex characters can never become a path`(@TempDir dir: File) {
        val nasty = listOf(
            "../../../etc/passwd",
            "..",
            "/etc/passwd",
            "abc",
            "",
            "g".repeat(40),                 // right length, not hex
            "a".repeat(39),                 // one short
            "a".repeat(41),                 // one long
            "A".repeat(40),                 // uppercase is not our shape
            "a".repeat(20) + "/" + "b".repeat(19)
        )
        for (id in nasty) {
            assertFalse(TextureStore.isId(id)) { "$id was accepted as an id" }
            assertNull(TextureStore.file(dir, id)) { "$id resolved to a file" }
            assertNull(TextureStore.load(dir, id)) { "$id loaded something" }
            assertFalse(TextureStore.delete(dir, id)) { "$id deleted something" }
        }
    }

    @Test
    fun `a real id is accepted`(@TempDir dir: File) {
        val id = TextureStore.save(dir, png)
        assertTrue(TextureStore.isId(id)) { "$id is not recognised as an id" }
        assertEquals(40, id.length)
    }

    /** Unknown means null, not a crash: a face can outlive its image. */
    @Test
    fun `an unknown id reads as absent`(@TempDir dir: File) {
        assertNull(TextureStore.load(dir, "a".repeat(40)))
        assertFalse(TextureStore.has(dir, "a".repeat(40)))
    }

    @Test
    fun `deleting forgets the image`(@TempDir dir: File) {
        val id = TextureStore.save(dir, png)
        assertTrue(TextureStore.delete(dir, id))
        assertFalse(TextureStore.has(dir, id))
        assertFalse(TextureStore.delete(dir, id)) { "deleting twice reported success" }
    }

    /**
     * Absurd input is refused BEFORE anything decodes it.
     *
     * Decoding is the expensive and attackable step, and the encoded size is the
     * only thing measurable without doing it.
     */
    @Test
    fun `an empty or enormous image is refused`(@TempDir dir: File) {
        assertThrows(IllegalArgumentException::class.java) { TextureStore.save(dir, ByteArray(0)) }
        assertThrows(IllegalArgumentException::class.java) {
            TextureStore.save(dir, ByteArray(TextureStore.MAX_BYTES + 1))
        }
    }

    /**
     * THE GUARANTEE, on the path that would actually do the publishing.
     *
     * Hiding a Share button is a courtesy and it stops working the moment a new
     * screen forgets to hide one. This refuses the submission itself, so the
     * Data Safety claim that photos are not collected holds even then — and it
     * never reaches the network to find out.
     */
    @Test
    fun `submitting a photo face is refused before it reaches the network`() {
        var posted = false
        val transport = object : CatalogTransport {
            override fun get(url: String): CatalogTransport.Reply {
                posted = true
                return CatalogTransport.Reply(200, "{}")
            }
            override fun post(url: String, body: String, bearer: String?): CatalogTransport.Reply {
                posted = true
                return CatalogTransport.Reply(200, "{}")
            }
        }
        val service = CatalogService(transport)
        val photo = com.bfg.watchfaces.generator.DialParams(
            engine = com.bfg.watchfaces.generator.Engine.TEXTURE,
            texture = "b".repeat(40)
        )
        val result = service.submit("Bailey", "", photo, "token")
        assertTrue(result is CatalogService.Result.Failed) { "a photo face was accepted" }
        assertFalse(posted) { "the photo face reached the network before being refused" }
    }

    /**
     * A face carrying an image cannot be shared, and that is a code rule.
     *
     * The Data Safety declaration says photos are not collected. What makes that
     * TRUE rather than a promise is this predicate on the submit path — hiding a
     * Share button is only a courtesy.
     */
    @Test
    fun `a face with an image is local only`() {
        val plain = com.bfg.watchfaces.generator.DialParams()
        assertFalse(plain.isLocalOnly)

        val photo = com.bfg.watchfaces.generator.DialParams(
            engine = com.bfg.watchfaces.generator.Engine.TEXTURE,
            texture = "a".repeat(40)
        )
        assertTrue(photo.isLocalOnly) { "a photo face would be publishable" }

        // TEXTURE with no image is not carrying anybody's photograph.
        val empty = photo.copy(texture = "")
        assertFalse(empty.isLocalOnly)
    }
}
