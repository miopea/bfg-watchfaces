package com.bfg.watchfaces.appcore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The device app and the watch app are two APKs talking over Bluetooth, and
 * every string they share has to be identical or a face silently never arrives.
 *
 * "Silently" is the whole problem. A wrong channel path means the sender opens a
 * channel nobody listens on; a wrong capability name means the device concludes
 * there is no watch. Neither raises an error anywhere — the face just does not
 * turn up, which is the single worst failure this system can produce.
 */
class WatchLinkTest {

    @Test
    fun `a round trip through the channel path preserves the token`() {
        // A REAL token from the validator, taken off a device run on
        // 2026-08-29. It is standard base64 with a version suffix, not the
        // URL-safe shape this test used to assume.
        val token = "EsHCFGgf0GIQjD5UfB61BgMka8ShjdykSmb1SVS+MmU=:MS4wLjA="
        assertEquals(token, WatchLink.tokenFromChannelPath(WatchLink.channelPathFor(token)))
    }

    @Test
    fun `a blank token is refused at the sending end`() {
        // Not at the receiving end: an empty token fails inside addWatchFace
        // AFTER the whole APK has crossed over Bluetooth, which reads like a
        // transfer bug and is not.
        for (blank in listOf("", "   ", "\t")) {
            assertThrows(IllegalArgumentException::class.java) { WatchLink.channelPathFor(blank) }
        }
    }

    @Test
    fun `a path that is not ours is ignored rather than half-parsed`() {
        assertNull(WatchLink.tokenFromChannelPath("/something-else/face/abc"))
        assertNull(WatchLink.tokenFromChannelPath(""))
        assertNull(WatchLink.tokenFromChannelPath("/bfg-watchfaces/face"))
    }

    @Test
    fun `a face path with no token is refused, not defaulted`() {
        assertNull(WatchLink.tokenFromChannelPath(WatchLink.FACE_CHANNEL_PREFIX))
        assertNull(WatchLink.tokenFromChannelPath(WatchLink.FACE_CHANNEL_PREFIX + "   "))
    }

    /**
     * The capability is declared in a Wear resource file and looked up by this
     * constant. Nothing in the compiler connects the two, so this reads the file.
     */
    @Test
    fun `a token containing a slash cannot split the channel path`() {
        // The one that would have bitten. Standard base64 includes "/", and a
        // "/" in a Data Layer path is a new path segment. The encoded form has
        // to stay a single segment whatever the validator emits.
        val token = "ab/cd+ef/gh=:MS4wLjA="
        val path = WatchLink.channelPathFor(token)
        val segment = path.removePrefix(WatchLink.FACE_CHANNEL_PREFIX)
        assertFalse(segment.contains("/")) { "the token became two path segments: $path" }
        assertEquals(token, WatchLink.tokenFromChannelPath(path))
    }

    @Test
    fun `every character the encoder emits is safe in a path`() {
        val token = "EsHCFGgf0GIQjD5UfB61BgMka8ShjdykSmb1SVS+MmU=:MS4wLjA="
        val segment = WatchLink.channelPathFor(token).removePrefix(WatchLink.FACE_CHANNEL_PREFIX)
        assertTrue(segment.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            "unsafe character in the path segment: $segment"
        }
    }

    @Test
    fun `a segment that is not our encoding is refused rather than mangled`() {
        // A bad token fails inside addWatchFace AFTER the whole APK has crossed
        // over Bluetooth, which reads like a transfer bug and is not. Better to
        // refuse the path than to hand the watch something that will waste a
        // transfer and then an addWatchFace call.
        assertNull(WatchLink.tokenFromChannelPath(WatchLink.FACE_CHANNEL_PREFIX + "not base64 !!"))
    }

    @Test
    fun `the watch listens on the path the device opens`() {
        // The manifest's pathPrefix is a hardcoded string and FACE_CHANNEL_PREFIX
        // is Kotlin, so nothing but this test connects them. A mismatch is
        // silent in the worst way: the device opens the channel, the service is
        // never invoked, the bytes go nowhere and no error is raised anywhere.
        val manifest = File("../wear/src/main/AndroidManifest.xml")
        assertTrue(manifest.isFile) { "not where the test expected: ${manifest.absolutePath}" }
        val prefix = Regex("""android:pathPrefix="([^"]+)"""")
            .find(manifest.readText())?.groupValues?.get(1)
        assertNotNull(prefix) { "no pathPrefix on the channel listener" }
        assertTrue(WatchLink.FACE_CHANNEL_PREFIX.startsWith(prefix!!)) {
            "the device opens ${WatchLink.FACE_CHANNEL_PREFIX} and the watch listens on $prefix"
        }
    }

    @Test
    fun `the watch advertises exactly the capability the device looks for`() {
        val xml = File("../wear/src/main/res/values/wear.xml")
        assertTrue(xml.isFile) {
            "wear.xml is gone. The watch app then advertises nothing, the device " +
                "concludes there is no watch, and no face is ever sent."
        }
        val declared = Regex("""<item>\s*([A-Za-z0-9_]+)\s*</item>""")
            .findAll(xml.readText()).map { it.groupValues[1] }.toList()

        assertTrue(WatchLink.CAPABILITY in declared) {
            "the device looks for '${WatchLink.CAPABILITY}' but the watch advertises $declared"
        }
    }

    @Test
    fun `the reset path carries the token and says so`() {
        val token = "EsHCFGgf0GIQjD5UfB61BgMka8Shjdyk/MmU=:MS4wLjA="
        val plain = WatchLink.channelPathFor(token)
        val reset = WatchLink.channelPathFor(token, resetComplications = true)

        assertEquals(token, WatchLink.tokenFromChannelPath(plain))
        assertEquals(token, WatchLink.tokenFromChannelPath(reset))
        assertFalse(WatchLink.resetsComplications(plain))
        assertTrue(WatchLink.resetsComplications(reset))
    }

    @Test
    fun `the ordinary path is unchanged, so an older watch still accepts it`() {
        // The whole point of a separate prefix: a watch running the previous
        // build parses FACE_CHANNEL_PREFIX and nothing else, and a send that
        // does not need a reset must keep working on it.
        val token = "abc123=:MS4wLjA="
        assertTrue(WatchLink.channelPathFor(token).startsWith("/bfg-watchfaces/face/"))
        assertFalse(WatchLink.channelPathFor(token).startsWith("/bfg-watchfaces/face-reset/"))
    }

    @Test
    fun `an unknown path never asks for a reset`() {
        // A reset costs one of a finite number of setWatchFaceAsActive calls.
        // Nothing unrecognised may spend one.
        assertFalse(WatchLink.resetsComplications("/bfg-watchfaces/face/xyz"))
        assertFalse(WatchLink.resetsComplications("/something/else"))
        assertFalse(WatchLink.resetsComplications(""))
        assertNull(WatchLink.tokenFromChannelPath("/bfg-watchfaces/face-reset/"))
    }


    @Test
    fun `the watch's verdict becomes a sentence a person can act on`() {
        val ok = WatchLink.Report.describe("My Face", "Pixel Watch", WatchLink.Report.OK)
        assertTrue(ok.contains("switched on")) { ok }

        // The common case after the first send: installed, but the watch will
        // not switch, because setWatchFaceAsActive succeeds once per install.
        // It must name the action that works and must NOT read as a failure.
        val notActive = WatchLink.Report.describe("My Face", "Pixel Watch", WatchLink.Report.OK_NOT_ACTIVE)
        assertTrue(notActive.contains("Long-press")) { notActive }
        assertFalse(notActive.lowercase().contains("could not")) { notActive }

        val failed = WatchLink.Report.describe(
            "My Face", "Pixel Watch", WatchLink.Report.failed("no free watch face slot")
        )
        assertTrue(failed.contains("could not install")) { failed }
        assertTrue(failed.contains("no free watch face slot")) { failed }
    }

    @Test
    fun `silence is reported as unknown, not as success or failure`() {
        // A watch on an older build writes nothing. Claiming either outcome is
        // exactly how three bugs stayed hidden for a week.
        val quiet = WatchLink.Report.describe("My Face", "Pixel Watch", null)
        assertTrue(quiet.contains("did not confirm")) { quiet }
        assertFalse(quiet.contains("switched on")) { quiet }
        assertFalse(quiet.contains("could not install")) { quiet }
    }

    @Test
    fun `a failure reason cannot run away with the message`() {
        val huge = WatchLink.Report.failed("x".repeat(5_000) + "\nsecond line")
        assertTrue(huge.length < WatchLink.Report.MAX_BYTES) { "report is ${huge.length} bytes" }
        assertFalse(huge.contains("\n")) { "a newline would truncate the read at the phone" }
    }

}
