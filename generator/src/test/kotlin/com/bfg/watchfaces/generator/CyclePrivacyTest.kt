package com.bfg.watchfaces.generator

import org.junit.jupiter.api.Assertions.assertEquals
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

    /**
     * Reported from a wrist on 2026-09-20: a Music slot switched to the cycle
     * complication kept showing music, and the picker showed both as selected.
     *
     * The cause was silent and structural. A SHORTCUT source emits a PartDraw
     * with a `<Launch>` and returns BEFORE any `<ComplicationSlot>`, so the
     * named provider was never read. Same for a DRAWN source. Naming a provider
     * on such a slot did nothing at all on the watch, with nothing to see.
     */
    @Test
    fun `a named provider beats a shortcut source, which would otherwise discard it`() {
        val p = DialParams()
            .withSlot(SlotPosition.LEFT, ComplicationSource.SHORTCUT_MUSIC)
            .let { it.copy(providers = it.providers + (SlotPosition.LEFT to "com.bfg.watchfaces/.CycleDayService")) }

        assertEquals(ComplicationSource.DATE, p.effectiveSlot(SlotPosition.LEFT)) {
            "a shortcut cannot host a provider, so the slot must fall back to a real source"
        }
        val xml = WffEmitter.emit(p)
        assertTrue(xml.contains("""primaryProvider="com.bfg.watchfaces/.CycleDayService"""")) {
            "the chosen provider never reached the face"
        }
        // And the shortcut's Launch must be gone from that slot, or the face
        // would both open music and show a complication.
        assertTrue(!xml.contains("<Launch target=\"MUSIC_PLAYER\"")) {
            "the slot still launches music as well as naming a provider"
        }
    }

    /** The same hole existed for a DRAWN source, which also returns early. */
    @Test
    fun `a named provider beats a drawn source too`() {
        val p = DialParams()
            .withSlot(SlotPosition.LEFT, ComplicationSource.WEATHER_TEMPERATURE)
            .let { it.copy(providers = it.providers + (SlotPosition.LEFT to "com.x/.CycleDayService")) }
        assertEquals(ComplicationSource.DATE, p.effectiveSlot(SlotPosition.LEFT))
        assertTrue(WffEmitter.emit(p).contains("""primaryProvider="com.x/.CycleDayService""""))
    }

    /** An ordinary source with a provider is left exactly alone. */
    @Test
    fun `a provider on a normal slot does not change its source`() {
        val p = DialParams()
            .withSlot(SlotPosition.LEFT, ComplicationSource.STEP_COUNT)
            .let { it.copy(providers = it.providers + (SlotPosition.LEFT to "com.fitbit/.Steps")) }
        assertEquals(ComplicationSource.STEP_COUNT, p.effectiveSlot(SlotPosition.LEFT))
    }

    /**
     * Seen on a wrist, 2026-09-20: a Battery slot pointed at the cycle
     * complication rendered "Day 18%".
     *
     * WATCH_BATTERY's format is "%s%%" because the battery provider sends a
     * bare "78" with no per cent sign. That decoration belongs to ITS value.
     * A named provider supplies its own complete text, and wrapping somebody
     * else's format around it produces exactly this.
     */
    @Test
    fun `a named provider's value is not decorated by the fallback source`() {
        val p = DialParams()
            .withSlot(SlotPosition.BOTTOM, ComplicationSource.WATCH_BATTERY)
            .let { it.copy(providers = it.providers + (SlotPosition.BOTTOM to "com.bfg.watchfaces/.CycleDayService")) }
        val xml = WffEmitter.emit(p)
        assertTrue(!xml.contains("<![CDATA[%s%%]]>")) {
            "the battery's per-cent sign was wrapped around another provider's text"
        }
        assertTrue(xml.contains("<![CDATA[%s]]>")) { "expected a plain template for a named provider" }
    }

    /** And an ordinary battery slot KEEPS its per cent sign. */
    @Test
    fun `a battery slot with no provider still gets its per cent sign`() {
        val xml = WffEmitter.emit(DialParams().withSlot(SlotPosition.BOTTOM, ComplicationSource.WATCH_BATTERY))
        assertTrue(xml.contains("<![CDATA[%s%%]]>")) {
            "the battery lost the per cent sign it exists to add"
        }
    }

    /**
     * The glyph follows the same rule: a battery symbol over a cycle day is
     * the same mistake as a per cent sign after it.
     */
    @Test
    fun `a named provider draws its own icon, not the fallback source's`() {
        val p = DialParams()
            .withSlot(SlotPosition.BOTTOM, ComplicationSource.WATCH_BATTERY)
            .copy(iconSlots = SlotPosition.entries.toSet())
            .let { it.copy(providers = it.providers + (SlotPosition.BOTTOM to "com.x/.CycleDayService")) }
        val xml = WffEmitter.emit(p)
        assertTrue(xml.contains("[COMPLICATION.MONOCHROMATIC_IMAGE]")) {
            "expected the provider's own icon for a slot it fills"
        }
    }
}
