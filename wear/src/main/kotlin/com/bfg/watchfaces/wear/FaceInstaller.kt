package com.bfg.watchfaces.wear

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.wear.watchfacepush.WatchFacePushManagerFactory
import com.bfg.watchfaces.appcore.ActivationConsent
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
        data class Installed(val slotId: String, val replaced: Boolean) : Result
        data object Unsupported : Result
        data class Failed(val cause: Throwable) : Result
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
                if (ours != null && !resetComplications) {
                    // Nothing about the complications changed, so keep the slot.
                    // This preserves anything the wearer picked on the watch
                    // AND costs no activation call, because the face is never
                    // deactivated.
                    val details = manager.updateWatchFace(ours.slotId, fd, token)
                    Log.i(TAG, "updated slot ${ours.slotId} in place")
                    Result.Installed(details.slotId, replaced = true)
                } else if (ours != null) {
                    // Whether OUR face is the one on the wrist decides if the
                    // new one has to be activated. Ask BEFORE removing it,
                    // because afterwards there is nothing left to ask about.
                    val wasActive = runCatching { manager.isWatchFaceActive(ours.packageName) }
                        .getOrElse { false }
                    Log.i(TAG, "replacing slot ${ours.slotId} (active=$wasActive)")
                    runCatching { manager.removeWatchFace(ours.slotId) }
                        .onFailure { Log.w(TAG, "could not remove the old face; adding anyway", it) }
                    val details = manager.addWatchFace(fd, token)
                    // Only re-activate when we displaced the active face.
                    // setWatchFaceAsActive has an undocumented attempt limit
                    // this project has already hit, so it is not spent on a
                    // face that was sitting in the picker anyway.
                    if (wasActive) onFaceInstalled(context, details.slotId)
                    Result.Installed(details.slotId, replaced = true)
                } else if (existing.remainingSlotCount > 0) {
                    val details = manager.addWatchFace(fd, token)
                    onFaceInstalled(context, details.slotId)
                    Result.Installed(details.slotId, replaced = false)
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
                    Result.Failed(
                        IllegalStateException(
                            "the watch has no free watch face slot, and none of the " +
                                "faces in it were installed by this app"
                        )
                    )
                }
            }
        }.getOrElse { Result.Failed(it) }
    }

    /**
     * A face has landed. This is the moment operator decision
     * 01a049a1-390b-7b50-a5d3-cc082037bb55 names, and the only time activation
     * can ever be asked.
     *
     * It posts a notification rather than opening the dialog directly, because
     * Android will not let this context open anything: see [ActivationPrompt].
     */
    private fun onFaceInstalled(context: Context, slotId: String) {
        val state = ActivationConsent.load(context.filesDir)

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
            runCatching {
                val manager = WatchFacePushManagerFactory.createWatchFacePushManager(context)
                kotlinx.coroutines.runBlocking { manager.setWatchFaceAsActive(slotId) }
            }
                .onSuccess { Log.i(TAG, "switched to the new face (slot $slotId)") }
                .onFailure { Log.e(TAG, "installed, but could not switch to it", it) }
            return
        }

        if (!ActivationConsent.canAsk(state)) {
            // Asked once and refused. Nothing can reopen that, by design -- a
            // denial also leaves the permission missing, and re-reading that as
            // "ask again" is how the one shot gets spent on someone who said no.
            // The face stays in the picker for them to choose.
            Log.i(TAG, "activation was refused; installed but not switched")
            return
        }
        ActivationPrompt.show(context, slotId)
    }

    private const val TAG = "BfgFaceInstaller"
}
