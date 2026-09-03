package com.bfg.watchfaces.generator

/**
 * A band of light across the crystal, for the tilt effect to move.
 *
 * ## Why a separate layer rather than moving the dial
 *
 * Two earlier attempts failed for the same reason, and it was not the mechanism:
 *
 * - **Text relief** could only ever show through a hairline around the numerals
 *   — measured at 4,097 px, 2.51% of the dial.
 * - **Dial parallax** covered 97% of the screen and was still invisible, because
 *   a guilloche pattern is PERIODIC. Measured: shifting it 6px gave a mean
 *   channel delta of 9/255, and tripling the distance to 20px gave 10.3. There
 *   is no landmark in a repeating texture to see movement against.
 *
 * So a visible effect needs three things at once, and neither attempt had all
 * three: a large area, **no periodicity to hide in**, and travel measured in
 * tens of pixels rather than single ones.
 *
 * A single soft band has all three. It is one shape, so moving it is
 * unambiguous; it spans the dial, so it covers area; and it can travel a long
 * way because it is not tied to anything underneath.
 *
 * ## Described here, drawn by each platform
 *
 * The same arrangement as [PatternEngines] and [Hands]: one definition, two
 * executions. A gradient is simple enough to describe as numbers, and doing so
 * is what stops the phone and the workbench drawing different light.
 */
object Glare {

    /**
     * How far the band travels from one extreme of tilt to the other.
     *
     * Large on purpose. The failures above were both effects of a few pixels;
     * this sweeps most of the way across the dial, which is what makes it
     * something a person notices rather than something a diff notices.
     */
    const val TRAVEL = 150.0

    /** The band's angle, as a fraction of a turn. Down-right, like the engraving. */
    const val ANGLE_DEGREES = 45.0

    /**
     * Half-width of the soft band, in dial units.
     *
     * Wide enough that its edges are never both on screen at once — a band with
     * two visible edges reads as a stripe laid on top, rather than as light.
     */
    const val HALF_WIDTH = 150.0

    /**
     * Peak opacity at the centre of the band, 0-255.
     *
     * The ceiling is set by the time staying readable: this sits OVER the
     * numerals, so anything strong enough to wash them out is wrong however
     * good it looks standing still.
     *
     * MEASURED, not chosen. Mean channel delta between the two extremes of
     * tilt, over the dial:
     *
     * ```text
     * peak  46 -> 4.7    (fainter than the parallax that failed)
     * peak  90 -> 9.3
     * peak 140 -> 14.5   <- here
     * peak 190 -> 19.7   (starts to flatten the numerals)
     * ```
     *
     * 140 is where the band is unmistakable in a render and the cream numerals
     * are still fully legible over it.
     */
    const val PEAK_ALPHA = 140

    /**
     * How bright the band is, at [distance] dial units from its centre line.
     *
     * A raised cosine rather than a linear ramp: linear leaves a visible crease
     * where the slope changes, and the whole point of this shape is that it has
     * no edges to give it away as a drawn object.
     */
    fun intensityAt(distance: Double): Double {
        val d = kotlin.math.abs(distance)
        if (d >= HALF_WIDTH) return 0.0
        val t = d / HALF_WIDTH
        return 0.5 * (1.0 + kotlin.math.cos(Math.PI * t))
    }

    /**
     * True when a face should carry one.
     *
     * Gated with the rest of the tilt work, AND on the control being above
     * zero. A face turned down to nothing emits no layer at all rather than a
     * transparent one, so it carries no `<Gyro>` and never turns the
     * accelerometer on. See [DialParams.glare].
     */
    fun enabledFor(p: DialParams): Boolean = supportedBy(p) && p.glare > 0.0

    /**
     * Whether this face's geometry has a glow at all, regardless of the setting.
     *
     * Separate from [enabledFor] because a UI needs to know whether to OFFER the
     * control, and "turned down to zero" must keep offering it -- otherwise the
     * slider vanishes at the moment somebody drags it to nothing, and there is
     * no way to bring it back.
     */
    fun supportedBy(p: DialParams): Boolean = p.generatorVersion >= 13

    /**
     * [PEAK_ALPHA] scaled by the face's own setting. What renderers must use.
     *
     * ## Why the scale is here and not on the emitted `alpha`
     *
     * The `<PartImage>` could have carried it instead, and that was the first
     * shape. But the two PREVIEWS do not go through the emitter -- they call
     * `renderGlare` directly -- so an emitter-side scale would need scaling
     * again in the workbench preview and again in the phone's, and a control
     * implemented three times is three chances to disagree. That is the exact
     * shape `SlotGeometry` and `ControlInventory` exist to stop.
     *
     * Putting it in the PNG means the previews and the shipped face read the
     * same number by construction, and the emitter keeps the literal 255 it
     * always had.
     */
    fun peakAlphaFor(p: DialParams): Int =
        (PEAK_ALPHA * (p.glare.coerceIn(0.0, 100.0) / 100.0)).toInt()
}
