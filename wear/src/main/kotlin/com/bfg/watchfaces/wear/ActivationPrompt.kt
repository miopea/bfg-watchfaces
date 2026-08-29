package com.bfg.watchfaces.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bfg.watchfaces.appcore.ActivationConsent

/**
 * Carries the one irreversible ask from "a face landed" to "someone is looking
 * at the watch".
 *
 * ## Why this is not just `startActivity`
 *
 * It was, and Android refuses it. A face arrives in a background context — a
 * `WearableListenerService` handling `onChannelOpened` — and a background
 * activity launch is blocked outright:
 *
 * ```text
 * Background activity launch blocked! goo.gle/android-bal
 *   cmp=com.bfg.watchfaces/.wear.ActivationRequestActivity
 *   callingUidProcState: RECEIVER
 * ```
 *
 * Observed on a Wear OS 6 emulator on 2026-08-29, and not an artefact of the
 * debug harness: the shipped path is a background context by the identical
 * rule. The permission had never once been requested, on any device, because
 * the code that asks could never run.
 *
 * A notification is the bridge Android intends here. The tap is a foreground
 * action, so the activity it launches is allowed to start, and the person is by
 * definition looking at the watch when it happens — which is more than the
 * original design could promise. It fires cold on a wrist either way; at least
 * this way it fires because they chose to look.
 *
 * ## What is deliberately NOT here
 *
 * No re-post, no reminder, no second notification. If it is dismissed, the face
 * is still installed and still reachable by long-pressing the dial, which is
 * exactly what [ActivationConsent.DENIED_NOTE] tells people to do. Nagging
 * someone toward a permanent decision is the one thing this flow must not do.
 */
object ActivationPrompt {

    /**
     * Post the prompt for a freshly installed face.
     *
     * Returns false when nothing was shown, which is not necessarily a failure:
     * `POST_NOTIFICATIONS` is a runtime permission on API 33 and up, and
     * `notify` is silently a no-op without it rather than throwing.
     */
    fun show(context: Context, slotId: String): Boolean {
        val manager = NotificationManagerCompat.from(context)

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                ActivationConsent.NOTIFY_CHANNEL_NAME,
                // Default, not high: this must not vibrate a wrist for something
                // that has already succeeded. The face is installed by now.
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )

        val tap = PendingIntent.getActivity(
            context,
            REQUEST,
            ActivationRequestActivity.intent(context, slotId),
            // Immutable is required from API 31 and correct regardless: nothing
            // outside this app has any business rewriting which slot gets
            // activated.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle(ActivationConsent.NOTIFY_TITLE)
            .setContentText(ActivationConsent.NOTIFY_BODY)
            // The body is two sentences and a watch is narrow. Without this the
            // one-shot warning is the half that gets truncated away.
            .setStyle(NotificationCompat.BigTextStyle().bigText(ActivationConsent.NOTIFY_BODY))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()

        if (!manager.areNotificationsEnabled()) {
            // Worth its own line. `notify` below would return quietly and the
            // ask would simply never happen, which is indistinguishable from the
            // bug this whole class exists to fix.
            Log.w(TAG, "notifications are not enabled; the activation ask cannot be shown")
            return false
        }
        return runCatching {
            manager.notify(NOTIFICATION_ID, notification)
            Log.i(TAG, "activation prompt posted for slot $slotId")
            true
        }.getOrElse {
            Log.e(TAG, "could not post the activation prompt", it)
            false
        }
    }

    private const val TAG = "BfgActivationPrompt"
    private const val CHANNEL = "bfg-new-face"
    private const val NOTIFICATION_ID = 1
    private const val REQUEST = 0
}
