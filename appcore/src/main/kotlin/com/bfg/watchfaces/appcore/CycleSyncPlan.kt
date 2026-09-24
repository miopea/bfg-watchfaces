package com.bfg.watchfaces.appcore

import java.io.File

/**
 * Whether a launch needs to tell the watch anything about the cycle day.
 *
 * ## The bug this exists to stop
 *
 * The phone used to decide this with one line:
 *
 * ```
 * if (!granted && CycleDay.load(context.filesDir) == null) return
 * ```
 *
 * The cached date was standing in for "she has used this feature". That proxy
 * is correct for a revoke — the date is still on disk, so the sync runs and
 * clears the watch — and it is WRONG for a reinstall, which is the one case
 * where the two devices disagree about what has happened.
 *
 * Uninstalling the phone app revokes the Health Connect grant AND wipes
 * `filesDir`. The watch is not involved in either and keeps the start date it
 * was last sent. So on the next launch the phone sees no permission and no
 * cached date, concludes she never turned this on, and returns without saying
 * anything — while her wrist goes on counting from a date the phone has
 * forgotten. [CycleSender][com.bfg.watchfaces.appcore.CycleDay] already names
 * that as the worst outcome this feature has: "a watch holding the last date
 * it ever saw would keep counting up from it forever".
 *
 * Found on 2026-09-24 on the wearer's own phone, after a reinstall done for an
 * unrelated reason (getting her onto the internal testing track).
 *
 * ## Why not simply sync every time
 *
 * Because `onResume` is often, and the work is not free: a node query and two
 * Data Layer messages, each with an eight-second timeout, for someone who may
 * never have opened the cycle feature at all. Health Connect ships with
 * Android, so "supported" is nearly everybody.
 *
 * So the rule keeps the quiet steady state and buys the correctness with one
 * flag: **an install that has never told the watch anything tells it once.**
 * After that first launch a non-user is silent forever, and a reinstall is not
 * a non-user — it is an install that has never spoken to a watch which may
 * still be holding her last period.
 *
 * ## The flag is per install, deliberately
 *
 * It lives in `filesDir` beside the date, so an uninstall clears it. That is
 * the entire mechanism: the thing that creates the problem is the same thing
 * that arms the fix.
 */
object CycleSyncPlan {

    /** Where the phone records that it has spoken to a watch at least once. */
    private fun file(root: File): File = File(root, "cycle-told.txt")

    /**
     * Whether to read Health Connect and push the result to the watch.
     *
     * @param granted she has given the Health Connect read.
     * @param phoneRemembersDate `filesDir` still holds a start date, which means
     *   this install has had the feature on even if the permission is now gone.
     * @param watchToldThisInstall this install has already successfully pushed
     *   something — a date or a clear — to at least one watch.
     *
     * The first two are the old rule and both still stand on their own. The
     * third is the new one, and it is the reinstall case: nothing granted,
     * nothing remembered, nothing yet said.
     */
    fun shouldSync(
        granted: Boolean,
        phoneRemembersDate: Boolean,
        watchToldThisInstall: Boolean
    ): Boolean = granted || phoneRemembersDate || !watchToldThisInstall

    /**
     * Whether this install has ever got a cycle payload onto a watch.
     *
     * Unreadable reads as "no", like every other bit of state in this feature.
     * Being wrong in that direction costs one redundant sync; being wrong the
     * other way costs a false day count on a wrist.
     */
    fun wasTold(root: File): Boolean = runCatching { file(root).isFile }.getOrDefault(false)

    /**
     * Record that a watch took a cycle payload.
     *
     * Called only when a send actually reached a watch. Marking it on a failed
     * send would retire the retry: a phone launched once with the watch out of
     * range would fall silent for the rest of the install, which is the bug
     * again with an extra step.
     */
    fun markTold(root: File) {
        runCatching { file(root).writeText("1") }
    }
}
