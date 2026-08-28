package com.bfg.watchfaces.appcore

import org.junit.jupiter.api.Assertions.assertEquals
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
        val token = "eyJhbGciOiJI.UzI1NiJ9-_=="
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
}
