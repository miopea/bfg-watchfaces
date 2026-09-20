package com.bfg.watchfaces.wear

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.wear.remote.interactions.RemoteActivityHelper

/**
 * What tapping the cycle card does: open the cycle section of Google Health.
 *
 * ## Why this opens on the PHONE
 *
 * Not a preference. The watch's health app exposes no cycle deep link at all:
 * read off the operator's Pixel Watch 5 on 2026-09-20, `com.fitbit.FitbitMobile`
 * there declares exactly three VIEW authorities — `fitbit://auth`,
 * `fitbit://workouts` and `health://heartrate` — and one launcher activity, the
 * Today screen. Asking it for a cycle section resolves to nothing.
 *
 * The phone's copy of the same app does have one: `fitbit://minerva` resolves to
 * `MenstrualHealthDeepLink`, verified with `cmd package query-activities` on the
 * same day. That is also where she logs, so the handoff goes where the work
 * actually happens rather than to a screen that can only be read.
 *
 * ## Why an Activity and not a link
 *
 * A tile's [androidx.wear.protolayout.ModifiersBuilders.Clickable] can start an
 * activity on the WATCH or ask the tile to reload, and nothing else. Reaching
 * the phone needs `RemoteActivityHelper`, which needs a Context that can bind —
 * so the click starts this, and this does the handoff. It draws nothing and
 * finishes immediately; the card stays on screen behind it.
 *
 * ## It holds no health data
 *
 * Same as everything else in `:wear`. It opens an app. It reads no records,
 * holds no permission, and the URI it launches is a constant in this file.
 */
class CycleOpenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val future = RemoteActivityHelper(this).startRemoteActivity(
            Intent(Intent.ACTION_VIEW)
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .setData(Uri.parse(CYCLE_ON_PHONE))
        )
        future.addListener({
            // Never a crash and never silence. "Check your phone" is wrong when
            // the phone is out of range, so the two cases say different things:
            // one tells her where to look, the other tells her why nothing
            // happened. Neither mentions a deep link or a node.
            val ok = runCatching { future.get() }
                .onFailure { Log.w(TAG, "could not open the cycle section on the phone", it) }
                .isSuccess
            Toast.makeText(
                this,
                if (ok) "Opening on your phone" else "Your phone isn't connected",
                Toast.LENGTH_SHORT
            ).show()
            finish()
        }, ContextCompat.getMainExecutor(this))
    }

    private companion object {
        private const val TAG = "BfgCycleOpen"

        /**
         * The cycle section of Google Health on the phone.
         *
         * "minerva" is that app's own name for it, which is why this constant
         * does not read like the thing it opens. Verified resolving to
         * `MenstrualHealthDeepLink` on the operator's phone, 2026-09-20; if the
         * app ever renames it the handoff fails closed, saying the phone is not
         * connected, rather than opening something else.
         */
        private const val CYCLE_ON_PHONE = "fitbit://minerva"
    }
}
