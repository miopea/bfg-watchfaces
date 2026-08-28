package com.bfg.watchfaces.generator

/**
 * The two gradients that sit under and over the pattern, described once.
 *
 * Same reasoning as [EngravedStroke], and the same division of labour: what
 * colour, at what stop, between which points is decided here and tested on the
 * JVM; each platform then draws it with its own gradient API. AWT calls them
 * `LinearGradientPaint` and `RadialGradientPaint`, Android calls them
 * `LinearGradient` and `RadialGradient`, and neither difference should be able
 * to change what a dial looks like.
 *
 * This is the rest of what `DECISIONS.md` 2026-08-27 flagged: two rasterizers
 * that "start identical and drift". The stroke passes moved first because they
 * are the engraved look itself; the shading is the other half, and a sheen that
 * is subtly lighter on one platform is exactly the kind of drift nobody notices
 * until two screenshots are put side by side.
 */
object DialShading {

    /** A gradient stop: how far along, and what colour. */
    data class Stop(val at: Float, val argb: Int)

    /**
     * The diagonal sheen across the dial, or null when it is switched off.
     *
     * Runs from upper-left to lower-right, light to transparent to dark, so the
     * dial reads as a physical disc catching a single light rather than a flat
     * fill. [from] and [to] are in 456x456 dial space.
     */
    data class Sheen(
        val fromX: Double, val fromY: Double,
        val toX: Double, val toY: Double,
        val stops: List<Stop>
    )

    /**
     * The darkening toward the rim, or null when it is switched off.
     *
     * Radial from the centre. The middle stop is deliberately much closer to
     * transparent than to the edge value: a linear fade to the rim reads as a
     * grey wash over the whole face, where this keeps the centre clean and only
     * turns down in the last third.
     */
    data class Vignette(
        val centerX: Double, val centerY: Double,
        val radius: Double,
        val stops: List<Stop>
    )

    fun sheen(p: DialParams): Sheen? {
        if (p.sheen <= 0.0) return null
        val dial = EngravedStroke.rgb(p.dialColor)
        val k = (p.sheen / 100.0).coerceIn(0.0, 1.0)
        val light = EngravedStroke.withAlpha(EngravedStroke.mix(dial, 0xFFFFFF, 0.75), (k * 90).toInt())
        val dark = EngravedStroke.withAlpha(EngravedStroke.mix(dial, 0x000000, 0.55), (k * 70).toInt())
        // The middle stop is the dial colour at zero alpha rather than plain
        // transparent black: blending toward transparent BLACK would dirty the
        // midtones on a light dial.
        val clear = EngravedStroke.withAlpha(dial, 0)
        return Sheen(
            fromX = DIAL_SIZE * 0.12, fromY = DIAL_SIZE * 0.05,
            toX = DIAL_SIZE * 0.88, toY = DIAL_SIZE * 0.95,
            stops = listOf(Stop(0.0f, light), Stop(0.5f, clear), Stop(1.0f, dark))
        )
    }

    fun vignette(p: DialParams): Vignette? {
        if (p.vignette <= 0.0) return null
        val k = (p.vignette / 100.0).coerceIn(0.0, 1.0)
        return Vignette(
            centerX = DIAL_CENTER, centerY = DIAL_CENTER,
            radius = DIAL_RADIUS.toDouble(),
            stops = listOf(
                Stop(0.0f, 0x00000000),
                Stop(0.55f, (k * 40).toInt().coerceIn(0, 255) shl 24),
                Stop(1.0f, (k * 235).toInt().coerceIn(0, 255) shl 24)
            )
        )
    }
}
