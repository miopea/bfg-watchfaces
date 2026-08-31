package com.bfg.watchfaces.generator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The clock a preview draws has to be the clock the watch will draw. */
class ClockTextTest {

    @Test
    fun `a 12-hour clock drops the leading zero`() {
        val p = DialParams(hourFormat = HourFormat.TWELVE)
        assertEquals("6:10", ClockText.of(p, 6, 10))
        assertEquals("9:05", ClockText.of(p, 21, 5))
        // Midnight and noon are 12, not 0.
        assertEquals("12:00", ClockText.of(p, 0, 0))
        assertEquals("12:30", ClockText.of(p, 12, 30))
    }

    @Test
    fun `a 24-hour clock keeps it`() {
        val p = DialParams(hourFormat = HourFormat.TWENTY_FOUR)
        assertEquals("06:10", ClockText.of(p, 6, 10))
        assertEquals("21:05", ClockText.of(p, 21, 5))
        assertEquals("00:00", ClockText.of(p, 0, 0))
    }

    @Test
    fun `matching the watch follows the device`() {
        val p = DialParams(hourFormat = HourFormat.DEVICE)
        assertEquals("06:10", ClockText.of(p, 6, 10, deviceIs24Hour = true))
        assertEquals("6:10", ClockText.of(p, 6, 10, deviceIs24Hour = false))
    }

    @Test
    fun `the emitted pattern matches the choice`() {
        // "hh" is a leading zero and "h" is not; the watch formats its own
        // clock from these, so a preview agreeing is not enough.
        assertEquals("h:mm", HourFormat.TWELVE.pattern)
        assertEquals("12", HourFormat.TWELVE.wff)
        assertEquals("hh:mm", HourFormat.TWENTY_FOUR.pattern)
        assertEquals("SYNC_TO_DEVICE", HourFormat.DEVICE.wff)
    }
}
