package com.bfg.watchfaces.mobile

import android.content.Context
import java.io.File

/**
 * Whether the send flow has been explained on this phone.
 *
 * Deliberately NOT [com.bfg.watchfaces.appcore.ActivationConsent]. That records
 * what the WATCH was asked and answered, it lives on the watch, and the phone's
 * copy is never written — so reading it here would say UNASKED forever and the
 * explanation would appear before every send.
 *
 * This records something narrower and phone-local: have we shown this person the
 * three steps once. After that, "Send to watch" just sends.
 */
object Onboarding {

    private const val FILE = "handoff-explained"

    fun hasExplainedSend(context: Context): Boolean =
        File(context.filesDir, FILE).exists()

    fun markSendExplained(context: Context) {
        runCatching { File(context.filesDir, FILE).writeText("1") }
    }

    private const val COMPLICATIONS_MOVED = "complications-moved"

    /**
     * Whether to explain, once, that complications are chosen in the app now.
     *
     * Only for someone who HAD a face from before the change. Until generator
     * v8 the watch's own editor owned a slot once it had touched one, so
     * anything picked there survived every send — and now it does not. Their
     * choices changing with no explanation is indistinguishable from a bug.
     *
     * Nobody else is told anything. To a person whose first face is v8 or
     * later there is nothing to migrate, and the note would be a warning about
     * a world they never saw.
     */
    fun shouldExplainComplications(context: Context, previousVersion: Int?): Boolean =
        previousVersion != null &&
            previousVersion < FIRST_AUTHORITATIVE_VERSION &&
            !File(context.filesDir, COMPLICATIONS_MOVED).exists()

    fun markComplicationsExplained(context: Context) {
        runCatching { File(context.filesDir, COMPLICATIONS_MOVED).writeText("1") }
    }

    /** The version at which the face definition started winning. See DECISIONS. */
    const val FIRST_AUTHORITATIVE_VERSION = 8
}
