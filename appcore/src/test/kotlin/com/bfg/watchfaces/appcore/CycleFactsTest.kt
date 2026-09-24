package com.bfg.watchfaces.appcore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate

class CycleFactsTest {

    private fun d(m: Int, day: Int) = LocalDate.of(2026, m, day)

    /** The most recent START is the cycle she is in, whatever order they arrive. */
    @Test
    fun `the latest period wins, in any order`() {
        val f = CycleFacts.from(
            listOf(
                d(7, 2) to d(7, 6),
                d(9, 3) to d(9, 7),
                d(8, 4) to d(8, 8)
            )
        )!!
        assertEquals(d(9, 3), f.start)
        assertEquals(d(9, 7), f.periodEnd)
    }

    @Test
    fun `period length counts both end days`() {
        // 3rd to 7th inclusive is five days, not four. Off by one here is the
        // kind of thing only the person living it would notice.
        assertEquals(5, CycleFacts(d(9, 3), d(9, 7)).periodLengthDays)
        assertNull(CycleFacts(d(9, 3), null).periodLengthDays)
    }

    /**
     * The average is over gaps between STARTS, which is what a cycle length is.
     * Averaging period lengths instead would be a different number entirely.
     */
    @Test
    fun `the average is the gap between starts`() {
        val f = CycleFacts.from(
            listOf(
                d(7, 1) to d(7, 5),   // ->  31 days
                d(8, 1) to d(8, 5),   // ->  28 days
                d(8, 29) to d(9, 2)
            )
        )!!
        assertEquals(29, f.averageCycleDays)   // (31 + 28) / 2, truncated
    }

    /**
     * A six-month hole in her logging is not a cycle length.
     *
     * Without this a single gap drags the average into nonsense and the card
     * states it as fact. Wide bounds on purpose: the job is to drop the hole,
     * not to opine on whether a 21-day cycle is normal.
     */
    @Test
    fun `an implausible gap is not treated as a cycle`() {
        // FOUR periods, because the average now needs two plausible gaps and
        // the whole point of this case is that the 212-day one does not count
        // as one of them. Were it counted, the mean would be near 88 rather
        // than 27, so this still fails loudly if the filter stops working.
        val f = CycleFacts.from(
            listOf(
                d(1, 1) to d(1, 5),    // -> 212 days, a logging gap
                d(8, 1) to d(8, 5),    // ->  28 days
                d(8, 29) to d(9, 2),   // ->  26 days
                d(9, 24) to d(9, 28)
            )
        )!!
        assertEquals(27, f.averageCycleDays)   // (28 + 26) / 2
    }

    /** One period says nothing about a cycle length, and we do not invent one. */
    @Test
    fun `a single period has no average`() {
        assertNull(CycleFacts.from(listOf(d(9, 3) to d(9, 7)))!!.averageCycleDays)
    }

    @Test
    fun `no periods means no facts`() {
        assertNull(CycleFacts.from(emptyList()))
    }

    /** Bleeding today, or not, and an OPEN period counts as ongoing. */
    @Test
    fun `day of period is only while she is bleeding`() {
        val ended = CycleFacts(d(9, 3), d(9, 7))
        assertEquals(1, ended.dayOfPeriod(d(9, 3)))
        assertEquals(5, ended.dayOfPeriod(d(9, 7)))
        assertNull(ended.dayOfPeriod(d(9, 8)))
        assertNull(ended.dayOfPeriod(d(9, 2)))

        // An unfinished record means the period has not ended.
        val open = CycleFacts(d(9, 3), null)
        assertEquals(6, open.dayOfPeriod(d(9, 8)))
    }

    @Test
    fun `day of cycle agrees with CycleDay`() {
        val f = CycleFacts(d(9, 3), d(9, 7))
        assertEquals(CycleDay.dayNumber(d(9, 3), d(9, 20)), f.dayOfCycle(d(9, 20)))
        assertEquals(18, f.dayOfCycle(d(9, 20)))
    }

    @Test
    fun `the wire form survives a round trip`() {
        val full = CycleFacts(d(9, 3), d(9, 7), 29)
        assertEquals(full, CycleFacts.decode(full.encode()))

        // The absent fields stay absent rather than becoming zero or today.
        val sparse = CycleFacts(d(9, 3), null, null)
        assertEquals(sparse, CycleFacts.decode(sparse.encode()))
    }

    /**
     * A missing START is fatal because everything derives from it. A missing
     * end or average is not: those are genuinely absent for a first or open
     * period, and the card just shows less.
     */
    @Test
    fun `a broken payload degrades rather than throwing`() {
        assertNull(CycleFacts.decode(null))
        assertNull(CycleFacts.decode(""))
        assertNull(CycleFacts.decode("|2026-09-07|29"))
        assertNull(CycleFacts.decode("not-a-date|x|y"))

        val partial = CycleFacts.decode("2026-09-03|rubbish|rubbish")!!
        assertEquals(d(9, 3), partial.start)
        assertNull(partial.periodEnd)
        assertNull(partial.averageCycleDays)
    }

    @Test
    fun `facts survive being stored and read back`(@TempDir dir: File) {
        val f = CycleFacts(d(9, 3), d(9, 7), 29)
        CycleFacts.save(dir, f)
        assertEquals(f, CycleFacts.load(dir))
        CycleFacts.save(dir, null)
        assertNull(CycleFacts.load(dir))
    }

    @Test
    fun `a corrupt stored file reads as nothing`(@TempDir dir: File) {
        File(dir, "cycle-facts.txt").writeText("garbage")
        assertNull(CycleFacts.load(dir))
    }

    /**
     * "Last period 1 days" reached a real wrist on 2026-09-24.
     *
     * Both shipped apps had written `"$it days"` inline, in two files, so the
     * rule had nowhere to live and nothing to fail when it was wrong. One
     * implementation, both callers -- the same reason SlotGeometry exists.
     */
    @Test
    fun `one day is singular and everything else is not`() {
        assertEquals("1 day", CycleFacts.dayCount(1))
        assertEquals("2 days", CycleFacts.dayCount(2))
        assertEquals("27 days", CycleFacts.dayCount(27))
        assertEquals("0 days", CycleFacts.dayCount(0))
    }

    /**
     * One gap is an observation, not an average.
     *
     * A watch told someone who had just started logging that her average cycle
     * was 27 days. She had nothing like the history that would support it, and
     * her reading was that the app was making things up -- which is the right
     * reading, and the reason the bar is now two gaps rather than one.
     */
    @Test
    fun `two logged periods are not enough for an average`() {
        val two = listOf(
            LocalDate.of(2026, 8, 26) to LocalDate.of(2026, 8, 30),
            LocalDate.of(2026, 9, 22) to LocalDate.of(2026, 9, 23)
        )
        assertNull(CycleFacts.from(two)?.averageCycleDays)
    }

    /** Three periods give two gaps, which is the first honest mean. */
    @Test
    fun `three logged periods do support an average`() {
        val three = listOf(
            LocalDate.of(2026, 7, 30) to LocalDate.of(2026, 8, 3),
            LocalDate.of(2026, 8, 26) to LocalDate.of(2026, 8, 30),
            LocalDate.of(2026, 9, 22) to LocalDate.of(2026, 9, 23)
        )
        // 27 then 27.
        assertEquals(27, CycleFacts.from(three)?.averageCycleDays)
    }

    /**
     * A period that is still running has no "last period" length.
     *
     * The number would shrink as she logged more days, which is the opposite
     * of what a finished total does.
     */
    @Test
    fun `an unfinished period reports no length`() {
        val start = LocalDate.of(2026, 9, 22)
        val open = CycleFacts(start = start, periodEnd = null)
        assertNull(open.finishedPeriodLengthDays(LocalDate.of(2026, 9, 24)))

        // Ends today: it can still gain a day before midnight.
        val endingToday = CycleFacts(start = start, periodEnd = LocalDate.of(2026, 9, 24))
        assertNull(endingToday.finishedPeriodLengthDays(LocalDate.of(2026, 9, 24)))
    }

    /** Once it is genuinely over, the length is a fact and is shown. */
    @Test
    fun `a finished period reports its length`() {
        val facts = CycleFacts(
            start = LocalDate.of(2026, 9, 22),
            periodEnd = LocalDate.of(2026, 9, 23)
        )
        assertEquals(2, facts.finishedPeriodLengthDays(LocalDate.of(2026, 9, 24)))
    }
}
