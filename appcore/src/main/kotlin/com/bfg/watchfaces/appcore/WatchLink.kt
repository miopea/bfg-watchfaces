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
     * The token from a channel path, or null when the path is not one of ours.
     *
     * Undoes [channelPathFor]'s encoding. A segment that is not valid URL-safe
     * base64 returns null rather than a mangled token: a bad token fails inside
     * `addWatchFace` AFTER the whole APK has crossed over Bluetooth, which reads
     * like a transfer bug and is not.
     */
    fun tokenFromChannelPath(path: String): String? {
        if (!path.startsWith(FACE_CHANNEL_PREFIX)) return null
        val segment = path.removePrefix(FACE_CHANNEL_PREFIX).takeIf { it.isNotBlank() } ?: return null
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
    fun channelPathFor(validationToken: String): String {
        require(validationToken.isNotBlank()) {
            "a face cannot be sent without a validation token"
        }
        val segment = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(validationToken.toByteArray(Charsets.UTF_8))
        return FACE_CHANNEL_PREFIX + segment
    }
}
