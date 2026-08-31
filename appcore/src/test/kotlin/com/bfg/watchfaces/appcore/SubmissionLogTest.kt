package com.bfg.watchfaces.appcore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant

/**
 * The record of what this device shared.
 *
 * Pure JVM on purpose: every rule here is one the phone would otherwise only
 * exercise on an emulator, and the two failures that matter — telling somebody
 * their face was rejected when it was not, and letting them submit the same
 * face twice — are both cheap to pin here and expensive to find on a wrist.
 */
class SubmissionLogTest {

    @Test
    fun `nothing submitted reads as an empty list, not a crash`(@TempDir dir: File) {
        assertEquals(emptyList<SubmissionLog.Record>(), SubmissionLog.list(dir))
        assertNull(SubmissionLog.forSlug(dir, "anything"))
    }

    @Test
    fun `a submission survives a round trip`(@TempDir dir: File) {
        SubmissionLog.record(dir, "midnight_knot", "abc-123", Instant.parse("2026-08-31T12:00:00Z"))
        val back = SubmissionLog.forSlug(dir, "midnight_knot")!!
        assertEquals("abc-123", back.id)
        assertEquals("2026-08-31T12:00:00Z", back.submitted)
        assertEquals(SubmissionLog.State.PENDING, back.state)
        assertTrue(!back.settled) { "a new submission is waiting, not settled" }
    }

    /**
     * Submitting the same face again REPLACES rather than appends.
     *
     * Otherwise "have I shared this?" has more than one answer, and the honest
     * one — the most recent — is the one a list ordered by insertion buries.
     */
    @Test
    fun `re-submitting a face replaces its record`(@TempDir dir: File) {
        SubmissionLog.record(dir, "midnight_knot", "first")
        SubmissionLog.record(dir, "midnight_knot", "second")
        assertEquals(1, SubmissionLog.list(dir).size)
        assertEquals("second", SubmissionLog.forSlug(dir, "midnight_knot")!!.id)
    }

    @Test
    fun `two different faces both keep their records`(@TempDir dir: File) {
        SubmissionLog.record(dir, "one", "id-1")
        SubmissionLog.record(dir, "two", "id-2")
        assertEquals(listOf("id-1", "id-2"), SubmissionLog.list(dir).map { it.id })
    }

    @Test
    fun `a state update lands on the right record`(@TempDir dir: File) {
        SubmissionLog.record(dir, "one", "id-1")
        SubmissionLog.record(dir, "two", "id-2")
        SubmissionLog.setState(dir, "id-2", SubmissionLog.State.PUBLISHED)
        assertEquals(SubmissionLog.State.PENDING, SubmissionLog.forSlug(dir, "one")!!.state)
        assertEquals(SubmissionLog.State.PUBLISHED, SubmissionLog.forSlug(dir, "two")!!.state)
        assertTrue(SubmissionLog.forSlug(dir, "two")!!.settled)
    }

    @Test
    fun `withdrawing forgets the submission`(@TempDir dir: File) {
        SubmissionLog.record(dir, "one", "id-1")
        SubmissionLog.forget(dir, "id-1")
        assertNull(SubmissionLog.forSlug(dir, "one"))
    }

    /** Updating something that is not there does nothing, rather than inventing it. */
    @Test
    fun `an update for an unknown id changes nothing`(@TempDir dir: File) {
        SubmissionLog.record(dir, "one", "id-1")
        SubmissionLog.setState(dir, "not-a-real-id", SubmissionLog.State.REJECTED)
        SubmissionLog.forget(dir, "also-not-real")
        assertEquals(listOf("id-1"), SubmissionLog.list(dir).map { it.id })
        assertEquals(SubmissionLog.State.PENDING, SubmissionLog.forSlug(dir, "one")!!.state)
    }

    /**
     * THE ONE THAT DECIDES WHAT SOMEBODY IS TOLD.
     *
     * A state this build has never heard of has to read as "still in the
     * queue". Any other default eventually tells an author their face was
     * refused because the service added a word — which is bad news that never
     * happened, about work somebody made.
     */
    @Test
    fun `an unknown state reads as pending rather than as bad news`() {
        assertEquals(SubmissionLog.State.PENDING, SubmissionLog.State.of("escalated"))
        assertEquals(SubmissionLog.State.PENDING, SubmissionLog.State.of(""))
        assertEquals(SubmissionLog.State.REJECTED, SubmissionLog.State.of("rejected"))
        assertEquals(SubmissionLog.State.PUBLISHED, SubmissionLog.State.of(" PUBLISHED "))
    }

    /** Every state the SERVICE can send is one this app can name. */
    @Test
    fun `the states the service uses all parse`() {
        for (wire in listOf("pending", "published", "rejected", "removed")) {
            assertEquals(wire, SubmissionLog.State.of(wire).wire) {
                "$wire round-trips to something else, so the app would misreport it"
            }
        }
    }

    @Test
    fun `a corrupt file reads as empty rather than throwing`(@TempDir dir: File) {
        File(dir, "submissions.json").writeText("{not json at all")
        assertEquals(emptyList<SubmissionLog.Record>(), SubmissionLog.list(dir))
        // And the next write repairs it rather than compounding the damage.
        SubmissionLog.record(dir, "one", "id-1")
        assertEquals(listOf("id-1"), SubmissionLog.list(dir).map { it.id })
    }

    @Test
    fun `names with quotes do not break the file`(@TempDir dir: File) {
        SubmissionLog.record(dir, "quote_\"slug\"", "id\\1")
        val back = SubmissionLog.list(dir).single()
        assertEquals("quote_\"slug\"", back.slug)
        assertEquals("id\\1", back.id)
    }
}
