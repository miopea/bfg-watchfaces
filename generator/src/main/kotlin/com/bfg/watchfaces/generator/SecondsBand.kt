package com.bfg.watchfaces.generator

/**
 * Where the seconds go, and how big and how faint they are.
 *
 * ## Why this is an object and not four numbers
 *
 * The seconds shipped with `0.45` and `48` written out in `WffEmitter`, in the
 * workbench preview and in the Android preview — three copies of the same
 * judgement, which is the arrangement `DECISIONS.md` keeps recording as the way
 * two renderers drift apart. `SlotGeometry` and `EngravedStroke` exist for the
 * same reason.
 *
 * ## The look
 *
 * Just under half the clock's size and the lightest weight the format offers.
 * Not `hh:mm:ss` on the clock itself: that makes every digit the same size, so
 * the seconds shout as loudly as the hour and the line grows wide enough to
 * crowd the rim. The clock is centred, which leaves roughly a hundred points of
 * empty dial on each side, and the seconds use the right one.
 *
 * Awake only. Ambient updates once a minute, so a second digit there would be
 * wrong for most of the minute it was shown.
 */
object SecondsBand {

    /** Size relative to the clock. About a third, so it never competes. */
    const val SCALE = 0.35

    /**
     * How far in from the rim.
     *
     * 24, not 48. The clock at full size runs to x=377 for the widest time, and
     * the dial is 456 — so there are 79 points of gutter, and the seconds need
     * 46 of them at [SCALE]. At an inset of 48 they did not fit beside a
     * full-size clock, which is what the 0.82 clock shrink was papering over.
     * Measured, not guessed.
     */
    const val INSET = 24

    /**
     * How faint, out of 255.
     *
     * The seconds are the fastest-moving thing on the dial and the least worth
     * reading, so they sit back from the time rather than competing with it.
     *
     * This is applied to the AWAKE ink. The emitter used `inkDim` — the ambient
     * ink — on an element that is only ever visible awake. From v3 that colour
     * is lifted to clear a contrast floor against BLACK, so on a pale dial with
     * dark ink the built face drew pale seconds on a pale dial while both
     * previews drew them dark. Nothing failed; the two just disagreed.
     */
    const val ALPHA = 190

    /**
     * The clock's element box is `timeSize * 1.4` tall and the seconds share it.
     *
     * They used to sit at 0.72 of the clock's size BELOW its origin, which put
     * them under the time rather than beside it. Sharing the box means both are
     * centred in the same band, so the seconds read as part of the same line —
     * which is what they are.
     */
    /**
     * LIGHT, not THIN.
     *
     * THIN is the thinnest weight the format offers and at a third of the
     * clock's size it stopped reading as type -- "a little bit too fine" on a
     * real wrist. LIGHT is the next step up and still clearly lighter than the
     * time beside it. Both previews draw a normal weight (AWT has no light
     * face), so this also narrows a gap where the preview was heavier than the
     * built face rather than lighter.
     */
    const val WEIGHT = "LIGHT"

    private const val CLOCK_BOX = 1.4

    /** Font size, in dial units. */
    fun fontSize(l: Layout): Int = (l.timeSize * SCALE).toInt()

    /**
     * Offset from the clock's own origin. Zero: the seconds share its box, and
     * therefore its centre line.
     */
    fun offsetY(l: Layout): Int = 0

    /** The same height as the clock's box, so both centre on one line. */
    fun height(l: Layout): Int = (l.timeSize * CLOCK_BOX).toInt()

    /**
     * Top of the band in DIAL space, for the previews.
     *
     * The emitter nests the seconds inside `DigitalClock` and works in its
     * coordinates; a preview draws straight onto the dial. One function so the
     * two cannot disagree about which line the seconds sit on.
     */
    fun topInDial(l: Layout): Int = l.timeY - l.timeSize / 2 + offsetY(l)

    /** Right edge, held in from the rim by [INSET]. */
    fun rightEdge(): Int = DIAL_SIZE - INSET
}
