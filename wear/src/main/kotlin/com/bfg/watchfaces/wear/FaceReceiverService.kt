package com.bfg.watchfaces.wear

import android.util.Log
import androidx.wear.watchfacepush.WatchFacePushManager
import com.bfg.watchfaces.appcore.ActivationConsent
import com.bfg.watchfaces.appcore.PhoneNote
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

    /**
     * A note from the phone: store it and tell the system to re-ask.
     *
     * A MESSAGE, not a channel — see [WatchLink.NOTE_PATH]. Twenty characters do
     * not need a stream.
     *
     * The push matters as much as the storing. Without
     * [PhoneNoteService.notifyChanged] the watch keeps whatever it last read
     * until something else happens to ask, so a note typed on the phone would
     * arrive at an unpredictable time or look like it never arrived.
     */
    override fun onMessageReceived(event: com.google.android.gms.wearable.MessageEvent) {
        // ASKED DIRECTLY, rather than told as a side effect of a send.
        //
        // The catalog still rides back on every successful send and that is
        // unchanged. This exists because a send is a terrible way to DISCOVER
        // the list: the operator updated both apps to pick up a package
        // visibility fix on 2026-09-19, opened the picker, and saw the same
        // entries, because nothing had asked the watch since. Nobody would
        // guess that sending an unrelated face is how you refresh it.
        if (event.path == WatchLink.CATALOG_REQUEST_PATH) {
            answerCatalog(event.sourceNodeId)
            return
        }
        // The date her most recent period started. A DATE, not a day number --
        // the watch derives the number itself whenever it is asked, so nothing
        // goes stale at midnight. See CycleDay.
        //
        // An empty payload CLEARS it: she revoked the permission, deleted the
        // records, or turned the feature off, and the slot has to go back to an
        // em dash rather than keeping the last number it ever saw.
        if (event.path == WatchLink.CYCLE_START_PATH) {
            val raw = runCatching { String(event.data, Charsets.UTF_8) }.getOrDefault("")
            val date = com.bfg.watchfaces.appcore.CycleDay.parse(raw)
            com.bfg.watchfaces.appcore.CycleDay.save(applicationContext.filesDir, date)
            // Logged WITHOUT the date. It is health-derived, it is hers, and a
            // logcat line is the one place in this system where it would sit in
            // plain text on a device we do not control the lifetime of.
            Log.i(TAG, if (date == null) "cycle start cleared" else "cycle start set")
            CycleDayService.notifyChanged(applicationContext)
            return
        }
        if (event.path != WatchLink.NOTE_PATH) {
            super.onMessageReceived(event)
            return
        }
        val text = runCatching { String(event.data, Charsets.UTF_8) }.getOrDefault("")
        val stored = PhoneNote.save(applicationContext.filesDir, text)
        Log.i(TAG, if (stored.isEmpty()) "note cleared" else "note set (${stored.length} chars)")
        PhoneNoteService.notifyChanged(applicationContext)
    }

    /**
     * Send back what this watch can see, to whoever asked.
     *
     * Built from the same two [ProviderCatalog] calls a send reports with, and
     * encoded by the same [WatchLink.catalogReply] the phone parses with its
     * send-report readers — one encoding of "what this watch has", not two
     * that can drift.
     *
     * Failures are logged and dropped rather than retried. The phone treats a
     * silent watch as "keep what you had", which is the honest outcome: the
     * cached list is stale rather than wrong, and a picker that refuses to
     * open because a watch is charging in another room would be worse than one
     * showing last week's answer.
     */
    private fun answerCatalog(nodeId: String) {
        val payload = WatchLink.catalogReply(
            ProviderCatalog.toJson(ProviderCatalog.installed(this)),
            ProviderCatalog.toJson(ProviderCatalog.launchable(this))
        )
        Log.i(TAG, "catalog asked for by $nodeId; answering with ${payload.length} chars")
        runCatching {
            // AWAITED, and that is the whole bug this line was written with.
            //
            // sendMessage returns a Task. A WearableListenerService is torn
            // down as soon as its callback returns -- this file's own header
            // says so, about an install that was being cancelled halfway -- so
            // firing the Task and returning let the process die before the
            // message was delivered. The watch logged "answering with N chars"
            // and the phone logged "asked, but no catalog answer", which is
            // exactly what it looks like when both ends are telling the truth.
            //
            // Measured on the operator's Pixel Watch 5, 2026-09-20: a fresh
            // install showed NO watch providers in the picker at all, because
            // the only other way the cache gets filled is riding back on a
            // successful face send, and a fresh install has never sent one.
            //
            // Awaiting keeps the service alive exactly as long as the delivery
            // takes. Already on a background thread; this is the same blocking
            // await onChannelOpened uses a few lines below.
            Tasks.await(
                Wearable.getMessageClient(this).sendMessage(
                    nodeId, WatchLink.CATALOG_REPLY_PATH, payload.toByteArray(Charsets.UTF_8)
                ),
                WatchLink.REPLY_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS
            )
            Log.i(TAG, "catalog answer delivered")
        }.onFailure { Log.w(TAG, "could not answer the catalog request", it) }
    }

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
                val providers = ProviderCatalog.toJson(
                    ProviderCatalog.installed(this@FaceReceiverService)
                )
                val launchable = ProviderCatalog.toJson(
                    ProviderCatalog.launchable(this@FaceReceiverService)
                )
                reply(
                    client, channel,
                    lineFor(result) + WatchLink.Report.SEPARATOR + providers +
                        WatchLink.Report.SEPARATOR + launchable +
                        // Third line: what this watch's wearer said about the
                        // activation permission. The phone has no other way to
                        // find out -- it was reading its OWN copy of a file
                        // only this device writes, so it always read UNASKED
                        // and could never explain a denial.
                        WatchLink.Report.SEPARATOR +
                        Activation.state(applicationContext).name +
                        // FOURTH line: how the install actually went, when
                        // there is anything to say. Additive on purpose --
                        // every existing reader takes lines 0..2 and a phone
                        // that has never heard of this one is unaffected, so
                        // the watch can be instrumented WITHOUT shipping a
                        // phone build to read it. FaceSender logs the whole
                        // reply verbatim, so adb on the phone is the readout.
                        noteFor(result)
                )
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

    /** The fourth line, or nothing at all when there is nothing to add. */
    private fun noteFor(result: FaceInstaller.Result): String =
        if (result is FaceInstaller.Result.Installed && result.note.isNotEmpty()) {
            WatchLink.Report.SEPARATOR + result.note
        } else {
            ""
        }

    private fun lineFor(result: FaceInstaller.Result): String = when (result) {
        is FaceInstaller.Result.Installed ->
            WatchLink.Report.installed(result.active)
        is FaceInstaller.Result.Unsupported ->
            WatchLink.Report.failed("this watch does not support Watch Face Push")
        is FaceInstaller.Result.Failed ->
            // CLASS, message, cause chain AND the slot picture.
            //
            // This used to send `message ?: simpleName` -- one or the other,
            // never both, and never the cause. It produced "Unknown error while
            // updating a watch face", which named nothing and could not be
            // followed, and there is no second channel to go and look: Bluetooth
            // debugging was removed in Wear OS 3, the Pixel Watch has no data
            // port, and Wi-Fi debugging needs a network. This line IS the log.
            //
            // The phone spent a day in exactly this position for exactly this
            // reason. Same fix, same reason: report, do not summarise.
            WatchLink.Report.failed(detail(result))
    }

    /** Everything a failure actually carried, for a device nothing can attach to. */
    private fun detail(result: FaceInstaller.Result.Failed): String {
        val parts = mutableListOf<String>()
        parts += result.cause.javaClass.simpleName
        result.cause.message?.takeIf { it.isNotBlank() }?.let { parts += it }
        var cause = result.cause.cause
        var depth = 0
        while (cause != null && depth < 3) {
            parts += "<- ${cause.javaClass.simpleName}: ${cause.message ?: "(no message)"}"
            cause = cause.cause
            depth++
        }
        if (result.slots.isNotBlank()) parts += "slots{${result.slots}}"
        return parts.joinToString(" | ")
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
