package com.bfg.watchfaces.appcore

import com.bfg.watchfaces.generator.ComplicationSource
import com.bfg.watchfaces.generator.DialParams
import com.bfg.watchfaces.generator.SlotPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The rule behind the "isn't on your watch" note, without Android in the way.
 *
 * The note exists because a face naming an app the watch lacks still RENDERS —
 * the provider falls back and a shortcut does nothing — so it looks different
 * from its preview with nothing saying why. Every expensive bug in this project
 * has been a difference nobody could see.
 */
class MissingAppsRuleTest {

    private val face = DialParams(
        complications = listOf(
            ComplicationSource.DAY_AND_DATE, ComplicationSource.STEP_COUNT,
            ComplicationSource.HEART_RATE, ComplicationSource.SHORTCUT_APP,
            ComplicationSource.WATCH_BATTERY
        ),
        providers = mapOf(SlotPosition.LEFT to "com.example.fit/.Provider"),
        launchers = mapOf(SlotPosition.RIGHT to "com.example.player/.Main")
    )

    /** What the screen does: everything named, minus everything known. */
    private fun missing(known: Set<String>): List<String> =
        (face.providers.values + face.launchers.values).distinct()
            .filter { it !in known }
            .map { it.substringBefore('/') }

    @Test
    fun `an app the watch never reported is named`() {
        val found = missing(setOf("com.example.fit/.Provider"))
        assertEquals(listOf("com.example.player"), found)
    }

    @Test
    fun `nothing is flagged when the watch has both`() {
        assertTrue(missing(setOf("com.example.fit/.Provider", "com.example.player/.Main")).isEmpty())
    }

    @Test
    fun `a face naming no apps is never flagged`() {
        val plain = DialParams()
        assertTrue((plain.providers.values + plain.launchers.values).isEmpty())
    }
}
