package com.bfg.watchfaces.generator

/**
 * Shades a generated height field into a dial, as pixels.
 *
 * The last of the rendering decisions to leave a renderer. [EngravedStroke] took
 * the stroke passes, [DialShading] the gradients, [ComplicationGlyphs] the
 * icons; this is the lighting model for `GRAIN`, `BRUSHED`, `CARBON` and
 * `LINEN`. It was the reason `AndroidDialRenderer` fell back to a plain dial for
 * four of the thirteen styles — the arithmetic lived in the AWT renderer, and
 * copying it would have been a fourth chance to drift.
 *
 * Output is raw ARGB. Neither platform's image type appears here, so `AWT` can
 * `setRGB` it and Android can `Bitmap.createBitmap` it, and neither gets an
 * opinion about the lighting on the way.
 *
 * ## The lighting
 *
 * A cheap directional bump: the field's slope brightens or darkens the dial
 * colour. That is what makes brushed metal look brushed rather than merely
 * noisy, and it is the same top-left key light the stroked engines emboss
 * against, so the two families sit together.
 */
object ProceduralDial {

    /**
     * The dial as ARGB pixels, [size] x [size], row-major.
     *
     * [dialRgb] is 0xRRGGBB. The result is fully opaque — the caller clips it to
     * the dial circle, which is not this object's business.
     */
    fun pixels(kind: TextureField.Kind, p: DialParams, dialRgb: Int, size: Int): IntArray {
        val s = size.toDouble() / DIAL_SIZE

        // The field is built into an array ONCE and the surface normal comes
        // from neighbouring cells. Re-sampling the field for each gradient would
        // be four extra fBm evaluations per pixel -- roughly five times the work
        // for the same picture, and a preview that stutters while a slider moves.
        val field = DoubleArray(size * size)
        for (y in 0 until size) {
            val dy = y / s
            for (x in 0 until size) {
                field[y * size + x] = TextureField.sample(kind, x / s, dy, p)
            }
        }

        val amp = (p.contrast / 100.0).coerceIn(0.0, 1.0)
        val relief = (p.relief / 6.0).coerceIn(0.0, 1.0)
        val red = (dialRgb shr 16) and 0xFF
        val green = (dialRgb shr 8) and 0xFF
        val blue = dialRgb and 0xFF

        val out = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val i = y * size + x
                val h = field[i]
                // Central differences, clamped at the edges.
                val l = field[i - if (x > 0) 1 else 0]
                val r = field[i + if (x < size - 1) 1 else 0]
                val u = field[i - if (y > 0) size else 0]
                val d = field[i + if (y < size - 1) size else 0]
                val slope = ((l - r) + (u - d)) * 0.5

                // Height gives the base tone, slope gives the lit edge.
                val t = (h - 0.5) * amp * 0.55 + slope * relief * 6.0
                out[i] = (0xFF shl 24) or
                    (shift(red, t) shl 16) or (shift(green, t) shl 8) or shift(blue, t)
            }
        }
        return out
    }

    /** Lighten or darken a channel by t in roughly [-1, 1]. */
    fun shift(c: Int, t: Double): Int {
        // Toward white when lifting, toward black when darkening, so the same t
        // reads as the same amount of light on any dial colour.
        val room = if (t >= 0) (255 - c).toDouble() else c.toDouble()
        return (c + t * room).toInt().coerceIn(0, 255)
    }
}
