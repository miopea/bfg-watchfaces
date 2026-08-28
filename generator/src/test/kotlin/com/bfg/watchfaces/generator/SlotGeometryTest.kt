package com.bfg.watchfaces.generator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.math.sqrt

/**
 * The old hand-placed slot positions overlapped each other by 3px horizontally
 * and 14px vertically, and ran into the clock at both ends. Nothing caught it
 * because nothing asserted it -- the tests checked that the emitter and the
 * preview AGREED, and they agreed on being wrong.
 */
class SlotGeometryTest {

    private val allFive = listOf(
        ComplicationSource.DAY_AND_DATE, ComplicationSource.STEP_COUNT,
        ComplicationSource.HEART_RATE, ComplicationSource.UNREAD_NOTIFICATION_COUNT,
        ComplicationSource.WATCH_BATTERY
    )

    @ParameterizedTest
    @ValueSource(ints = [12, 14, 16, 19, 23, 26, 30])
    fun `no two slots ever overlap, at any size`(size: Int) {
        val p = DialParams(complications = allFive, layout = Layout(complicationSize = size))
        val boxes = SlotGeometry.boxes(p)
        assertEquals(5, boxes.size)
        assertFalse(SlotGeometry.hasOverlap(boxes.values)) {
            "slots overlap at complicationSize=$size:\n" +
                boxes.entries.joinToString("\n") { "  ${it.key}: ${it.value}" }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [12, 16, 19, 23, 30])
    fun `no slot collides with the clock`(size: Int) {
        val p = DialParams(complications = allFive, layout = Layout(complicationSize = size))
        val l = p.layout
        val half = (l.timeSize * 0.42).toInt()
        val timeTop = l.timeY - half
        val timeBottom = l.timeY + half
        for ((pos, b) in SlotGeometry.boxes(p)) {
            val clear = b.bottom <= timeTop || b.y >= timeBottom
            assertTrue(clear) { "$pos ($b) runs into the clock band $timeTop..$timeBottom at size=$size" }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [12, 16, 19, 23, 30])
    fun `every slot stays inside the dial circle`(size: Int) {
        val p = DialParams(complications = allFive, layout = Layout(complicationSize = size))
        for ((pos, b) in SlotGeometry.boxes(p)) {
            // Check all four corners against the circle, not the square: the
            // dial curves away and a corner can leave it while x/y look fine.
            for (cx in listOf(b.x, b.right)) for (cy in listOf(b.y, b.bottom)) {
                val dx = cx - DIAL_CENTER
                val dy = cy - DIAL_CENTER
                val d = sqrt(dx * dx + dy * dy)
                assertTrue(d <= DIAL_RADIUS) {
                    "$pos corner ($cx,$cy) is ${"%.1f".format(d)} from centre, outside the ${DIAL_RADIUS} dial at size=$size"
                }
            }
        }
    }

    @Test
    fun `disabled slots are absent and the row re-centres`() {
        val N = ComplicationSource.NONE
        val H = ComplicationSource.HEART_RATE
        val three = SlotGeometry.boxes(DialParams(complications = listOf(N, H, H, H, N)))
        val one = SlotGeometry.boxes(DialParams(complications = listOf(N, N, H, N, N)))
        assertEquals(3, three.size)
        assertEquals(1, one.size)
        assertEquals(three[SlotPosition.MIDDLE]!!.x, one[SlotPosition.MIDDLE]!!.x) {
            "a lone row slot did not re-centre"
        }
    }

    @Test
    fun `a spread narrower than the boxes is widened rather than obeyed`() {
        // The stored value is a preference. Overlapping is not a look anyone
        // chose, so the geometry refuses it.
        val p = DialParams(
            complications = listOf(ComplicationSource.NONE, ComplicationSource.STEP_COUNT,
                ComplicationSource.HEART_RATE, ComplicationSource.DATE, ComplicationSource.NONE),
            layout = Layout(complicationSpread = 10)
        )
        val boxes = SlotGeometry.boxes(p)
        assertFalse(SlotGeometry.hasOverlap(boxes.values)) { "a tiny spread produced overlapping slots" }
    }

    @Test
    fun `boxes are sized to their content, not padded out`() {
        val size = 19
        // icon + one line of text must fit, with no more than a little slack.
        val content = SlotGeometry.textOffset(size) + SlotGeometry.textHeight(size)
        val h = SlotGeometry.boxHeight(size)
        assertTrue(h >= content) { "box height $h cannot hold $content of content" }
        assertTrue(h - content <= size / 2) { "box height $h leaves ${h - content}px of dead space" }
    }

    @Test
    fun `the emitted WFF uses exactly these boxes`() {
        // Guards the thing that actually breaks: the emitter drifting from the
        // geometry the preview draws.
        val p = DialParams(complications = allFive)
        val xml = WffEmitter.emit(p)
        for ((_, b) in SlotGeometry.boxes(p)) {
            assertTrue(xml.contains("""x="${b.x}" y="${b.y}" width="${b.w}" height="${b.h}"""")) {
                "emitted WFF has no slot at $b"
            }
        }
    }
}

/**
 * The clock band once assumed the digits were centred on `timeY`. They are
 * centred inside the DigitalClock element box, which sits timeSize*0.2 lower --
 * 20px at the default size. The band was that far too high and the complication
 * row slid under the descenders, which is visible on the dial and invisible to
 * every other test.
 */
class ClockBandTest {

    private fun rowTop(p: DialParams): Int =
        SlotGeometry.boxes(p)[SlotPosition.LEFT]!!.y

    @Test
    fun `the row clears the clock as the clock is actually drawn`() {
        val p = DialParams()
        val l = p.layout
        // Reproduce the emitter's own box arithmetic rather than trusting timeY.
        val boxTop = l.timeY - l.timeSize / 2
        val boxBottom = boxTop + (l.timeSize * 1.4).toInt()
        val visualCentre = (boxTop + boxBottom) / 2.0
        val digitsBottom = visualCentre + l.timeSize * 0.42

        assertTrue(rowTop(p) >= digitsBottom) {
            "complication row starts at ${rowTop(p)} but the digits reach ${digitsBottom.toInt()}"
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [80, 104, 130, 160])
    fun `the row follows the clock when the clock grows`(timeSize: Int) {
        val p = DialParams(layout = Layout(timeSize = timeSize))
        val l = p.layout
        val boxTop = l.timeY - l.timeSize / 2
        val visualCentre = boxTop + (l.timeSize * 1.4) / 2.0
        val digitsBottom = visualCentre + l.timeSize * 0.42
        assertTrue(rowTop(p) >= digitsBottom) {
            "at timeSize=$timeSize the row (${rowTop(p)}) overlaps the digits (${digitsBottom.toInt()})"
        }
    }
}
