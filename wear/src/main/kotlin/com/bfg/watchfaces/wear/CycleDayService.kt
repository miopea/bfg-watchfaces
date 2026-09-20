package com.bfg.watchfaces.wear

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.bfg.watchfaces.appcore.CycleDay
import java.time.LocalDate

/**
 * The day of her cycle, on her own wrist.
 *
 * ## Why the watch does the arithmetic
 *
 * The phone sends a DATE and this turns it into a number, every time the watch
 * asks. Nothing has to wake at midnight, nothing has to be scheduled, and the
 * value cannot be stale because the phone was in another room — see
 * [WatchLink.CYCLE_START_PATH][com.bfg.watchfaces.appcore.WatchLink.CYCLE_START_PATH].
 *
 * The sum itself lives in [CycleDay], in `:appcore`, so the phone can show the
 * same number in the same words without a second implementation of it.
 *
 * ## This watch holds no health data
 *
 * It has one date, which the wearer's own phone sent it. It never touches
 * Health Connect, holds no permission, and has no way to read a record. The
 * phone reduces everything to that one date before anything crosses, which is
 * what keeps the declaration in `docs/specs/cycle-complication-declarations.md`
 * true rather than aspirational.
 *
 * ## It is a registered provider, and that is a disclosed consequence
 *
 * Any watch face on this device can select this source, exactly as it can
 * select [PhoneNoteService]. That falls out of being a complication provider at
 * all, and Watch Face Format offers no date arithmetic that would let a face
 * compute this itself. It is disclosed in the Data Safety declaration rather
 * than quietly true.
 *
 * ## UPDATE_PERIOD_SECONDS is 0, unlike everything else that shows a date
 *
 * A day count changes at midnight, so the obvious thing is a timer. It is still
 * 0, for the same reason [PhoneNoteService] is: polling wakes the watch to
 * re-read a file that almost never differs. The system already re-requests
 * complication data when the date changes, and [notifyChanged] pushes at the
 * one moment there is genuinely something new. If a stale number is ever seen
 * across a midnight on a real wrist, that is the evidence that this was the
 * wrong call — and no test here can produce it.
 */
class CycleDayService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.SHORT_TEXT) return null
        // LocalDate.now() reads the WATCH's timezone, deliberately. She is
        // looking at the watch, so the day it is on the watch is the day she
        // means -- and a phone in another zone must not decide it for her.
        val start = CycleDay.load(applicationContext.filesDir)
        return shortText(CycleDay.label(start, LocalDate.now()))
    }

    /**
     * What the picker shows while she is choosing the source.
     *
     * A plausible day rather than the word "preview", matching
     * [PhoneNoteService]: this is what the feature looks like working, shown at
     * the moment somebody decides whether to use it.
     */
    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.SHORT_TEXT) return null
        return shortText(CycleDay.PREVIEW_LABEL)
    }

    private fun shortText(text: String) = ShortTextComplicationData.Builder(
        text = PlainComplicationText.Builder(text).build(),
        // Read aloud, this is the one place the slot says what it is. On the
        // dial it is a bare number on purpose -- decision 01a0ba48 -- so that a
        // glance over her shoulder shows nothing. A screen reader is not a
        // glance over her shoulder; it is her, asking.
        contentDescription = PlainComplicationText.Builder(
            if (text == CycleDay.EMPTY_PLACEHOLDER) "Cycle day not available" else "Cycle $text"
        ).build()
    ).build()

    companion object {
        private const val TAG = "BfgCycleDay"

        /**
         * Tell the system this answer has changed.
         *
         * Called when a new start date arrives from the phone. Without it the
         * watch keeps whatever it last read until something else happens to
         * ask, so a period logged this morning would appear at an unpredictable
         * time or look like it never arrived. Same mechanism, same reason, as
         * [PhoneNoteService.notifyChanged].
         */
        fun notifyChanged(context: Context) {
            runCatching {
                ComplicationDataSourceUpdateRequester.create(
                    context, ComponentName(context, CycleDayService::class.java)
                ).requestUpdateAll()
            }.onFailure { Log.w(TAG, "could not ask for a complication refresh", it) }
        }
    }
}
