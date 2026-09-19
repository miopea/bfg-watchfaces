package com.bfg.watchfaces.appcore

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The report a person copies out of a failed send.
 *
 * ## The bug this exists for
 *
 * A shipped build could not send any face with dark text, and all the person
 * could say was "it doesn't work" — so the cause took developer mode, wireless
 * adb, an SSH tunnel and about forty scripted sends to reach. These pin the
 * three properties that make the copied text worth more than that: it says
 * which build, it says where it stopped, and it does not soften the error.
 */
class FailureReportTest {

    private fun report(
        version: String = "1.82 (83)",
        stage: FailureReport.Stage = FailureReport.Stage.VALIDATE,
        name: String = "Testing",
        type: String = "IllegalStateException",
        message: String? = "Invalid content was found starting with element 'Variant'"
    ) = FailureReport.text(version, stage, name, type, message)

    /**
     * The version leads, and that is not cosmetic ordering.
     *
     * A report that reaches us without a build number costs a round trip to a
     * person who has already been let down once. First line, every time.
     */
    @Test
    fun `the build comes first`() {
        val first = report().lineSequence().first()
        assertTrue(first.contains("1.82 (83)")) {
            "the first line must name the build; it was \"$first\""
        }
    }

    /**
     * Build and validate must not read alike.
     *
     * These two send an investigation to opposite halves of the code — the
     * packer and renderer, or the emitted XML. Establishing which one it was
     * took a tunnel to a phone once already.
     */
    @Test
    fun `the two stages are told apart`() {
        val build = report(stage = FailureReport.Stage.BUILD)
        val validate = report(stage = FailureReport.Stage.VALIDATE)
        assertFalse(build == validate) { "both stages produced identical text" }
        assertTrue(FailureReport.Stage.entries.map { it.wording }.toSet().size
            == FailureReport.Stage.entries.size) { "two stages share wording" }
    }

    /**
     * The error survives verbatim.
     *
     * The friendly sentence already happened elsewhere. If this paraphrases,
     * the one part that names an element and a line is gone and the report is
     * back to being "it doesn't work" with extra steps.
     */
    @Test
    fun `the error text is not softened`() {
        val exact = "cvc-complex-type.2.4.a: Invalid content was found starting with element 'Variant'"
        assertTrue(report(message = exact).contains(exact)) {
            "the validator's own words must survive into the report"
        }
    }

    /**
     * A Throwable's message is nullable, so this one is too.
     *
     * The failure mode being pinned is cosmetic but corrosive: a trailing
     * ": " reads as though the text was cut off, which invites somebody to
     * wonder what they lost instead of sending what they have.
     */
    @Test
    fun `a missing message leaves no dangling separator`() {
        for (empty in listOf(null, "", "   ")) {
            val text = report(type = "TimeoutException", message = empty)
            assertTrue(text.trimEnd().endsWith("TimeoutException")) {
                "message=${empty?.let { "\"$it\"" } ?: "null"} produced: \"$text\""
            }
            assertFalse(text.contains("TimeoutException:")) {
                "a separator was written with nothing after it: \"$text\""
            }
        }
    }

    /** A nameless error still names something rather than nothing. */
    @Test
    fun `a blank error type still says Error`() {
        assertTrue(report(type = "", message = null).trimEnd().endsWith("Error"))
    }
}
