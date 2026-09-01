package com.bfg.watchfaces.mobile

import android.content.Context
import android.util.Log
import com.bfg.watchfaces.appcore.ActivationConsent
import java.io.File

/**
 * What the WATCH last said about the activation permission.
 *
 * Deliberately a separate record from the phone's own [ActivationConsent] file.
 * That state machine guards a one-shot, unrecoverable action ON THE WATCH, and
 * giving it a second writer that means something subtly different is how a
 * guard stops guarding. This is a report about another device, not this one's
 * own state, and the name says so.
 *
 * The phone previously read `ActivationConsent.load(filesDir)` — its own
 * `filesDir`, a file only the watch ever writes — so it was permanently
 * `UNASKED`, `persistentNote` could never return anything, and the screen built
 * to explain a denial was unreachable.
 */
object WatchConsent {

    private const val FILE = "watch-consent.txt"
    private const val TAG = "WatchConsent"

    /** Records the state name the watch reported. Unknown names are ignored. */
    fun record(context: Context, name: String) {
        val state = runCatching { ActivationConsent.State.valueOf(name.trim()) }.getOrNull()
        if (state == null) {
            // A watch on a newer build naming a state this one has never heard
            // of. Keeping the previous answer is better than storing a word
            // this app cannot act on.
            Log.i(TAG, "the watch reported an activation state this build does not know: $name")
            return
        }
        runCatching { File(context.filesDir, FILE).writeText(state.name) }
            .onFailure { Log.w(TAG, "could not record the watch's activation state", it) }
    }

    /**
     * The last state the watch reported, or UNASKED when it never has.
     *
     * UNASKED is the right unknown: it is the state whose note is null, so a
     * phone that has never heard from a watch says nothing rather than guessing
     * at somebody's answer.
     */
    fun load(context: Context): ActivationConsent.State {
        val text = runCatching { File(context.filesDir, FILE).readText().trim() }.getOrNull()
            ?: return ActivationConsent.State.UNASKED
        return runCatching { ActivationConsent.State.valueOf(text) }
            .getOrDefault(ActivationConsent.State.UNASKED)
    }
}
