package com.bfg.watchfaces.appcore

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * What the transport does with an address it cannot use.
 *
 * ## Why these can run without a network
 *
 * Every case here fails while the address is being PARSED or while the
 * connection object is being created — both of which happen before any byte is
 * sent. `HttpURLConnection` does not contact anything until the request is
 * made. So this exercises the real [HttpTransport] rather than a fake, and
 * still touches nothing.
 *
 * ## What is actually being protected
 *
 * Most of what gets interpolated into these addresses is a slug that arrived
 * FROM the catalog. The deprecated `URL(String)` constructor parsed leniently
 * and would build a URL from a string carrying a space or a control character,
 * which meant a server could shape a path this app then requested. `URI.create`
 * refuses, here, before anything is sent — so the strictness introduced when
 * that deprecation was fixed is a property worth pinning rather than an
 * incidental side effect of silencing a warning.
 */
class HttpTransportAddressTest {

    private val transport = HttpTransport()

    /**
     * A bad address must not escape as something a caller has never heard of.
     *
     * `URI.create` throws `IllegalArgumentException`, which is not an
     * `IOException` and would sail straight past every `catch` the callers
     * have. The contract is that reaching the catalog fails as [Unreachable].
     */
    @Test
    fun `an unusable address fails as Unreachable`() {
        for (bad in listOf("not a url", "http://exa mple.com/faces", "://missing-scheme")) {
            val thrown = assertThrows<CatalogTransport.Unreachable>("address: $bad") {
                transport.get(bad)
            }
            assertTrue(thrown.message!!.contains("not a usable address")) {
                "wrong failure for \"$bad\": ${thrown.message}"
            }
        }
    }

    /**
     * A space in a path is refused rather than sent.
     *
     * This is the case the old lenient constructor let through, and the one
     * that matters: the slug in that position comes off the network.
     */
    @Test
    fun `a space in the path is refused before anything is sent`() {
        val thrown = assertThrows<CatalogTransport.Unreachable> {
            transport.get("https://example.invalid/faces/midnight knot")
        }
        assertTrue(thrown.message!!.contains("not a usable address")) {
            "a space reached the network layer: ${thrown.message}"
        }
    }

    /**
     * "Not an address" and "that address would not open" stay distinguishable.
     *
     * Both are Unreachable so callers are unchanged, but they send an
     * investigation to different places, so the wording is not shared. A
     * non-HTTP scheme parses fine and then fails the cast.
     */
    @Test
    fun `a non-HTTP scheme is reported as a failure to open, not a bad address`() {
        val thrown = assertThrows<CatalogTransport.Unreachable> {
            transport.get("file:///tmp/not-the-catalog")
        }
        assertTrue(thrown.message!!.contains("could not open")) {
            "expected an open failure, got: ${thrown.message}"
        }
    }
}
