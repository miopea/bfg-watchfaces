package com.bfg.watchfaces.appcore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
        assertNotNull(back) { "a real cause chain must still round-trip" }
        assertEquals(36, back!!.sdkInt)
        assertEquals(0, back.receivers)
        assertEquals(PushAvailability.Probe.FAILED, back.probe)
        assertEquals(PushAvailability.Reason.NO_RECEIVER, back.reason)
    }

    /**
     * Garbage decodes to NULL: neither a usable watch nor a refusal.
     *
     * A false OK puts the person back in front of the raw failure this type
     * exists to prevent. A false REFUSAL is the mirror of that and was the
     * actual shipped bug: decode returned `unknown()`, whose `usable` is false,
     * and the caller blocks any answer that is non-null and not usable. So an
     * unreadable reply silently meant "this watch cannot take a face".
     *
     * Null is the third state. The caller already treats null as "go ahead",
     * because silence is not a refusal, and an answer we cannot read is a kind
     * of silence.
     */
    @Test
    fun `nothing unreadable decodes to an answer at all`() {
        for (bad in listOf(null, "", "   ", "36", "36|1", "x|y|z", "36|1|NOT_A_PROBE", "|||")) {
            assertNull(PushAvailability.decode(bad)) { "decoded $bad into an answer" }
        }
    }

    /**
     * The caller's rule, pinned here because it spans two modules.
     *
     * `MainActivity` refuses a send on `readiness != null && !readiness.usable`.
     * That is correct ONLY while every value decode can return is an answer the
     * watch actually gave and we actually understood. This asserts the two ends
     * of that: a real FAILED answer is a refusal, and an unreadable one is not.
     */
    @Test
    fun `only an answer we understood may stop a send`() {
        val refused = PushAvailability.decode(
            PushAvailability(36, 0, PushAvailability.Probe.FAILED, "boom").encode()
        )
        assertNotNull(refused)
        assertFalse(refused!!.usable) { "a FAILED probe must still block the send" }

        assertNull(PushAvailability.decode("total nonsense")) {
            "an unreadable answer must not reach the caller as a refusal"
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
