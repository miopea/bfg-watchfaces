package com.bfg.watchfaces.appcore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * The answer to "what complications does this watch have", asked directly.
 *
 * ## Why this shares an encoding with the send report
 *
 * The catalog has always ridden back on a successful send. Asking for it
 * directly is a second way to receive the same thing, and two encodings of one
 * fact is how the phone and the watch end up disagreeing about it — the
 * mistake `SlotGeometry` exists to prevent, one layer up. So the reply reuses
 * the send report's lines and is read by the same two functions; these pin
 * that, because the cheapest way to break it is to "tidy" the empty first line
 * away.
 */
class CatalogReplyTest {

    private val providers = """[{"component":"a/b","label":"Steps","app":"Fit"}]"""
    private val launchers = """[{"component":"c/d","label":"Timer","app":"Clock"}]"""

    @Test
    fun `a direct reply is read by the same parser as a send report`() {
        val reply = WatchLink.catalogReply(providers, launchers)
        assertEquals(providers, WatchLink.Report.catalogIn(reply))
        assertEquals(launchers, WatchLink.Report.launchersIn(reply))
    }

    /**
     * The empty first line is load-bearing, not an artefact.
     *
     * Both readers index AFTER a verdict. Drop the leading separator to make
     * the payload look tidier and both lists shift by one: the picker would
     * offer launchable apps as complication sources.
     */
    @Test
    fun `dropping the empty verdict line would shift both catalogs`() {
        val tidied = providers + WatchLink.Report.SEPARATOR + launchers
        assertNotEquals(providers, WatchLink.Report.catalogIn(tidied)) {
            "a payload without the leading separator parsed as though it had one"
        }
    }

    /** A watch that knows of nothing still answers, rather than saying nothing. */
    @Test
    fun `an empty watch still produces a readable reply`() {
        val reply = WatchLink.catalogReply("[]", "[]")
        assertEquals("[]", WatchLink.Report.catalogIn(reply))
        assertEquals("[]", WatchLink.Report.launchersIn(reply))
    }

    /** The two paths must not collide with the note path or each other. */
    @Test
    fun `the message paths are distinct`() {
        val paths = listOf(
            WatchLink.NOTE_PATH,
            WatchLink.CATALOG_REQUEST_PATH,
            WatchLink.CATALOG_REPLY_PATH
        )
        assertEquals(paths.size, paths.toSet().size) { "two message paths are identical: $paths" }
    }
}
