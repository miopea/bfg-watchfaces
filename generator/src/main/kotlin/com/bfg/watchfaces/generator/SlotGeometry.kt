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

    /**
     * The spread at which spacing is "normal" — no extra vertical air either
     * way. Matches Layout.complicationSpread's default.
     */
    const val NEUTRAL_SPREAD = 92

    /**
     * Extra vertical air, derived from the SAME control as horizontal spread.
     *
     * From v5 spacing is one concept: Tight and Wide loosen or tighten the whole
     * layout rather than only the middle row. The top slot moves up and the
     * bottom slot moves down — away from the clock, which is the fixed thing —
     * and the gap between the row and the bottom grows with them.
     *
     * v1..v4 get zero, so a stored face from before this renders exactly as its
     * author saw it. That is the whole job of generatorVersion.
     *
     * The factor is under 1: a 456px dial holding five slots and a 104px clock
     * has far less vertical slack than horizontal, so matching the horizontal
     * travel one-for-one would spend the whole range against a clamp.
     *
     * Deliberately NOT clamped here. Every limit is applied by the layout
     * itself, which is the only place that knows about the clock and the rim,
     * and [effective] reports what it refused. A clamp hidden in this function
     * would be a silent override with nothing to surface it.
     */
    fun verticalAir(p: DialParams): Int =
        if (p.generatorVersion >= 5)
            ((p.layout.complicationSpread - NEUTRAL_SPREAD) * 0.45).roundToInt()
        else 0

    fun boxWidth(size: Int): Int = (size * 3.9).roundToInt()

    /**
     * Icon over a single line of text, and nothing more. The old 4.0x height
     * left ~15px of dead space inside every slot, which is what made five slots
     * look crowded when they were merely mis-measured.
     */
    fun boxHeight(size: Int): Int = (size * 3.3).roundToInt()

    fun iconHeight(size: Int): Int = (size * 1.25).roundToInt()

    /**
     * Where the face's own drawn date goes, or null when there is not one.
     *
     * Here rather than in the emitter, because the emitter and BOTH previews
     * need it and the first version of the date computed `dateY - dateSize / 2`
     * in all three. That is the duplicate-geometry mistake this object was
     * created to end -- the slot boxes were once computed independently in two
     * places, agreed in a test, and overlapped on both axes in reality.
     *
     * Full width and centred, matching the emitted PartText.
     *
     * Placed directly ABOVE THE CLOCK rather than at `dateY`. Despite the name,
     * `dateY` has always been the TOP SLOT's anchor -- see the top block in
     * [layoutAt] -- so putting the drawn date there put two things in one place
     * and left nothing above for the slot. Sitting against the clock instead
     * gives the whole upper dial back to the top complication, which is what
     * makes both fit at every complication size.
     */
    fun dateBand(p: DialParams): Box? {
        if (p.dateStyle == DateStyle.NONE) return null
        val l = p.layout
        val (timeTop, _) = timeBand(l)
        val h = (l.dateSize * 1.6).roundToInt()
        return Box(0, timeTop - GAP - h, DIAL_SIZE, h)
    }
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
    data class Effective(
        val size: Int,
        val spread: Int,
        val verticalAir: Int,
        val sizeClamped: Boolean,
        val spreadClamped: Boolean,
        val verticalClamped: Boolean
    )

    fun effective(p: DialParams): Effective {
        val l = p.layout
        val size = fittedSize(p)
        val boxes = layoutAt(p, size)
        val air = verticalAir(p)

        val row = listOf(SlotPosition.LEFT, SlotPosition.MIDDLE, SlotPosition.RIGHT).mapNotNull { boxes[it] }
        val spread = if (row.size > 1) row[1].x - row[0].x else l.complicationSpread

        // Vertical room on a 456px dial is genuinely tight, so a wide setting is
        // often refused and the honest question is not "did it land where the
        // arithmetic said" -- clamps are normal and fine -- but "did moving this
        // control actually move anything". Compare against the same layout with
        // no air at all: if an end slot did not shift by the full amount, the
        // request was not honoured and the readout has to say so.
        val baseline = layoutAt(p, size, airOverride = 0)
        val ends = listOf(
            SlotPosition.TOP to -1,        // air pushes the top UP
            SlotPosition.BOTTOM to 1       // and the bottom DOWN
        ).mapNotNull { (pos, dir) ->
            val now = boxes[pos] ?: return@mapNotNull null
            val was = baseline[pos] ?: return@mapNotNull null
            (now.y - was.y) * dir          // how far it actually travelled
        }

        return Effective(
            size = size,
            spread = spread,
            verticalAir = air,
            sizeClamped = size != l.complicationSize,
            spreadClamped = row.size > 1 && spread != l.complicationSpread,
            // Nothing to move is also a control that did nothing.
            verticalClamped = air != 0 && (ends.isEmpty() || ends.any { it != air })
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

    /** [airOverride] exists so [effective] can compare against the no-air layout. */
    private fun layoutAt(p: DialParams, size: Int, airOverride: Int? = null): LinkedHashMap<SlotPosition, Box> {
        val l = p.layout
        val w = boxWidth(size)
        val h = boxHeight(size)
        val anchor = (size * 1.2).roundToInt()
        val (timeTop, timeBottom) = timeBand(l)
        val air = airOverride ?: verticalAir(p)
        val out = LinkedHashMap<SlotPosition, Box>()

        val limit = maxCentreOffset(w)
        val highestY = (DIAL_CENTER - limit).roundToInt()          // top edge floor
        val lowestBottom = (DIAL_CENTER + limit).roundToInt()      // bottom edge ceiling

        // ---- TOP: centred, above the clock ----
        val top = p.slot(SlotPosition.TOP)
        var topBottom = 0
        if (top.enabled) {
            var y = l.dateY - anchor - air         // spacing pushes it away from the clock
            y = min(y, timeTop - GAP - h)          // never run into the clock
            // The drawn date sits AT dateY, which is where the top slot anchors
            // -- so with both on they land on each other. The date wins the
            // band and the slot goes above it. Seen on a phone: "SUN AUG 30"
            // printed straight through the top complication's own text.
            dateBand(p)?.let { y = min(y, it.y - GAP - h) }
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
            var y = max(l.batteryY - anchor + air, rowBottom + GAP + max(0, air))
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
