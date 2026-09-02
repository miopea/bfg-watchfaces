package com.bfg.watchfaces.wear

import android.content.ComponentName
import android.util.Log
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.bfg.watchfaces.appcore.PhoneNote

/**
 * This app answering a complication, so a value can change without a new face.
 *
 * ## The point of it
 *
 * Everything else about a face is baked into an APK. Changing a colour, a
 * layout, a complication choice means rebuilding, sending over Bluetooth, and
 * spending one of the watch's finite `addWatchFace` calls.
 *
 * A complication is the exception: the face names a provider and the watch asks
 * it for a value whenever it wants. So anything delivered this way changes on
 * the wrist **with no rebuild, no send, and no slot spent** — which is exactly
 * what Google's Watch Face Push guidance recommends building a companion app
 * around.
 *
 * This is the first thing in the project on that path. A short note typed on the
 * phone, appearing in whichever slot the wearer points at it.
 *
 * ## Why it shows up in the picker for free
 *
 * `ProviderCatalog` finds complication sources by querying services for the
 * provider intent and keeping the ones that support `SHORT_TEXT`. Registering
 * this in the manifest is all it takes for the existing slot picker to offer it
 * — there was no picker change, which is the sign the seam was already in the
 * right place.
 *
 * ## No timed updates
 *
 * `UPDATE_PERIOD_SECONDS` is 0. This value changes when a person changes it and
 * at no other time, so polling would wake the watch to re-read a file that
 * almost never differs. [notifyChanged] pushes instead, at the one moment there
 * is something new.
 */
class PhoneNoteService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.SHORT_TEXT) return null
        val note = PhoneNote.load(applicationContext.filesDir)
        return shortText(note.ifEmpty { PhoneNote.EMPTY_PLACEHOLDER })
    }

    /**
     * What the editor and the picker show before a real value exists.
     *
     * Deliberately a plausible note rather than the word "preview": this is what
     * somebody sees while choosing the source, and it has to look like the
     * feature working.
     */
    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.SHORT_TEXT) return null
        return shortText("Back at 6")
    }

    private fun shortText(text: String) = ShortTextComplicationData.Builder(
        text = PlainComplicationText.Builder(text).build(),
        contentDescription = PlainComplicationText.Builder(
            if (text == PhoneNote.EMPTY_PLACEHOLDER) "No note from your phone" else text
        ).build()
    ).build()

    companion object {
        private const val TAG = "BfgPhoneNote"

        /**
         * Tell the system this app's answer has changed.
         *
         * Without this the watch would keep whatever it last read until
         * something else happened to ask — so a note typed on the phone would
         * arrive at an unpredictable time, or look like it had not arrived at
         * all. Push, at the one moment there is something new.
         */
        fun notifyChanged(context: android.content.Context) {
            runCatching {
                ComplicationDataSourceUpdateRequester
                    .create(context, ComponentName(context, PhoneNoteService::class.java))
                    .requestUpdateAll()
            }.onFailure {
                // Never fatal: the note is already stored, and the watch will
                // pick it up the next time it asks. Losing the push costs
                // freshness, not the value.
                Log.w(TAG, "could not ask the system to refresh the note", it)
            }
        }
    }
}
