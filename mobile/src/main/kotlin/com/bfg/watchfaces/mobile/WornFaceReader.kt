package com.bfg.watchfaces.mobile

import android.content.Context
import android.util.Log
import com.bfg.watchfaces.appcore.FaceLibrary
import com.bfg.watchfaces.appcore.WatchLink
import com.bfg.watchfaces.appcore.WornFacePlan
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Ask the watch what it is actually wearing, instead of assuming.
 *
 * ## Why this exists
 *
 * `CurrentFace` is a record of what this phone last SENT. That is an intention,
 * and its own comment is careful about when an intention is the right thing to
 * remember. It is wrong in exactly the cases a person notices: a send that
 * failed after the transfer, a face put there from a different phone, and the
 * one that prompted this — the phone app reinstalled, `filesDir` wiped, saved
 * faces gone, and the watch still wearing something the phone has no record of.
 *
 * ## An empty answer and no answer are different, and the difference is the point
 *
 * The watch replies with an empty payload to say "none of ours is installed".
 * That is a fact. Not replying at all means out of range, asleep, or a build
 * that predates this path. Reporting the second as the first would tell someone
 * their watch is bare while they are looking at the face on it.
 *
 * So this returns [WornFacePlan.NoAnswer] for silence, and that is never
 * presented as an empty wrist. Same rule as [WatchReadiness], for the same
 * reason.
 */
object WornFaceReader {

    private const val TAG = "BfgWornFace"

    /**
     * Long enough for a paired watch to wake and answer, short enough that a
     * person does not think the screen has hung. Matches the readiness check.
     */
    private const val TIMEOUT_MS = 6_000L

    /**
     * What the watch is wearing, reconciled against what this phone has saved.
     *
     * Suspending, and the Data Layer calls are moved off the main thread
     * deliberately: `Tasks.await` BLOCKS and throws on the main thread, and a
     * `runCatching` then reports that as "the watch did not answer". That exact
     * mistake cost an evening on 2026-09-19 and is written up in
     * [WatchProviders.refresh].
     */
    suspend fun read(context: Context): WornFacePlan {
        val client = Wearable.getMessageClient(context)
        val answer = CompletableDeferred<String?>()

        val listener = MessageClient.OnMessageReceivedListener { event ->
            if (event.path == WatchLink.WORN_FACE_REPLY_PATH) {
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
                                node.id, WatchLink.WORN_FACE_REQUEST_PATH, ByteArray(0)
                            ), 4, TimeUnit.SECONDS
                        )
                    }
                    nodes.isNotEmpty()
                }.getOrElse {
                    Log.w(TAG, "could not ask the watch what it is wearing", it)
                    false
                }
            }
            if (!asked) {
                Log.i(TAG, "no watch to ask")
                return WornFacePlan.NoAnswer
            }

            // withTimeoutOrNull gives null for "no reply arrived in time"; the
            // reply itself is BLANK when the watch is wearing none of ours.
            // Those are different answers and WornFacePlan.fromReply is where
            // the three-way distinction lives, under test, rather than here.
            val reply = withTimeoutOrNull(TIMEOUT_MS) { answer.await() }
            val saved = FaceLibrary.list(context.filesDir)
            val plan = WornFacePlan.fromReply(reply, saved)
            // The SHAPE of the answer, never the slug. A face name is the one
            // thing in this app that belongs to the person who chose it, and a
            // logcat line is readable by anything on the device.
            Log.i(TAG, "worn face: ${plan::class.simpleName} (saved=${saved.size})")
            return plan
        } finally {
            client.removeListener(listener)
        }
    }
}
