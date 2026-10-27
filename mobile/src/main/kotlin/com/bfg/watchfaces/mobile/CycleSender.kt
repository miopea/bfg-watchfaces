package com.bfg.watchfaces.mobile

import android.content.Context
import android.util.Log
import com.bfg.watchfaces.appcore.CycleDay
import com.bfg.watchfaces.appcore.CycleSyncPlan
import com.bfg.watchfaces.appcore.WatchLink
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Send the watch the date to count from.
 *
 * ## Four values, and nothing else
 *
 * These are the only things derived from health data that leave the phone. Not
 * the records, not a flow or a symptom: on [WatchLink.CYCLE_START_PATH] one
 * date, and on [WatchLink.CYCLE_DETAIL_PATH] that date, its end, the average
 * gap and a count of periods -- to the wearer's own paired watch, over the Wear
 * OS Data Layer, which is a direct device-to-device channel and not a server.
 *
 * Keeping that literally true is what lets the Play declaration say it. The
 * filed text names exactly those four, and says the watch works out the
 * estimate itself, so the next period's date must never be added here. See
 * `docs/specs/cycle-complication-declarations.md`.
 *
 * ## Why a date rather than the number
 *
 * A number is right for one day. A date is right forever, so nothing has to
 * wake at midnight and the value cannot be stale because the phone was in
 * another room. The watch derives the day whenever it is asked. See
 * [com.bfg.watchfaces.appcore.CycleDay].
 *
 * ## Clearing is a first-class operation
 *
 * Passing null sends an empty payload, and the watch treats that as "no date"
 * and goes back to an em dash. That is not a tidiness feature: if she revokes
 * the permission or deletes her records, a watch holding the last date it ever
 * saw would keep counting up from it forever, which is the worst possible
 * behaviour for this particular value.
 */
object CycleSender {

    private const val TAG = "BfgCycleSender"

    /** Long enough for a paired watch, short enough not to hang a screen. */
    private const val TIMEOUT_SECONDS = 8L

    /**
     * Send [start] to every connected watch, or clear it when null.
     *
     * Returns true when at least one watch took it. Blocking — call it off the
     * main thread. That warning is not decorative: `Tasks.await` on the main
     * thread throws, and in this codebase it has already been swallowed once by
     * a `runCatching` and read as "the watch did not answer".
     */
    fun send(context: Context, start: LocalDate?): Boolean {
        val payload = (start?.toString() ?: "").toByteArray(Charsets.UTF_8)
        return runCatching {
            val nodes = Tasks.await(
                Wearable.getNodeClient(context).connectedNodes,
                TIMEOUT_SECONDS, TimeUnit.SECONDS
            )
            if (nodes.isEmpty()) {
                Log.w(TAG, "no watch connected; will send again when one is")
                return false
            }
            // EVERY node. Two watches paired means both, and picking one
            // arbitrarily makes the feature look broken on whichever she is
            // actually wearing.
            for (node in nodes) {
                Tasks.await(
                    Wearable.getMessageClient(context)
                        .sendMessage(node.id, WatchLink.CYCLE_START_PATH, payload),
                    TIMEOUT_SECONDS, TimeUnit.SECONDS
                )
            }
            // The COUNT of watches, never the date. A logcat line is the one
            // place in this system where her data would sit in plain text on a
            // device whose logs we do not control.
            Log.i(TAG, if (start == null) "cycle start cleared on ${nodes.size} watch(es)"
                       else "cycle start sent to ${nodes.size} watch(es)")
            true
        }.getOrElse {
            Log.w(TAG, "could not send the cycle start", it)
            false
        }
    }

    /**
     * Send the richer facts for the carousel card, on their own path.
     *
     * Separate from [send] so the two degrade independently: a watch that has
     * never heard of this path still gets its day count, and a failure here
     * cannot take the complication down with it. See
     * [WatchLink.CYCLE_DETAIL_PATH].
     *
     * Best effort, and quiet. The complication is the thing that matters; this
     * is a card she may not even have added.
     */
    private fun sendDetail(context: Context, facts: com.bfg.watchfaces.appcore.CycleFacts?) {
        val payload = (facts?.encode() ?: "").toByteArray(Charsets.UTF_8)
        runCatching {
            val nodes = Tasks.await(
                Wearable.getNodeClient(context).connectedNodes,
                TIMEOUT_SECONDS, TimeUnit.SECONDS
            )
            for (node in nodes) {
                Tasks.await(
                    Wearable.getMessageClient(context)
                        .sendMessage(node.id, WatchLink.CYCLE_DETAIL_PATH, payload),
                    TIMEOUT_SECONDS, TimeUnit.SECONDS
                )
            }
            // Counts, never dates. Same rule as everywhere else this value goes.
            Log.i(TAG, "cycle detail ${if (facts == null) "cleared" else "sent"} to ${nodes.size} watch(es)")
        }.onFailure { Log.w(TAG, "could not send the cycle detail", it) }
    }

    /**
     * Refresh the watch on app launch, whatever screen she opens.
     *
     * ## The bug this exists to stop
     *
     * Until this existed, [sync] had exactly ONE caller: [CycleSetup], which is
     * only composed on the Studio tab, and only when the face being edited
     * already has a slot pointing at the cycle source. So the watch was
     * refreshed by an act of design, not by the passage of time.
     *
     * That is survivable for the day count only because the watch holds a DATE
     * and derives the number itself. It is not survivable across a new period:
     * the date it holds is the start of the PREVIOUS one, so the day after her
     * period begins the dial reads "Day 30" instead of "Day 1" and keeps
     * climbing until she happens to open Studio on a cycle face. The carousel
     * card is worse, because "day 9 of your period" stays on screen after the
     * period it refers to has ended.
     *
     * Once per launch is the right frequency for a launch. It was meant to be
     * enough on its own, on the theory that she picks the phone up anyway. It
     * was not: on 2026-10-27 she logged a new period and her tile did not
     * change until someone opened this app. [CycleRefreshWorker] now covers the
     * hours between, when she has granted the background read.
     *
     * ## Why it does not always run
     *
     * Skipped when Health Connect is absent, and when [CycleSyncPlan] says this
     * install has nothing to say. That used to be "not granted and no cached
     * date", which silently excluded the one case where the two devices
     * disagree: a REINSTALL revokes the grant and wipes `filesDir` while the
     * watch keeps counting from the date it was last sent. The rule now spends
     * one push per install to close that. See [CycleSyncPlan].
     *
     * Blocking; call it off the main thread.
     */
    fun syncOnLaunch(context: Context) {
        if (!CycleSource.isSupported(context)) return
        val granted = kotlinx.coroutines.runBlocking { CycleSource.isGranted(context) }
        val decided = CycleSyncPlan.shouldSync(
            granted = granted,
            phoneRemembersDate = CycleDay.load(context.filesDir) != null,
            watchToldThisInstall = CycleSyncPlan.wasTold(context.filesDir)
        )
        if (!decided) return
        sync(context)
    }

    /**
     * Read Health Connect and push whatever it says, including "nothing".
     *
     * The single call the UI makes. It deliberately sends a CLEAR when the read
     * comes back without a date — permission revoked, records deleted, or the
     * tracking app not sharing — because the alternative is a watch counting up
     * from a date that is no longer true.
     *
     * Blocking; callers are on `Dispatchers.IO`.
     */
    fun sync(context: Context): CycleSource.Availability {
        val state = kotlinx.coroutines.runBlocking { CycleSource.read(context) }
        push(context, (state as? CycleSource.Availability.Ready)?.facts)
        return state
    }

    /**
     * Send [facts] to every watch and keep them on the phone; null clears.
     *
     * Split out of [sync] so [CycleRefreshWorker] can send what it read without
     * reading twice, and so the two callers cannot drift in what "sent" means.
     * The worker never passes null: see [CycleSyncPlan.backgroundSends].
     *
     * Blocking; callers are off the main thread.
     */
    fun push(context: Context, facts: com.bfg.watchfaces.appcore.CycleFacts?) {
        val date = facts?.start
        // ONLY on a send that a watch actually took. Marking it on a failure
        // would retire the retry, and a phone launched once with the watch out
        // of range would fall silent for the rest of the install.
        if (send(context, date)) CycleSyncPlan.markTold(context.filesDir)
        sendDetail(context, facts)
        // Kept on the PHONE too, so a preview can draw the real day instead of
        // a stand-in. The operator's watch read "Day 18" while the phone's own
        // preview said "Day 14", which is the preview telling him something the
        // app already knew was wrong.
        //
        // Through CycleState rather than straight to disk: writing the file
        // alone lost a race with the preview, which had already run and had no
        // key that would change. See CycleState.
        CycleState.set(context, facts)
    }
}
