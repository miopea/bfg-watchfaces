package com.bfg.watchfaces.appcore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The one value in this app that reaches the watch without rebuilding a face.
 *
 * Both sides read these rules, so they are pinned here rather than trusted to
 * two implementations that would eventually disagree about what was typed.
 */
class PhoneNoteTest {

    @Test
    fun `a note survives a round trip`(@TempDir dir: File) {
        PhoneNote.save(dir, "Back at 6")
        assertEquals("Back at 6", PhoneNote.load(dir))
        assertTrue(PhoneNote.has(dir))
    }

    @Test
    fun `nothing stored reads as empty rather than crashing`(@TempDir dir: File) {
        assertEquals("", PhoneNote.load(dir))
        assertFalse(PhoneNote.has(dir))
    }

    /**
     * An empty note REMOVES it, rather than storing a blank.
     *
     * A slot showing nothing and a slot showing a blank are different on a
     * watch: the first falls back, the second is a visible gap.
     */
    @Test
    fun `clearing a note removes it`(@TempDir dir: File) {
        PhoneNote.save(dir, "something")
        assertEquals("", PhoneNote.save(dir, "   "))
        assertFalse(PhoneNote.has(dir)) { "a blank note was stored instead of cleared" }
    }

    /**
     * A complication is one line, so a pasted paragraph becomes one.
     *
     * Rejecting it would lose the words, which is not what somebody pasting two
     * lines meant — they meant the text, not the break, and a complication has
     * nowhere to put a break.
     */
    @Test
    fun `newlines and runs of whitespace collapse`() {
        assertEquals("one two", PhoneNote.clean("one\n\ntwo"))
        assertEquals("one two", PhoneNote.clean("  one   two  "))
        assertEquals("one two", PhoneNote.clean("one\ttwo"))
    }

    /**
     * Truncation happens on the PHONE, where somebody can see it.
     *
     * The watch truncates a SHORT_TEXT complication regardless. Doing it here
     * means what is stored equals what is shown, and the limit is visible while
     * typing rather than discovered on a wrist.
     */
    @Test
    fun `a long note is cut to what a complication can hold`(@TempDir dir: File) {
        val long = "a".repeat(PhoneNote.MAX_LENGTH * 3)
        val stored = PhoneNote.save(dir, long)
        assertEquals(PhoneNote.MAX_LENGTH, stored.length)
        assertEquals(stored, PhoneNote.load(dir)) { "stored and shown disagree" }
    }

    /** Whatever is written, reading it back gives the same answer. */
    @Test
    fun `load is idempotent with save`(@TempDir dir: File) {
        for (raw in listOf("Hi", "  padded  ", "line\nbreak", "x".repeat(50), "é ü ñ")) {
            val saved = PhoneNote.save(dir, raw)
            assertEquals(saved, PhoneNote.load(dir)) { "'$raw' round-tripped differently" }
        }
    }

    /**
     * The placeholder is not blank, deliberately.
     *
     * A complication rendering nothing looks like a provider that failed, and
     * somebody who has just chosen this source needs to see it working before
     * they have typed anything.
     */
    @Test
    fun `the empty placeholder is something you can see`() {
        assertTrue(PhoneNote.EMPTY_PLACEHOLDER.isNotBlank())
        assertTrue(PhoneNote.EMPTY_PLACEHOLDER.length <= 3) { "the placeholder is a value, not a sentence" }
    }

    /**
     * A corrupt file reads as NO NOTE, never as a crash and never as garbage.
     *
     * ## What this used to assert, which was nothing
     *
     * It wrote two NUL bytes and then checked `clean(note) == note`. Since
     * [PhoneNote.load] already applies `clean` and `clean` is idempotent, that
     * compared `clean(clean(x))` with `clean(x)` and held for EVERY possible
     * input. It was an assertion about `clean` being idempotent, not about a
     * corrupt file, and it passed while the behaviour it named did not happen:
     * `load` returned the two NUL characters and `has` reported true.
     *
     * Those bytes reached further than a test. Nothing between here and the
     * watch removed them, so a truncated write would have arrived at a
     * SHORT_TEXT complication and rendered as tofu on somebody's dial.
     *
     * Fixed on both sides on 2026-09-03: `clean` now strips control characters,
     * and this asserts the outcome the name always claimed.
     */
    @Test
    fun `an unreadable note reads as absent`(@TempDir dir: File) {
        File(dir, "phone-note.txt").writeText("\u0000\u0000")

        assertEquals("", PhoneNote.load(dir)) { "control bytes survived load()" }
        assertFalse(PhoneNote.has(dir)) { "a file of control bytes counts as a note" }
    }

    /**
     * Control bytes are removed, and the words either side of them are not.
     *
     * The order inside `clean` is load-bearing: whitespace collapses to spaces
     * FIRST, then controls are removed. Filtering first would delete the tab in
     * "one\ttwo" and leave "onetwo".
     */
    @Test
    fun `a control byte in the middle of a note does not eat the words`() {
        assertEquals("one two", PhoneNote.clean("one" + "\u0000" + " two"))
        assertEquals("one two", PhoneNote.clean("one\ttwo"))
        assertEquals("ab", PhoneNote.clean("a\u0000b"))
    }

    /**
     * A zero-width joiner is NOT a control character and must survive.
     *
     * It is what holds a family emoji together. Stripping the wider "format"
     * category along with the control one would quietly break somebody's note
     * into separate people.
     */
    @Test
    fun `a joined emoji is not taken apart`() {
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"
        assertEquals(family, PhoneNote.clean(family)) { "the joiner was stripped" }
    }
}
