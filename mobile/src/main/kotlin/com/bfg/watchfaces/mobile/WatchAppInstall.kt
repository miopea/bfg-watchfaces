package com.bfg.watchfaces.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.wear.remote.interactions.RemoteActivityHelper

/**
 * Offering to put this app on the watch, rather than telling somebody to.
 *
 * ## Why this exists
 *
 * `FaceSender` already detects a watch without our app — `Target.AppMissing` —
 * and its own doc comment quotes Google's guidance: the phone app should detect
 * that absence "and offer to install it". It did not offer. It said "Install
 * BFG Watch Faces on the watch and try again", which leaves somebody to work
 * out how, on a device with no keyboard, having already made a face.
 *
 * ## Why this is not the same as the phone install doing it
 *
 * Both apps share one package name and one Play listing, so installing on the
 * phone normally brings the watch app down automatically. Normally is not
 * always: it needs the watch paired and reachable at the time, and somebody who
 * pairs a watch AFTER installing the phone app gets nothing. This is the
 * recovery path for exactly that case, and it is the case a first-run person is
 * most likely to be in.
 */
object WatchAppInstall {

    private const val TAG = "BfgWatchAppInstall"

    /**
     * Open this app's Play listing ON THE WATCH.
     *
     * `RemoteActivityHelper` is the only supported way to start something on
     * the paired device; a phone cannot reach across and install for you. The
     * wearer still taps Install, which is right — it is their watch.
     */
    fun openListingOnWatch(context: Context, onResult: (Boolean) -> Unit) {
        val future = RemoteActivityHelper(context).startRemoteActivity(
            Intent(Intent.ACTION_VIEW)
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .setData(Uri.parse("market://details?id=${context.packageName}"))
        )
        future.addListener({
            val ok = runCatching { future.get() }
                .onFailure { Log.w(TAG, "could not open the watch's Play listing", it) }
                .isSuccess
            onResult(ok)
        }, ContextCompat.getMainExecutor(context))
    }
}
