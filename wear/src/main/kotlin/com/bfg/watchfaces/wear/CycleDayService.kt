package com.bfg.watchfaces.wear

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.MonochromaticImage
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
 * ## UPDATE_PERIOD_SECONDS is an hour, and it used to be 0
 *
 * A day count changes at midnight, so the obvious thing is a timer. It shipped
 * as 0 anyway, on the same reasoning as [PhoneNoteService]: polling wakes the
 * watch to re-read a file that almost never differs, the system re-requests
 * complication data when the date changes, and [notifyChanged] pushes when
 * there is genuinely something new. That paragraph ended: "If a stale number is
 * ever seen across a midnight on a real wrist, that is the evidence that this
 * was the wrong call — and no test here can produce it."
 *
 * It was seen, on 2026-09-27, and in the clearest possible form. The wearer's
 * TILE had moved on and her COMPLICATION had not — one watch, one date file,
 * two refresh mechanisms, and only the one that asked for nothing was stale.
 * The tile had been asking for an hour through `setFreshnessIntervalMillis`
 * since it was written; this had no equivalent, so "the system re-requests on a
 * date change" was load-bearing and untrue.
 *
 * It is now an hour, matching the tile, and an hour rather than a request for
 * exactly midnight for the reason the tile already gives: a wake-up may be
 * honoured late, and a value that is only right when one wake-up lands on time
 * is sometimes a day out with nothing to show for it.
 *
 * **[PhoneNoteService] keeps 0 and should.** Its value changes when a person
 * changes it and never on a clock. The distinction is the lesson: 0 is right
 * for a value that is PUSHED, and wrong for a value DERIVED FROM THE DATE.
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

    /**
     * The ring the dial draws above the number.
     *
     * Monochromatic on purpose: the WATCH FACE tints it with the wearer's ink,
     * which is why the drawable is white and carries no colour of its own. The
     * face already asks for `[COMPLICATION.MONOCHROMATIC_IMAGE]` for any named
     * provider, so supplying this is the whole of what makes the mark appear.
     *
     * `setAmbientImage(null)` is deliberate rather than an omission: the slot
     * already carries an ambient alpha Variant, and a second ambient asset
     * would be a second thing to keep in step for no gain.
     */
    private fun ring() = MonochromaticImage.Builder(
        image = Icon.createWithResource(this, R.drawable.ic_cycle_ring)
    ).setAmbientImage(null).build()

    private fun shortText(text: String) = ShortTextComplicationData.Builder(
        text = PlainComplicationText.Builder(text).build(),
        // Read aloud, this is the one place the slot says what it is. On the
        // dial it is a bare number on purpose -- decision 01a0ba48 -- so that a
        // glance over her shoulder shows nothing. A screen reader is not a
        // glance over her shoulder; it is her, asking.
        contentDescription = PlainComplicationText.Builder(
            // "Cycle day 18", not "Cycle 18". The number is bare on the DIAL
            // so a glance shows nothing; read aloud it needs its noun back, or
            // it is a number with no subject.
            if (text == CycleDay.EMPTY_PLACEHOLDER) "Cycle day not available" else "Cycle day $text"
        ).build()
    ).setMonochromaticImage(ring()).build()

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
