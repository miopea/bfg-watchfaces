package com.bfg.watchfaces.generator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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

    /**
     * Why a preview fixed at 10:10 made the hour format control look broken.
     *
     * 10:10 is the watch-advertising time, and it is the one time of day where
     * 12- and 24-hour form produce the SAME STRING. So the phone's preview
     * rendered identically whichever format was chosen, and the control
     * appeared to do nothing. Reported from the app.
     *
     * The fix was to draw the real time on screen. This pins the reason, so
     * nobody restores a fixed showroom time to the editing preview without
     * meeting the bug again.
     */
    @Test
    fun `at ten past ten the two hour formats are indistinguishable`() {
        val twelve = DialParams(hourFormat = HourFormat.TWELVE)
        val twentyFour = DialParams(hourFormat = HourFormat.TWENTY_FOUR)

        assertEquals(ClockText.of(twelve, 10, 10), ClockText.of(twentyFour, 10, 10)) {
            "10:10 is expected to read the same both ways; that is the whole trap"
        }

        // Any afternoon time tells them apart, which is what a live preview gets.
        val pmTwelve = ClockText.of(twelve, 14, 35)
        val pmTwentyFour = ClockText.of(twentyFour, 14, 35)
        assertTrue(pmTwelve != pmTwentyFour) {
            "14:35 rendered the same in both formats: $pmTwelve"
        }
        assertTrue(pmTwentyFour.startsWith("14")) { "24-hour form lost the 24-hour hour" }
        assertTrue(pmTwelve.startsWith("2")) { "12-hour form did not fold the afternoon" }
    }

    /**
     * Exactly three hours in the day hide the difference, and 10 is one.
     *
     * Measured rather than assumed -- the first guess here was that every
     * morning hour read alike, which is wrong: the 12-hour form drops the
     * leading zero, so 9 gives "9:30" against "09:30" and they differ. Only
     * 10, 11 and 12 produce the same string both ways.
     *
     * That is what made the old preview so unlucky. A fixed 10:10 sat in a
     * three-hour window out of twenty-four -- an eighth of the day -- where
     * this control cannot show itself.
     */
    @Test
    fun `exactly three hours read the same in both formats`() {
        val twelve = DialParams(hourFormat = HourFormat.TWELVE)
        val twentyFour = DialParams(hourFormat = HourFormat.TWENTY_FOUR)
        val ambiguous = (0..23).filter {
            ClockText.of(twelve, it, 30) == ClockText.of(twentyFour, it, 30)
        }
        assertEquals(listOf(10, 11, 12), ambiguous)
    }
}
