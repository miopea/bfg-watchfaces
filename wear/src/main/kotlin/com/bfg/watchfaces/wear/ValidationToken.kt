package com.bfg.watchfaces.wear

/**
 * The validation token Watch Face Push requires for every install.
 *
 * ## It comes FROM the device, and is not made here
 *
 * `docs/SPEC.md` puts it in the pipeline explicitly:
 *
 * ```text
 * params -> pack builds APK -> validator issues token -> Data Layer -> addWatchFace()
 * ```
 *
 * The token is issued next to `pack`, on the device, because it is a statement
 * about a specific APK and the device is what built it. `:mobile` is the module
 * carrying `wfp-validator-android` for exactly that; `:wear` does not have it
 * and should not. Generating it again on the watch would be doing the work
 * twice, on the slower machine, and risking a token that describes a subtly
 * different set of bytes than the ones that were sent.
 *
 * So it travels with the face. The channel path carries it:
 *
 * ```text
 * /bfg-watchfaces/face/<token>
 * ```
 *
 * One channel, no second message to arrive out of order, and nothing to correlate.
 *
 * Tokens never expire, so the device caches by APK hash and re-sending an
 * unchanged face costs nothing.
 */
object ValidationToken {

    /** The channel prefix a face arrives on. */
    const val PREFIX = "/bfg-watchfaces/face/"

    /**
     * Pull the token off a channel path, or null if this is not a face channel.
     *
     * Null is refused by the caller rather than replaced with a placeholder: an
     * invented token fails inside `addWatchFace` with
     * `ERROR_INVALID_VALIDATION_TOKEN` at the very end of a Bluetooth transfer,
     * which reads like a transfer bug and is not.
     */
    fun fromChannelPath(path: String): String? {
        if (!path.startsWith(PREFIX)) return null
        return path.removePrefix(PREFIX).takeIf { it.isNotBlank() }
    }
}
