package com.bfg.watchfaces.mobile

import android.content.Context
import android.util.Log
import com.bfg.watchfaces.appcore.PushAvailability
import com.bfg.watchfaces.appcore.WatchLink
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Ask the watch whether it can take a face, BEFORE building one.
 *
 * ## What this is for
 *
 * On 2026-09-24 a Pixel Watch 4 accepted a build and a Bluetooth transfer and
 * then told its owner:
 *
 * ```
 * Pixel Watch 4 could not install "Default": ListWatchFacesException | ...
 * | <- ReceiverConnectionException: Binding to the watch face receiver was
 * unsuccessful
 * ```
 *
 * Every part of that was knowable before the build started. The watch knows;
 * the phone only had to ask.
 *
 * ## The rule that matters most here
 *
 * **Silence is not a refusal.** A watch that is out of range, slow, or running
 * a build that predates this check will not answer, and all three must still be
 * sent to. Turning an unanswered question into a blocked send would trade a
 * rare, explained failure for a common, baffling one — the same trade
 * `InstallPlan.mayRemoveAfterFailedUpdate` exists to refuse.
 *
 * So [check] returns null for "no answer", and a null must be treated as "go
 * ahead" by every caller.
 */
object WatchReadiness {

    private const val TAG = "BfgWatchReadiness"

    /**
     * Long enough for a paired watch to wake and answer, short enough that a
     * person does not think the button is broken. The catalog request uses the
     * same budget.
     */
    private const val TIMEOUT_MS = 6_000L

    /**
     * What the watch says about itself, or null if it did not answer.
     *
     * Null means UNKNOWN, never "no". See the class note.
     *
     * Suspending, and the Data Layer calls inside are moved off the main thread
     * deliberately: `Tasks.await` BLOCKS and throws on the main thread, and a
     * `runCatching` then reports it as "the watch did not answer". That exact
     * mistake cost an evening on 2026-09-19 and is written up in
     * [WatchProviders.refresh].
     */
    suspend fun check(context: Context): PushAvailability? {
        val client = Wearable.getMessageClient(context)
        val answer = CompletableDeferred<String?>()

        val listener = MessageClient.OnMessageReceivedListener { event ->
            if (event.path == WatchLink.PUSH_CHECK_REPLY_PATH) {
                answer.complete(runCatching { String(event.data, Charsets.UTF_8) }.getOrNull())
            }
        }
        client.addListener(listener)
        try {
            val asked = withContext(Dispatchers.IO) {
                runCatching {
                    val nodes = Tasks.await(
                        Wearable.getNodeClient(context).connectedNodes, 4, TimeUnit.SECONDS
                    )
                    for (node in nodes) {
                        Tasks.await(
                            client.sendMessage(
                                node.id, WatchLink.PUSH_CHECK_REQUEST_PATH, ByteArray(0)
                            ), 4, TimeUnit.SECONDS
                        )
                    }
                    nodes.isNotEmpty()
                }.getOrElse {
                    // Logged, never swallowed silently. A quiet catch here
                    // would look identical to a watch that cannot take a face.
                    Log.w(TAG, "could not ask the watch whether it is ready", it)
                    false
                }
            }
            if (!asked) {
                Log.i(TAG, "no watch to ask; proceeding without an answer")
                return null
            }

            val reply = withTimeoutOrNull(TIMEOUT_MS) { answer.await() }
            if (reply.isNullOrBlank()) {
                // An older watch build has never heard of this path and will
                // never answer. Proceeding is correct for it.
                Log.i(TAG, "asked, but no readiness answer; proceeding without one")
                return null
            }
            val availability = PushAvailability.decode(reply)
            Log.i(
                TAG,
                "watch readiness: usable=${availability.usable} reason=${availability.reason}"
            )
            return availability
        } finally {
            client.removeListener(listener)
        }
    }
}
