package com.bfg.watchfaces.mobile

import android.content.Context
import android.net.Uri
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
        val channel = Tasks.await(channelClient.openChannel(target.nodeId, path))
        try {
            Tasks.await(channelClient.sendFile(channel, Uri.fromFile(apk)))
        } finally {
            runCatching { Tasks.await(channelClient.close(channel)) }
        }
    }
}
