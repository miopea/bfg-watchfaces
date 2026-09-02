package com.bfg.watchfaces.mobile

import android.content.Context
import android.util.Log
import com.bfg.watchfaces.appcore.PhoneNote
import com.bfg.watchfaces.appcore.WatchLink
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.TimeUnit

/**
 * Send a short note to the watch, without rebuilding anything.
 *
 * ## Why this is not a face send
 *
 * A face is half a megabyte of APK over a channel, and installing it spends one
 * of the watch's finite `addWatchFace` calls. This is twenty characters over
 * `MessageClient`: one call, no stream, no slot, and it lands in whichever
 * complication slot the wearer pointed at `PhoneNoteService`.
 *
 * It is the first thing in the app a person can change and see on their wrist
 * without a rebuild, and the reason the complication path is worth having at
 * all.
 *
 * ## Stored on the phone too
 *
 * So the field shows what was last sent after the app is reopened, and so a
 * failed send can be retried without retyping. The watch has its own copy;
 * neither is authoritative over the other, because the only thing that can
 * disagree is a send that did not arrive, and the fix for that is sending
 * again.
 */
object NoteSender {

    private const val TAG = "BfgNoteSender"

    /** Long enough for a paired watch, short enough not to hang a screen. */
    private const val TIMEOUT_SECONDS = 8L

    /**
     * Send [raw] to every connected watch, and remember it here.
     *
     * Returns the cleaned text on success, or null when nothing could be
     * reached. Blocking — call it off the main thread.
     */
    fun send(context: Context, raw: String): String? {
        val text = PhoneNote.save(context.filesDir, raw)
        return runCatching {
            val nodes = Tasks.await(
                Wearable.getNodeClient(context).connectedNodes,
                TIMEOUT_SECONDS, TimeUnit.SECONDS
            )
            if (nodes.isEmpty()) {
                Log.w(TAG, "no watch connected; the note is stored and will need sending again")
                return null
            }
            // EVERY node, not just the first. Somebody with two watches paired
            // means both, and picking one arbitrarily would make it look like
            // the feature works on whichever they happen to be wearing.
            for (node in nodes) {
                Tasks.await(
                    Wearable.getMessageClient(context)
                        .sendMessage(node.id, WatchLink.NOTE_PATH, text.toByteArray(Charsets.UTF_8)),
                    TIMEOUT_SECONDS, TimeUnit.SECONDS
                )
            }
            Log.i(TAG, "note sent to ${nodes.size} watch(es)")
            text
        }.getOrElse {
            Log.w(TAG, "could not send the note", it)
            null
        }
    }

    /** What was last sent from this phone, for the field to open on. */
    fun current(context: Context): String = PhoneNote.load(context.filesDir)
}
