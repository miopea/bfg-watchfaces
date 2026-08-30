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

    /** Size relative to the clock. Under half, so it reads as a subdial. */
    const val SCALE = 0.45

    /** How far in from the rim, so the digits clear a round bezel. */
    const val INSET = 48

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

    private const val Y_FACTOR = 0.72
    private const val H_FACTOR = 0.6

    /** Font size, in dial units. */
    fun fontSize(l: Layout): Int = (l.timeSize * SCALE).toInt()

    /** Offset DOWN from the clock's own origin — the seconds nest inside it. */
    fun offsetY(l: Layout): Int = (l.timeSize * Y_FACTOR).toInt()

    fun height(l: Layout): Int = (l.timeSize * H_FACTOR).toInt()

    /** Right edge, held in from the rim by [INSET]. */
    fun rightEdge(): Int = DIAL_SIZE - INSET
}
