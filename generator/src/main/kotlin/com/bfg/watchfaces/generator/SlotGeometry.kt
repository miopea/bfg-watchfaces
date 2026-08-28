package com.bfg.watchfaces.generator

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Where every complication slot goes.
 *
 * This exists because the arithmetic was written TWICE -- once in [WffEmitter]
 * for the shipped face and once in the workbench's preview -- and the only
 * thing keeping them equal was a test asserting they agreed. That is a test
 * guarding a copy-paste, which is the wrong shape: the fix is to have one
 * calculation and let both callers use it.
 *
 * It also enforces the constraints the old hand-placed numbers violated. With
 * the previous defaults the row boxes overlapped each other by 3px, the bottom
 * slot overlapped the row by 14px, and both the top slot and the row collided
 * with the time. Rather than hand-tune numbers that break again at a different
 * size, the collisions are prevented here:
 *
 *  - boxes are sized to their CONTENT (icon over one line of text) instead of
 *    the generous 4.0x square they used to get
 *  - horizontal spread is widened if the boxes would touch
 *  - the bottom slot is pushed down if the row would reach it
 *  - nothing is allowed past the rim
 *
 * Callers get boxes for the enabled slots only, already re-centred.
 */
object SlotGeometry {

    data class Box(val x: Int, val y: Int, val w: Int, val h: Int) {
        val bottom: Int get() = y + h
        val right: Int get() = x + w
    }

    /** Minimum air between two boxes. Below this they read as one control. */
    private const val GAP = 8

    /** Keep everything this far inside the rim; the dial curves away fast. */
    private const val RIM = 14

    fun boxWidth(size: Int): Int = (size * 3.9).roundToInt()

    /**
     * Icon over a single line of text, and nothing more. The old 4.0x height
     * left ~15px of dead space inside every slot, which is what made five slots
     * look crowded when they were merely mis-measured.
     */
    fun boxHeight(size: Int): Int = (size * 3.3).roundToInt()

    fun iconHeight(size: Int): Int = (size * 1.25).roundToInt()
    fun textOffset(size: Int): Int = (size * 1.45).roundToInt()
    fun textHeight(size: Int): Int = (size * 1.7).roundToInt()
    fun fontSize(size: Int): Int = (size * 0.92).roundToInt()

    /** The visual extent of the clock, which slots must not collide with. */
    private fun timeBand(l: Layout): Pair<Int, Int> {
        // The digits are centred inside the DigitalClock ELEMENT BOX, not on
        // timeY. The box runs from timeY - timeSize/2 for timeSize*1.4, so its
        // centre sits timeSize*0.2 BELOW timeY -- 20px at the default size.
        // Using timeY as the centre put this band 20px too high and let the
        // complication row slide under the descenders.
        val boxTop = l.timeY - l.timeSize / 2
        val centre = boxTop + (l.timeSize * 1.4) / 2.0
        // The glyphs occupy far less than the box, so clamping against the box
        // itself would shove the slots to the rim for no reason. 0.42 is a
        // generous half-height for real digits.
        val half = l.timeSize * 0.42
        return (centre - half).roundToInt() to (centre + half).roundToInt()
    }

    /** Half-width of the dial circle at a given y, for rim checks. */
    private fun halfWidthAt(y: Int): Double {
        val dy = (y - DIAL_CENTER)
        val inside = DIAL_RADIUS * DIAL_RADIUS - dy * dy
        return if (inside <= 0) 0.0 else sqrt(inside)
    }

    /**
     * Boxes for the slots that are switched on, keyed by position.
     *
     * Disabled slots are absent rather than present-and-empty: an empty slot
     * still costs a tap target and a frame budget on the watch.
     */
    /**
     * The furthest a CENTRED box of width [w] can sit from the vertical centre
     * before a corner leaves the circle. The binding corner is always the one
     * on the far edge, so this is a single closed-form limit rather than a
     * search.
     */
    private fun maxCentreOffset(w: Int): Double {
        val half = w / 2.0
        val inside = DIAL_RADIUS * DIAL_RADIUS - half * half
        return if (inside <= 0) 0.0 else sqrt(inside) - RIM
    }

    /**
     * Boxes at the requested size, stepping down until they actually fit.
     *
     * On a 456px dial, five slots plus a 104px clock genuinely runs out of room
     * somewhere above size ~24. The previous code clamped the bottom slot and
     * pushed it off the edge of the circle, producing a face that would have
     * shipped with a complication hanging into the bezel.
     *
     * Shrinking is the only option that stays correct AND keeps every slot the
     * user switched on. Dropping a slot would silently lose data they asked
     * for; overlapping is not a layout. The step-down is bounded and the result
     * is reported by [fittedSize] so the UI can say what it actually used.
     */
    fun boxes(p: DialParams): LinkedHashMap<SlotPosition, Box> = layoutAt(p, fittedSize(p))

    /**
     * What the layout actually used, versus what was asked for.
     *
     * Both size and spacing get clamped -- by the rim, by each other, and by the
     * clock. A control whose value is silently overridden feels broken, so the
     * UI can show the effective numbers instead of pretending the request won.
     */
    data class Effective(val size: Int, val spread: Int, val sizeClamped: Boolean, val spreadClamped: Boolean)

    fun effective(p: DialParams): Effective {
        val size = fittedSize(p)
        val boxes = layoutAt(p, size)
        val row = listOf(SlotPosition.LEFT, SlotPosition.MIDDLE, SlotPosition.RIGHT).mapNotNull { boxes[it] }
        val spread = if (row.size > 1) row[1].x - row[0].x else p.layout.complicationSpread
        return Effective(
            size = size,
            spread = spread,
            sizeClamped = size != p.layout.complicationSize,
            spreadClamped = row.size > 1 && spread != p.layout.complicationSpread
        )
    }

    /** The size actually used, which is [Layout.complicationSize] unless it did not fit. */
    fun fittedSize(p: DialParams): Int {
        val requested = p.layout.complicationSize.coerceIn(MIN_SIZE, MAX_SIZE)
        var size = requested
        while (size > MIN_SIZE) {
            val boxes = layoutAt(p, size)
            if (fits(boxes.values)) return size
            size -= 1
        }
        return MIN_SIZE
    }

    const val MIN_SIZE = 10
    const val MAX_SIZE = 40

    /** No overlaps, and every corner inside the dial. */
    private fun fits(boxes: Collection<Box>): Boolean {
        if (hasOverlap(boxes)) return false
        for (b in boxes) {
            for (cx in listOf(b.x, b.right)) for (cy in listOf(b.y, b.bottom)) {
                val dx = cx - DIAL_CENTER
                val dy = cy - DIAL_CENTER
                if (sqrt(dx * dx + dy * dy) > DIAL_RADIUS - 1) return false
            }
        }
        return true
    }

    private fun layoutAt(p: DialParams, size: Int): LinkedHashMap<SlotPosition, Box> {
        val l = p.layout
        val w = boxWidth(size)
        val h = boxHeight(size)
        val anchor = (size * 1.2).roundToInt()
        val (timeTop, timeBottom) = timeBand(l)
        val out = LinkedHashMap<SlotPosition, Box>()

        val limit = maxCentreOffset(w)
        val highestY = (DIAL_CENTER - limit).roundToInt()          // top edge floor
        val lowestBottom = (DIAL_CENTER + limit).roundToInt()      // bottom edge ceiling

        // ---- TOP: centred, above the clock ----
        val top = p.slot(SlotPosition.TOP)
        var topBottom = 0
        if (top.enabled) {
            var y = l.dateY - anchor
            y = min(y, timeTop - GAP - h)          // never run into the clock
            y = max(y, highestY)                   // and never leave the circle
            out[SlotPosition.TOP] = Box(DIAL_SIZE / 2 - w / 2, y, w, h)
            topBottom = y + h
        }

        // ---- ROW: left / middle / right, re-centred among the enabled ones ----
        val rowPositions = listOf(SlotPosition.LEFT, SlotPosition.MIDDLE, SlotPosition.RIGHT)
            .filter { p.slot(it).enabled }
        var rowBottom = max(topBottom, timeBottom)
        if (rowPositions.isNotEmpty()) {
            var y = l.complicationY - anchor
            y = max(y, timeBottom + GAP)           // below the clock
            y = max(y, topBottom + GAP)            // and below the top slot
            y = min(y, lowestBottom - h)

            // Widen if the boxes would touch; narrow if the outer ones would
            // leave the circle. The stored value is a preference, and neither
            // overlapping nor hanging off the rim is a look anyone chose.
            var spread = max(l.complicationSpread, w + GAP + 2)
            if (rowPositions.size > 1) {
                // The binding row is whichever edge sits further from centre.
                val bindingY = if (kotlin.math.abs(y - DIAL_CENTER) > kotlin.math.abs(y + h - DIAL_CENTER)) y else y + h
                val room = halfWidthAt(bindingY) - RIM - w / 2.0
                val maxSpread = (2 * room / (rowPositions.size - 1)).toInt()
                if (maxSpread > 0) spread = min(spread, maxSpread)
            }

            rowPositions.forEachIndexed { index, pos ->
                val offset = (index - (rowPositions.size - 1) / 2.0) * spread
                out[pos] = Box((DIAL_SIZE / 2 + offset - w / 2).roundToInt(), y, w, h)
            }
            rowBottom = y + h
        }

        // ---- BOTTOM: centred, below the row ----
        val bottom = p.slot(SlotPosition.BOTTOM)
        if (bottom.enabled) {
            // Clear of the row, then inside the circle. If those two cannot
            // both hold at this size, the layout is rejected by fits() and the
            // caller retries a size down -- rather than shipping a slot that
            // hangs into the bezel, which is what the clamp used to do.
            var y = max(l.batteryY - anchor, rowBottom + GAP)
            y = min(y, lowestBottom - h)
            out[SlotPosition.BOTTOM] = Box(DIAL_SIZE / 2 - w / 2, y, w, h)
        }

        return out
    }

    /** True when no two boxes overlap. Used by tests and worth keeping honest. */
    fun hasOverlap(boxes: Collection<Box>): Boolean {
        val list = boxes.toList()
        for (i in list.indices) for (j in i + 1 until list.size) {
            val a = list[i]; val b = list[j]
            val overlapX = a.x < b.right && b.x < a.right
            val overlapY = a.y < b.bottom && b.y < a.bottom
            if (overlapX && overlapY) return true
        }
        return false
    }
}
