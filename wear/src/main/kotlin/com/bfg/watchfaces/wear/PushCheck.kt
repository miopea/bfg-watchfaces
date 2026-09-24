package com.bfg.watchfaces.wear

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.wear.watchfacepush.WatchFacePushManagerFactory
import com.bfg.watchfaces.appcore.PushAvailability

/**
 * Ask this watch whether it can actually take a pushed face.
 *
 * ## Why the library's own answer is not enough
 *
 * `WatchFacePushManagerFactory.isSupported()` is `Build.VERSION.SDK_INT >= 36`
 * and nothing else — disassembled from watchfacepush 1.0.0 on 2026-09-24, not
 * inferred. `FaceInstaller` used it as a capability check, and a Pixel Watch 4
 * that passed it then failed at the first real call with
 * `ReceiverConnectionException`, after its owner had waited through a build and
 * a Bluetooth transfer.
 *
 * ## This observes rather than predicts
 *
 * Three facts, all cheap and all local:
 *
 * 1. The OS level, which is the one thing the library covers.
 * 2. How many services answer the Push action. Needs the `<queries>` entry in
 *    the manifest or it is zero everywhere — see the comment there.
 * 3. **An actual `listWatchFaces()` call.** This is the definitive one. It is
 *    the same call that failed on her wrist, it costs nothing here, and it
 *    answers the question instead of guessing at it.
 *
 * The first two exist to explain the third. Without them a failure can only say
 * "something went wrong"; with them it can say which thing, and therefore what
 * a person might do about it.
 */
object PushCheck {

    private const val TAG = "BfgPushCheck"

    /**
     * What this watch can do, right now.
     *
     * Never throws. Every probe is wrapped, because this runs to decide whether
     * something else is safe to attempt and a crash here would be worse than
     * the failure it is meant to pre-empt.
     */
    suspend fun observe(context: Context): PushAvailability {
        val sdk = Build.VERSION.SDK_INT

        // Below the floor there is nothing to look for and nothing to probe.
        // Saying OS_TOO_OLD is the useful answer; "no receiver" would send
        // somebody hunting for an update that cannot help.
        if (sdk < PushAvailability.MIN_SDK) {
            return PushAvailability(sdk, 0, PushAvailability.Probe.SKIPPED)
        }

        val receivers = runCatching {
            context.packageManager
                .queryIntentServices(Intent(PushAvailability.PUSH_ACTION), 0)
                .size
        }.getOrElse {
            // -1, not 0. Zero is a finding; a failed query is an absence of
            // one, and the two must not read the same.
            Log.w(TAG, "could not query for the push receiver", it)
            -1
        }

        // The real thing. listWatchFaces is what FaceInstaller calls first and
        // what threw on the Pixel Watch 4, so running it here asks exactly the
        // question the send will ask -- just before the build rather than after.
        return try {
            val manager = WatchFacePushManagerFactory.createWatchFacePushManager(
                context.applicationContext
            )
            manager.listWatchFaces()
            PushAvailability(sdk, receivers, PushAvailability.Probe.OK)
        } catch (cause: Throwable) {
            Log.w(TAG, "this watch cannot take a pushed face", cause)
            PushAvailability(sdk, receivers, PushAvailability.Probe.FAILED, chain(cause))
        }
    }

    /**
     * The cause, flattened, for the details view a person can choose to open.
     *
     * Kept because a report with no cause is what made the original failure
     * expensive to diagnose -- but it goes behind a tap, never in the sentence.
     * Same rule as `FailureReport`.
     */
    private fun chain(t: Throwable): String {
        val parts = mutableListOf<String>()
        var e: Throwable? = t
        var guard = 0
        while (e != null && guard++ < 6) {
            parts += "${e::class.simpleName}: ${e.message.orEmpty()}"
            e = e.cause
        }
        return parts.joinToString(" <- ")
    }
}
