package com.bfg.watchfaces.mobile

import com.bfg.watchfaces.generator.ComplicationSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Every complication is findable, and each appears exactly once.
 *
 * ## The bug this exists for
 *
 * The picker's tail was "everything else, in the enum's own order" — the order
 * things were added to `ComplicationSource` over months. Reported on
 * 2026-09-19 as "the complications options seem a bit disorganized", and the
 * operator chose grouping by kind.
 *
 * ## What actually needs defending
 *
 * Not the grouping itself, which is taste. The property worth a test is that
 * the sections between them still show EVERYTHING: a source nobody can reach
 * is a source that does not exist, and the way to lose one is to add it to
 * `:generator` and forget the picker. `groupOf` is a `when` over the enum so
 * that fails to compile — but nothing stops a future edit adding an `else`,
 * and nothing at all guards the three sections agreeing with each other.
 */
class PickerGroupingTest {

    private val shown: List<ComplicationSource> =
        Presentation.PICKER_COMMON +
            Presentation.PICKER_SHORTCUTS +
            Presentation.PICKER_GROUPED.flatMap { it.second }

    @Test
    fun `every source is reachable in the picker`() {
        val missing = ComplicationSource.entries.filter { it !in shown }
        assertTrue(missing.isEmpty()) { "sources no one can pick: $missing" }
    }

    @Test
    fun `no source is offered twice`() {
        val duplicated = shown.groupBy { it }.filter { it.value.size > 1 }.keys
        assertTrue(duplicated.isEmpty()) { "offered in more than one section: $duplicated" }
    }

    /**
     * A heading with nothing under it reads as a bug in the app.
     *
     * Reachable today: curate every weather source into the top six and the
     * weather group empties, leaving a title and a gap.
     */
    @Test
    fun `no group is rendered empty`() {
        val empty = Presentation.PICKER_GROUPED.filter { it.second.isEmpty() }
        assertTrue(empty.isEmpty()) { "empty headings would be drawn: ${empty.map { it.first }}" }
    }

    /** Two sections sharing a title would look like the list repeated itself. */
    @Test
    fun `group headings are distinct`() {
        val headings = Presentation.PICKER_GROUPED.map { it.first.heading }
        assertEquals(headings.size, headings.toSet().size)
    }
}
