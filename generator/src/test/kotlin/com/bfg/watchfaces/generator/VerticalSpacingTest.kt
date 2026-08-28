package com.bfg.watchfaces.generator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.math.sqrt

/**
 * Spacing is ONE control, and from v5 it works on both axes.
 *
 * It used to move only the middle row, so "Wide" widened three slots and left
 * the top and bottom exactly where they were -- the dial got wider and no
 * airier, which is not what anyone means by the word.
 *
 * The risk in changing it is that complication placement IS the stored file
 * format: every face in the catalog is parameters, so moving a slot rewrites
 * faces their authors already saved. Hence v5, and hence
 * `a face saved before v5 is not moved by this` below, which is the test that
 * actually matters.
 */
class VerticalSpacingTest {

    private val allFive = listOf(
        ComplicationSource.DAY_AND_DATE, ComplicationSource.STEP_COUNT,
        ComplicationSource.HEART_RATE, ComplicationSource.UNREAD_NOTIFICATION_COUNT,
        ComplicationSource.WATCH_BATTERY
    )

    private fun params(spread: Int, version: Int = CURRENT_GENERATOR_VERSION) = DialParams(
        generatorVersion = version,
        complications = allFive,
        layout = Layout(complicationSpread = spread)
    )

    /** The whole slider range the workbench offers, not just its three presets. */
    private val range = listOf(60, 70, 78, 84, 92, 100, 110, 125, 140, 150)

    @Test
    fun `spacing moves the end slots, not only the row`() {
        val normal = SlotGeometry.boxes(params(92))
        val wide = SlotGeometry.boxes(params(110))

        assertTrue(wide[SlotPosition.TOP]!!.y < normal[SlotPosition.TOP]!!.y) {
            "the top slot did not rise: ${normal[SlotPosition.TOP]} -> ${wide[SlotPosition.TOP]}"
        }
        assertTrue(wide[SlotPosition.BOTTOM]!!.y > normal[SlotPosition.BOTTOM]!!.y) {
            "the bottom slot did not drop: ${normal[SlotPosition.BOTTOM]} -> ${wide[SlotPosition.BOTTOM]}"
        }
    }

    @Test
    fun `the clock stays put -- it is the thing everything else spaces away from`() {
        val a = WffEmitter.emit(params(60))
        val b = WffEmitter.emit(params(150))
        val timeY = Layout().timeY
        assertTrue(a.contains("""y="${timeY - Layout().timeSize / 2}"""")) { "no clock in the tight face" }
        assertTrue(b.contains("""y="${timeY - Layout().timeSize / 2}"""")) { "no clock in the wide face" }
    }

    @ParameterizedTest
    @ValueSource(ints = [60, 70, 78, 84, 92, 100, 110, 125, 140, 150])
    fun `no two slots overlap at any spacing`(spread: Int) {
        val boxes = SlotGeometry.boxes(params(spread))
        assertEquals(5, boxes.size)
        assertFalse(SlotGeometry.hasOverlap(boxes.values)) {
            "slots overlap at spacing=$spread:\n" + boxes.entries.joinToString("\n") { "  ${it.key}: ${it.value}" }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [60, 70, 78, 84, 92, 100, 110, 125, 140, 150])
    fun `every slot stays inside the dial at any spacing`(spread: Int) {
        for ((pos, b) in SlotGeometry.boxes(params(spread))) {
            for (cx in listOf(b.x, b.right)) for (cy in listOf(b.y, b.bottom)) {
                val dx = cx - DIAL_CENTER
                val dy = cy - DIAL_CENTER
                val d = sqrt(dx * dx + dy * dy)
                assertTrue(d <= DIAL_RADIUS) {
                    "$pos corner ($cx,$cy) is ${"%.1f".format(d)} from centre at spacing=$spread"
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [60, 70, 78, 84, 92, 100, 110, 125, 140, 150])
    fun `no slot collides with the clock at any spacing`(spread: Int) {
        val l = Layout(complicationSpread = spread)
        val half = (l.timeSize * 0.42).toInt()
        val timeTop = l.timeY - half
        val timeBottom = l.timeY + half
        for ((pos, b) in SlotGeometry.boxes(params(spread))) {
            assertTrue(b.bottom <= timeTop || b.y >= timeBottom) {
                "$pos ($b) runs into the clock at spacing=$spread"
            }
        }
    }

    /**
     * The guard that makes the version bump worth anything. A v4 face is stored
     * parameters; if v5 arithmetic reached it, every saved face would silently
     * move the next time it was opened.
     */
    @ParameterizedTest
    @ValueSource(ints = [60, 84, 92, 110, 150])
    fun `a face saved before v5 is not moved by this`(spread: Int) {
        val old = SlotGeometry.boxes(params(spread, version = 4))
        val neutral = SlotGeometry.boxes(params(92, version = 4))
        assertEquals(0, SlotGeometry.verticalAir(params(spread, version = 4)))
        assertEquals(neutral[SlotPosition.TOP], old[SlotPosition.TOP]) {
            "a v4 face's top slot moved when spacing changed to $spread"
        }
        assertEquals(neutral[SlotPosition.BOTTOM], old[SlotPosition.BOTTOM]) {
            "a v4 face's bottom slot moved when spacing changed to $spread"
        }
    }

    @Test
    fun `wider never moves a slot the wrong way`() {
        var lastTop = Int.MAX_VALUE
        var lastBottom = Int.MIN_VALUE
        for (spread in range) {
            val b = SlotGeometry.boxes(params(spread))
            val top = b[SlotPosition.TOP]!!.y
            val bottom = b[SlotPosition.BOTTOM]!!.y
            assertTrue(top <= lastTop) { "top slot dropped from $lastTop to $top going to spacing=$spread" }
            assertTrue(bottom >= lastBottom) { "bottom slot rose from $lastBottom to $bottom going to spacing=$spread" }
            lastTop = top
            lastBottom = bottom
        }
    }

    // ---- what the readout is allowed to claim ---------------------------------

    @Test
    fun `at normal spacing nothing is reported as adjusted`() {
        val e = SlotGeometry.effective(params(92))
        assertEquals(0, e.verticalAir)
        assertFalse(e.verticalClamped)
        assertFalse(e.spreadClamped)
    }

    @Test
    fun `tightening past what the layout allows is admitted, not hidden`() {
        // The bottom slot already sits at its minimum gap from the row at normal
        // spacing, so it has nothing left to give. Saying so is the point: a
        // control that silently ignores you is the failure this reports.
        val e = SlotGeometry.effective(params(60))
        assertTrue(e.verticalAir < 0)
        assertTrue(e.verticalClamped) { "spacing was refused at 60 and the readout claimed otherwise" }
    }

    @Test
    fun `spacing that is honoured in full is not reported as adjusted`() {
        val e = SlotGeometry.effective(params(110))
        assertTrue(e.verticalAir > 0)
        assertFalse(e.verticalClamped) { "spacing at 110 moved both end slots in full but was reported as refused" }
    }

    @Test
    fun `with no top or bottom slot, vertical spacing has nothing to move and says so`() {
        val N = ComplicationSource.NONE
        val H = ComplicationSource.HEART_RATE
        val e = SlotGeometry.effective(
            DialParams(complications = listOf(N, H, H, H, N), layout = Layout(complicationSpread = 110))
        )
        assertTrue(e.verticalClamped) { "there is no end slot to space out, which is not the same as it having worked" }
    }
}
