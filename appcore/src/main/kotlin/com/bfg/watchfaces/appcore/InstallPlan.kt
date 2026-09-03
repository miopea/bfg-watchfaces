package com.bfg.watchfaces.appcore

/**
 * The DECISIONS an install makes, separated from the calls that carry them out.
 *
 * ## Why this is not in `FaceInstaller` any more
 *
 * Every expensive bug in this project has lived between "the bytes left the
 * phone" and "the face is on the watch", and none of that can be exercised
 * here: the emulators cannot pair, so `FaceInstaller` first runs on a wrist.
 *
 * But the two that cost the most were not transport at all. They were
 * decisions:
 *
 * - a fallback removed the face the wearer was WEARING, and
 *   `setWatchFaceAsActive` is spendable once per install, so nothing could put
 *   it back. Measured on a Pixel Watch 5, 2026-09-01.
 * - activation was spent on every send, exhausting that same budget, and the
 *   app then told somebody to long-press and pick a face that was already on
 *   their wrist.
 *
 * Neither needed a watch to catch. They were unreachable only because they sat
 * inside a `runCatching` around a system service and a `ParcelFileDescriptor`.
 * Pulled out here they are ordinary functions over ordinary values, they run in
 * milliseconds with no Android at all, and `InstallPlanTest` pins them.
 *
 * This does NOT make the transport testable, and nothing here should be read as
 * claiming it does. It makes the choices the transport carries out testable.
 *
 * ## Why `:appcore`
 *
 * Same reason [ActivationConsent] is here: it is a RULE both sides depend on
 * rather than a piece of either app, and the one-shot activation budget these
 * decisions protect is the same budget that file guards.
 */
object InstallPlan {

    /** How a face gets onto the watch, given what is already there. */
    sealed interface Route {
        /**
         * Keep our slot and write over it.
         *
         * Preserves any complication the wearer assigned ON the watch, and
         * costs no activation call because the face is never deactivated.
         */
        data class UpdateInPlace(val slotId: String) : Route

        /**
         * Delete our face and add the new one.
         *
         * A fresh slot has nothing assigned to it, so the face's own
         * `DefaultProviderPolicy` applies and the app's design is what appears.
         * The cost, accepted deliberately on 2026-08-30: anything the wearer
         * changed on the watch is reset, and an `addWatchFace` call is spent.
         */
        data class ReplaceOurs(val slotId: String) : Route

        /** Nothing of ours is installed and there is room. */
        data object AddFresh : Route

        /**
         * Slots are full and none of them is ours.
         *
         * Unrecoverable through the API: `removeWatchFace` needs a slotId this
         * app owns and the whole problem is that it owns none. Reachable when a
         * reinstall leaves a face occupying the only slot with nothing left to
         * attribute it to.
         */
        data object NoSlotAvailable : Route
    }

    /**
     * Which route an install takes.
     *
     * [oursSlotId] is the slot holding a face this install can attribute to
     * itself, or null. [freeSlots] is `remainingSlotCount`, which is 1 on some
     * watches and 0 more often than anybody expects.
     */
    fun route(oursSlotId: String?, resetComplications: Boolean, freeSlots: Int): Route = when {
        oursSlotId != null && !resetComplications -> Route.UpdateInPlace(oursSlotId)
        oursSlotId != null -> Route.ReplaceOurs(oursSlotId)
        freeSlots > 0 -> Route.AddFresh
        else -> Route.NoSlotAvailable
    }

    /**
     * Whether a failed update may fall back to removing our face and re-adding.
     *
     * **False whenever the wearer is wearing it, and that is the invariant this
     * whole file exists for.** Remove-and-add deletes the installed face before
     * adding its replacement, and deleting the ACTIVE one deactivates it —
     * while `setWatchFaceAsActive` is spendable once per app install, so once
     * that is gone nothing can switch it back. The wearer is left on the system
     * default with no way out but reinstalling the watch app.
     *
     * That trades a RECOVERABLE failure — a send that did not land, try again —
     * for an UNRECOVERABLE one. The first version of this fallback made exactly
     * that trade and cost the operator his watch face.
     */
    fun mayRemoveAfterFailedUpdate(wearingOurs: Boolean): Boolean = !wearingOurs

    /**
     * Whether to spend an activation call, given the route and whether our face
     * was on the wrist BEFORE anything was written.
     *
     * The asymmetry between the two replace-ish routes is deliberate and is the
     * part that is easy to get backwards:
     *
     * - [Route.UpdateInPlace] INHERITS active status. A face already being worn
     *   stays worn, so asking would spend the one-per-install budget to achieve
     *   nothing. Spend only when it was NOT already there.
     * - [Route.ReplaceOurs] destroys the slot, which deactivates whatever was
     *   in it. So spend precisely when we displaced the active face, and not on
     *   a face that was sitting unused in the picker.
     *
     * The question must be asked BEFORE the write in both cases:
     * `isWatchFaceActive` returns false in the moments after `updateWatchFace`
     * even with the face demonstrably on the wrist — measured 2026-09-01, with
     * `dumpsys wallpaper` showing our face rendering while the call said no.
     */
    fun spendActivation(route: Route, faceWasOnWrist: Boolean): Boolean = when (route) {
        is Route.UpdateInPlace -> !faceWasOnWrist
        is Route.ReplaceOurs -> faceWasOnWrist
        Route.AddFresh -> true
        Route.NoSlotAvailable -> false
    }

    /**
     * What to tell somebody whose watch has no slot this app can use.
     *
     * The message names the only way out because there is no API for one, and a
     * wearer cannot be left to work that out — one did, by uninstalling the
     * watch app on a hunch, after an evening of sends that all reported success.
     */
    const val NO_SLOT_MESSAGE =
        "the watch's face slot is full and holds nothing this app can replace. " +
            "Reinstall BFG Watch Faces on the watch, then send again."
}
