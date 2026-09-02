package com.bfg.watchfaces.workbench

import com.bfg.watchfaces.generator.DIAL_SIZE
import com.bfg.watchfaces.generator.DialParams
import com.bfg.watchfaces.generator.HandStyle
import com.bfg.watchfaces.generator.Hands
import java.awt.image.BufferedImage
import java.awt.geom.AffineTransform
import java.io.File
import javax.imageio.ImageIO

/**
 * Look at the hands before a watch ever does.
 *
 * `./gradlew :workbench:hands` writes the three hand images, the hub, and a
 * composite showing them assembled on a real dial at 10:10:30. Judging geometry
 * from a photograph of a wrist is how the last week went; this is the loop that
 * replaces it, and it runs in about a second with no device.
 *
 * ## Why 10:10:30
 *
 * The showroom time, and not for looks: at 10:10 the hour and minute hands are
 * splayed symmetrically, so an hour hand mistakenly the same length as the
 * minute hand is immediately obvious — which is exactly the class of mistake
 * that is invisible at, say, 12:00 where they overlap.
 *
 * ## This composites the same way Watch Face Format will
 *
 * Each hand is drawn on the full dial canvas pointing at twelve and ROTATED
 * about the centre, which is what `HourHand` with `pivotX="0.5" pivotY="0.5"`
 * does on the watch. So if a hand wobbles here it will wobble there, and if the
 * pivot is wrong this sheet shows it rather than a wrist three days later.
 */
object HandsSheet {

    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(args.firstOrNull()?.removePrefix("--out=") ?: "build/hands")
        out.mkdirs()

        val styles = HandStyle.entries.filter { drawnYet(it) }
        require(styles.isNotEmpty()) { "no hand style has geometry yet" }

        for (style in styles) {
            val p = DialParams()
            for (hand in Hands.Hand.entries) {
                ImageIO.write(
                    DialRenderer.renderHand(p, style, hand),
                    "png",
                    File(out, "${style.name.lowercase()}_${hand.name.lowercase()}.png")
                )
            }
            ImageIO.write(
                DialRenderer.renderHub(p, style), "png",
                File(out, "${style.name.lowercase()}_hub.png")
            )
            ImageIO.write(assemble(p, style), "png", File(out, "${style.name.lowercase()}_face.png"))
            println("wrote ${style.label} -> ${out.absolutePath}")
        }
    }

    /** A style with no geometry throws rather than substituting; ask, don't assume. */
    private fun drawnYet(style: HandStyle): Boolean =
        runCatching { Hands.shapes(style, Hands.Hand.HOUR) }.isSuccess

    /**
     * The dial, its indices, and the three hands at 10:10:30.
     *
     * Deliberately NOT the shipped composition — on the watch these are separate
     * elements that Watch Face Format layers and rotates. This assembles them
     * the same way so the result can be judged, which is a different job from
     * emitting them.
     */
    fun assemble(p: DialParams, style: HandStyle, size: Int = DIAL_SIZE): BufferedImage {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()

        val dial = DialRenderer.render(p, size)
        g.drawImage(dial, 0, 0, null)

        // Indices belong to the dial: they do not move, so on the watch they are
        // baked into dial_bg.png rather than costing a second PartImage.
        val s = size.toDouble() / DIAL_SIZE
        val old = g.transform
        g.scale(s, s)
        DialRenderer.drawIndices(g, p, style)
        g.transform = old

        // 10:10:30, as angles from twelve.
        val hourDeg = (10.0 + 10.0 / 60.0 + 30.0 / 3600.0) / 12.0 * 360.0
        val minuteDeg = (10.0 + 30.0 / 60.0) / 60.0 * 360.0
        val secondDeg = 30.0 / 60.0 * 360.0

        for ((hand, deg) in listOf(
            Hands.Hand.HOUR to hourDeg,
            Hands.Hand.MINUTE to minuteDeg,
            Hands.Hand.SECOND to secondDeg
        )) {
            val layer = DialRenderer.renderHand(p, style, hand, size)
            val at = AffineTransform.getRotateInstance(
                Math.toRadians(deg), size / 2.0, size / 2.0
            )
            g.drawImage(layer, at, null)
        }
        g.drawImage(DialRenderer.renderHub(p, style, size), 0, 0, null)

        g.dispose()
        return img
    }
}
