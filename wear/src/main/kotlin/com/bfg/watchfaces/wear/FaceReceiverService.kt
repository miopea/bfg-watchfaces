package com.bfg.watchfaces.wear

import android.util.Log
import androidx.wear.watchfacepush.WatchFacePushManager
import com.bfg.watchfaces.appcore.WatchLink
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
 * The watch half. It receives a face from the device and hands it to
 * [FaceInstaller].
 *
 * This class is now only the transport: read the token off the channel path,
 * stage the bytes, delegate. What `addWatchFace` does with them lives in
 * [FaceInstaller], which does not depend on a channel and so can be driven on
 * a watch with no phone paired to it.
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
        // No token, no install: see WatchLink.
        val token = WatchLink.tokenFromChannelPath(channel.path) ?: return
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
                report(FaceInstaller.install(this@FaceReceiverService, staged, token))
            }.onFailure {
                Log.e(TAG, "face did not arrive or would not install", it)
            }
            staged.delete()
            runCatching { Tasks.await(client.close(channel)) }
        }
    }

    private fun report(result: FaceInstaller.Result) = when (result) {
        is FaceInstaller.Result.Installed ->
            Log.i(TAG, "face installed in slot ${result.slotId} (replaced=${result.replaced})")
        is FaceInstaller.Result.Unsupported ->
            Log.w(TAG, "this watch does not support Watch Face Push")
        is FaceInstaller.Result.Failed ->
            Log.e(TAG, "face would not install", result.cause)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    companion object {
        private const val TAG = "BfgFaceReceiver"
    }
}
