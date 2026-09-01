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

    /**
     * THE REAL FUNCTION, not a copy of it.
     *
     * This test used to declare its own private `missing()` with the same
     * shape and assert on that, because the real one lived in `:mobile` where
     * `:appcore` cannot reach it. So the rule that decides what warning a
     * person sees had no test touching it, while a test named after it passed
     * — two implementations agreeing until they don't, with the drift hidden
     * inside the assertion.
     */
    private fun missing(known: Set<String>): List<String> =
        MissingAppsRule.namesOf(face, known)

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
