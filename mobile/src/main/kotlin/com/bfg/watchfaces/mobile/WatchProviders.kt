package com.bfg.watchfaces.mobile

import android.content.Context
import android.util.Log
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
 * Asking the watch what complications it has, instead of waiting to be told.
 *
 * ## Why this exists
 *
 * [ProviderCache] is filled by a successful send, and that remains true — the
 * catalog rides back on the channel that is already open. It is a fine way to
 * keep the list FRESH and a hopeless way to DISCOVER it.
 *
 * Measured on 2026-09-19: the operator updated both apps to pick up a package
 * visibility fix, opened the slot picker, and saw exactly the list he had
 * before, because nothing had asked the watch in between. The fix was real and
 * invisible. Nobody would guess that sending an unrelated watch face is how
 * you refresh a list of complications, and nobody should have to.
 *
 * ## Why failure is quiet
 *
 * A watch charging in another room cannot answer, and that is not an error
 * worth a sentence: the cached list is STALE rather than wrong, and every
 * entry in it still works. So this returns false and the picker opens on what
 * it had. The alternative — a picker that reports a problem, or refuses to
 * open, because a watch is out of range — would be worse than one quietly
 * showing last week's answer.
 */
object WatchProviders {

    private const val TAG = "BfgWatchProviders"

    /**
     * Long enough for a watch that is present, short enough not to be a wait.
     *
     * The picker opens immediately either way; this races in the background and
     * updates the list if it wins. Nothing is blocked on it.
     */
    private const val TIMEOUT_MS = 4_000L

    /**
     * Ask every connected watch, take the first answer, and cache it.
     *
     * Returns true when the cache was updated. Suspending: the Data Layer calls
     * block, and this is called from a composition.
     */
    suspend fun refresh(context: Context): Boolean {
        val client = Wearable.getMessageClient(context)
        val answer = CompletableDeferred<String?>()

        // NO URI FILTER. The handler already checks the path, and the filter
        // was a second thing that had to be right for this to work at all --
        // it was one of two suspects when this did not fire, and removing it
        // leaves one.
        val listener = MessageClient.OnMessageReceivedListener { event ->
            if (event.path == WatchLink.CATALOG_REPLY_PATH) {
                answer.complete(runCatching { String(event.data, Charsets.UTF_8) }.getOrNull())
            }
        }
        client.addListener(listener)
        try {
            // OFF THE MAIN THREAD, and this is the whole bug it was written
            // with. Tasks.await BLOCKS, this is called from a LaunchedEffect,
            // and blocking the main thread throws -- which the runCatching
            // below then swallowed into "no catalog answer". Measured on the
            // operator's phone 2026-09-19: the note arrived on the watch while
            // this request never left, and the two differ only here.
            //
            // DesignsScreen carries the same scar twenty lines from where this
            // was written: "OFF the main thread ... which reportInstall's own
            // runCatching then swallows. The counter has never incremented on
            // hardware."
            val asked = withContext(Dispatchers.IO) {
                runCatching {
                    val nodes = Tasks.await(
                        Wearable.getNodeClient(context).connectedNodes, 4, TimeUnit.SECONDS
                    )
                    // EVERY node, for the same reason NoteSender does: two
                    // watches paired means asking both, and taking whichever
                    // answers first rather than guessing which is worn.
                    for (node in nodes) {
                        Tasks.await(
                            client.sendMessage(
                                node.id, WatchLink.CATALOG_REQUEST_PATH, ByteArray(0)
                            ), 4, TimeUnit.SECONDS
                        )
                    }
                    nodes.isNotEmpty()
                }.getOrElse {
                    // LOGGED, not swallowed. A silent catch is what hid this
                    // for a whole evening.
                    Log.w(TAG, "could not ask the watch for its catalog", it)
                    false
                }
            }
            if (!asked) {
                Log.i(TAG, "no watch to ask; keeping the cached list")
                return false
            }

            val reply = withTimeoutOrNull(TIMEOUT_MS) { answer.await() }
            if (reply.isNullOrBlank()) {
                Log.i(TAG, "asked, but no catalog answer; keeping the cached list")
                return false
            }
            // Read with the SEND REPORT's own parsers, because the watch
            // encoded it with the send report's own shape. One encoding, not
            // two.
            WatchLink.Report.catalogIn(reply)?.let { ProviderCache.save(context, it) }
            WatchLink.Report.launchersIn(reply)?.let { ProviderCache.saveLaunchers(context, it) }
            Log.i(TAG, "catalog refreshed from the watch")
            return true
        } finally {
            client.removeListener(listener)
        }
    }
}
