package com.bfg.watchfaces.workbench

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Turn device captures into images the Play Console will accept.
 *
 * ## Why this exists rather than "just upload the screenshots"
 *
 * Play requires an aspect ratio between 16:9 and 9:16. A Pixel 11 Pro XL is
 * 1080x2404, which is **9:20** — taller than the limit, so a raw capture is
 * rejected. Cropping to 9:16 would cut a third of the screen off, which on a
 * scrolling list means cutting off the thing the screenshot is meant to show.
 *
 * So it scales to fit and pads. The whole screen survives, and the padding is
 * the app's own background so it reads as letterboxing rather than as a mistake.
 *
 * ## The status bar goes
 *
 * A store screenshot with somebody's notification icons and battery level in it
 * is a picture of their phone, not of the app. Cropping is the honest way to
 * remove it: the alternative is Android's demo mode, which is a system setting,
 * and this project does not touch those.
 *
 * ## Output is NOT checked in
 *
 * `build/store/`, deliberately. These captures contain whatever was on the
 * device — a saved face made from a personal photo, for one — and that is for
 * the operator to look at before anything reaches a public listing.
 */
object StoreShots {

    /** Play's tallest accepted shape, and the one a phone capture is closest to. */
    private const val OUT_W = 1080
    private const val OUT_H = 1920

    /**
     * The status bar, on a Pixel 11 Pro XL at this density.
     *
     * MEASURED from the output rather than guessed. The first value was 96,
     * which left a sliver of notification icons and a green battery along the
     * top of the padded image — visible only after rendering it, because the
     * crop is fine in the source and only wrong once scaled.
     */
    private const val STATUS_BAR = 150

    /** The app's own background, so the padding does not read as a border. */
    private val BACKDROP = Color(18, 18, 20)

    @JvmStatic
    fun main(args: Array<String>) {
        val from = File(args.firstOrNull()?.removePrefix("--from=") ?: "build/captures")
        val to = File("build/store").apply { mkdirs() }

        val captures = (from.listFiles { f -> f.extension.lowercase() == "png" } ?: emptyArray())
            .sortedBy { it.name }
        if (captures.isEmpty()) {
            println("no captures in ${from.absolutePath}")
            println("put device screenshots there, or pass --from=<dir>")
            return
        }

        for (f in captures) {
            val src = ImageIO.read(f)
            if (src == null) {
                println("skipped ${f.name}: not a readable image")
                continue
            }
            val out = if (src.width == src.height) square(src) else phone(src)
            val target = File(to, f.name)
            ImageIO.write(out, "png", target)
            println("${f.name}  ${src.width}x${src.height} -> ${out.width}x${out.height}")
        }
        println("wrote ${captures.size} to ${to.absolutePath}")
    }

    /**
     * A phone capture: drop the status bar, then scale to fit and centre.
     *
     * Scaled to FIT rather than filled, because filling would crop — and on a
     * list of watch faces the crop lands on the faces.
     */
    private fun phone(src: BufferedImage): BufferedImage {
        val body = src.getSubimage(0, STATUS_BAR.coerceAtMost(src.height - 1), src.width, src.height - STATUS_BAR)
        val scale = minOf(OUT_W.toDouble() / body.width, OUT_H.toDouble() / body.height)
        return canvas(OUT_W, OUT_H, body, scale)
    }

    /** A watch capture is already square; Play wants it larger than it comes off the device. */
    private fun square(src: BufferedImage): BufferedImage {
        val side = 1024
        return canvas(side, side, src, side.toDouble() / src.width)
    }

    private fun canvas(w: Int, h: Int, src: BufferedImage, scale: Double): BufferedImage {
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.color = BACKDROP
        g.fillRect(0, 0, w, h)
        val dw = (src.width * scale).toInt()
        val dh = (src.height * scale).toInt()
        g.drawImage(src, (w - dw) / 2, (h - dh) / 2, dw, dh, null)
        g.dispose()
        return out
    }
}
