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
     * Icon over a single line of text — or just the text.
     *
     * A slot whose glyph is off does not need the icon's height OR the offset
     * that clears it. It used to get both: the value sat low in a box reserving
     * room for something never drawn, and the wasted height counted against
     * every OTHER slot, because the vertical stack (top, clock, row, bottom) is
     * what caps the complication size on a 456 dial. Measured: with all five
     * slots on, "Large" was silently clamped from 28 to 25.
     */
    fun boxHeight(
        size: Int,
        withIcon: Boolean = true,
        version: Int = CURRENT_GENERATOR_VERSION
    ): Int = when {
        version < 6 -> (size * 3.3).roundToInt()      // v1..v5: one height, icon or not
        withIcon && version >= 7 -> (size * 2.45).roundToInt()
        withIcon -> (size * 2.85).roundToInt()
        else -> textHeight(size, version)
    }

    /**
     * The glyph, which from v7 is SMALLER than the value it labels.
     *
     * It was 1.25x the slot size against a 0.92x value -- the little symbol was
     * bigger than the number, which is backwards for something whose whole job
     * is to say what the number means. Shrinking it also buys the vertical room
     * the size control had run out of.
     */
    fun iconHeight(size: Int, version: Int = CURRENT_GENERATOR_VERSION): Int =
        (size * if (version >= 7) 0.85 else 1.25).roundToInt()

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
    /**
     * Average character advance, as a fraction of the font size.
     *
     * Measured with AWT at the sizes this face actually uses: "10:10" at 104
     * is 299px, so a digit runs about 0.575. Letters and spaces average wider
     * per character than digits once mixed, and 0.62 reproduced the measured
     * fits within a point across every date style.
     *
     * An ESTIMATE, and it has to be: the emitter runs on the phone, where
     * java.awt does not exist, and the same number has to serve the emitter and
     * both previews or they disagree about how big the date is.
     */
    private const val DIGIT_ADVANCE = 0.575
    private const val TEXT_ADVANCE = 0.62

    /**
     * The date, scaled to sit across roughly the same width as the time.
     *
     * A single stored size cannot do this: at a 104pt clock, "Wed Sep 30" fits
     * at 49 and "Sep 30" at 85. So the size is DERIVED from how wide the
     * style's longest form is, and nothing stored constrains it.
     *
     * It was briefly clamped to [Layout.dateSize] as a ceiling, which looked
     * like a way to keep the control meaningful and quietly undid the whole
     * change: a face SAVED before this carries the old small value, so the fit
     * was clamped straight back down to it and the date did not move. Raising
     * the default only ever helped faces that did not exist yet.
     *
     * `dateSize` is therefore no longer read when drawing. It stays in [Layout]
     * and in the file so faces written by any build still parse.
     *
     * Seconds are excluded from the target on purpose: they sit in the gutter
     * beside the clock, not on its line, so matching "HH:MM" is what makes the
     * date look the same width as the time.
     */
    fun fittedDateSize(p: DialParams): Int {
        if (p.dateStyle == DateStyle.NONE) return 0
        val l = p.layout
        val clockWidth = l.timeSize * DIGIT_ADVANCE * "HH:MM".length
        val chars = p.dateStyle.widestSample().length.coerceAtLeast(1)
        val fitted = clockWidth / (TEXT_ADVANCE * chars) * p.dateScale.factor
        return fitted.roundToInt().coerceIn(MIN_DATE_SIZE, MAX_DATE_SIZE)
    }

    /**
     * The date never grows past this, whatever the arithmetic says.
     *
     * "30" alone would fit at 96 against a 104pt clock, which is a date the
     * size of the time. Matching the WIDTH is the ask; matching the height is
     * not.
     */
    const val MAX_DATE_SIZE = 56

    const val MIN_DATE_SIZE = 12

    /**
     * A drawn value's font, shrunk if the box cannot hold it.
     *
     * A complication's text belongs to its provider, which shortens its own
     * value for a small slot. A DRAWN source has no provider to do that, so a
     * long one is simply clipped — "72° Cloudy" reached a watch as "° Unknow".
     *
     * Never grows, only shrinks: matching the other slots is the point, and a
     * short value should not be bigger than the number beside it.
     */
    fun drawnFontSize(source: ComplicationSource, box: Box, base: Int): Int {
        val chars = source.widestValue
        if (chars <= 0) return base
        // FLOOR, not round: rounding up overflows the box by a fraction of a
        // character, which is a clipped last letter rather than a tight fit.
        val fits = (box.w / (TEXT_ADVANCE * chars)).toInt()
        return minOf(base, fits).coerceAtLeast(MIN_DRAWN_FONT)
    }

    /** Below this a value is not worth drawing; it would be a smudge. */
    const val MIN_DRAWN_FONT = 10

    fun dateBand(p: DialParams): Box? {
        if (p.dateStyle == DateStyle.NONE) return null
        val l = p.layout
        val (timeTop, _) = timeBand(l)
        val h = (fittedDateSize(p) * 1.6).roundToInt()
        return Box(0, timeTop - GAP - h, DIAL_SIZE, h)
    }
    /** Where the value sits inside its box: below the glyph, or at the top. */
    fun textOffset(
        size: Int,
        withIcon: Boolean = true,
        version: Int = CURRENT_GENERATOR_VERSION
    ): Int = when {
        !withIcon && version >= 6 -> 0
        version >= 7 -> (size * 1.05).roundToInt()   // the glyph above is smaller
        else -> (size * 1.45).roundToInt()
    }
    /**
     * The value's own box: one line, so 1.35x the slot size.
     *
     * It was 1.7x, which is 1.85x the FONT — half a line of empty space under
     * every value. Harmless on its own, and not harmless in aggregate: the
     * vertical stack of top, clock, row and bottom is what caps the
     * complication size, so the slack was being paid for by the size control,
     * which silently clamped "Large" from 28 down to 25.
     */
    fun textHeight(size: Int, version: Int = CURRENT_GENERATOR_VERSION): Int =
        (size * if (version >= 6) 1.35 else 1.7).roundToInt()
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
            // LESS than the air asked for, not merely different from it.
            // Spacing now pushes the row down as well as the ends out, so the
            // bottom slot travels FURTHER than `air` -- it moves with the row
            // and then again below it. Moving further is not a refusal, and
            // reporting it as one told people a control had been ignored while
            // it was working.
            // "Fell SHORT in the direction asked for", which is direction
            // dependent: positive air pushes the ends apart and negative air
            // pulls them together, so a refusal is `less` in one case and
            // `more` in the other.
            //
            // Not "different from air": spacing now pushes the row down as well
            // as the ends out, so the bottom slot travels FURTHER than `air` --
            // with the row, then again below it. Moving further is not a
            // refusal, and calling it one told people a working control had
            // been ignored.
            verticalClamped = air != 0 && (
                ends.isEmpty() || ends.any { if (air >= 0) it < air else it > air }
            )
        )
    }

    /** The size actually used, which is [Layout.complicationSize] unless it did not fit. */
    fun fittedSize(p: DialParams): Int {
        val requested = p.layout.complicationSize.coerceIn(MIN_SIZE, MAX_SIZE)
        var size = requested
        // The drawn date is part of the budget. It sits against the clock and
        // the top slot goes above it, so a big date leaves less room -- and
        // since the date is sized to the CLOCK rather than to what is left,
        // the complications are what give way. Without this the top slot was
        // clamped to the rim and drawn straight through the date.
        val band = dateBand(p)
        while (size > MIN_SIZE) {
            val boxes = layoutAt(p, size)
            val clearOfDate = band == null || boxes[SlotPosition.TOP]?.let {
                it.y + it.h <= band.y
            } ?: true
            if (clearOfDate && fits(boxes.values)) return size
            size -= 1
        }
        return MIN_SIZE
    }

    /**
     * The biggest complication size THIS face can take.
     *
     * Five slots and a 104pt clock on a 456 dial is a genuinely tight budget,
     * and the ceiling moves with the layout: turn the top slot off and there is
     * room for much more. A UI that offers a fixed "Large" therefore offers a
     * number that is sometimes impossible, silently clamps, and looks broken --
     * which is what "when I select larger they should be larger" was about.
     *
     * Offer sizes derived from THIS, and Large always means as large as this
     * face allows.
     */
    /**
     * The spacings this face can actually take, narrowest to widest.
     *
     * Asked of the layout rather than assumed, because the stored value is only
     * a REQUEST: `layoutAt` widens it so boxes cannot touch and narrows it so
     * they cannot leave the rim. Fixed options stopped meaning anything once
     * the boxes grew — measured at complication size 27, "Tight" 84, "Normal"
     * 92 and "Wide" 110 all came out as 115, because the minimum had passed all
     * three. Three controls, one result.
     *
     * Derived options cannot drift like that: they are whatever this layout
     * will honour today.
     */
    /**
     * The three complication sizes to offer, smallest first.
     *
     * Here rather than in a UI so it can be TESTED, and so the workbench and
     * the phone cannot drift. The steps are deliberately far apart: three
     * controls that produce nearly the same face read as a broken control, and
     * this project has shipped that twice — once when Large was clamped to
     * within four points of Medium, once when all three spacings came out as
     * the same number.
     */
    fun sizeOptions(p: DialParams): List<Int> {
        val max = maxSize(p)
        return listOf((max * 0.60).roundToInt(), (max * 0.79).roundToInt(), max)
            .map { it.coerceAtLeast(MIN_SIZE) }
    }

    /** The three spacings to offer, narrowest first. See [sizeOptions]. */
    fun spreadOptions(p: DialParams): List<Int> {
        val r = spreadRange(p)
        return listOf(r.first, (r.first + r.last) / 2, r.last)
    }

    fun spreadRange(p: DialParams): IntRange {
        val lo = spreadAt(p, 0)
        // SCANNED, not probed at the extreme. Spacing also drives vertical air,
        // so an enormous request pushes the row down until the complications
        // have to shrink -- and a shrunk box allows a NARROWER spread than a
        // moderate request did. Measured: asking for 456 produced 47px, while
        // 160 produced 144.
        //
        // So walk up and keep the widest that still leaves the complications
        // the size this face would otherwise have. Anything that shrinks them
        // is not a wider layout, it is a different one.
        val baseline = fittedSize(p)
        var hi = lo
        var request = lo
        while (request <= DIAL_SIZE) {
            val q = p.copy(layout = p.layout.copy(complicationSpread = request))
            if (fittedSize(q) >= baseline) {
                val actual = spreadAt(p, request)
                if (actual > hi) hi = actual
            }
            request += 4
        }
        return lo..hi
    }

    /** What the layout settles on when asked for [requested]. */
    private fun spreadAt(p: DialParams, requested: Int): Int {
        val boxes = boxes(p.copy(layout = p.layout.copy(complicationSpread = requested)))
        val left = boxes[SlotPosition.LEFT]
        val middle = boxes[SlotPosition.MIDDLE] ?: boxes[SlotPosition.RIGHT]
        return if (left != null && middle != null) middle.x - left.x
        else p.layout.complicationSpread
    }

    fun maxSize(p: DialParams): Int =
        fittedSize(p.copy(layout = p.layout.copy(complicationSize = MAX_SIZE)))

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
        // Per slot: a glyph-less slot is shorter, which is what frees the
        // vertical room the size control was running out of.
        fun hFor(pos: SlotPosition) = boxHeight(size, pos in p.iconSlots, p.generatorVersion)

        /**
         * TOP and BOTTOM are alone on their rows, so they are not held to a
         * third of the dial the way the middle row is.
         *
         * They were, and it showed: a provider returning "Sat, Aug 30" had to
         * fit a box built for three-across, and the watch shrank the text to
         * make it — so the top complication read as tiny beside the others
         * while the emitted font size was identical. Measured on a watch with
         * the same provider in all five slots: they render the same, and the
         * difference only appears with a long value.
         *
         * Capped rather than given the whole chord: a slot that ran the width
         * of the dial would stop reading as one of a set.
         */
        fun wFor(pos: SlotPosition): Int =
            if (pos == SlotPosition.TOP || pos == SlotPosition.BOTTOM) (w * 1.7).roundToInt()
            else w
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
            val h = hFor(SlotPosition.TOP)
            var y = l.dateY - anchor - air         // spacing pushes it away from the clock
            y = min(y, timeTop - GAP - h)          // never run into the clock
            // The drawn date sits AT dateY, which is where the top slot anchors
            // -- so with both on they land on each other. The date wins the
            // band and the slot goes above it. Seen on a phone: "SUN AUG 30"
            // printed straight through the top complication's own text.
            dateBand(p)?.let { y = min(y, it.y - GAP - h) }
            y = max(y, highestY)                   // and never leave the circle
            val tw = wFor(SlotPosition.TOP)
            out[SlotPosition.TOP] = Box(DIAL_SIZE / 2 - tw / 2, y, tw, h)
            topBottom = y + h
        }

        // ---- ROW: left / middle / right, re-centred among the enabled ones ----
        val rowPositions = listOf(SlotPosition.LEFT, SlotPosition.MIDDLE, SlotPosition.RIGHT)
            .filter { p.slot(it).enabled }
        var rowBottom = max(topBottom, timeBottom)
        if (rowPositions.isNotEmpty()) {
            val h = rowPositions.maxOf { hFor(it) }
            var y = l.complicationY - anchor
            // Spacing pushes the row DOWN from the clock as well as pushing the
            // slots apart. It only widened before, so "Wide" spread the row out
            // and left it as close to the time as "Tight" did -- the gap that
            // actually reads as crowding.
            y = max(y, timeBottom + GAP + max(0, air))
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
                out[pos] = Box((DIAL_SIZE / 2 + offset - w / 2).roundToInt(), y, w, hFor(pos))
            }
            rowBottom = y + h
        }

        // ---- BOTTOM: centred, below the row ----
        val bottom = p.slot(SlotPosition.BOTTOM)
        if (bottom.enabled) {
            val h = hFor(SlotPosition.BOTTOM)
            // Clear of the row, then inside the circle. If those two cannot
            // both hold at this size, the layout is rejected by fits() and the
            // caller retries a size down -- rather than shipping a slot that
            // hangs into the bezel, which is what the clamp used to do.
            var y = max(l.batteryY - anchor + air, rowBottom + GAP + max(0, air))
            y = min(y, lowestBottom - h)
            val bw = wFor(SlotPosition.BOTTOM)
            out[SlotPosition.BOTTOM] = Box(DIAL_SIZE / 2 - bw / 2, y, bw, h)
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
