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
}
