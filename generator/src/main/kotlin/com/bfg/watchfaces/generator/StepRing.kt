package com.bfg.watchfaces.generator

/**
 * The step-goal ring: where it sits and how thick it is.
 *
 * Here rather than in the emitter because both previews draw it too, and this
 * project's recurring bug is the same number written down in three places.
 *
 * ## Why a ring and not a slot
 *
 * A goal is a proportion, and a proportion reads better as a shape than as
 * "8,412 / 10,000" in a box four characters wide. It also costs none of the
 * five slots, which matters on a dial that already has to fit a date, a clock
 * and a row.
 *
 * ## Why the format can do this unaided
 *
 * `[STEP_PERCENT]` is a first-class WFF source, and `<Transform>` binds an
 * arithmetic expression to any attribute — so the sweep is
 * `[STEP_PERCENT] * 3.6` degrees and the watch keeps it current. No
 * complication, no provider, and nothing for this app to recompute.
 */
object StepRing {

    /** How far the ring sits in from the edge of the dial. */
    const val INSET = 10

    /** Stroke weight, on the 456 dial. */
    const val THICKNESS = 9

    /** How faint the unfilled part of the ring is, out of 255. */
    const val TRACK_ALPHA = 48

    /** Degrees of sweep per per-cent of the goal. */
    const val DEGREES_PER_PERCENT = 3.6

    /** The ring's bounding box on the dial. */
    fun box(): SlotGeometry.Box =
        SlotGeometry.Box(INSET, INSET, DIAL_SIZE - INSET * 2, DIAL_SIZE - INSET * 2)

    /**
     * The sweep for a given progress, for the previews.
     *
     * The watch computes this itself from `[STEP_PERCENT]`; a preview has no
     * step count, so it draws a representative arc instead. Capped at a full
     * turn: someone who walks twice their goal gets a complete ring, not two.
     */
    fun sweepDegrees(percent: Double): Double =
        (percent.coerceIn(0.0, 100.0) * DEGREES_PER_PERCENT)

    /** What a preview shows, with no real step data to draw. */
    const val SAMPLE_PERCENT = 68.0
}
