package com.bfg.watchfaces.appcore

/**
 * The contract between the device app and the watch app.
 *
 * These are two APKs that talk over Bluetooth, so every one of these strings has
 * to be identical on both sides or a face silently never arrives — the sender
 * opens a channel nobody is listening on, and nothing anywhere reports an error.
 *
 * That is precisely the shape this repo has been bitten by twice before:
 * `SlotGeometry` exists because the slot arithmetic was written twice with a
 * test asserting the copies agreed, and they agreed while both were wrong; and
 * `DECISIONS.md` 2026-08-27 rejected a JavaScript re-implementation of the dial
 * for the same reason. So these live once, in the module both apps depend on,
 * rather than twice with a test hoping they match.
 */
object WatchLink {

    /**
     * Advertised by the watch app and looked for by the device app, so the
     * device can tell "no watch" apart from "watch without our app". Those need
     * different words in front of a person, and the first handoff step exists
     * because of the second one.
     *
     * Declared on the watch side in `res/values/wear.xml`; that file and this
     * constant must agree, which `WatchLinkTest` asserts by reading it.
     */
    const val CAPABILITY = "bfg_watchfaces_receiver"

    /**
     * Channel path prefix. The validation token is appended.
     *
     * The token rides on the path rather than arriving as a separate message
     * because it describes the exact APK being sent: two messages could arrive
     * out of order or get correlated wrongly, and the failure would land inside
     * `addWatchFace` at the end of a Bluetooth transfer.
     */
    const val FACE_CHANNEL_PREFIX = "/bfg-watchfaces/face/"

    /**
     * The same thing, but asking the watch to RESET the complication slots.
     *
     * `updateWatchFace` keeps the slot, and the watch keeps the data sources
     * assigned to that slot -- `DefaultProviderPolicy` only fills a slot nothing
     * has been assigned to. So a face re-sent with different complications
     * installed and showed the OLD ones. Removing and re-adding gives a fresh
     * slot, and the design wins.
     *
     * It is a separate PATH rather than a flag inside the old one for a reason
     * that matters more than tidiness: a watch running the previous build parses
     * [FACE_CHANNEL_PREFIX] and nothing else. Sending the ordinary path when
     * nothing changed keeps that watch working, and only a send that genuinely
     * needs a reset depends on the newer app.
     *
     * The cost of a reset is real -- it deactivates the face, so it spends one
     * of a finite number of `setWatchFaceAsActive` calls -- which is the whole
     * reason this is a choice per send rather than what every send does.
     */
    const val FACE_RESET_CHANNEL_PREFIX = "/bfg-watchfaces/face-reset/"

    /**
     * The token from a channel path, or null when the path is not one of ours.
     *
     * Undoes [channelPathFor]'s encoding. A segment that is not valid URL-safe
     * base64 returns null rather than a mangled token: a bad token fails inside
     * `addWatchFace` AFTER the whole APK has crossed over Bluetooth, which reads
     * like a transfer bug and is not.
     */
    fun tokenFromChannelPath(path: String): String? {
        val prefix = when {
            path.startsWith(FACE_RESET_CHANNEL_PREFIX) -> FACE_RESET_CHANNEL_PREFIX
            path.startsWith(FACE_CHANNEL_PREFIX) -> FACE_CHANNEL_PREFIX
            else -> return null
        }
        val segment = path.removePrefix(prefix).takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            String(java.util.Base64.getUrlDecoder().decode(segment), Charsets.UTF_8)
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * The channel path carrying [validationToken].
     *
     * ## The token is re-encoded, and that is not fussiness
     *
     * The validator's token is STANDARD base64 with a version suffix — measured,
     * not guessed: `EsHCFGgf0GIQjD5UfB61BgMka8Shjdyk...MmU=:MS4wLjA=`. Standard
     * base64's alphabet includes `+`, `=` and **`/`**, and a `/` dropped into a
     * Data Layer path is a new path segment. The receiver would still recover
     * the token by prefix-stripping, but nothing promises the transport hands
     * back the path it was given, byte for byte, once it contains separators.
     *
     * So the segment is URL-safe base64 of the token's bytes: `[A-Za-z0-9_-]`
     * and nothing else, whatever the validator produces.
     *
     * This file previously assumed a URL-safe token — the old test used
     * `eyJhbGciOiJI.UzI1NiJ9-_==` — which is what running the real validator on
     * a device corrected. Nothing has ever crossed this link, so there is no
     * wire compatibility to keep.
     */
    fun channelPathFor(validationToken: String, resetComplications: Boolean = false): String {
        require(validationToken.isNotBlank()) {
            "a face cannot be sent without a validation token"
        }
        val segment = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(validationToken.toByteArray(Charsets.UTF_8))
        return (if (resetComplications) FACE_RESET_CHANNEL_PREFIX else FACE_CHANNEL_PREFIX) + segment
    }

    /**
     * Whether this path asks for the complication slots to be reset.
     *
     * False for anything unrecognised, so an unexpected path can never cost a
     * `setWatchFaceAsActive` call by accident.
     */
    /**
     * The one line the watch writes back once it has dealt with a face.
     *
     * ## Why this exists
     *
     * `FaceSender` resolves when the BYTES are across. Whether the watch then
     * installed the face, refused it, or installed it and could not switch to
     * it, all looked identical from the phone: "Sent". Three separate bugs hid
     * behind that in one week, and each cost hours before anyone could even say
     * which half was broken. A person holding a phone that says "sent" and a
     * watch that has not changed has been told nothing.
     *
     * Deliberately a plain line on the SAME channel rather than a message or a
     * second connection: the channel is already open, already correlated with
     * this exact face, and needs no new path, listener or manifest entry.
     *
     * A watch running an older build writes nothing at all. The phone reads
     * with a timeout and falls back to what it used to say, so this degrades to
     * the previous behaviour rather than hanging a send.
     */
    object Report {
        const val OK = "OK"
        const val OK_NOT_ACTIVE = "OK_NOT_ACTIVE"
        const val FAILED = "FAILED"

        /**
         * At most this many bytes; the phone reads no further.
         *
         * Big enough for the verdict AND the watch's provider catalog, which
         * rides back on the same reply. A bare emulator has 37 complication
         * sources; a real watch with apps on it has more.
         */
        const val MAX_BYTES = 128 * 1024

        /** Separates the verdict line from the catalog that follows it. */
        const val SEPARATOR = "\n"

        /** The verdict alone, ignoring any catalog after it. */
        fun verdictIn(raw: String?): String? =
            raw?.substringBefore(SEPARATOR)?.trim()?.takeIf { it.isNotEmpty() }

        /**
         * The catalogs after the verdict: providers first, then launchable apps.
         *
         * Two lines rather than one merged list, because they answer different
         * questions -- who can FILL a slot, and what pressing one can OPEN. An
         * app can be either, both or neither.
         *
         * A watch that only sends the first line is not broken; the second is
         * simply absent, and the phone keeps whatever it had.
         */
        fun catalogIn(raw: String?): String? = lineAfterVerdict(raw, 0)

        /** The launchable-app catalog, or null when the watch sent none. */
        fun launchersIn(raw: String?): String? = lineAfterVerdict(raw, 1)

        /**
         * What the WATCH says about the activation permission.
         *
         * The phone cannot know this any other way, and it used to pretend it
         * could: it read `ActivationConsent` from its OWN `filesDir`, which
         * only the watch ever writes. So the phone's copy was permanently
         * UNASKED, `persistentNote` could never return anything, and the
         * "you already said no" note — a screen that exists specifically to
         * explain why nothing happened — was unreachable. Somebody who denied
         * the one-shot got silence from both devices.
         *
         * A third line on a reply that already carries two, for the same
         * reason as the other two: the channel is open and already correlated
         * with this send. A watch that sends nothing here is not broken; the
         * phone keeps what it had, which is how the other lines already behave.
         *
         * Deliberately NOT written into the phone's own `ActivationConsent`
         * store. That state machine guards a one-shot, unrecoverable action on
         * the WATCH; giving it a second writer that means something subtly
         * different is how a guard stops guarding.
         */
        fun consentIn(raw: String?): String? =
            raw?.substringAfter(SEPARATOR, "")
                ?.split(SEPARATOR)
                ?.getOrNull(2)
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it.none { c -> c == '[' } }

        private fun lineAfterVerdict(raw: String?, index: Int): String? =
            raw?.substringAfter(SEPARATOR, "")
                ?.split(SEPARATOR)
                ?.getOrNull(index)
                ?.trim()
                ?.takeIf { it.startsWith("[") }

        fun installed(active: Boolean): String = if (active) OK else OK_NOT_ACTIVE

        fun failed(reason: String): String =
            "$FAILED ${reason.take(400).replace('\n', ' ')}"

        /**
         * What to show a person, given whatever came back.
         *
         * [raw] is null when the watch said nothing -- an older build, or a
         * transfer the watch never picked up. That is NOT reported as failure:
         * it is genuinely unknown, and claiming either way is how this went
         * wrong in the first place.
         */
        /**
         * Did the face actually reach the watch?
         *
         * Separate from [describe] because the caller needs the ANSWER, not the
         * sentence. The phone used to decide by testing whether the sentence
         * started with "Sent " -- which was true only in the one case where the
         * watch did NOT confirm, and false on both real successes. So the
         * "what is on the watch" record was written exactly when it was least
         * certain and skipped when it was known. Prose is not a return value.
         */
        fun landed(raw: String?): Boolean =
            verdictIn(raw).orEmpty().let { it == OK || it == OK_NOT_ACTIVE }

        fun describe(faceName: String, watchName: String, raw: String?): String {
            val line = verdictIn(raw).orEmpty()
            return when {
                // BOTH say the same thing, and that is deliberate.
                //
                // "Long-press your watch face and pick it" was rejected twice,
                // and the second version -- "Choose it from your watch faces to
                // wear it" -- was the same instruction in politer words, which
                // is why it was rejected again. The operator's answer both
                // times was that the face is SUPPOSED to appear on its own:
                // "It should be appearing automatically. That's the whole point
                // of what we're doing."
                //
                // He is right, and that makes a face that installed without
                // switching a BUG in this app, not a state to narrate. Text
                // asking someone to finish the job by hand is this app failing
                // and then delegating the failure. Watch Face Push preserves
                // active status across an in-place update, so the only way to
                // land here is if something deactivated the face -- which is
                // now prevented in FaceInstaller rather than described here.
                line == OK || line == OK_NOT_ACTIVE ->
                    "“$faceName” is on your $watchName."
                line.startsWith(FAILED) -> {
                    val why = line.removePrefix(FAILED).trim()
                    if (why.isEmpty()) "${watchName} could not install “$faceName”."
                    else "${watchName} could not install “$faceName”: $why"
                }
                else -> "Sent “$faceName” to $watchName, but it did not confirm. " +
                    "If nothing changed, check the app is up to date on your watch."
            }
        }
    }

    fun resetsComplications(path: String): Boolean =
        path.startsWith(FACE_RESET_CHANNEL_PREFIX)
}
