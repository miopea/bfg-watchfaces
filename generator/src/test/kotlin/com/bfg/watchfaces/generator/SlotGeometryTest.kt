package com.bfg.watchfaces.generator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.math.hypot
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

    /**
     * A control never offers the same value twice.
     *
     * At complication size 31 and above the spread range collapses to a single
     * value, and this returned it three times. The UI zipped that to
     * Tight/Normal/Wide, all three matched the current value, so all three drew
     * as selected and none of them did anything — reported from a phone as
     * "the spacing option no longer works and the UI has all three selected".
     *
     * The geometry was right; the offer was the lie.
     */
    private fun analog(date: DateStyle = DateStyle.NONE) =
        DialParams(clockMode = ClockMode.ANALOG, dateStyle = date)

    /**
     * Nothing sits where the hands live.
     *
     * MIDDLE is under the hub and TOP fights both the twelve index and the hour
     * hand's most-used arc, so an analog face renders neither — while KEEPING
     * both in the params, because switching a face to hands and back must not
     * destroy two of somebody's choices.
     */
    @Test
    fun `an analog face renders no centre or top complication`() {
        val boxes = SlotGeometry.boxes(analog())
        assertTrue(SlotPosition.MIDDLE !in boxes) { "a complication sits under the hub" }
        assertTrue(SlotPosition.TOP !in boxes) { "a complication sits under the twelve" }
        assertTrue(SlotPosition.LEFT in boxes && SlotPosition.RIGHT in boxes) {
            "the nine and three sub-dials are missing: ${boxes.keys}"
        }
    }

    /** Six is a sub-dial, or the date, and never both. */
    @Test
    fun `the date and the six o'clock sub-dial never share the spot`() {
        val bare = SlotGeometry.boxes(analog())
        assertTrue(SlotPosition.BOTTOM in bare) { "six should hold a sub-dial when there is no date" }

        val dated = analog(DateStyle.MONTH_DAY)
        assertTrue(SlotPosition.BOTTOM !in SlotGeometry.boxes(dated)) {
            "the date and a sub-dial both claimed six"
        }
        val band = SlotGeometry.dateBand(dated)
        assertTrue(band != null) { "the date vanished instead of taking six" }
        assertTrue(band!!.y > DIAL_CENTER) { "the analog date is not at the bottom of the dial" }
    }

    /**
     * Sub-dials clear the hub and stop short of the chapter ring.
     *
     * Too far in and a hand's pivot covers the value; too far out and it draws
     * through the indices. Both look like a rendering fault rather than a
     * layout choice.
     */
    @Test
    fun `sub-dials sit between the hub and the chapter ring`() {
        for (date in listOf(DateStyle.NONE, DateStyle.MONTH_DAY)) {
            for ((pos, box) in SlotGeometry.boxes(analog(date))) {
                val corners = listOf(
                    box.x to box.y,
                    box.x + box.w to box.y,
                    box.x to box.y + box.h,
                    box.x + box.w to box.y + box.h
                )
                for ((x, y) in corners) {
                    val r = hypot(x - DIAL_CENTER, y - DIAL_CENTER)
                    assertTrue(r < DIAL_RADIUS * 0.88) {
                        "$pos reaches radius $r, into the chapter ring at ${DIAL_RADIUS * 0.88}"
                    }
                }
                val centre = hypot(
                    box.x + box.w / 2.0 - DIAL_CENTER,
                    box.y + box.h / 2.0 - DIAL_CENTER
                )
                assertTrue(centre > DIAL_RADIUS * 0.20) { "$pos is close enough to sit under the hub" }
            }
        }
    }

    /** Two sub-dials must never overlap; a value drawn over another is unreadable. */
    @Test
    fun `analog sub-dials do not overlap each other`() {
        val boxes = SlotGeometry.boxes(analog()).toList()
        for (i in boxes.indices) for (j in i + 1 until boxes.size) {
            val (posA, a) = boxes[i]
            val (posB, b) = boxes[j]
            val apart = a.x + a.w <= b.x || b.x + b.w <= a.x ||
                a.y + a.h <= b.y || b.y + b.h <= a.y
            assertTrue(apart) { "$posA and $posB overlap: $a and $b" }
        }
    }

    /**
     * The DATE is a window, not a band.
     *
     * The digital rule sizes it against the clock's width, which at six on an
     * analog dial rendered "Mar 10" nearly as wide as the watch.
     */
    @Test
    fun `the analog date reads as a sub-dial, not a headline`() {
        val dated = analog(DateStyle.MONTH_DAY)
        val analogSize = SlotGeometry.fittedDateSize(dated)
        val digitalSize = SlotGeometry.fittedDateSize(
            dated.copy(clockMode = ClockMode.DIGITAL)
        )
        assertTrue(analogSize < digitalSize) {
            "the analog date ($analogSize) is not smaller than the digital one ($digitalSize)"
        }
        assertTrue(analogSize <= SlotGeometry.fontSize(SlotGeometry.analogSize(dated))) {
            "the date out-shouts the complications it shares the dial with"
        }
    }

    /** A digital face is untouched by any of the analog branching. */
    @Test
    fun `the digital layout is unchanged by the analog branch`() {
        val p = DialParams()
        val boxes = SlotGeometry.boxes(p)
        assertEquals(
            listOf(SlotPosition.TOP, SlotPosition.LEFT, SlotPosition.MIDDLE, SlotPosition.RIGHT, SlotPosition.BOTTOM),
            boxes.keys.toList()
        ) { "the digital layout lost or reordered a slot" }
    }

    @Test
    fun `spacing never offers the same value more than once`() {
        for (size in 14..40) {
            val p = DialParams().let {
                it.copy(layout = it.layout.copy(complicationSize = size))
            }
            val opts = SlotGeometry.spreadOptions(p)
            assertEquals(opts.distinct(), opts) { "duplicate spacing offered at size $size: $opts" }
            assertTrue(opts.isNotEmpty()) { "no spacing at all at size $size" }
        }
    }

    /**
     * A slot alone on its row keeps the word and shrinks instead.
     *
     * Once v11 measured a weather value honestly at seventeen characters,
     * "4° Partly cloudy" stopped clearing the row-slot 85% floor in the TOP
     * slot and was replaced by "4°" — "weather and conditions now just shows
     * weather by itself". TOP has no neighbour to be out-shouted by, so the
     * comparison that floor protects does not exist there.
     */
    @Test
    fun `the top slot keeps the whole reading rather than dropping a word`() {
        for (size in listOf(22, 26, 31, 36)) {
            val p = DialParams()
                .withSlot(SlotPosition.TOP, ComplicationSource.WEATHER_TEMP_CONDITION)
                .let { it.copy(layout = it.layout.copy(complicationSize = size)) }
            val box = SlotGeometry.boxes(p)[SlotPosition.TOP]!!
            val base = SlotGeometry.fontSize(SlotGeometry.sizeAt(p, SlotPosition.TOP))
            val drawn = SlotGeometry.drawnText(
                ComplicationSource.WEATHER_TEMP_CONDITION, box, base,
                p.generatorVersion, SlotPosition.TOP
            )
            assertEquals(
                ComplicationSource.WEATHER_TEMP_CONDITION.format, drawn.format
            ) { "the condition was dropped at complication size $size" }
            assertTrue(drawn.fontSize >= (base * 0.7).toInt()) {
                "kept the word but shrank it to ${drawn.fontSize} against a base of $base"
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
    // ---- editing a slot ------------------------------------------------------

    @Test
    fun `withSlot changes one slot and leaves the others alone`() {
        val p = DialParams()
        val edited = p.withSlot(SlotPosition.MIDDLE, ComplicationSource.WORLD_CLOCK)
        assertEquals(ComplicationSource.WORLD_CLOCK, edited.slot(SlotPosition.MIDDLE))
        for (pos in SlotPosition.entries) {
            if (pos == SlotPosition.MIDDLE) continue
            assertEquals(p.slot(pos), edited.slot(pos)) { "$pos changed too" }
        }
    }

    @Test
    fun `withSlot agrees with slot about a short list`() {
        // slot() already reads a short list as "the rest are off". A setter that
        // could not write past the end would make a face stored with three
        // complications uneditable in its fifth slot.
        val short = DialParams(complications = listOf(ComplicationSource.DATE))
        assertEquals(ComplicationSource.NONE, short.slot(SlotPosition.BOTTOM))
        val edited = short.withSlot(SlotPosition.BOTTOM, ComplicationSource.WATCH_BATTERY)
        assertEquals(ComplicationSource.WATCH_BATTERY, edited.slot(SlotPosition.BOTTOM))
        assertEquals(ComplicationSource.DATE, edited.slot(SlotPosition.TOP))
        assertEquals(ComplicationSource.NONE, edited.slot(SlotPosition.MIDDLE))
    }

    @Test
    fun `turning every slot off leaves a face that still lays out`() {
        var p = DialParams()
        for (pos in SlotPosition.entries) p = p.withSlot(pos, ComplicationSource.NONE)
        // Slots set to NONE are not emitted at all, so there is nothing to place.
        assertTrue(SlotGeometry.boxes(p).isEmpty()) { "an all-off face still reserves slot boxes" }
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



    /**
     * The drawn date and the top slot never overlap.
     *
     * They collided by construction: the top slot anchors at `dateY` and so
     * does the drawn date, so switching the date on printed it straight through
     * the top complication. Seen on a phone, not in a test -- which is the
     * whole reason this file exists, since the slot boxes were once computed in
     * two places, agreed in a test, and overlapped in reality.
     */
    @Test
    fun `the drawn date never lands on the top slot`() {
        for (style in DateStyle.entries) {
            if (style == DateStyle.NONE) continue
            for (size in listOf(10, 19, 28, 40)) {
                for (dateSize in listOf(14, 21, 30)) {
                    val p = DialParams(
                        dateStyle = style,
                        layout = Layout(complicationSize = size, dateSize = dateSize)
                    )
                    val band = SlotGeometry.dateBand(p)!!
                    val top = SlotGeometry.boxes(p)[SlotPosition.TOP] ?: continue
                    assertTrue(top.y + top.h <= band.y) {
                        "$style at size=$size dateSize=$dateSize: the top slot " +
                            "(y=${top.y}..${top.y + top.h}) runs into the date " +
                            "(y=${band.y}..${band.y + band.h})"
                    }
                }
            }
        }
    }

    /** With no drawn date, nothing about the top slot changes. */
    @Test
    fun `switching the date off leaves the slots exactly where they were`() {
        val off = DialParams(dateStyle = DateStyle.NONE)
        assertNull(SlotGeometry.dateBand(off))
        // The boxes a face emitted before the drawn date existed.
        assertEquals(SlotGeometry.boxes(DialParams()), SlotGeometry.boxes(off))
    }

}
