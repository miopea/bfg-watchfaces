package com.bfg.watchfaces.appcore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The words for a complication live in exactly one place.
 *
 * ## The bug this exists because of
 *
 * `:mobile` carried its own `sample()` and `label()` over
 * [ComplicationSource], duplicating [Complications]. They had already drifted:
 * `DAY_AND_DATE` sampled as "TUE MAR 10" on the phone and "MAR 10" in
 * `:appcore`, `TIME_AND_DATE` as "10:10 TUE" against "10:10".
 *
 * Sample strings are not decoration. [SlotGeometry] decides whether a value
 * fits its box from how many characters it runs to, so two different samples
 * are two different answers to "does this fit" — the phone preview and the
 * workbench preview drawing different faces from the same parameters. That is
 * precisely the class of bug `SlotGeometry` was created to end, reappearing one
 * layer up: in the words rather than the boxes.
 *
 * `label()` matters for a second reason: it is written into the built face's
 * `strings.xml` as each slot's `displayName`, so a second table lets the app
 * call a slot one thing and the WATCH call it another.
 *
 * ## Why this scans source rather than asserting on code
 *
 * The duplicate was in a module `:appcore` cannot see — `:mobile` depends on
 * this one, not the other way round — so there is nothing to import and compare.
 * Reading the files is the only way to notice, and noticing is the whole job.
 */
class OneVocabularyTest {

    /** Every Kotlin source in the repo that is not this module's own. */
    private fun otherModuleSources(): List<File> {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        return listOf("mobile", "wear", "workbench")
            .map { File(root, "$it/src") }
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" } }
    }

    /**
     * A POSITIVE CONTROL, and it is not ceremony.
     *
     * If the walk finds nothing — a moved directory, a renamed module, a test
     * run from somewhere unexpected — every assertion below passes while
     * measuring nothing at all. That is indistinguishable from passing because
     * the code is right, which is the failure `ci-integrity.md` is about.
     */
    @Test
    fun `the scan actually reads files`() {
        val sources = otherModuleSources()
        assertTrue(sources.size > 20) {
            "only found ${sources.size} sources in the other modules, so the checks " +
                "below are measuring nothing. Did a module move?"
        }
        assertTrue(sources.any { it.name == "AndroidFacePreview.kt" }) {
            "the phone's preview was not among the scanned files"
        }
    }

    @Test
    fun `nothing else declares its own complication sample table`() {
        val offenders = otherModuleSources().filter {
            Regex("""fun\s+sample\s*\(\s*\w+\s*:\s*ComplicationSource""").containsMatchIn(it.readText())
        }
        assertEquals(emptyList<String>(), offenders.map { it.name }) {
            "a second sample table means two answers to whether a value fits its " +
                "box, and so two different previews of one face. Use Complications.sample."
        }
    }

    @Test
    fun `nothing else declares its own complication label table`() {
        val offenders = otherModuleSources().filter {
            Regex("""fun\s+label\s*\(\s*\w+\s*:\s*ComplicationSource""").containsMatchIn(it.readText())
        }
        assertEquals(emptyList<String>(), offenders.map { it.name }) {
            "a second label table lets the app call a slot one thing and the watch, " +
                "which gets Complications.label in its strings.xml, call it another."
        }
    }
}
