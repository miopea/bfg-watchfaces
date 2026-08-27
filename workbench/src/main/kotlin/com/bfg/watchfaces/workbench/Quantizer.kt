package com.bfg.watchfaces.workbench

import java.awt.image.BufferedImage
import java.awt.image.IndexColorModel
import kotlin.math.abs

/**
 * Median-cut colour quantization to a palette PNG.
 *
 * Not optional decoration: the APK crosses to the watch over Bluetooth, and
 * docs/SPEC.md requires this on every dial. Measured on the reference face,
 * 368KB -> 77KB at 64 colours with mean error 0.66/255 -- visually identical on
 * a soft low-contrast dial.
 *
 * This buys TRANSFER time only. The in-memory footprint on the watch is
 * unchanged at 456*456*4 = 831KB, because the framebuffer is RGBA regardless.
 */
object Quantizer {

    data class Result(val image: BufferedImage, val colors: Int, val meanError: Double)

    private class Box(val pixels: IntArray, var from: Int, var to: Int) {
        var rMin = 255; var rMax = 0; var gMin = 255; var gMax = 0; var bMin = 255; var bMax = 0
        fun shrink() {
            rMin = 255; rMax = 0; gMin = 255; gMax = 0; bMin = 255; bMax = 0
            for (i in from until to) {
                val p = pixels[i]
                val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                if (r < rMin) rMin = r; if (r > rMax) rMax = r
                if (g < gMin) gMin = g; if (g > gMax) gMax = g
                if (b < bMin) bMin = b; if (b > bMax) bMax = b
            }
        }
        fun widest(): Int {
            val dr = rMax - rMin; val dg = gMax - gMin; val db = bMax - bMin
            return if (dr >= dg && dr >= db) 0 else if (dg >= db) 1 else 2
        }
        fun volume(): Int = (rMax - rMin + 1) * (gMax - gMin + 1) * (bMax - bMin + 1)
        fun count(): Int = to - from
    }

    /**
     * Quantize to at most [maxColors]. The dial is fully opaque inside the
     * circle and fully transparent outside it, so alpha is handled as a single
     * dedicated transparent palette entry rather than being dithered.
     */
    fun quantize(src: BufferedImage, maxColors: Int = 64): Result {
        require(maxColors in 2..256) { "maxColors must be 2..256, got $maxColors" }
        val w = src.width; val h = src.height
        val argb = IntArray(w * h)
        src.getRGB(0, 0, w, h, argb, 0, w)

        // Opaque pixels only feed the palette; transparent ones get their own slot.
        val opaque = ArrayList<Int>(argb.size)
        var hasTransparent = false
        for (p in argb) {
            if ((p ushr 24) < 128) hasTransparent = true else opaque.add(p and 0xFFFFFF)
        }
        val budget = if (hasTransparent) maxColors - 1 else maxColors
        val pix = opaque.toIntArray()

        val palette: IntArray = if (pix.isEmpty()) {
            intArrayOf(0)
        } else {
            val boxes = ArrayList<Box>()
            boxes.add(Box(pix, 0, pix.size).also { it.shrink() })
            while (boxes.size < budget) {
                // Split the box with the largest volume*population. Population
                // alone over-splits gradients; volume alone chases outliers.
                val target = boxes.filter { it.count() > 1 && it.volume() > 1 }
                    .maxByOrNull { it.volume().toLong() * it.count() } ?: break
                val axis = target.widest()
                val sub = pix.copyOfRange(target.from, target.to)
                sub.sortedBy { (it shr (16 - 8 * axis)) and 0xFF }
                    .forEachIndexed { i, v -> pix[target.from + i] = v }
                val mid = target.from + target.count() / 2
                val right = Box(pix, mid, target.to)
                target.to = mid
                target.shrink(); right.shrink()
                boxes.add(right)
            }
            IntArray(boxes.size) { i ->
                val b = boxes[i]
                var r = 0L; var g = 0L; var bl = 0L
                for (j in b.from until b.to) {
                    val p = b.pixels[j]
                    r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; bl += p and 0xFF
                }
                val n = maxOf(1, b.count())
                (((r / n).toInt()) shl 16) or (((g / n).toInt()) shl 8) or ((bl / n).toInt())
            }
        }

        val size = palette.size + if (hasTransparent) 1 else 0
        val reds = ByteArray(size); val greens = ByteArray(size); val blues = ByteArray(size)
        val alphas = ByteArray(size)
        for (i in palette.indices) {
            reds[i] = (((palette[i] shr 16) and 0xFF)).toByte()
            greens[i] = (((palette[i] shr 8) and 0xFF)).toByte()
            blues[i] = ((palette[i] and 0xFF)).toByte()
            alphas[i] = 255.toByte()
        }
        val transparentIndex = if (hasTransparent) size - 1 else -1
        if (hasTransparent) { reds[transparentIndex] = 0; greens[transparentIndex] = 0; blues[transparentIndex] = 0; alphas[transparentIndex] = 0 }

        val bits = Math.max(1, 32 - Integer.numberOfLeadingZeros(Math.max(1, size - 1)))
        val icm = IndexColorModel(bits, size, reds, greens, blues, alphas)
        val out = BufferedImage(w, h, BufferedImage.TYPE_BYTE_INDEXED, icm)
        val raster = out.raster

        val cache = HashMap<Int, Int>(4096)
        var errSum = 0.0; var errN = 0L
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = argb[y * w + x]
                if ((p ushr 24) < 128 && transparentIndex >= 0) {
                    raster.setSample(x, y, 0, transparentIndex); continue
                }
                val rgb = p and 0xFFFFFF
                val idx = cache.getOrPut(rgb) { nearest(palette, rgb) }
                raster.setSample(x, y, 0, idx)
                val q = palette[idx]
                errSum += (abs(((rgb shr 16) and 0xFF) - ((q shr 16) and 0xFF)) +
                           abs(((rgb shr 8) and 0xFF) - ((q shr 8) and 0xFF)) +
                           abs((rgb and 0xFF) - (q and 0xFF))) / 3.0
                errN++
            }
        }
        return Result(out, size, if (errN == 0L) 0.0 else errSum / errN)
    }

    private fun nearest(palette: IntArray, rgb: Int): Int {
        val r = (rgb shr 16) and 0xFF; val g = (rgb shr 8) and 0xFF; val b = rgb and 0xFF
        var best = 0; var bestD = Int.MAX_VALUE
        for (i in palette.indices) {
            val p = palette[i]
            val dr = r - ((p shr 16) and 0xFF); val dg = g - ((p shr 8) and 0xFF); val db = b - (p and 0xFF)
            // Weighted to human luminance sensitivity; plain RGB distance visibly
            // mis-picks on the warm low-contrast dials this project ships.
            val d = 2 * dr * dr + 4 * dg * dg + 3 * db * db
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }
}
