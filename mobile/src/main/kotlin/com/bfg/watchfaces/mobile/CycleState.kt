package com.bfg.watchfaces.mobile

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bfg.watchfaces.appcore.CycleDay
import java.time.LocalDate

/**
 * The cycle start date, as something the UI can WATCH rather than re-read.
 *
 * ## The bug this exists to stop
 *
 * The dial preview needs the real day so it does not draw a stand-in beside a
 * watch showing the true one. The first attempt read the cached date from disk
 * inside the preview's `produceState`, and it lost a race it could not win: the
 * background sync writes that file, the preview had already run, and none of
 * the preview's keys changed afterwards — so it never recomputed. The phone
 * showed "Day 14" while the watch showed "Day 18", both from the same app.
 *
 * Holding it in Compose state instead means the write IS the signal. Anything
 * reading [startDate] recomposes when the sync lands, with no key to remember
 * and no file read on a hot path.
 *
 * ## Why a process-wide holder is the right size here
 *
 * There is exactly one wearer, one Health Connect, and one answer. A ViewModel
 * per screen would be three copies of one fact, which is the shape this repo
 * keeps getting hurt by.
 *
 * The disk copy in [CycleDay] stays: it is what survives the process dying, and
 * it is the same file the watch keeps for the same reason. This is a cache in
 * front of it, not a replacement.
 */
object CycleState {

    /**
     * The most recent period start, or null when there is none.
     *
     * Null also means "not looked yet", and the two are deliberately not
     * distinguished: both draw a stand-in, and a preview has nothing useful to
     * say about the difference.
     */
    var startDate by mutableStateOf<LocalDate?>(null)
        private set

    /** Seed from disk, for a process that has just started. */
    fun load(context: Context) {
        startDate = CycleDay.load(context.filesDir)
    }

    /**
     * Record what Health Connect last said, on disk and in memory.
     *
     * Both, in that order, because the disk copy is the durable one and the
     * state is what makes the screen notice.
     */
    fun set(context: Context, date: LocalDate?) {
        CycleDay.save(context.filesDir, date)
        startDate = date
    }

    /** What a slot filled by the cycle provider should draw right now. */
    fun label(): String? =
        startDate?.let { CycleDay.label(it, LocalDate.now()) }
}
