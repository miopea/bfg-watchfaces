package com.bfg.watchfaces.generator

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A face that would disclose a cycle cannot be shared.
 *
 * No health VALUE is ever in a face -- the JSON carries a provider's component
 * name, not a reading -- which is exactly why this is easy to get wrong. The
 * component name IS the disclosure: a published face naming the cycle provider
 * tells everyone who downloads it that its author tracks a menstrual cycle.
 *
 * `CatalogService` refuses anything `isLocalOnly`, so these tests are about the
 * one seam every publish path already goes through.
 */
class CyclePrivacyTest {

    private fun withProvider(component: String) =
        DialParams()
            .withSlot(SlotPosition.LEFT, ComplicationSource.STEP_COUNT)
            .copy(providers = mapOf(SlotPosition.LEFT to component))

    @Test
    fun `a face naming the cycle provider cannot be shared`() {
        assertTrue(withProvider("com.bfg.watchfaces/com.bfg.watchfaces.wear.CycleDayService").isLocalOnly) {
            "a cycle face reached the shareable path; the component name discloses the author tracks a cycle"
        }
    }

    /**
     * Matched on the CLASS, so a hand-edited face under another package is
     * caught too. A stored face is just JSON somebody can type.
     */
    @Test
    fun `the match is on the class, not the package`() {
        assertTrue(withProvider("com.someone.else/com.someone.else.CycleDayService").isLocalOnly)
        assertTrue(withProvider("a.b/.CycleDayService").isLocalOnly)
    }

    /**
     * And ordinary providers stay shareable, or this rule would quietly break
     * the catalog rather than protect anybody.
     */
    @Test
    fun `an ordinary provider is still shareable`() {
        assertFalse(withProvider("com.fitbit.app/com.fitbit.StepsProvider").isLocalOnly)
        assertFalse(DialParams().isLocalOnly)
    }

    /**
     * The note is deliberately NOT private.
     *
     * It is whatever she typed, it is on the face because she put it there, and
     * the provider's name reveals nothing she did not choose to write. Pinned
     * so that "make everything private to be safe" does not quietly remove a
     * feature from the catalog.
     */
    @Test
    fun `the phone note provider stays shareable`() {
        assertFalse(withProvider("com.bfg.watchfaces/com.bfg.watchfaces.wear.PhoneNoteService").isLocalOnly)
    }

    /** The texture rule still works; this changed shape and must not have changed meaning. */
    @Test
    fun `a local texture is still local only`() {
        val photo = DialParams(engine = Engine.TEXTURE, texture = "sha1-of-her-photo")
        assertTrue(photo.isLocalOnly)
        assertFalse(DialParams(engine = Engine.TEXTURE, texture = "").isLocalOnly)
    }
}
