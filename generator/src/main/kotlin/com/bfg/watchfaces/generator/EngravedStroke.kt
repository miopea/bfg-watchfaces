package com.bfg.watchfaces.generator

/**
 * The engraved look, defined once: three passes per polyline.
 *
 * A light pass offset up-left by `relief`, a dark pass offset down-right by the
 * same, and a thin mid pass at zero that holds the line together. `CLAUDE.md`
 * states this as a rule, and it has always been a renderer concern rather than
 * an engine one — the engines emit bare geometry and something else decides what
 * a stroke looks like.
 *
 * ## Why it moved here
 *
 * `DECISIONS.md` 2026-08-27 recorded the cost of the workbench's AWT renderer:
 * when `:mobile` arrives there will be two rasterizers unless they are
 * deliberately unified, "most cleanly by extracting a small drawing interface
 * into `:generator` that AWT and Android `Canvas` both implement, leaving stroke
 * order and compositing defined once." It said not to guess that shape before
 * the Android side existed. It exists now, so this is that extraction.
 *
 * What is shared is deliberately NOT a canvas. It is the *description* of the
 * passes: how many, in what order, offset how far, in what colour, at what
 * width. That is the part where a second renderer would silently drift into
 * looking almost-right. Each platform still executes the passes with its own
 * drawing API, because a lowest-common-denominator canvas interface across AWT
 * and Android `Canvas` would be a large surface to maintain for no extra
 * safety.
 *
 * Pure and Android-free, so the arithmetic that decides how every dial in the
 * catalog looks is tested on the JVM in CI.
 */
object EngravedStroke {

    /**
     * One pass over every polyline in the pattern.
     *
     * [dx]/[dy] are a translation applied before drawing, in dial-space pixels.
     * [argb] is straight 0xAARRGGBB. [width] is the stroke width.
     */
    data class Pass(val dx: Double, val dy: Double, val argb: Int, val width: Double)

    /**
     * The passes for a set of parameters, in the order they must be drawn.
     *
     * Order matters and is not cosmetic: the mid pass goes last so it sits over
     * both offsets, which is what stops a thin line reading as two parallel
     * ghosts at high relief.
     */
    fun passes(p: DialParams): List<Pass> {
        val dial = rgb(p.dialColor)
        val ink = rgb(p.inkColor)

        val k = (p.contrast / 100.0).coerceIn(0.0, 1.0)
        val light = withAlpha(mix(dial, WHITE, 0.80), (k * 205).toInt())
        val dark = withAlpha(mix(dial, BLACK, 0.62), (k * 185).toInt())
        val mid = withAlpha(ink, (k * 42).toInt())

        // Diagonal component, so `relief` reads as a distance rather than as a
        // per-axis offset that would be sqrt(2) too far.
        val d = p.relief * 0.7071

        return listOf(
            Pass(-d, -d, light, p.stroke),        // highlight, up-left
            Pass(d, d, dark, p.stroke),           // shadow, down-right
            Pass(0.0, 0.0, mid, p.stroke * 0.5)   // thin mid pass holds the line together
        )
    }

    // ---- colour arithmetic, kept here so both renderers get the same answer ---

    private const val WHITE = 0xFFFFFF
    private const val BLACK = 0x000000

    /** `#RRGGBB` to a 24-bit int. Params are six digits; WFF's eight are the emitter's job. */
    fun rgb(hex: String): Int {
        val h = hex.removePrefix("#")
        require(h.length == 6) { "colours in DialParams are #RRGGBB, got '$hex'" }
        return h.toInt(16)
    }

    /**
     * Blend towards [b] by [t].
     *
     * TRUNCATES rather than rounds, matching what the workbench renderer has
     * always done. That biases every channel very slightly down, and it would
     * be defensible to round instead — but not here and not quietly: this
     * arithmetic decides the colour of every dial already in the catalog, so
     * changing it would restyle faces their authors have saved. That is what
     * `generatorVersion` exists to prevent, and this extraction is explicitly
     * a no-op refactor, proved by rendering every engine before and after and
     * comparing the bytes.
     */
    fun mix(a: Int, b: Int, t: Double): Int {
        val u = t.coerceIn(0.0, 1.0)
        fun ch(shift: Int): Int {
            val av = (a shr shift) and 0xFF
            val bv = (b shr shift) and 0xFF
            return (av + (bv - av) * u).toInt().coerceIn(0, 255)
        }
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Attach an alpha to a 24-bit colour, producing 0xAARRGGBB. */
    fun withAlpha(rgb: Int, alpha: Int): Int =
        (alpha.coerceIn(0, 255) shl 24) or (rgb and 0xFFFFFF)
}
