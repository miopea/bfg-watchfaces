package com.bfg.watchfaces.appcore

import com.bfg.watchfaces.generator.ComplicationSource
import com.bfg.watchfaces.generator.DialParams
import com.bfg.watchfaces.generator.Engine
import com.bfg.watchfaces.generator.Layout
import com.bfg.watchfaces.generator.SlotPosition
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A reset spends one of a finite number of `setWatchFaceAsActive` calls, so the
 * cases that must NOT trigger one matter as much as the ones that must.
 */
class ComplicationChangeTest {

    private val base = DialParams(
        complications = listOf(
            ComplicationSource.DAY_AND_DATE, ComplicationSource.STEP_COUNT,
            ComplicationSource.HEART_RATE, ComplicationSource.SUNRISE_SUNSET,
            ComplicationSource.WATCH_BATTERY
        )
    )

    @Test
    fun `changing a complication needs a reset`() {
        assertTrue(ComplicationChange.needsReset(
            base, base.withSlot(SlotPosition.RIGHT, ComplicationSource.NEXT_EVENT)))
    }

    @Test
    fun `turning a slot off needs a reset`() {
        assertTrue(ComplicationChange.needsReset(
            base, base.withSlot(SlotPosition.BOTTOM, ComplicationSource.NONE)))
    }

    @Test
    fun `naming a different provider app needs a reset`() {
        assertTrue(ComplicationChange.needsReset(
            base, base.copy(providers = mapOf(SlotPosition.RIGHT to "com.example.weather/.P"))))
    }

    @Test
    fun `the first send from this phone never resets`() {
        // A fresh install has nothing assigned, so there is nothing to reset,
        // and spending the activation allowance on it would be pure waste.
        assertFalse(ComplicationChange.needsReset(null, base))
    }

    @Test
    fun `re-sending the same face does not reset`() {
        assertFalse(ComplicationChange.needsReset(base, base.copy()))
    }

    @Test
    fun `appearance changes alone do not reset`() {
        // These change the face without changing which provider fills which
        // slot. Resetting for them would spend an activation call on nothing
        // and throw away complications the wearer chose on the watch.
        val restyled = base.copy(
            engine = Engine.ROSETTE,
            dialColor = "#123456",
            inkColor = "#FEDCBA",
            showSeconds = true,
            iconSlots = emptySet(),
            layout = Layout(complicationSize = 31, timeSize = 96)
        )
        assertFalse(ComplicationChange.needsReset(base, restyled))
    }
}
