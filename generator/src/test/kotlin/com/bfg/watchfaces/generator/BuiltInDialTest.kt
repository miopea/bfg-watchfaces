package com.bfg.watchfaces.generator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The rule that makes a picture-dial shareable, and the one that keeps a photo
 * from ever being.
 */
class BuiltInDialTest {

    /**
     * A built-in id can never be mistaken for an imported one, by SHAPE.
     *
     * `TextureStore.isId` accepts exactly 40 lowercase hex characters — a SHA-1
     * of the imported bytes. If a built-in id could satisfy that, a crafted
     * face could name a photo and be published, or a photo hash could collide
     * with a mascot. Neither check has to know about the other as long as the
     * shapes cannot overlap.
     */
    @Test
    fun `no built-in id could pass for the hash of an imported image`() {
        for (dial in BuiltInDial.entries) {
            val looksLikeAHash =
                dial.id.length == 40 && dial.id.all { it in '0'..'9' || it in 'a'..'f' }
            assertFalse(looksLikeAHash) {
                "${dial.id} has the shape of a TextureStore id and could be confused for one"
            }
        }
    }

    /** A face drawing a mascot is publishable. That is the entire point. */
    @Test
    fun `a face using a built-in dial is not local-only`() {
        for (dial in BuiltInDial.entries) {
            val p = DialParams(engine = Engine.TEXTURE, texture = dial.id)
            assertFalse(p.isLocalOnly) { "${dial.id} was treated as somebody's own photo" }
        }
    }

    /** A face drawing an imported photo is still refused, which is the shield. */
    @Test
    fun `a face using an imported image is still local-only`() {
        val p = DialParams(engine = Engine.TEXTURE, texture = "a".repeat(40))
        assertTrue(p.isLocalOnly) { "an imported photo became publishable" }
    }

    /** No texture at all is not a picture face and never was local-only. */
    @Test
    fun `an ordinary face is unaffected`() {
        assertFalse(DialParams(engine = Engine.ROSETTE).isLocalOnly)
        assertFalse(DialParams(engine = Engine.TEXTURE, texture = "").isLocalOnly)
    }

    /** The bytes must actually be there, or the dial renders as nothing. */
    @Test
    fun `every built-in dial has an image behind it`() {
        for (dial in BuiltInDial.entries) {
            val bytes = dial.bytes()
            assertTrue(bytes != null && bytes.size > 1000) {
                "${dial.id} resolves to no image; the dial would draw blank"
            }
            // PNG magic, so a truncated or wrong-format file fails here rather
            // than silently rendering nothing on a wrist.
            assertEquals(0x89.toByte(), bytes!![0])
            assertEquals('P'.code.toByte(), bytes[1])
        }
    }

    /**
     * The ids the contract publishes are the ids that exist.
     *
     * The Worker accepts a texture only if the contract lists it, so a mismatch
     * here means every mascot face is refused on submission. That the COMMITTED
     * contract file matches what the generator would write is `ContractFileTest`
     * in :workbench, which is where the file is reachable; this guards the list
     * it is built from.
     */
    @Test
    fun `the published id list is exactly the dials that exist`() {
        assertEquals(BuiltInDial.entries.map { it.id }, BuiltInDial.IDS)
        assertEquals(BuiltInDial.IDS.size, BuiltInDial.IDS.toSet().size) { "duplicate id" }
        assertTrue(BuiltInDial.IDS.isNotEmpty())
    }

    /** A label is what somebody picks from; an id is not one. */
    @Test
    fun `every dial is named for a person rather than by its id`() {
        for (dial in BuiltInDial.entries) {
            assertTrue(dial.label.isNotBlank())
            assertFalse(dial.label.contains('-')) { "${dial.label} reads as an identifier" }
        }
    }
}
