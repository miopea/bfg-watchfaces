package com.bfg.watchfaces.appcore

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a person is told about a face they shared, in both lengths.
 *
 * ## The bug this exists for
 *
 * The face row showed [SubmissionLog.describe] — a full sentence — in the same
 * small grey type as the install name directly above it. Three grey lines
 * stacked up and the one that was a STATE looked like more metadata, so a face
 * waiting on moderation was indistinguishable from a face nobody had shared.
 * The operator reported it: "it isn't obvious that Default is shared and
 * waiting for approval."
 *
 * [SubmissionLog.label] is the fix — the same fact, short enough to sit in a
 * chip that reads as a state. Two lengths of one vocabulary, which is only safe
 * while they cannot drift apart or grow into something a chip cannot hold.
 */
class SubmissionWordsTest {

    /**
     * Every state answers in both lengths.
     *
     * A `when` over an enum is exhaustive, so a new state cannot compile
     * without a sentence — but it CAN compile with a label copied off its
     * neighbour, and two states sharing a chip is a chip that says nothing.
     */
    @Test
    fun `every state has its own words, in both lengths`() {
        val labels = SubmissionLog.State.entries.map { SubmissionLog.label(it) }
        val sentences = SubmissionLog.State.entries.map { SubmissionLog.describe(it) }

        assertTrue(labels.none { it.isBlank() }) { "a state has no chip label: $labels" }
        assertTrue(sentences.none { it.isBlank() }) { "a state has no sentence: $sentences" }

        assertTrue(labels.size == labels.toSet().size) {
            "two states share a chip label, so the chip cannot tell them apart: $labels"
        }
        assertTrue(sentences.size == sentences.toSet().size) {
            "two states share a sentence: $sentences"
        }
    }

    /**
     * A chip that wraps is worse than no chip.
     *
     * This is the constraint the label exists to satisfy — it is short BECAUSE
     * it goes in a chip beside a name, and a four-word label pushes the row
     * back into the wall of text this replaced.
     */
    @Test
    fun `a chip label stays short enough to be a chip`() {
        for (state in SubmissionLog.State.entries) {
            val label = SubmissionLog.label(state)
            assertTrue(label.length <= 20) {
                "$state's chip label is ${label.length} characters (\"$label\"); " +
                    "a chip this wide wraps beside a face name"
            }
            assertTrue(label.split(" ").size <= 4) {
                "$state's chip label is a phrase, not a label: \"$label\""
            }
            assertFalse(label.endsWith(".")) {
                "$state's chip label ends in a full stop (\"$label\"); the SENTENCE is " +
                    "SubmissionLog.describe, the chip is a label"
            }
        }
    }

    /**
     * The two lengths describe the same thing.
     *
     * Not a string comparison — they are deliberately different words. This
     * pins the one pair that would be actively misleading if it drifted:
     * PUBLISHED and PENDING are the states a person acts on, and a chip saying
     * a face is in the gallery over a sentence saying it is still waiting is
     * worse than either alone.
     */
    @Test
    fun `the chip and the sentence agree about the gallery`() {
        val published = SubmissionLog.label(SubmissionLog.State.PUBLISHED).lowercase()
        val publishedSentence = SubmissionLog.describe(SubmissionLog.State.PUBLISHED).lowercase()
        assertTrue(published.contains("gallery") && publishedSentence.contains("gallery")) {
            "PUBLISHED should say the same thing twice: \"$published\" / \"$publishedSentence\""
        }

        val pending = SubmissionLog.label(SubmissionLog.State.PENDING).lowercase()
        assertFalse(pending.contains("gallery")) {
            "PENDING's chip says \"$pending\", which reads as though the face is already " +
                "in the gallery. It is still waiting."
        }
    }
}
