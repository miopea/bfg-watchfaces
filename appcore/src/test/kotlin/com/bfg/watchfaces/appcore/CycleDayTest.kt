package com.bfg.watchfaces.appcore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate

class CycleDayTest {

    private val jan1 = LocalDate.of(2026, 1, 1)

    /**
     * The off-by-one that would be invisible to us and obvious to her.
     *
     * "Day 1" is the first day of the period, not the day after it. Everyone
     * who uses the phrase means it that way, and nothing in a test suite would
     * catch us meaning the other thing.
     */
    @Test
    fun `the first day of the period is day one`() {
        assertEquals(1, CycleDay.dayNumber(jan1, jan1))
        assertEquals(2, CycleDay.dayNumber(jan1, jan1.plusDays(1)))
        assertEquals(14, CycleDay.dayNumber(jan1, jan1.plusDays(13)))
    }

    /**
     * A future start is a typo or two clocks disagreeing, not a cycle.
     *
     * The phone and the watch can be in different timezones, and an instant
     * near midnight can land on different dates on each. "Day -3" on a wrist is
     * worse than a blank, so this is null rather than a negative number.
     */
    @Test
    fun `a start date in the future shows nothing`() {
        assertNull(CycleDay.dayNumber(jan1, jan1.minusDays(1)))
        assertNull(CycleDay.dayNumber(jan1, jan1.minusDays(400)))
    }

    @Test
    fun `no start date shows nothing`() {
        assertNull(CycleDay.dayNumber(null, jan1))
    }

    /**
     * A big number is shown on purpose.
     *
     * Ninety days means her tracking or her sync has lapsed, and that is worth
     * knowing. A slot she deliberately chose going quietly blank is the failure
     * this repo keeps paying for.
     */
    @Test
    fun `a long gap still shows a number rather than going blank`() {
        assertEquals(91, CycleDay.dayNumber(jan1, jan1.plusDays(90)))
        assertEquals(366, CycleDay.dayNumber(jan1, jan1.plusDays(365)))
    }

    /** Across a month and a year boundary, because day arithmetic loves those. */
    @Test
    fun `counting crosses months and years`() {
        assertEquals(32, CycleDay.dayNumber(LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 31)))
        // 2028 is a leap year: Feb has 29 days, and the count must include it.
        assertEquals(30, CycleDay.dayNumber(LocalDate.of(2028, 2, 1), LocalDate.of(2028, 3, 1)))
    }

    @Test
    fun `the label is the number, or the same em dash every other empty slot shows`() {
        assertEquals("14", CycleDay.label(jan1, jan1.plusDays(13)))
        assertEquals(CycleDay.EMPTY_PLACEHOLDER, CycleDay.label(null, jan1))
        assertEquals("—", CycleDay.EMPTY_PLACEHOLDER)
    }

    @Test
    fun `a date survives being stored and read back`(@TempDir dir: File) {
        CycleDay.save(dir, jan1)
        assertEquals(jan1, CycleDay.load(dir))
        CycleDay.save(dir, null)
        assertNull(CycleDay.load(dir))
    }

    /**
     * A half-written or poked-at file reads as "no date", never as a crash.
     *
     * This service runs on the watch behind the watch face. An exception here
     * is not a blank slot, it is a complication provider falling over, and
     * [PhoneNote] learned the same lesson the same way.
     */
    @Test
    fun `a corrupt stored date reads as nothing`(@TempDir dir: File) {
        File(dir, "cycle-start.txt").writeText("not-a-date")
        assertNull(CycleDay.load(dir))
        File(dir, "cycle-start.txt").writeText("")
        assertNull(CycleDay.load(dir))
        File(dir, "cycle-start.txt").writeText("2026-13-45")
        assertNull(CycleDay.load(dir))
    }

    @Test
    fun `parsing tolerates whitespace but not nonsense`() {
        assertEquals(jan1, CycleDay.parse("2026-01-01"))
        assertEquals(jan1, CycleDay.parse("  2026-01-01\n"))
        assertNull(CycleDay.parse(null))
        assertNull(CycleDay.parse(""))
        assertNull(CycleDay.parse("14"))
    }

    /**
     * The label carries NO letters, on any day, ever.
     *
     * Decision `01a0ba48` said a plain unlabelled number and gave the reason:
     * "Day 18" on a wrist tells anyone glancing at it what the slot is, and
     * "18" beside a step count tells them nothing. The rule lived in a spec,
     * nothing executed it, and "Day 18" shipped to a real wrist -- where the
     * person whose data it is noticed, which is the worst way to find out.
     *
     * So this sweeps rather than checking one day. A word reintroduced on the
     * edge of a range -- the first day, a three-digit lapse -- is exactly the
     * shape of bug this repo has already paid for once, in the ambient Variant
     * that only appeared on dark ink.
     */
    @Test
    fun `no label anywhere in the range carries a letter`() {
        val start = LocalDate.of(2026, 1, 1)
        for (offset in 0..400) {
            val label = CycleDay.label(start, start.plusDays(offset.toLong()))
            assertTrue(label.none { it.isLetter() }) {
                "day ${offset + 1} rendered as \"$label\"; the dial number must be bare"
            }
        }
        // The empty state is a dash, not the word "none".
        assertTrue(CycleDay.EMPTY_PLACEHOLDER.none { it.isLetter() })
        // And the PICKER preview is the same shape, so what she sees while
        // choosing is what she gets after choosing.
        assertTrue(CycleDay.PREVIEW_LABEL.none { it.isLetter() })
    }
}
