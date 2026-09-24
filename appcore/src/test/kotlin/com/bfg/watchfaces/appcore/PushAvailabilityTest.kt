package com.bfg.watchfaces.appcore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PushAvailabilityTest {

    private fun ok() = PushAvailability(36, 1, PushAvailability.Probe.OK)

    /**
     * The whole point: a watch that passes the OS floor can still be unable to
     * take a face. That is the Pixel Watch 4 of 2026-09-24, and the case the
     * library's own `isSupported()` — `SDK_INT >= 36` and nothing else — says
     * yes to.
     */
    @Test
    fun `API 36 with no receiver is not usable`() {
        val watch = PushAvailability(36, 0, PushAvailability.Probe.FAILED, "ReceiverConnectionException")
        assertFalse(watch.usable)
        assertEquals(PushAvailability.Reason.NO_RECEIVER, watch.reason)
    }

    @Test
    fun `a working watch is usable and offers nothing to fix`() {
        assertTrue(ok().usable)
        assertEquals(PushAvailability.Reason.NONE, ok().reason)
        assertTrue(ok().whatToTry().isEmpty())
    }

    /**
     * An old OS has no receiver either, so both conditions hold. It must report
     * the OS, because "your watch cannot receive faces" would send somebody on
     * Wear OS 5 hunting for a fix that does not exist.
     */
    @Test
    fun `too old reports the OS rather than the missing receiver`() {
        val old = PushAvailability(34, 0, PushAvailability.Probe.FAILED)
        assertEquals(PushAvailability.Reason.OS_TOO_OLD, old.reason)
        assertTrue(old.whatToTry().any { it.contains("Wear OS 6") })
    }

    /** A receiver that exists but would not answer is a third, different case. */
    @Test
    fun `present but unreachable is its own reason`() {
        val flaky = PushAvailability(36, 1, PushAvailability.Probe.FAILED, "timeout")
        assertEquals(PushAvailability.Reason.UNREACHABLE, flaky.reason)
        assertTrue(flaky.whatToTry().any { it.contains("Restart") })
    }

    /**
     * No exception name, no "bind", no "service" reaches the first line.
     *
     * The message that started this was a four-clause exception chain shown to
     * somebody with nobody to ask. Sweeping every reason rather than checking
     * one: the next reason added is exactly the one that would slip through.
     */
    @Test
    fun `no headline leaks a technical term`() {
        val banned = listOf("Exception", "bind", "Binding", "service", "receiver", "API", "SDK", "null")
        for (r in PushAvailability.Reason.entries) {
            val sample = when (r) {
                PushAvailability.Reason.NONE -> ok()
                PushAvailability.Reason.OS_TOO_OLD -> PushAvailability(34, 0, PushAvailability.Probe.FAILED)
                PushAvailability.Reason.NO_RECEIVER -> PushAvailability(36, 0, PushAvailability.Probe.FAILED)
                PushAvailability.Reason.UNREACHABLE -> PushAvailability(36, 1, PushAvailability.Probe.FAILED)
            }
            val line = sample.headline("Pixel Watch 4")
            for (word in banned) {
                assertFalse(line.contains(word)) { "$r headline leaks \"$word\": $line" }
            }
            assertTrue(line.contains("Pixel Watch 4")) { "$r headline does not name the watch: $line" }
        }
    }

    /** Every unusable reason gives the person something to do. */
    @Test
    fun `every failure offers at least one thing to try`() {
        for (r in PushAvailability.Reason.entries.filter { it != PushAvailability.Reason.NONE }) {
            val sample = when (r) {
                PushAvailability.Reason.OS_TOO_OLD -> PushAvailability(34, 0, PushAvailability.Probe.FAILED)
                PushAvailability.Reason.NO_RECEIVER -> PushAvailability(36, 0, PushAvailability.Probe.FAILED)
                else -> PushAvailability(36, 1, PushAvailability.Probe.FAILED)
            }
            assertTrue(sample.whatToTry().isNotEmpty()) { "$r offers nothing" }
        }
    }

    @Test
    fun `the wire form survives a round trip`() {
        val w = PushAvailability(36, 2, PushAvailability.Probe.FAILED, "ReceiverConnectionException: nope")
        assertEquals(w, PushAvailability.decode(w.encode()))
    }

    /**
     * A pipe or a newline in the exception text must not shift the fields.
     *
     * The real message carried " | <- " three times, so this is the actual
     * input, not a hypothetical one.
     */
    @Test
    fun `a cause chain full of pipes does not corrupt the fields`() {
        val nasty = "ListWatchFacesException | Unknown error\n| <- ReceiverConnectionException"
        val w = PushAvailability(36, 0, PushAvailability.Probe.FAILED, nasty)
        val back = PushAvailability.decode(w.encode())
        assertEquals(36, back.sdkInt)
        assertEquals(0, back.receivers)
        assertEquals(PushAvailability.Probe.FAILED, back.probe)
        assertEquals(PushAvailability.Reason.NO_RECEIVER, back.reason)
    }

    /**
     * Garbage must never decode to a usable watch.
     *
     * A false OK puts the person straight back in front of the raw failure this
     * whole type exists to prevent, which is worse than admitting we do not know.
     */
    @Test
    fun `nothing unreadable decodes as usable`() {
        for (bad in listOf(null, "", "   ", "36", "36|1", "x|y|z", "36|1|NOT_A_PROBE", "|||")) {
            assertFalse(PushAvailability.decode(bad).usable) { "decoded $bad as usable" }
        }
    }

    /** A silent watch is unknown, and unknown is not a refusal. See the caller. */
    @Test
    fun `unknown is not usable and not a stated reason to give up`() {
        val u = PushAvailability.unknown()
        assertFalse(u.usable)
        assertEquals(PushAvailability.Probe.SKIPPED, u.probe)
    }
}
