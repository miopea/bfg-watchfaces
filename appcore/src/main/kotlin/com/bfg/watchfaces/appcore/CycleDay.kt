package com.bfg.watchfaces.appcore

import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * How far into her cycle it is, as a number on a watch face.
 *
 * ## What this is and is not
 *
 * It is arithmetic on one date: how many days since the most recent period
 * started. It is **not** a prediction. It says nothing about a fertile window,
 * an ovulation day or a next period, and it must not grow into saying any of
 * those — see `docs/specs/cycle-complication.md`, where that boundary is a
 * Play policy line rather than a product preference. A day count is a fact
 * about a date she entered; "fertile" is an inference about a body.
 *
 * ## Why the arithmetic is here and not on either side
 *
 * The phone reads Health Connect and sends a DATE. The watch turns that date
 * into a number. If both sides could compute it they would eventually disagree
 * about the one thing that matters — which day it is — and this repo already
 * has [com.bfg.watchfaces.generator.SlotGeometry] as a monument to what happens
 * when the same sum is written twice. One implementation, both callers.
 *
 * ## The date is the payload, not the number
 *
 * A number goes stale at midnight and needs something awake to refresh it. A
 * date is correct forever: the watch re-derives the day whenever it is asked,
 * so nothing has to wake up, nothing has to be scheduled, and the value cannot
 * be wrong because the phone was in another room. That was settled against a
 * daily `WorkManager` push.
 */
object CycleDay {

    /** Where the watch keeps the start date it was last told. */
    private fun file(root: File): File = File(root, "cycle-start.txt")

    /**
     * The day number for [today], or null when there is nothing to show.
     *
     * **Day 1 is the first day of the period**, not the day after it. That is
     * what "day of your cycle" means to everyone who uses the word, and an
     * off-by-one here is the kind of error that is invisible to us and obvious
     * to her.
     *
     * Null when there is no start date, or when the start date is in the
     * FUTURE. A future start is not a cycle that has begun; it is a typo or a
     * clock disagreeing across two devices, and "Day -3" on a wrist is worse
     * than a blank.
     *
     * ## A large number is shown, deliberately
     *
     * There is no staleness ceiling. If she last logged a period ninety days
     * ago this says `Day 90`, which is strange-looking and TRUE, and it tells
     * her the thing she needs to know: the tracking has lapsed, or the sync
     * has. The alternative is a slot she deliberately chose quietly going
     * blank, which is the failure this repo keeps paying for — see the note on
     * hiding a slot in `docs/specs/cycle-complication.md`.
     */
    fun dayNumber(start: LocalDate?, today: LocalDate): Int? {
        if (start == null || start.isAfter(today)) return null
        return ChronoUnit.DAYS.between(start, today).toInt() + 1
    }

    /**
     * What the slot shows: `Day 14`, or the em dash when there is nothing.
     *
     * The em dash is what every other slot shows with no data, so a cycle slot
     * with nothing in it looks like an empty slot rather than like a broken
     * feature. The explanation belongs on the phone, where there is room to
     * give one.
     *
     * ## The number is BARE, and that is the point
     *
     * No "Day", no unit, no word of any kind -- decision `01a0ba48`, and the
     * reason is privacy rather than taste. A dial reading "Day 18" tells anyone
     * who glances at her wrist what the slot IS; a dial reading "18" beside a
     * step count and a temperature tells them nothing at all.
     *
     * It shipped as "Day 18" anyway, because the rule lived in a spec and
     * nothing executed it, and the person whose wrist it is noticed before any
     * of this did. `CycleDayTest` now fails on any label carrying a letter.
     *
     * The spoken description is the exception and is built in CycleDayService,
     * not here: a screen reader is not a glance over her shoulder, it is her
     * asking.
     */
    fun label(start: LocalDate?, today: LocalDate): String =
        dayNumber(start, today)?.toString() ?: EMPTY_PLACEHOLDER

    /** Shown when there is no start date, matching the other empty slots. */
    const val EMPTY_PLACEHOLDER = "—"

    /**
     * The day a PREVIEW pretends it is.
     *
     * Used by both dial previews and by the watch's own complication picker, so
     * what she sees while choosing is what she sees after choosing. A plausible
     * day rather than the word "preview", matching how every other slot
     * previews.
     */
    const val PREVIEW_LABEL = "14"

    /**
     * Remember the start date the phone sent.
     *
     * Stored as ISO-8601 (`2026-09-14`) — a DATE with no time and no zone,
     * because that is exactly what it is. Storing an instant would drag a
     * timezone into a value that has none, and the watch would then have to
     * decide which day an instant fell on, which is the bug this shape avoids.
     */
    fun save(root: File, date: LocalDate?) {
        runCatching {
            val f = file(root)
            if (date == null) f.delete() else f.writeText(date.toString())
        }
    }

    /**
     * The stored start date, or null when there is none or it is unreadable.
     *
     * A corrupt or truncated file reads as "no date" rather than throwing. The
     * same reasoning as [PhoneNote]: a file that has been half-written or poked
     * at must degrade to an empty slot, not to a crashed complication service
     * that takes the watch face down with it.
     */
    fun load(root: File): LocalDate? = runCatching {
        val f = file(root)
        if (!f.isFile) return null
        parse(f.readText())
    }.getOrNull()

    /**
     * Read a date off the wire.
     *
     * Trimmed, because a payload that arrived with a trailing newline is the
     * same date and refusing it would be pedantry the wearer pays for.
     */
    fun parse(text: String?): LocalDate? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return null
        return try {
            LocalDate.parse(t)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
