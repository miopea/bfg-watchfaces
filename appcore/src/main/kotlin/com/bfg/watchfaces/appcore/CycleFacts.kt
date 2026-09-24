package com.bfg.watchfaces.appcore

import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * What her own logged periods say, beyond the day count.
 *
 * ## All of this is description, none of it is prediction
 *
 * Every value here is arithmetic on dates she entered: how long her last period
 * ran, how far apart her recent periods started, whether today falls inside the
 * last one. Nothing here says when the next period will be, or when she is
 * fertile. That boundary is a Play policy line rather than a preference — see
 * `docs/specs/cycle-complication.md` — and it is easy to cross by accident,
 * because "average cycle 29 days" is one careless sentence away from "next
 * period in 11 days".
 *
 * ## Why it costs nothing extra
 *
 * Every field is derived from `MenstruationPeriodRecord`, which the app already
 * reads under the one permission it already holds. No new Health Connect
 * permission, no change to the Play declaration, no second trip through review.
 * That is the whole reason this set of facts and not another.
 *
 * ## Why it is separate from [CycleDay]
 *
 * [CycleDay] answers the one question the complication asks, from one date, and
 * is proven on hardware. This is the richer set the carousel card can afford
 * screen for. Keeping them apart means the dial cannot break when the card
 * gains a field.
 */
data class CycleFacts(
    /** The most recent period's first day. The same date [CycleDay] counts from. */
    val start: LocalDate,
    /** Its last day, when she logged one. Null while a period is still open. */
    val periodEnd: LocalDate? = null,
    /**
     * The average gap between recent period starts, in days.
     *
     * Null until there are at least two periods to measure between. A single
     * logged period says nothing about a cycle length, and inventing a
     * population average would be the app making something up about her.
     */
    val averageCycleDays: Int? = null
) {

    /** How far into her cycle today is. Day 1 is the first day of the period. */
    fun dayOfCycle(today: LocalDate): Int? = CycleDay.dayNumber(start, today)

    /**
     * Which day OF THE PERIOD today is, or null when she is not bleeding.
     *
     * An open period — a start with no end — counts as ongoing, because that is
     * what an unfinished record means. A period that ended counts only up to
     * its last day.
     */
    fun dayOfPeriod(today: LocalDate): Int? {
        if (today.isBefore(start)) return null
        if (periodEnd != null && today.isAfter(periodEnd)) return null
        return ChronoUnit.DAYS.between(start, today).toInt() + 1
    }

    /** How many days the last period ran, once it has an end. Inclusive of both days. */
    val periodLengthDays: Int?
        get() = periodEnd?.let { ChronoUnit.DAYS.between(start, it).toInt() + 1 }

    /**
     * The same length, but only once the period has actually FINISHED.
     *
     * "Last period 2 days" while she is on day 2 of it is not a fact about a
     * last period, it is a running total wearing the wrong label -- and it
     * shrinks the moment she logs another day, which is the opposite of what a
     * finished number does.
     *
     * A record with no end is still open by definition. A record whose end is
     * today may still gain a day before midnight, so it does not count as
     * finished either.
     */
    fun finishedPeriodLengthDays(today: LocalDate): Int? =
        periodLengthDays?.takeIf { periodEnd != null && today.isAfter(periodEnd) }

    /**
     * The wire and disk form.
     *
     * Pipe-separated rather than JSON: three fields, no nesting, and it is read
     * on a watch where every dependency is weight. Empty fields stay empty
     * rather than being dropped, so the positions never shift.
     */
    fun encode(): String = listOf(
        start.toString(),
        periodEnd?.toString() ?: "",
        averageCycleDays?.toString() ?: ""
    ).joinToString("|")

    companion object {

        private fun file(root: File): File = File(root, "cycle-facts.txt")

        /**
         * Read the wire form back, or null when there is nothing usable.
         *
         * A missing or unparseable START is fatal — everything else is derived
         * from it. A missing end or average is not: those are genuinely absent
         * for a first period or an open one, and the card simply shows less.
         */
        fun decode(text: String?): CycleFacts? {
            val parts = text?.trim().orEmpty().split("|")
            val start = CycleDay.parse(parts.getOrNull(0)) ?: return null
            return CycleFacts(
                start = start,
                periodEnd = CycleDay.parse(parts.getOrNull(1)),
                averageCycleDays = parts.getOrNull(2)?.trim()?.toIntOrNull()
            )
        }

        fun save(root: File, facts: CycleFacts?) {
            runCatching {
                val f = file(root)
                if (facts == null) f.delete() else f.writeText(facts.encode())
            }
        }

        /** Corrupt or absent reads as nothing, never as a crash. Same rule as [CycleDay]. */
        fun load(root: File): CycleFacts? = runCatching {
            val f = file(root)
            if (!f.isFile) null else decode(f.readText())
        }.getOrNull()

        /**
         * Build from the period starts and ends Health Connect returned.
         *
         * [periods] is every period in the window, in any order. The most
         * recent START wins, because that is the cycle she is in.
         *
         * The average is over the GAPS between consecutive starts, not over
         * period lengths, and it deliberately ignores absurd gaps: a record
         * from a year ago next to one from last month would otherwise drag the
         * average into nonsense. Anything outside 15..60 days is not a cycle
         * length, it is a gap in her logging.
         */
        fun from(periods: List<Pair<LocalDate, LocalDate?>>): CycleFacts? {
            if (periods.isEmpty()) return null
            val sorted = periods.sortedBy { it.first }
            val latest = sorted.last()

            val gaps = sorted.map { it.first }
                .zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b).toInt() }
                .filter { it in PLAUSIBLE_CYCLE }

            return CycleFacts(
                start = latest.first,
                periodEnd = latest.second,
                averageCycleDays =
                    if (gaps.size < MIN_GAPS_FOR_AVERAGE) null else gaps.average().toInt()
            )
        }

        /**
         * What counts as a cycle length rather than a logging gap.
         *
         * Deliberately wide. The job is to throw out the six-month hole, not to
         * judge whether somebody's 21-day cycle is normal — that is a medical
         * opinion and this app does not have one.
         */
        private val PLAUSIBLE_CYCLE = 15..60

        /**
         * How many gaps it takes before a mean is worth the word "average".
         *
         * Two, which means three logged periods. One gap is a single
         * observation, and calling it an average tells her the app knows
         * something about her cycle that it does not.
         *
         * Raised from one on 2026-09-24, after a watch showed "Average cycle
         * 27 days" to someone who had just started logging. The wearer's own
         * reading of it: it "doesn't make any sense". A number she cannot
         * account for is worse than a line that is not there, because she has
         * no way to tell a real one from an artefact.
         */
        private const val MIN_GAPS_FOR_AVERAGE = 2

        /**
         * A count of days in words, singular when it is one.
         *
         * "Last period 1 days" reached a real wrist on 2026-09-24. Both shipped
         * apps had written `"$it days"` inline, in two files, so there was
         * nowhere for the rule to live and nothing to fail when it was wrong.
         *
         * The COUNT is shared and the LABEL is not: `:appcore` holds the words
         * both apps must agree on, and which sentence they sit in stays with
         * the UI that draws it -- the tile capitalises each line, the phone
         * joins them with a separator and capitalises the first. See the
         * `ControlInventory` split in CLAUDE.md for the same division.
         */
        fun dayCount(days: Int): String = if (days == 1) "1 day" else "$days days"
    }
}
