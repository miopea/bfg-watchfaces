package com.bfg.watchfaces.wear

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.wear.watchfacepush.WatchFacePushManager
import androidx.wear.watchfacepush.WatchFacePushManagerFactory
import com.bfg.watchfaces.appcore.ActivationConsent
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * The watch half. It receives a face from the device and installs it.
 *
 * Google's guidance describes this app as "primarily a bridge between the phone
 * app and the Watch Face Push APIs", with "not a significant user interface",
 * and that is exactly what this is: no design UI, nothing to browse. The reason
 * it has to exist at all is that [WatchFacePushManager.addWatchFace] takes a
 * `ParcelFileDescriptor` — a handle to a LOCAL file — and there is no
 * "send this to the paired watch" call anywhere in the API. Something on the
 * watch has to be holding the bytes.
 *
 * ## Where the one irreversible ask happens
 *
 * Operator decision 01a049a1-390b-7b50-a5d3-cc082037bb55: the watch asks the
 * first time a face lands. Not at first launch of this app — a companion
 * installed alongside a handheld app is often never deliberately opened, so the
 * single ask could sit unused for weeks or fire cold on a wrist.
 *
 * The device has already explained what is coming
 * (`ActivationConsent.HANDOFF`), so by the time the system dialog appears the
 * person knows what it is for. That split is deliberate: a round watch screen is
 * a poor place to read anything careful.
 */
class FaceReceiverService : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        // The token rides on the path, issued by the device next to `pack`.
        // No token, no install: see ValidationToken.
        val token = ValidationToken.fromChannelPath(channel.path) ?: return
        val client = Wearable.getChannelClient(this)
        scope.launch {
            // Staged to a private file first. addWatchFace needs a descriptor it
            // can read, not a stream, and a half-received APK must never reach
            // it -- Push rejects a malformed one, but only after we have spent
            // the transfer.
            val staged = File(cacheDir, "incoming-face.apk")
            runCatching {
                // Blocking await, deliberately: this is already on
                // Dispatchers.IO, and it avoids pulling in
                // kotlinx-coroutines-play-services for one call.
                Tasks.await(client.receiveFile(channel, android.net.Uri.fromFile(staged), false))
                install(staged, token)
            }.onFailure {
                Log.e(TAG, "face did not arrive or would not install", it)
            }
            staged.delete()
            runCatching { Tasks.await(client.close(channel)) }
        }
    }

    private suspend fun install(apk: File, token: String) {
        if (!WatchFacePushManagerFactory.isSupported()) {
            // Wear OS 6 is a hard floor. Saying so here is cheap; discovering it
            // as an opaque failure after a Bluetooth transfer is not.
            Log.w(TAG, "Watch Face Push is not available on this watch")
            return
        }
        val manager = WatchFacePushManagerFactory.createWatchFacePushManager(this)

        ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            val existing = manager.listWatchFaces()
            // Slots are finite. Replacing our own oldest face is better than
            // failing with ERROR_SLOT_LIMIT_REACHED, which the user cannot act
            // on and which reads as "the app is broken".
            val details = if (existing.remainingSlotCount > 0) {
                manager.addWatchFace(fd, token)
            } else {
                val oldest = existing.installedWatchFaceDetails.firstOrNull() ?: return
                manager.updateWatchFace(oldest.slotId, fd, token)
            }
            onFaceInstalled(details.slotId)
        }
    }

    /**
     * A face has landed. This is the moment the decision names, and the only
     * time this can ever be asked.
     */
    private fun onFaceInstalled(slotId: String) {
        val state = ActivationConsent.load(filesDir)
        if (!ActivationConsent.canAsk(state)) {
            // Already answered. Not an error -- it is the rule working, and it
            // is why canAsk exists rather than a bare permission check: a denial
            // also leaves the permission missing, and re-reading that as
            // "ask again" is how the one shot gets spent on someone who said no.
            return
        }
        startActivity(
            ActivationRequestActivity.intent(this, slotId).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    companion object {
        private const val TAG = "BfgFaceReceiver"
    }
}
