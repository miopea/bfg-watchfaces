package com.bfg.watchfaces.generator

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Makes a user's ink colour readable on the always-on screen.
 *
 * Ambient is a BLACK screen: the dial image fades to alpha 0 and only text
 * remains. Nothing stopped someone choosing near-black ink -- the palette offers
 * `#1A1A1A` -- which looks deliberate on a pale dial and then renders the time
 * invisible the moment the watch dims. The face is not broken in any way a test
 * or a schema could see; it is simply unreadable on the wrist.
 *
 * The fix keeps the colour's CHARACTER and raises only its brightness: a deep
 * navy stays recognisably navy, just light enough to read. Forcing everything to
 * white would work and would throw away a design dimension across the whole
 * catalog; warning the user would be honest and would still ship broken faces.
 *
 * Hue and saturation are preserved exactly; HSL lightness is raised until the
 * colour clears a contrast floor against black.
 */
object AmbientPalette {

    /**
     * WCAG 2.1 AA for large text is 3:1, and the time is very large. This is the
     * 4.5:1 floor instead, because ambient is viewed at a glance, at an angle,
     * often outdoors, and the panel is dimmed further by the watch itself.
     *
     * contrast = (L + 0.05) / 0.05 against black, so 4.5:1 needs L >= 0.175.
     */
    const val MIN_LUMINANCE = 0.175

    /** WCAG relative luminance. */
    fun relativeLuminance(hex: String): Double {
        val (r, g, b) = rgb(hex)
        fun ch(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(b)
    }

    /** Contrast ratio against a black screen. */
    fun contrastOnBlack(hex: String): Double = (relativeLuminance(hex) + 0.05) / 0.05

    /**
     * The ambient form of [hex]: same hue and saturation, lightened only as far
     * as it takes to clear [minLuminance]. A colour already bright enough is
     * returned unchanged, so a white-ink face is byte-identical to before.
     */
    fun forAmbient(hex: String, minLuminance: Double = MIN_LUMINANCE): String {
        if (relativeLuminance(hex) >= minLuminance) return normalize(hex)

        val (h, s, l0) = toHsl(hex)
        // Lightness maps monotonically to luminance at fixed hue/saturation, so
        // a bisection converges quickly and needs no per-hue special cases.
        var lo = l0
        var hi = 1.0
        repeat(24) {
            val mid = (lo + hi) / 2
            if (relativeLuminance(fromHsl(h, s, mid)) < minLuminance) lo = mid else hi = mid
        }
        return fromHsl(h, s, hi)
    }

    // ---- colour space plumbing ----------------------------------------------

    private fun rgb(hex: String): Triple<Int, Int, Int> {
        val v = hex.removePrefix("#")
        require(v.length == 6) { "expected #RRGGBB, got '$hex'" }
        return Triple(v.substring(0, 2).toInt(16), v.substring(2, 4).toInt(16), v.substring(4, 6).toInt(16))
    }

    private fun normalize(hex: String): String {
        val (r, g, b) = rgb(hex)
        return "#%02X%02X%02X".format(r, g, b)
    }

    private fun toHsl(hex: String): Triple<Double, Double, Double> {
        val (ri, gi, bi) = rgb(hex)
        val r = ri / 255.0; val g = gi / 255.0; val b = bi / 255.0
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        val l = (mx + mn) / 2
        if (abs(mx - mn) < 1e-9) return Triple(0.0, 0.0, l)   // grey: hue is undefined
        val d = mx - mn
        val s = if (l > 0.5) d / (2 - mx - mn) else d / (mx + mn)
        val h = when (mx) {
            r -> ((g - b) / d + if (g < b) 6 else 0)
            g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        } * 60
        return Triple(h, s, l)
    }

    private fun fromHsl(h: Double, s: Double, l: Double): String {
        if (s < 1e-9) {
            val v = (l * 255).toInt().coerceIn(0, 255)
            return "#%02X%02X%02X".format(v, v, v)
        }
        val c = (1 - abs(2 * l - 1)) * s
        val x = c * (1 - abs(((h / 60) % 2) - 1))
        val m = l - c / 2
        val (r1, g1, b1) = when {
            h < 60 -> Triple(c, x, 0.0)
            h < 120 -> Triple(x, c, 0.0)
            h < 180 -> Triple(0.0, c, x)
            h < 240 -> Triple(0.0, x, c)
            h < 300 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        fun ch(v: Double) = ((v + m) * 255).toInt().coerceIn(0, 255)
        return "#%02X%02X%02X".format(ch(r1), ch(g1), ch(b1))
    }
}
