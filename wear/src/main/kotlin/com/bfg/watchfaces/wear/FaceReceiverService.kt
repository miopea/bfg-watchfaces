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
import kotlinx.coroutines.runBlocking
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

    // No long-lived scope. WearableListenerService callbacks already arrive on
    // a background thread, and the service is torn down as soon as the callback
    // returns -- so work launched into a scope here was being cancelled mid
    // install with "Job was cancelled". Doing it inline keeps the service alive
    // for exactly as long as the face takes to arrive and install.

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        // Logged before anything can fail, because the report people actually
        // bring is "the phone said sent and the watch did nothing" -- and the
        // first thing to establish is whether this service was woken at all.
        Log.i(TAG, "channel opened: ${channel.path}")

        // The token rides on the path, issued by the device next to `pack`.
        // No token, no install: see WatchLink.
        val token = WatchLink.tokenFromChannelPath(channel.path)
        if (token == null) {
            Log.e(TAG, "not our path, or the token would not decode: ${channel.path}")
            return
        }
        val client = Wearable.getChannelClient(this)
        runBlocking {
            // Staged to a private file first. addWatchFace needs a descriptor it
            // can read, not a stream, and a half-received APK must never reach
            // it -- Push rejects a malformed one, but only after we have spent
            // the transfer.
            val staged = File(cacheDir, "incoming-face.apk")
            runCatching {
                // Blocking await, deliberately: this is already on
                // Dispatchers.IO, and it avoids pulling in
                // kotlinx-coroutines-play-services for one call.
                // Read the channel's InputStream to EOF rather than calling
                // receiveFile, and this is the second half of the transport bug.
                //
                // receiveFile's Task completes when the transfer has been SET
                // UP, not when the file has arrived. Awaiting it looked like
                // success, so this service read a 0-byte file, failed to install
                // it, and then closed the channel -- which aborted the phone
                // mid-write with "Channel closed unexpectedly before stream was
                // finished". Both ends were reporting the other one's fault.
                //
                // copyTo blocks until the sender closes its output stream, which
                // is the only unambiguous signal that a face is complete.
                val input = Tasks.await(client.getInputStream(channel))
                val received = input.use { stream ->
                    staged.outputStream().use { out -> stream.copyTo(out) }
                }
                Log.i(TAG, "received $received bytes")
                val result = FaceInstaller.install(
                    this@FaceReceiverService, staged, token,
                    resetComplications = WatchLink.resetsComplications(channel.path)
                )
                report(result)
                // The verdict, and the watch's provider catalog behind it.
                //
                // Complication providers are services on the WATCH, so the
                // phone cannot enumerate them and its picker could only ever
                // offer what the build knew. It rides back on a send because
                // the channel is already open and the watch was reachable by
                // definition -- no second connection, no background job, and
                // the picker still works with the watch out of range.
                val catalog = ProviderCatalog.toJson(
                    ProviderCatalog.installed(this@FaceReceiverService)
                )
                reply(client, channel, lineFor(result) + WatchLink.Report.SEPARATOR + catalog)
            }.onFailure {
                Log.e(TAG, "face did not arrive or would not install", it)
                reply(client, channel, WatchLink.Report.failed(it.message ?: it.javaClass.simpleName))
            }
            staged.delete()
            runCatching { Tasks.await(client.close(channel)) }
        }
    }

    /**
     * Tell the phone what happened, on the channel it already has open.
     *
     * Best effort: a phone on an older build is not reading, and a write that
     * fails must never turn a successful install into a failure. The face is
     * already on the watch by this point.
     */
    private fun reply(client: ChannelClient, channel: ChannelClient.Channel, line: String) {
        runCatching {
            Tasks.await(client.getOutputStream(channel)).use { out ->
                out.write(line.toByteArray(Charsets.UTF_8))
                out.flush()
            }
            Log.i(TAG, "reported to the phone: $line")
        }.onFailure { Log.w(TAG, "could not report back; the face is installed regardless", it) }
    }

    private fun lineFor(result: FaceInstaller.Result): String = when (result) {
        is FaceInstaller.Result.Installed ->
            WatchLink.Report.installed(result.active)
        is FaceInstaller.Result.Unsupported ->
            WatchLink.Report.failed("this watch does not support Watch Face Push")
        is FaceInstaller.Result.Failed ->
            WatchLink.Report.failed(result.cause.message ?: result.cause.javaClass.simpleName)
    }

    private fun report(result: FaceInstaller.Result) = when (result) {
        is FaceInstaller.Result.Installed ->
            Log.i(TAG, "face installed in slot ${result.slotId} (replaced=${result.replaced})")
        is FaceInstaller.Result.Unsupported ->
            Log.w(TAG, "this watch does not support Watch Face Push")
        is FaceInstaller.Result.Failed ->
            Log.e(TAG, "face would not install", result.cause)
    }

    companion object {
        private const val TAG = "BfgFaceReceiver"
    }
}
