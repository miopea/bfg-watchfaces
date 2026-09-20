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
        val f = CycleFacts.from(
            listOf(
                d(1, 1) to d(1, 5),    // -> 212 days, a logging gap
                d(8, 1) to d(8, 5),    // ->  28 days
                d(8, 29) to d(9, 2)
            )
        )!!
        assertEquals(28, f.averageCycleDays)
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
}
