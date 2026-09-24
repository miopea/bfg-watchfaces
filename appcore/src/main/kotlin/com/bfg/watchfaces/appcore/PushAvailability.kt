package com.bfg.watchfaces.appcore

/**
 * Whether a watch can actually accept a pushed face, and what to say if not.
 *
 * ## The bug this exists because of
 *
 * `WatchFacePushManagerFactory.isSupported()` is, in full:
 *
 * ```
 * return Build.VERSION.SDK_INT >= 36
 * ```
 *
 * An OS version check. It does not look for the receiver, does not check it is
 * enabled, and does not attempt a bind. `FaceInstaller` treated that answer as
 * a capability answer, and on 2026-09-24 a Pixel Watch 4 — API 36, so the app
 * installed and the guard waved it through — failed at the first real call and
 * showed its wearer this, as the whole message:
 *
 * ```
 * Pixel Watch 4 could not install "Default": ListWatchFacesException | Unknown
 * error while listing watch faces. ... | <- ReceiverConnectionException:
 * Binding to the watch face receiver was unsuccessful
 * ```
 *
 * She waited through a build and a Bluetooth transfer to read that. The
 * `Result.Unsupported` path exists precisely to prevent it and its own comment
 * says so — "saying so here is cheap; discovering it as an opaque failure after
 * a Bluetooth transfer is not" — but it was guarded by the wrong question.
 *
 * ## This asks the watch, it does not infer
 *
 * The three fields are OBSERVATIONS the watch makes about itself, cheaply and
 * locally, before the phone builds anything:
 *
 * - [sdkInt] is the OS floor, the one thing the library's own check covers.
 * - [receivers] is how many services answer `ACTION_PUSH_WATCH_FACES`. Zero
 *   means there is nothing to bind to. **This needs a `<queries>` entry or it
 *   is zero on every watch including working ones** — the package-visibility
 *   trap the wear manifest already documents at length for complications.
 * - [probe] is an actual `listWatchFaces()` call. That is the definitive one:
 *   it is the same call that failed on her wrist, it is local and cheap, and it
 *   answers the question rather than predicting it.
 *
 * Keeping all three lets the words say WHY rather than only that something is
 * wrong, which is the difference between a person acting and a person stuck.
 */
data class PushAvailability(
    /** `Build.VERSION.SDK_INT` on the watch. */
    val sdkInt: Int,
    /** Services answering the Push action. -1 when the watch did not look. */
    val receivers: Int,
    /** What an actual `listWatchFaces()` call did. */
    val probe: Probe,
    /**
     * The technical cause, for a details view. Never the first thing shown.
     *
     * Same rule as `FailureReport`: the sentence stays friendly and the cause
     * goes somewhere findable. Putting the exception chain in front of a person
     * is what this whole file is a response to.
     */
    val detail: String = ""
) {

    enum class Probe {
        /** `listWatchFaces()` returned. The watch can take a face. */
        OK,

        /** `listWatchFaces()` threw. The reason is in [detail]. */
        FAILED,

        /** Not attempted, because an earlier check already settled it. */
        SKIPPED
    }

    /** The one question the phone actually needs answered before it builds. */
    val usable: Boolean get() = probe == Probe.OK

    /**
     * Why it cannot, as a code the UI can branch on.
     *
     * Ordered most-specific first: a watch below the floor has no receiver
     * either, and saying "your watch does not have the service" to somebody on
     * Wear OS 5 would send them looking for a fix that does not exist.
     */
    val reason: Reason
        get() = when {
            probe == Probe.OK -> Reason.NONE
            sdkInt < MIN_SDK -> Reason.OS_TOO_OLD
            receivers == 0 -> Reason.NO_RECEIVER
            else -> Reason.UNREACHABLE
        }

    enum class Reason { NONE, OS_TOO_OLD, NO_RECEIVER, UNREACHABLE }

    /**
     * One sentence, naming the watch, in words a person did not have to learn.
     *
     * No exception names, no "bind", no "service". The operator's wife is the
     * reader this is written for, and she had nobody to ask.
     */
    fun headline(watchName: String): String = when (reason) {
        Reason.NONE -> "$watchName is ready for a face."
        Reason.OS_TOO_OLD ->
            "$watchName is running an older version of Wear OS than this needs."
        Reason.NO_RECEIVER ->
            "$watchName cannot receive watch faces from other apps."
        Reason.UNREACHABLE ->
            "$watchName would not accept a face just now."
    }

    /**
     * What to try, honestly.
     *
     * **Nothing here is promised to work, and that is deliberate.** Three Wear
     * branded-launch rejections were each a reasonable inference written as
     * fact, and each cost a release. Where the truth is "this watch may simply
     * not support it", that is said rather than dressed as a fix.
     */
    fun whatToTry(): List<String> = when (reason) {
        Reason.NONE -> emptyList()
        Reason.OS_TOO_OLD -> listOf(
            "Check your watch for a system update.",
            "Sending faces needs Wear OS 6 or newer."
        )
        Reason.NO_RECEIVER -> listOf(
            "Check your watch for a system update.",
            "Open the Play Store on your watch and let any updates finish.",
            "Some watches do not support receiving faces this way yet. If the " +
                "updates above change nothing, this is probably one of them."
        )
        Reason.UNREACHABLE -> listOf(
            "Restart your watch and try again.",
            "Check the watch is connected to your phone.",
            "If it keeps happening, the details below are what we would need."
        )
    }

    /** The wire form: four fields, pipe-separated, detail last because it is free text. */
    fun encode(): String = listOf(
        sdkInt.toString(), receivers.toString(), probe.name,
        detail.replace('\n', ' ').replace('|', '/')
    ).joinToString("|")

    companion object {
        /** Watch Face Push exists nowhere below this, per the library itself. */
        const val MIN_SDK = 36

        /**
         * The action a Push receiver answers.
         *
         * Read off a real watch on 2026-08-29, from the `SecurityException` a
         * missing permission produced, and already quoted in the wear manifest.
         */
        const val PUSH_ACTION = "com.google.wear.ACTION_PUSH_WATCH_FACES"

        /**
         * Parse a reply. Anything unreadable is [unknown], never a false OK.
         *
         * A malformed answer must not read as "the watch is fine" — that would
         * put the person back in front of the failure this file exists to stop.
         */
        fun decode(text: String?): PushAvailability {
            val parts = text?.split("|") ?: return unknown()
            if (parts.size < 3) return unknown()
            val sdk = parts[0].trim().toIntOrNull() ?: return unknown()
            val recv = parts[1].trim().toIntOrNull() ?: -1
            val probe = runCatching { Probe.valueOf(parts[2].trim()) }.getOrNull()
                ?: return unknown()
            return PushAvailability(sdk, recv, probe, parts.drop(3).joinToString("|"))
        }

        /**
         * What to assume when the watch did not answer.
         *
         * SKIPPED rather than FAILED, and `usable` is false either way — but the
         * phone must NOT refuse to send on this. A watch that is merely slow, out
         * of range, or running a build that predates the check is a watch that
         * should still be sent to. See the caller.
         */
        fun unknown() = PushAvailability(0, -1, Probe.SKIPPED, "")
    }
}
