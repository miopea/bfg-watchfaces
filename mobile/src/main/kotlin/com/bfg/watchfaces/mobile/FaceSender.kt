package com.bfg.watchfaces.mobile

import android.content.Context
import android.net.Uri
import android.util.Log
import com.bfg.watchfaces.appcore.WatchLink
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import java.io.File

/**
 * Sends a built face to the watch.
 *
 * The device half of the pipeline `docs/SPEC.md` describes:
 *
 * ```text
 * params -> pack builds APK -> validator issues token -> Data Layer -> addWatchFace()
 * ```
 *
 * Everything up to the arrow into the Data Layer happens here; everything after
 * it happens in `:wear`. This class owns the arrow.
 *
 * ## Why the token rides on the channel path
 *
 * The token is a statement about one specific APK, and it is issued next to
 * `pack` — on this device, which is what built the bytes. Sending it as a
 * separate message would mean two things arriving that have to be correlated and
 * can arrive out of order. One channel carries both.
 *
 * ## Why finding the watch app comes first
 *
 * Google's guidance: the phone app should detect the absence of the watch app
 * through `CapabilityClient` and offer to install it. Without that app nothing
 * can be received, and the failure mode is the worst available — the face simply
 * never appears and there is nothing to tell the person why. That is the reason
 * the first handoff step is about the companion app rather than about the face.
 */
object FaceSender {

    private const val TAG = "BfgFaceSender"

    /** What the device found when it went looking for somewhere to send a face. */
    sealed interface Target {
        /** A watch with our app on it. */
        data class Ready(val nodeId: String, val name: String) : Target

        /** A watch is paired, but our app is not on it. Offer to install. */
        data class AppMissing(val name: String) : Target

        /** Nothing paired at all. */
        data object NoWatch : Target
    }

    /**
     * Where a face could go right now.
     *
     * Blocking, and meant to be called off the main thread. Returning a
     * [Target] rather than a boolean is deliberate: "cannot send" has two very
     * different causes and they need different words in front of the user, which
     * a boolean would flatten into one unhelpful message.
     */
    fun findTarget(context: Context): Target {
        val capabilityClient = Wearable.getCapabilityClient(context)
        val info: CapabilityInfo = Tasks.await(
            capabilityClient.getCapability(WatchLink.CAPABILITY, CapabilityClient.FILTER_REACHABLE)
        )
        val withApp: Node? = info.nodes.firstOrNull { it.isNearby }
        if (withApp != null) return Target.Ready(withApp.id, withApp.displayName)

        val anyWatch = Tasks.await(Wearable.getNodeClient(context).connectedNodes)
            .firstOrNull { it.isNearby }
        return if (anyWatch != null) Target.AppMissing(anyWatch.displayName) else Target.NoWatch
    }

    /**
     * Push one built face to a watch that already has the app.
     *
     * Blocking. The channel closes in a `finally` because a channel left open
     * holds a Bluetooth resource, and the next send would then queue behind a
     * transfer nobody is waiting for.
     */
    fun send(context: Context, target: Target.Ready, apk: File, validationToken: String) {
        val channelClient = Wearable.getChannelClient(context)
        // Throws on a blank token, at the sending end. See WatchLink.
        val path = WatchLink.channelPathFor(validationToken)
        // Logged because "it said sent and nothing happened" is otherwise
        // undebuggable from this side: openChannel and sendFile both resolve
        // successfully whether or not anything on the watch ever wakes up.
        Log.i(TAG, "opening channel to ${target.nodeId} (${target.name})")
        Log.i(TAG, "  path  $path")
        Log.i(TAG, "  apk   ${apk.absolutePath} (${apk.length()} bytes)")

        val channel = Tasks.await(channelClient.openChannel(target.nodeId, path))
        try {
            // The bytes are written to the channel's own OutputStream rather
            // than handed over as a file Uri, and that is not a style choice.
            //
            // ChannelClient.sendFile(channel, Uri.fromFile(f)) is opened by
            // GOOGLE PLAY SERVICES, from its own process and uid. Under scoped
            // storage neither this app's cacheDir nor its external files dir is
            // readable there -- and the send Task resolves successfully anyway.
            // The watch's receiveFile then returns 0 BYTES and addWatchFace
            // rejects the empty file as a malformed APK, which is an error that
            // points at the packaging and is really about the transport.
            //
            // Measured on a Pixel Watch 5: "520KB sent" in 370ms, nothing
            // received, twice, from two different source directories.
            //
            // Writing the stream ourselves needs no cross-process file access at
            // all. Closing it is what tells the far end the face is complete.
            val stream = Tasks.await(channelClient.getOutputStream(channel))
            var written = 0L
            stream.use { out ->
                apk.inputStream().use { input -> written = input.copyTo(out) }
            }
            Log.i(TAG, "wrote $written bytes to the channel")
        } catch (t: Throwable) {
            Log.e(TAG, "send failed", t)
            runCatching { Tasks.await(channelClient.close(channel)) }
            throw t
        }
        // Not closed on the happy path: the receiver closes when it has finished
        // reading, which is the end that knows. Closing here races the transfer.
    }

}
