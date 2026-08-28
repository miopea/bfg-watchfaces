package com.bfg.watchfaces.generator

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Generated surface textures — grain, brushed metal, carbon weave, linen.
 *
 * These are the answer to a real gap: an IMPORTED image makes a face local-only,
 * because docs/SPEC.md's catalog is parameters and a picture is not. A GENERATED
 * texture is parameters, so a textured face can be shared like any other.
 *
 * ## Why a scalar field rather than polylines
 *
 * Every other engine emits `List<Polyline>` and the renderer strokes it. Grain
 * cannot be expressed that way honestly:
 *
 *  - it would take on the order of 100k strokes to read as grain at 456px, each
 *    stroked three times for the emboss, which blows the 400k point budget the
 *    other engines are held to and makes the preview crawl
 *  - stroked lines produce HATCHING, not isotropic noise; you can see the
 *    difference immediately
 *  - the three-pass emboss is meaningful for a cut line and meaningless for a
 *    field of noise
 *
 * So a texture engine emits no geometry (like [Engine.TEXTURE]) and this
 * supplies a height field the renderer shades instead.
 *
 * ## Why a field rather than an image
 *
 * :generator is deliberately free of Canvas, Graphics2D and Android — that is
 * what lets it be tested in CI without rendering anything. Returning a
 * BufferedImage here would break that for the sake of convenience in one
 * caller. A pure `(x, y) -> Double` keeps determinism directly testable and
 * leaves [com.bfg.watchfaces.workbench.DialRenderer] the only rasterizer.
 *
 * ## Determinism
 *
 * Integer hashing throughout, never `Random`. A stored face is parameters and
 * must re-render identically on someone else's device years later; a seeded RNG
 * would be reproducible only as long as nobody touched the call order.
 */
object TextureField {

    /** Which of the generated surfaces. Mirrors the texture [Engine] values. */
    enum class Kind { GRAIN, BRUSHED, CARBON, LINEN }

    fun kindFor(engine: Engine): Kind? = when (engine) {
        Engine.GRAIN -> Kind.GRAIN
        Engine.BRUSHED -> Kind.BRUSHED
        Engine.CARBON -> Kind.CARBON
        Engine.LINEN -> Kind.LINEN
        else -> null
    }

    /** True for engines whose dial is a generated field rather than strokes. */
    fun isProcedural(engine: Engine): Boolean = kindFor(engine) != null

    // ---- deterministic value noise -------------------------------------------

    /** Integer hash to [0,1). No Random anywhere in this file, on purpose. */
    private fun hash(x: Int, y: Int, seed: Int): Double {
        var h = x * 374761393 + y * 668265263 + seed * 1274126177
        h = (h xor (h shr 13)) * 1274126177
        h = h xor (h shr 16)
        return ((h ushr 8) and 0xFFFFFF) / 16777216.0
    }

    private fun smooth(t: Double) = t * t * (3 - 2 * t)

    /** Bilinear value noise at a point, in [0,1]. */
    private fun value(x: Double, y: Double, seed: Int): Double {
        val xi = kotlin.math.floor(x).toInt()
        val yi = kotlin.math.floor(y).toInt()
        val fx = smooth(x - xi)
        val fy = smooth(y - yi)
        val a = hash(xi, yi, seed)
        val b = hash(xi + 1, yi, seed)
        val c = hash(xi, yi + 1, seed)
        val d = hash(xi + 1, yi + 1, seed)
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
    }

    /** Fractional Brownian motion: octaves of value noise, halving in amplitude. */
    private fun fbm(x: Double, y: Double, seed: Int, octaves: Int): Double {
        var sum = 0.0
        var amp = 1.0
        var norm = 0.0
        var fx = x
        var fy = y
        repeat(octaves) {
            sum += amp * value(fx, fy, seed + it * 101)
            norm += amp
            amp *= 0.5
            fx *= 2.0
            fy *= 2.0
        }
        return sum / norm
    }

    /**
     * The surface height at a dial-space point, in [0,1].
     *
     * Pure: same arguments always give the same answer, with no shared state.
     */
    fun sample(kind: Kind, x: Double, y: Double, p: DialParams): Double {
        val a = p.rotate * PI / 180
        // Rotate into the texture's own frame so `rotate` turns the grain,
        // which is what a user means by it on brushed metal.
        val cx = x - DIAL_CENTER
        val cy = y - DIAL_CENTER
        val rx = cx * cos(a) + cy * sin(a)
        val ry = -cx * sin(a) + cy * cos(a)

        // scale reads as "feature size in pixels", so bigger = coarser.
        val s = max(2.0, p.scale) * 0.55
        val seed = p.freq * 7919

        return when (kind) {
            Kind.GRAIN -> fbm(rx / s, ry / s, seed, 4)

            // Brushed metal is noise stretched hard along one axis: the streaks
            // are long in the brush direction and fine across it.
            Kind.BRUSHED -> {
                val streak = fbm(rx / (s * 14), ry / (s * 0.30), seed, 3)
                val fine = fbm(rx / (s * 3), ry / (s * 0.12), seed + 31, 2)
                0.62 * streak + 0.38 * fine
            }

            // Carbon fibre is a 2x2 twill: alternating blocks of tows running at
            // right angles, each tow a fine directional ripple.
            Kind.CARBON -> {
                // Finer tows and a much smaller amplitude than the first
                // attempt, which read as diamond plate rather than carbon: real
                // twill is a subtle sheen change between blocks, not stripes.
                val cell = s * 2.3
                val bx = kotlin.math.floor(rx / cell).toInt()
                val by = kotlin.math.floor(ry / cell).toInt()
                val horizontal = ((bx + by) and 1) == 0
                val u = if (horizontal) ry else rx
                val tow = 0.5 + 0.5 * sin(u / (s * 0.26) * PI)
                // Soften the block seam so the weave reads as woven rather than
                // tiled -- a hard switch draws a grid the eye locks onto.
                val seam = 0.5 + 0.5 * sin((rx + ry) / cell * PI)
                val grit = fbm(rx / (s * 0.7), ry / (s * 0.7), seed + 57, 2)
                0.46 * tow + 0.34 * grit + 0.20 * seam
            }

            // Linen is a plain over-under weave: two soft ripples at right
            // angles, plus enough noise that it does not read as a grid.
            Kind.LINEN -> {
                val warp = 0.5 + 0.5 * sin(rx / (s * 0.9) * PI)
                val weft = 0.5 + 0.5 * sin(ry / (s * 0.9) * PI)
                val slub = fbm(rx / (s * 5), ry / (s * 5), seed + 13, 2)
                val weave = abs(warp - weft)
                0.60 * weave + 0.25 * slub + 0.15 * (warp * weft)
            }
        }
    }
}
