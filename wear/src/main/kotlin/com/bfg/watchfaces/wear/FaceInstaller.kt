package com.bfg.watchfaces.wear

import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.wear.watchfacepush.WatchFacePushManagerFactory
import com.bfg.watchfaces.appcore.ActivationConsent
import com.bfg.watchfaces.appcore.InstallPlan
import java.io.File

/**
 * Installing a face, with no opinion about how its bytes arrived.
 *
 * This was inside [FaceReceiverService], reachable only from
 * `onChannelOpened`. That welded the two genuinely novel calls in this project
 * — `addWatchFace` and the slot handling around it — to a Data Layer channel,
 * which needs a phone, which needs a pairing. So the part most worth exercising
 * was the part hardest to reach.
 *
 * Nothing about Watch Face Push actually requires a channel: `addWatchFace`
 * takes a descriptor to a local file and does not care who wrote it. Splitting
 * the two apart costs one object and makes the install reachable from a debug
 * harness on a watch with no phone at all. See `DECISIONS.md` 2026-08-29.
 */
object FaceInstaller {

    /** What happened, in enough detail for a caller to log or report it. */
    sealed interface Result {
        /**
         * @param active whether the watch actually SWITCHED to this face.
         *   False is normal, not an error: `setWatchFaceAsActive` succeeds once
         *   per app install. The phone has to be able to say which happened.
         */
        data class Installed(
            val slotId: String,
            val replaced: Boolean,
            val active: Boolean,
            /**
             * Anything unusual about HOW it installed, for the phone to relay.
             *
             * Empty on the ordinary path. Non-empty when the in-place update
             * failed and the remove-and-add fallback carried it — which is a
             * success the wearer should not notice and an engineer must.
             */
            val note: String = ""
        ) : Result
        data object Unsupported : Result
        /**
         * @param slots what `listWatchFaces` saw when the attempt began.
         *   Carried because the phone is the ONLY place this can be read from —
         *   Bluetooth debugging was removed in Wear OS 3, the Pixel Watch has no
         *   data port, and Wi-Fi debugging needs a network the operator may not
         *   have. Without it, "it failed" is the whole of what the watch can say,
         *   which is exactly the position the PHONE was in for a full day.
         */
        data class Failed(val cause: Throwable, val slots: String = "") : Result
    }

    /**
     * Put [apk] on the watch under [token], and ask about activation if this is
     * the first face to land.
     */
    /**
     * @param resetComplications remove and re-add rather than updating in
     *   place, so `DefaultProviderPolicy` applies and the design's complications
     *   are what appear. Costs one `setWatchFaceAsActive` call, which is a
     *   finite resource, so the SENDER decides -- it is the only side that knows
     *   whether the complications actually changed.
     */
    suspend fun install(
        caller: Context,
        apk: File,
        token: String,
        resetComplications: Boolean = false
    ): Result {
        // The application context, never the caller's own. WatchFacePushManager
        // binds to a system service to do anything at all -- listWatchFaces is
        // already an IPC -- and a BroadcastReceiver's context throws
        // ReceiverCallNotAllowedException on bindService. Observed on a Wear OS
        // 6 emulator, 2026-08-29: the failure names bindService and not Push, so
        // it reads like a bug in the caller rather than a context requirement.
        val context = caller.applicationContext
        // Filled in once the slots are read; empty if we never got that far.
        var slotPicture = ""
        activationNote = ""
        // Non-empty only if the in-place update was refused and the fallback ran.
        var fallback = ""

        if (!WatchFacePushManagerFactory.isSupported()) {
            // Wear OS 6 is a hard floor. Saying so here is cheap; discovering it
            // as an opaque failure after a Bluetooth transfer is not.
            Log.w(TAG, "Watch Face Push is not available on this watch")
            return Result.Unsupported
        }
        val manager = WatchFacePushManagerFactory.createWatchFacePushManager(context)

        return runCatching {
            ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                val existing = manager.listWatchFaces()
                Log.i(TAG, "slots: ${existing.remainingSlotCount} free, ${existing.installedWatchFaceDetails.size} used")
                // Held so a FAILURE can carry it back to the phone. The phone is
                // the only place this is readable from -- see Result.Failed.
                slotPicture = "free=${existing.remainingSlotCount} " +
                    "ours=${existing.installedWatchFaceDetails.size} " +
                    "pkgs=[${existing.installedWatchFaceDetails.joinToString(",") { it.packageName }}] " +
                    // Everything a failure needs, gathered BEFORE the call that
                    // might fail. listWatchFaces having got this far already
                    // proves the service is reachable and the push permission
                    // is held, so a later "could not access the service" is
                    // about something else -- which is exactly the reading that
                    // cost a release.
                    "apk=${apk.length()} " + permissions(context)
                // Slots are finite. Replacing our own oldest face is better than
                // failing with ERROR_SLOT_LIMIT_REACHED, which the user cannot act
                // on and which reads as "the app is broken".
                // REMOVE then ADD, rather than updateWatchFace.
                //
                // Operator decision, 2026-08-30. `updateWatchFace` keeps the
                // slot, and the watch keeps the complication data sources
                // ASSIGNED to that slot -- `DefaultProviderPolicy` only fills a
                // slot nothing has been assigned to. So with
                // `isCustomizable="TRUE"`, which is the only way a wearer can
                // ever pick weather or Google Health, every complication choice
                // made in the app after the first install was silently
                // discarded. Proven on a watch: a face declaring
                // battery/heart/steps/day-of-week rendered the assignments of a
                // build before it, unchanged across three different faces.
                //
                // A fresh slot has nothing assigned, so the policy applies and
                // the app's design is what appears. The cost is real and was
                // accepted deliberately: any complication the wearer changed ON
                // the watch is reset by the next send, and this spends an
                // addWatchFace call every time.
                val ours = existing.installedWatchFaceDetails.firstOrNull()
                // The DECISION comes from :appcore, not from conditions written
                // here. These branches were only ever reachable on a wrist, and
                // two of them cost real damage before anything could test them.
                // InstallPlanTest exercises every one in milliseconds.
                //
                // The `ours != null` guards below are for the COMPILER, so the
                // details smart-cast; the choice is InstallPlan's alone.
                val route = InstallPlan.route(
                    oursSlotId = ours?.slotId,
                    resetComplications = resetComplications,
                    freeSlots = existing.remainingSlotCount
                )
                if (ours != null && route is InstallPlan.Route.UpdateInPlace) {
                    // ASK BEFORE, because afterwards the answer is wrong.
                    //
                    // `isWatchFaceActive` returns FALSE in the moments after
                    // `updateWatchFace`, even when the face is demonstrably on
                    // the wrist -- measured on a Pixel Watch 5, 2026-09-01,
                    // with `dumpsys wallpaper` showing
                    // `DeclarativeWatchFaceRuntime0` rendering our face while
                    // this same call reported it inactive. The runtime is still
                    // reloading and has not re-pointed yet.
                    //
                    // Believing it cost twice over: an activation attempt spent
                    // on a face already being worn, out of a limit of roughly
                    // one per install; and OK_NOT_ACTIVE reported to somebody
                    // whose watch was at that moment showing the face they had
                    // just sent.
                    //
                    // Before the update there is no race, and it is the
                    // question that matters anyway: `updateWatchFace` INHERITS
                    // active status, so a face already on the wrist stays there
                    // and needs no activation call at all.
                    val wasWorn = runCatching {
                        manager.isWatchFaceActive(ours.packageName)
                    }.getOrElse { false }
                    // Nothing about the complications changed, so keep the slot.
                    // This preserves anything the wearer picked on the watch
                    // AND costs no activation call, because the face is never
                    // deactivated.
                    // FALL BACK rather than fail.
                    //
                    // updateWatchFace returned ERROR_UNKNOWN on a Pixel Watch 5
                    // on 2026-09-01 while listWatchFaces on the same manager
                    // had just succeeded -- so the service was reachable and
                    // the permission held, and "could not be accessed", which
                    // is how the library words code 1, was not what happened.
                    //
                    // Remove-and-add is not a guess at the cause: it is the
                    // path the operator already chose on 2026-08-30 for the
                    // complication-assignment problem, reachable today only
                    // when the sender asks for it. Trying it here answers
                    // which of the two calls is refused AND gets the wearer
                    // their face, instead of spending another release to learn
                    // one fact.
                    val updated = runCatching { manager.updateWatchFace(ours.slotId, fd, token) }
                    val details = updated.getOrElse { cause ->
                        // NEVER destroy the face somebody is WEARING to work
                        // around a failure.
                        //
                        // This is the invariant the first version of this
                        // fallback broke. Remove-and-add deletes the installed
                        // face before adding its replacement, and deleting the
                        // ACTIVE one deactivates it -- while
                        // `setWatchFaceAsActive` is spendable once per app
                        // install, so once it has been used there is nothing
                        // that can switch it back. The wearer is left on the
                        // system default with no way back except reinstalling
                        // the watch app.
                        //
                        // That trades a recoverable failure (a send that did
                        // not land; try again) for an unrecoverable one (the
                        // face is gone and the app cannot restore it). Measured
                        // on the operator's Pixel Watch 5, 2026-09-01: the
                        // fallback "worked" and cost him his watch face.
                        //
                        // So the fallback is only safe on a face nobody is
                        // wearing. If ours is on the wrist, report the refusal
                        // and leave it alone.
                        val wearingOurs = runCatching { manager.isWatchFaceActive(ours.packageName) }
                            .getOrElse { false }
                        if (!InstallPlan.mayRemoveAfterFailedUpdate(wearingOurs)) {
                            Log.e(TAG, "updateWatchFace failed and ours is the active face; " +
                                "refusing to remove it", cause)
                            throw cause
                        }
                        fallback = "updateWatchFace refused (${short(cause)}); removed and re-added"
                        Log.w(TAG, "updateWatchFace failed; falling back to remove+add", cause)
                        runCatching { manager.removeWatchFace(ours.slotId) }
                            .onFailure { Log.w(TAG, "could not remove the old face", it) }
                        // A FRESH descriptor. The failed call may have consumed
                        // the offset on this one, and an add reading from the
                        // middle of an APK is a malformed-package error that
                        // would look like a second, unrelated bug.
                        ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY)
                            .use { fresh -> manager.addWatchFace(fresh, token) }
                    }
                    Log.i(TAG, "updated slot ${ours.slotId}${if (fallback.isEmpty()) " in place" else " via remove+add"}")
                    // Still ask to activate. Dropping this was a regression:
                    // before, EVERY install tried, and a face sent to a watch
                    // wearing something else silently stopped switching.
                    // Already worn means already done: no call, nothing spent.
                    val asked = if (!InstallPlan.spendActivation(route, wasWorn)) {
                        Log.i(TAG, "ours was already on the wrist; the update keeps it there")
                        true
                    } else {
                        onFaceInstalled(context, details.slotId, details.packageName)
                    }
                    // ASK THE WATCH, rather than believing the call that failed.
                    //
                    // `setWatchFaceAsActive` works ONCE per install -- Google's
                    // reference says so in as many words -- so from the second
                    // send onward it throws, and this used to report
                    // OK_NOT_ACTIVE and tell the wearer to long-press and pick
                    // the face. Reported from a wrist: "the text says something
                    // about long press and set it, which doesn't need to be
                    // done." It did not: an update whose package name differs
                    // inherits active status by itself, so the face had already
                    // switched and the app was describing a failure that had no
                    // consequence.
                    //
                    // `isWatchFaceActive` costs nothing and is the only thing
                    // here that actually knows.
                    val active = asked || runCatching {
                        manager.isWatchFaceActive(details.packageName)
                    }.getOrElse { false }
                    Result.Installed(
                        details.slotId, replaced = true, active = active,
                        // The picture rides back on a SUCCESS too. Every
                        // diagnostic in this file so far was reachable only by
                        // failing, so the healthy shape was never once observed
                        // and there was nothing to compare a failure against.
                        note = listOf(fallback, activationNote, slotPicture)
                            .filter { it.isNotEmpty() }.joinToString(" | ")
                    )
                } else if (ours != null && route is InstallPlan.Route.ReplaceOurs) {
                    // Whether OUR face is the one on the wrist decides if the
                    // new one has to be activated. Ask BEFORE removing it,
                    // because afterwards there is nothing left to ask about.
                    val wasActive = runCatching { manager.isWatchFaceActive(ours.packageName) }
                        .getOrElse { false }
                    Log.i(TAG, "replacing slot ${ours.slotId} (active=$wasActive)")
                    val removed = runCatching { manager.removeWatchFace(ours.slotId) }
                        .onFailure { Log.w(TAG, "could not remove the old face", it) }
                        .isSuccess
                    // The dangerous moment in the system: the old face is gone
                    // and the new one is not in yet. If the add fails here the
                    // wearer is left with NO face of ours -- which looks like
                    // "I sent it and it is not even in the list". Say that,
                    // rather than surfacing a generic failure with no hint
                    // that something was deleted.
                    val details = runCatching { manager.addWatchFace(fd, token) }
                        .getOrElse { cause ->
                            Log.e(TAG, "removed=$removed but the new face could not be added; " +
                                "this watch now has no face from this app", cause)
                            throw cause
                        }
                    // Only re-activate when we displaced the active face.
                    // setWatchFaceAsActive has an undocumented attempt limit
                    // this project has already hit, so it is not spent on a
                    // face that was sitting in the picker anyway.
                    val asked = if (InstallPlan.spendActivation(route, wasActive)) {
                        onFaceInstalled(context, details.slotId, details.packageName)
                    } else false
                    // Same as the update branch: the activation call is not the
                    // authority on whether the face is on the wrist.
                    val active = asked || runCatching {
                        manager.isWatchFaceActive(details.packageName)
                    }.getOrElse { false }
                    Result.Installed(details.slotId, replaced = true, active = active)
                } else if (route is InstallPlan.Route.AddFresh) {
                    val details = manager.addWatchFace(fd, token)
                    val asked = onFaceInstalled(context, details.slotId, details.packageName)
                    val active = asked || runCatching {
                        manager.isWatchFaceActive(details.packageName)
                    }.getOrElse { false }
                    Result.Installed(details.slotId, replaced = false, active = active)
                } else {
                    // Slots are FULL and none of them is ours to replace.
                    //
                    // This check was in the original and a rewrite dropped it,
                    // which turned a reportable situation into a silent one:
                    // addWatchFace was called into a full slot, failed with
                    // ERROR_SLOT_LIMIT_REACHED, and the phone -- which only
                    // learns whether the BYTES arrived -- still said "Sent".
                    // Every send appeared to work and no face ever appeared.
                    //
                    // It happens when a slot holds a face this app can no
                    // longer see: listWatchFaces reports the faces THIS install
                    // pushed, so a reinstall can leave one occupying the only
                    // slot with nothing left to attribute it to.
                    Log.e(TAG, "no free slot (${existing.remainingSlotCount}) and " +
                        "none of ours to replace; the face cannot be installed")
                    // The message names the ONLY way out, because there is no
                    // API for one: `removeWatchFace` needs a slotId this app
                    // owns, and the whole problem is that it owns none. The
                    // wearer cannot be left to work this out -- one did, by
                    // uninstalling the watch app on a hunch, after an evening
                    // of sends that all reported success.
                    Result.Failed(IllegalStateException(InstallPlan.NO_SLOT_MESSAGE))
                }
            }
        }.getOrElse { Result.Failed(it, slotPicture) }
    }

    /**
     * A face has landed. This is the moment operator decision
     * 01a049a1-390b-7b50-a5d3-cc082037bb55 names, and the only time activation
     * can ever be asked.
     *
     * It posts a notification rather than opening the dialog directly, because
     * Android will not let this context open anything: see [ActivationPrompt].
     */
    /**
     * Both permissions, named separately, because conflating them cost a release.
     *
     * `PUSH_WATCH_FACES` is install-time and gates putting a face on the watch.
     * `SET_PUSHED_WATCH_FACE_AS_ACTIVE` is the runtime, ask-once one and gates
     * switching to it. A build shipped reading the first where it meant the
     * second, and the check silently answered true forever.
     */
    private fun permissions(context: Context): String {
        fun held(name: String) =
            context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED
        return "push=${held("com.google.wear.permission.PUSH_WATCH_FACES")} " +
            "activate=${held(ActivationConsent.PERMISSION)}"
    }

    /** Class and message, short enough to ride back on a channel reply. */
    private fun short(cause: Throwable): String =
        "${cause.javaClass.simpleName}: ${cause.message ?: "(no message)"}"

    /**
     * Why the last activation attempt failed, for the reply line.
     *
     * Set by [onFaceInstalled] and read by [install] on the same call, which is
     * serialised by the channel -- one face is handled at a time.
     */
    private var activationNote = ""

    private fun onFaceInstalled(
        context: Context,
        slotId: String,
        packageName: String
    ): Boolean {
        val state = Activation.state(context)

        // ALREADY WEARING IT? Then there is nothing to switch, and switching is
        // not free.
        //
        // This is the bug behind "I send a face and nothing happens".
        // `setWatchFaceAsActive` has a hard attempt limit per app install --
        // `SET_ACTIVE_MAXIMUM_ATTEMPTS_REACHED_ERROR`, read out of wear-sdk.jar
        // as code 2 -- and this was calling it on EVERY send, including the
        // overwhelming majority where our face was already on the wrist and the
        // call could not change anything.
        //
        // So a scarce, non-renewable resource was spent once per send until it
        // ran out, after which no face this install pushed could be switched on
        // again. Measured on the operator's Pixel Watch 5, 2026-09-01: the face
        // installed correctly every time and the watch stayed on a Google face,
        // because the only call that could move it had been exhausted days
        // earlier by sends that never needed it.
        //
        // The limit is not the problem. Spending it with nothing to buy is.
        val manager = WatchFacePushManagerFactory.createWatchFacePushManager(context)
        val worn = runCatching {
            kotlinx.coroutines.runBlocking { manager.isWatchFaceActive(packageName) }
        }.getOrElse { false }
        if (worn) {
            Log.i(TAG, "already wearing $packageName; not spending an activation attempt")
            return true
        }

        // Already granted: switch to it. This was the gap.
        //
        // setWatchFaceAsActive was only ever called from
        // ActivationRequestActivity, at the instant permission was granted --
        // so the FIRST face switched and every later one installed silently and
        // sat in the picker. From outside that is "I sent a face and nothing
        // happened", which is indistinguishable from the transport failing, and
        // is exactly what it looked like on real hardware once the transport
        // finally worked.
        if (ActivationConsent.canActivate(state)) {
            return runCatching {
                kotlinx.coroutines.runBlocking { manager.setWatchFaceAsActive(slotId) }
            }
                .onSuccess { Log.i(TAG, "switched to the new face (slot $slotId)") }
                .onFailure {
                    // NAMED on the reply line, not just in a log nobody could
                    // reach. This failure was invisible for days: the face
                    // installed, the send reported success, and the only
                    // evidence that activation had died was the watch quietly
                    // staying on somebody else's face.
                    activationNote = "setWatchFaceAsActive: ${short(it)}"
                    Log.e(TAG, "installed, but could not switch to it", it)
                }
                .isSuccess
        }

        if (!ActivationConsent.canAsk(state)) {
            // Asked once and refused. Nothing can reopen that, by design -- a
            // denial also leaves the permission missing, and re-reading that as
            // "ask again" is how the one shot gets spent on someone who said no.
            // The face stays in the picker for them to choose.
            Log.i(TAG, "activation was refused; installed but not switched")
            return false
        }
        // Asking is not switching: the prompt is a notification the wearer has
        // to act on, so the face is NOT active when this returns.
        ActivationPrompt.show(context, slotId)
        return false
    }

    private const val TAG = "BfgFaceInstaller"
}
