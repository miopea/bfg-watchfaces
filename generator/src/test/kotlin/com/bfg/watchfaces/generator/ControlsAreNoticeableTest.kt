package com.bfg.watchfaces.generator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A control has to CHANGE something a person can see.
 *
 * This project has shipped the opposite three times: "Large" clamped to within
 * four points of "Medium"; Tight, Normal and Wide all resolving to the same
 * number; and a date size that could not exceed the value already stored. Each
 * time the code was doing exactly what it said and the face did not move, and
 * each time it was found by someone using it rather than by a test.
 *
 * So the assertion is not "the numbers differ" — they always did — but that the
 * RENDERED result differs by enough to notice.
 */
class ControlsAreNoticeableTest {

    /** Faces worth checking: the layout's room changes a lot between these. */
    private fun faces() = listOf(
        "everything on" to DialParams(),
        "with a date" to DialParams(dateStyle = DateStyle.WEEKDAY_MONTH_DAY),
        "no top slot" to DialParams().withSlot(SlotPosition.TOP, ComplicationSource.NONE),
        "no glyphs" to DialParams(iconSlots = emptySet())
    )

    @Test
    fun `each complication size is visibly bigger than the one before`() {
        for ((label, p) in faces()) {
            val fonts = SlotGeometry.sizeOptions(p)
                .map { SlotGeometry.fontSize(SlotGeometry.fittedSize(p.copy(
                    layout = p.layout.copy(complicationSize = it)
                ))) }
            for (i in 1 until fonts.size) {
                assertTrue(fonts[i] - fonts[i - 1] >= 3) {
                    "$label: complication text goes ${fonts.joinToString(" -> ")}pt; " +
                        "steps ${i - 1} and $i differ by ${fonts[i] - fonts[i - 1]}pt, " +
                        "which nobody would call a different size"
                }
            }
        }
    }

    @Test
    fun `each spacing visibly moves the row slots apart`() {
        for ((label, p) in faces()) {
            val gaps = SlotGeometry.spreadOptions(p).map { spread ->
                val boxes = SlotGeometry.boxes(p.copy(
                    layout = p.layout.copy(complicationSpread = spread)
                ))
                val l = boxes[SlotPosition.LEFT]; val m = boxes[SlotPosition.MIDDLE]
                if (l != null && m != null) m.x - l.x else 0
            }
            for (i in 1 until gaps.size) {
                assertTrue(gaps[i] - gaps[i - 1] >= 6) {
                    "$label: spacing goes ${gaps.joinToString(" -> ")}px; steps " +
                        "${i - 1} and $i differ by ${gaps[i] - gaps[i - 1]}px"
                }
            }
        }
    }

    @Test
    fun `wider spacing also pushes the row away from the clock`() {
        // The gap between the time and the complications is the one that reads
        // as crowding, and spacing used to leave it alone entirely.
        val p = DialParams()
        val options = SlotGeometry.spreadOptions(p)
        val tops = options.map { spread ->
            SlotGeometry.boxes(p.copy(layout = p.layout.copy(complicationSpread = spread)))[
                SlotPosition.LEFT
            ]!!.y
        }
        assertTrue(tops.last() > tops.first()) {
            "the row sits at y=${tops.joinToString(", ")} across the spacings; " +
                "wider spacing did not move it down at all"
        }
    }

    @Test
    fun `each date size is visibly different`() {
        val p = DialParams(dateStyle = DateStyle.WEEKDAY_MONTH_DAY)
        val sizes = DateScale.entries.map { SlotGeometry.fittedDateSize(p.copy(dateScale = it)) }
        for (i in 1 until sizes.size) {
            assertTrue(sizes[i] - sizes[i - 1] >= 4) {
                "date sizes are ${sizes.joinToString(" -> ")}pt"
            }
        }
    }

    /** A drawn value is never clipped, whichever wording it ends up using. */
    @Test
    fun `a long drawn value fits inside its slot`() {
        val p = DialParams(complications = List(5) { ComplicationSource.WEATHER_TEMP_CONDITION })
        val fitted = SlotGeometry.fittedSize(p)
        val base = SlotGeometry.fontSize(fitted)
        for ((pos, box) in SlotGeometry.boxes(p)) {
            val drawn = SlotGeometry.drawnText(ComplicationSource.WEATHER_TEMP_CONDITION, box, base)
            val width = drawn.fontSize * 0.62 * drawn.widestValue
            assertTrue(width <= box.w) {
                "$pos: \"${drawn.format}\" needs ${width.toInt()}px at ${drawn.fontSize}pt in a " +
                    "${box.w}px box, so it would be clipped -- which reached a watch as \"° Unknow\""
            }
        }
    }

    /**
     * The long value is SHORTENED, not shrunk.
     *
     * This is the whole fix. "71° Cloudy" used to render at 19pt beside
     * neighbours at 29 — reported from a wrist as almost impossible to read.
     * Dropping the condition keeps the temperature at the size of the number
     * next to it, which is what a complication is for.
     */
    @Test
    fun `a long weather value drops the word rather than the point size`() {
        val p = DialParams(complications = List(5) { ComplicationSource.WEATHER_TEMP_CONDITION })
        val base = SlotGeometry.fontSize(SlotGeometry.fittedSize(p))
        val row = SlotGeometry.boxes(p)[SlotPosition.LEFT]!!
        val drawn = SlotGeometry.drawnText(ComplicationSource.WEATHER_TEMP_CONDITION, row, base)
        assertEquals(base, drawn.fontSize) {
            "the temperature came down to ${drawn.fontSize}pt from $base rather than dropping the word"
        }
        assertEquals("72°", drawn.sample)
        assertEquals(listOf("[WEATHER.TEMPERATURE]"), drawn.expressions)
    }

    /** A value that already fits is left exactly as it was written. */
    @Test
    fun `a short drawn value keeps its full wording`() {
        val p = DialParams(complications = List(5) { ComplicationSource.WEATHER_TEMPERATURE })
        val base = SlotGeometry.fontSize(SlotGeometry.fittedSize(p))
        val row = SlotGeometry.boxes(p)[SlotPosition.LEFT]!!
        val drawn = SlotGeometry.drawnText(ComplicationSource.WEATHER_TEMPERATURE, row, base)
        assertEquals(base, drawn.fontSize)
        assertEquals(ComplicationSource.WEATHER_TEMPERATURE.format, drawn.format)
        assertNull(drawn.sample) { "nothing was shortened, so a preview has no override to draw" }
    }
}
