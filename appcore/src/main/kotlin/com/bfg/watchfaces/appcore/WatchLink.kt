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
    fun resetsComplications(path: String): Boolean =
        path.startsWith(FACE_RESET_CHANNEL_PREFIX)
}
