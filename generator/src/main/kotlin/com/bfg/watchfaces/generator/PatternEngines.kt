package com.bfg.watchfaces.generator

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin

/**
 * Guilloche engines. Pure functions: params in, polylines out. No Canvas, no
 * Graphics2D, no Android. That's what lets the whole thing be unit tested in CI
 * without an emulator, and it's why the same code can drive the live preview
 * and bake the shipped PNG.
 *
 * Renderers stroke each polyline three times to read as cut metal rather than
 * print: a light pass offset by -relief, a dark pass offset by +relief, and a
 * thin mid pass.
 */
object PatternEngines {

    fun paths(p: DialParams): List<Polyline> = when (p.generatorVersion) {
        1 -> v1(p)
        else -> error("no engine implementation for generatorVersion=${p.generatorVersion}")
    }

    private fun v1(p: DialParams): List<Polyline> = when (p.engine) {
        Engine.LATTICE -> lattice(p)
        Engine.CLOUS -> clous(p)
        Engine.ROSETTE -> rosette(p)
        Engine.BARLEYCORN -> barleycorn(p)
        Engine.SUNBURST -> sunburst(p)
        Engine.BOTANICAL -> botanical(p)
        Engine.NONE -> emptyList()
    }

    private const val SPAN = DIAL_SIZE * 1.5

    private fun rot(x: Double, y: Double, a: Double): Pt =
        Pt(DIAL_CENTER + x * cos(a) - y * sin(a), DIAL_CENTER + x * sin(a) + y * cos(a))

    private fun lattice(p: DialParams): List<Polyline> {
        val out = ArrayList<Polyline>()
        for (dir in listOf(1.0, -1.0)) {
            var off = -SPAN / 2
            while (off <= SPAN / 2) {
                val pts = ArrayList<Pt>()
                var t = -SPAN / 2
                while (t <= SPAN / 2) {
                    // Small phase drift per line keeps it hand-cut. Large drift
                    // turns a lattice into wandering water - keep it low.
                    val w = p.depth * sin((t / SPAN) * p.freq * PI * 2 + off * 0.004)
                    pts.add(rot(t, off + w, dir * p.rotate * PI / 180))
                    t += 3.0
                }
                out.add(pts); off += p.scale
            }
        }
        return out
    }

    private fun clous(p: DialParams): List<Polyline> {
        val out = ArrayList<Polyline>()
        val step = max(6.0, p.scale)
        val a = p.rotate * PI / 180
        val m = { x: Double, y: Double -> rot(x, y, a) }
        var gx = -SPAN / 2
        while (gx <= SPAN / 2) {
            var gy = -SPAN / 2
            while (gy <= SPAN / 2) {
                val h = step / 2; val d = p.depth * 0.25
                out.add(listOf(m(gx - h, gy), m(gx, gy - h), m(gx + h, gy), m(gx, gy + h), m(gx - h, gy)))
                out.add(listOf(m(gx - h + d, gy), m(gx + h - d, gy)))
                out.add(listOf(m(gx, gy - h + d), m(gx, gy + h - d)))
                gy += step
            }
            gx += step
        }
        return out
    }

    private fun rosette(p: DialParams): List<Polyline> {
        val out = ArrayList<Polyline>()
        val rings = max(3, round(DIAL_RADIUS / max(4.0, p.scale * 0.85)).toInt())
        for (k in 0 until rings) {
            // Start rings out from the centre and taper the wave inward, or the
            // curves pile up on the pivot exactly where the time sits.
            val rr = (0.16 + 0.90 * (k + 1) / rings) * DIAL_RADIUS
            val phase = k * 0.26 + p.rotate * PI / 180
            val pts = ArrayList<Pt>()
            var t = 0.0
            while (t <= PI * 2 + 0.05) {
                val r = rr + p.depth * 0.65 * min(1.0, rr / (DIAL_RADIUS * 0.55)) * sin(p.freq * t * 2 + phase)
                pts.add(Pt(DIAL_CENTER + r * cos(t), DIAL_CENTER + r * sin(t)))
                t += 0.015
            }
            out.add(pts)
        }
        return out
    }

    private fun barleycorn(p: DialParams): List<Polyline> {
        val out = ArrayList<Polyline>()
        for (dir in listOf(0.0, 60.0, 120.0)) {
            var off = -SPAN / 2
            while (off <= SPAN / 2) {
                val pts = ArrayList<Pt>()
                var t = -SPAN / 2
                while (t <= SPAN / 2) {
                    val w = p.depth * sin((t / SPAN) * p.freq * PI * 2 + off * 0.05)
                    pts.add(rot(t, off + w, (dir + p.rotate) * PI / 180))
                    t += 3.0
                }
                out.add(pts); off += p.scale
            }
        }
        return out
    }

    private fun sunburst(p: DialParams): List<Polyline> {
        val out = ArrayList<Polyline>()
        val n = max(24, round(720.0 / p.scale).toInt())
        for (i in 0 until n) {
            val a = i.toDouble() / n * PI * 2 + p.rotate * PI / 180
            val pts = ArrayList<Pt>()
            var r = 6.0
            while (r <= DIAL_RADIUS * 1.05) {
                val w = p.depth * sin(r / DIAL_RADIUS * p.freq * PI * 2) / 40
                pts.add(Pt(DIAL_CENTER + r * cos(a + w), DIAL_CENTER + r * sin(a + w)))
                r += 4.0
            }
            out.add(pts)
        }
        return out
    }

    private fun botanical(p: DialParams): List<Polyline> {
        val out = ArrayList<Polyline>()
        val step = max(14.0, p.scale)
        val a = p.rotate * PI / 180
        val m = { x: Double, y: Double -> rot(x, y, a) }
        val petals = 4 + (p.freq % 3)
        val len = step * 0.36 + p.depth * 0.5
        val wid = len * 0.42

        fun leaf(cx: Double, cy: Double, ang: Double) {
            val A = ArrayList<Pt>(); val B = ArrayList<Pt>()
            var t = 0.0
            while (t <= 1.001) {
                val along = t * len; val across = wid * sin(PI * t)
                A.add(m(cx + along * cos(ang) - across * sin(ang), cy + along * sin(ang) + across * cos(ang)))
                B.add(m(cx + along * cos(ang) + across * sin(ang), cy + along * sin(ang) - across * cos(ang)))
                t += 0.08
            }
            out.add(A); out.add(B.reversed())
        }

        var gx = -SPAN / 2 * 0.94
        while (gx <= SPAN / 2 * 0.94) {
            var gy = -SPAN / 2 * 0.94
            while (gy <= SPAN / 2 * 0.94) {
                val stagger = if (round(gy / step).toInt() % 2 != 0) step / 2 else 0.0
                val cx = gx + stagger; val cy = gy
                for (i in 0 until petals) leaf(cx, cy, i.toDouble() / petals * PI * 2)
                val seed = ArrayList<Pt>()
                var t = 0.0
                while (t <= PI * 2 + 0.2) { seed.add(m(cx + 1.6 * cos(t), cy + 1.6 * sin(t))); t += 0.5 }
                out.add(seed)
                val vine = ArrayList<Pt>()
                var u = 0.0
                while (u <= 1.001) { vine.add(m(cx + len + u * (step - len * 2), cy + sin(u * PI) * step * 0.16)); u += 0.1 }
                out.add(vine)
                gy += step
            }
            gx += step
        }
        return out
    }

    /** Fraction of emitted points inside the dial circle. Sanity check for tests. */
    fun coverage(paths: List<Polyline>): Double {
        var inside = 0; var total = 0
        for (pl in paths) for (pt in pl) {
            total++
            if (hypot(pt.x - DIAL_CENTER, pt.y - DIAL_CENTER) <= DIAL_RADIUS) inside++
        }
        return if (total == 0) 0.0 else inside.toDouble() / total
    }
}
