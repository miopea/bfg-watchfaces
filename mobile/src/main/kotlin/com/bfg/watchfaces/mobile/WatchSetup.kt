package com.bfg.watchfaces.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.wear.remote.interactions.RemoteActivityHelper
import java.util.concurrent.TimeUnit

/**
 * Opening the watch app FROM the phone, for the one thing only the watch can do.
 *
 * ## Why this exists
 *
 * `SET_PUSHED_WATCH_FACE_AS_ACTIVE` can only be requested by the app on the
 * watch, and the way that ask reaches a wearer is a notification. A fresh
 * install holds no notification permission either, so the whole chain dead-ends
 * before anything appears:
 *
 * ```text
 * W/BfgActivationPrompt: notifications are not enabled;
 *                        the activation ask cannot be shown
 * ```
 *
 * Measured on a Pixel Watch 5 on 2026-09-01. The face installed correctly, the
 * send reported success, and the watch went on showing something else — with no
 * prompt, no error, and nothing on either device suggesting an action. The only
 * way through was to know, unprompted, to open an app on the watch, which is
 * not something anybody should have to work out.
 *
 * ## Why the phone is the right side to fix it from
 *
 * The phone is where the person already is: they just pressed Send. It also
 * knows when the ask is needed, because the watch reports its consent state
 * back on the reply. So the phone opens the watch app at exactly that moment,
 * `WatchActivity` requests notifications and offers the activation route as it
 * already does, and the wearer answers a question they were expecting.
 *
 * ## Why it is allowed to fail quietly
 *
 * This is a convenience over a path that still works by hand. If the watch is
 * out of range or refuses the launch, the face is still installed and the app
 * still opens normally on the watch. A failure here must never turn a
 * successful send into a reported failure — that mistake has already been made
 * twice in this project, in both directions.
 */
object WatchSetup {

    private const val TAG = "BfgWatchSetup"

    /** Matches the `bfgwatchfaces://setup` filter on `:wear`'s WatchActivity. */
    private const val SETUP = "bfgwatchfaces://setup"

    /**
     * Ask [nodeId] to bring the watch app up.
     *
     * Blocking, so call it off the main thread. Returns whether the launch was
     * accepted — NOT whether anybody answered.
     */
    fun openOnWatch(context: Context, nodeId: String): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_VIEW)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setData(Uri.parse(SETUP))
        RemoteActivityHelper(context)
            .startRemoteActivity(intent, nodeId)
            .get(10, TimeUnit.SECONDS)
        Log.i(TAG, "asked the watch to open for activation")
        true
    }.getOrElse {
        // Logged, never surfaced. See the class comment.
        Log.w(TAG, "could not open the watch app remotely", it)
        false
    }
}
