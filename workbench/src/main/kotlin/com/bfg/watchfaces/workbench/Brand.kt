package com.bfg.watchfaces.workbench

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * Writes the app icon everywhere it has to exist.
 *
 *     ./gradlew :workbench:brand
 *
 * Generated, not hand-drawn, for the reason in [BrandMark]: the same mark has to
 * be an Android adaptive icon, a Play store PNG and an SVG, and four hand-made
 * copies drift. Everything this writes is CHECKED IN -- the Android build must
 * not depend on a JVM task having been run, and a reviewer should see the
 * artwork change in the diff.
 *
 * ## The two Android layers
 *
 * An adaptive icon is a 108dp background plus a 108dp foreground, and the
 * launcher masks them to whatever shape it likes. Only the middle 72dp survives
 * every mask, so the mark is fitted to a 72dp circle -- [BrandMark.fitInto]
 * centres it on the ARTWORK's own centre, which is not the canvas centre because
 * of the crown.
 *
 * The third layer, `monochrome`, is what Android 13's themed icons draw: the
 * same paths with the colour left to the system.
 *
 * ## Light, not dark
 *
 * The launcher gets the light palette. The dark one is a near-black tile, and on
 * a dark wallpaper -- which is what a phone that asked for dark mode has -- it
 * stops being an icon and becomes a hole. The dark mark stays in `docs/brand`
 * for the site and the README, where there is a page behind it.
 */
object Brand {

    /**
     * Android's adaptive-icon canvas, and what a launcher actually shows of it.
     *
     * The 108dp layer is NOT what anybody sees. The system reserves 18dp on every
     * side for parallax and pulse effects, so the mask is applied to the middle
     * 72dp -- and inside that, Material's keyline for a round motif is 60dp.
     *
     * Getting this wrong is silent: sized to the 72dp viewport the mark looks
     * perfect in a square preview and loses its crown to the first circular mask
     * it meets. [ADAPTIVE_VIEWPORT] exists so the contact sheet crops the way a
     * launcher does rather than the way the file is authored.
     */
    internal const val ADAPTIVE_CANVAS = 108.0
    internal const val ADAPTIVE_VIEWPORT = 72.0
    internal const val ADAPTIVE_KEYLINE = 60.0

    /**
     * Play's store icon: 512x512, square, no rounding of our own -- Play applies
     * its own corners and rounding a rounded image leaves grey wedges.
     */
    internal const val STORE_PX = 512
    internal const val STORE_MARK_FRACTION = 0.78

    @JvmStatic
    fun main(args: Array<String>) {
        System.setProperty("java.awt.headless", "true")
        val root = findRoot()
        println("brand artwork into ${root.absolutePath}")

        val fit = BrandMark.fitInto(ADAPTIVE_CANVAS, ADAPTIVE_KEYLINE)
        val circle = BrandMark.enclosingCircle()
        println("  mark centre (${BrandMark.n(circle.cx)}, ${BrandMark.n(circle.cy)}) r=${BrandMark.n(circle.radius)}" +
            " -> ${BrandMark.n(ADAPTIVE_KEYLINE)}dp keyline in the ${BrandMark.n(ADAPTIVE_VIEWPORT)}dp viewport")

        for (module in listOf("mobile", "wear")) {
            val res = File(root, "$module/src/main/res")
            write(File(res, "drawable/ic_launcher_foreground.xml"),
                vectorDrawable(fit, BrandMark.Palette.LIGHT, monochrome = false))
            write(File(res, "drawable/ic_launcher_monochrome.xml"),
                vectorDrawable(fit, BrandMark.Palette.LIGHT, monochrome = true))
            write(File(res, "values/ic_launcher_background.xml"), backgroundColor())
            write(File(res, "mipmap-anydpi-v26/ic_launcher.xml"), adaptiveIcon())
            write(File(res, "mipmap-anydpi-v26/ic_launcher_round.xml"), adaptiveIcon())
        }

        val brand = File(root, "docs/brand")
        write(File(brand, "icon-light.svg"), tileSvg(BrandMark.Palette.LIGHT))
        write(File(brand, "icon-dark.svg"), tileSvg(BrandMark.Palette.DARK))
        writePng(File(brand, "play-icon-512.png"), storeIcon())

        // --sheet=<path> renders the icon as the launcher will actually mask it,
        // at the sizes it is actually seen. Not checked in and not shipped: it
        // is for the one judgement a 512px render cannot make, which is whether
        // the crown is still a crown at 48px or has turned into a smudge.
        args.firstOrNull { it.startsWith("--sheet=") }
            ?.removePrefix("--sheet=")
            ?.let { writePng(File(it), contactSheet()) }
        println("done.")
    }

    // ---- Android ----------------------------------------------------------

    /**
     * One `<vector>`. Every shape is a `<path>`: VectorDrawable has no `<circle>`
     * and no `<line>`, which is the whole reason [BrandMark.pathData] exists.
     */
    internal fun vectorDrawable(fit: BrandMark.Fit, palette: BrandMark.Palette, monochrome: Boolean): String {
        val body = BrandMark.parts.joinToString("\n") { part ->
            // The themed-icon layer is a silhouette the system tints. Black is
            // the convention; only the coverage matters, not the colour.
            val color = if (monochrome) "#FF000000" else BrandMark.colorOf(part, palette)
            val alpha = BrandMark.alphaOf(part)
            val d = BrandMark.pathData(part, fit)
            if (BrandMark.isFilled(part)) {
                buildString {
                    append("  <path\n")
                    append("      android:pathData=\"$d\"\n")
                    append("      android:fillColor=\"$color\"")
                    if (alpha < 1.0) append("\n      android:fillAlpha=\"${BrandMark.n(alpha)}\"")
                    append(" />")
                }
            } else {
                buildString {
                    append("  <path\n")
                    append("      android:pathData=\"$d\"\n")
                    append("      android:strokeColor=\"$color\"\n")
                    append("      android:strokeWidth=\"${BrandMark.n(BrandMark.strokeWidth(part, fit))}\"\n")
                    append("      android:strokeLineCap=\"round\"\n")
                    append("      android:strokeLineJoin=\"round\"")
                    if (alpha < 1.0) append("\n      android:strokeAlpha=\"${BrandMark.n(alpha)}\"")
                    append(" />")
                }
            }
        }
        val side = BrandMark.n(ADAPTIVE_CANVAS)
        return """
            |<?xml version="1.0" encoding="utf-8"?>
            |<!-- Generated by ./gradlew :workbench:brand. Edit BrandMark.kt; this file is overwritten. -->
            |<vector xmlns:android="http://schemas.android.com/apk/res/android"
            |    android:width="${side}dp"
            |    android:height="${side}dp"
            |    android:viewportWidth="$side"
            |    android:viewportHeight="$side">
            |$body
            |</vector>
            |""".trimMargin()
    }

    private fun backgroundColor(): String = """
        |<?xml version="1.0" encoding="utf-8"?>
        |<!-- Generated by ./gradlew :workbench:brand. Edit BrandMark.kt; this file is overwritten. -->
        |<resources>
        |  <color name="ic_launcher_background">${BrandMark.Palette.LIGHT.ground}</color>
        |</resources>
        |""".trimMargin()

    internal fun adaptiveIcon(): String = """
        |<?xml version="1.0" encoding="utf-8"?>
        |<!-- Generated by ./gradlew :workbench:brand. Edit BrandMark.kt; this file is overwritten. -->
        |<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
        |  <background android:drawable="@color/ic_launcher_background" />
        |  <foreground android:drawable="@drawable/ic_launcher_foreground" />
        |  <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
        |</adaptive-icon>
        |""".trimMargin()

    // ---- SVG, for the docs site and the README ----------------------------

    /**
     * The standalone tile, at the size and position the mark was designed at --
     * rounded corners of its own, because nothing masks an SVG on a web page.
     */
    internal fun tileSvg(palette: BrandMark.Palette): String {
        val g = BrandMark.n(BrandMark.GRID)
        val body = BrandMark.parts.joinToString("\n") { part ->
            val d = BrandMark.pathData(part, BrandMark.NATIVE)
            val color = BrandMark.colorOf(part, palette)
            val alpha = BrandMark.alphaOf(part)
            val a = if (alpha < 1.0) """ opacity="${BrandMark.n(alpha)}"""" else ""
            if (BrandMark.isFilled(part)) {
                """  <path d="$d" fill="$color"$a />"""
            } else {
                """  <path d="$d" fill="none" stroke="$color" """ +
                    """stroke-width="${BrandMark.n(BrandMark.strokeWidth(part, BrandMark.NATIVE))}" """ +
                    """stroke-linecap="round" stroke-linejoin="round"$a />"""
            }
        }
        return """
            |<?xml version="1.0" encoding="utf-8"?>
            |<!-- Generated by ./gradlew :workbench:brand. Edit BrandMark.kt; this file is overwritten. -->
            |<svg xmlns="http://www.w3.org/2000/svg" width="$g" height="$g" viewBox="0 0 $g $g"
            |     role="img" aria-label="BFG Watch Faces">
            |  <rect width="$g" height="$g" rx="${BrandMark.n(BrandMark.TILE_RADIUS)}" fill="${palette.ground}" />
            |$body
            |</svg>
            |""".trimMargin()
    }

    // ---- Play store icon --------------------------------------------------

    internal fun storeIcon(): BufferedImage {
        // TYPE_INT_RGB, not ARGB: Play rejects a store icon with an alpha
        // channel, and a transparent one would show black behind the corners.
        val img = BufferedImage(STORE_PX, STORE_PX, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        quality(g)
        g.color = Color.decode(BrandMark.Palette.LIGHT.ground)
        g.fillRect(0, 0, STORE_PX, STORE_PX)
        draw(g, BrandMark.fitInto(STORE_PX.toDouble(), STORE_PX * STORE_MARK_FRACTION), BrandMark.Palette.LIGHT)
        g.dispose()
        return img
    }

    /** The icon under a circular mask, at the sizes a launcher and a settings list use. */
    private fun contactSheet(): BufferedImage {
        val sizes = listOf(192, 96, 72, 48, 36, 24)
        val pad = 16
        val width = sizes.sumOf { it + pad } + pad
        val height = sizes.first() * 2 + pad * 3
        val sheet = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        quality(g)
        g.color = Color.decode("#0D0F12")
        g.fillRect(0, 0, width, height)

        var x = pad
        for (size in sizes) {
            for ((row, palette) in listOf(BrandMark.Palette.LIGHT, BrandMark.Palette.DARK).withIndex()) {
                val y = pad + row * (sizes.first() + pad)
                val tile = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
                val tg = tile.createGraphics()
                quality(tg)
                // Exactly what the launcher does: the 108dp layers, cropped to a
                // circle. Anything the mask eats is eaten here too.
                tg.clip(Ellipse2D.Double(0.0, 0.0, size.toDouble(), size.toDouble()))
                tg.color = Color.decode(palette.ground)
                tg.fillRect(0, 0, size, size)
                // `size` is the VIEWPORT, so the 108dp layer is drawn larger than
                // the tile and its outer 18dp falls off every edge -- which is
                // precisely what the launcher does with it.
                val fit = BrandMark.fitInto(ADAPTIVE_CANVAS, ADAPTIVE_KEYLINE)
                tg.scale(size / ADAPTIVE_VIEWPORT, size / ADAPTIVE_VIEWPORT)
                tg.translate(-(ADAPTIVE_CANVAS - ADAPTIVE_VIEWPORT) / 2, -(ADAPTIVE_CANVAS - ADAPTIVE_VIEWPORT) / 2)
                draw(tg, fit, palette)
                tg.dispose()
                g.drawImage(tile, x, y, null)
            }
            x += size + pad
        }
        g.dispose()
        return sheet
    }

    // ---- AWT executor -----------------------------------------------------

    private fun quality(g: Graphics2D) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    }

    private fun draw(g: Graphics2D, fit: BrandMark.Fit, palette: BrandMark.Palette) {
        for (part in BrandMark.parts) {
            g.color = ink(BrandMark.colorOf(part, palette), BrandMark.alphaOf(part))
            val w = BrandMark.strokeWidth(part, fit).toFloat()
            if (!BrandMark.isFilled(part)) {
                g.stroke = BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            }
            when (part) {
                is BrandMark.Part.Arc -> {
                    val r = fit.len(part.radius)
                    // Arc2D counts degrees anticlockwise from three o'clock; the
                    // mark counts them clockwise from twelve. phi = 90 - theta.
                    g.draw(
                        Arc2D.Double(
                            fit.x(BrandMark.CX) - r, fit.y(BrandMark.CY) - r, r * 2, r * 2,
                            90.0 - part.fromDeg, -BrandMark.sweepOf(part.fromDeg, part.toDeg), Arc2D.OPEN
                        )
                    )
                }

                is BrandMark.Part.Line ->
                    g.draw(Line2D.Double(fit.x(part.x1), fit.y(part.y1), fit.x(part.x2), fit.y(part.y2)))

                is BrandMark.Part.Dot -> {
                    val r = fit.len(part.radius)
                    g.fill(Ellipse2D.Double(fit.x(part.cx) - r, fit.y(part.cy) - r, r * 2, r * 2))
                }
            }
        }
    }

    private fun ink(hex: String, alpha: Double): Color {
        val c = Color.decode(hex)
        return if (alpha >= 1.0) c else Color(c.red, c.green, c.blue, (alpha * 255).roundToInt())
    }

    // ---- files ------------------------------------------------------------

    private fun write(file: File, text: String) {
        file.parentFile.mkdirs()
        file.writeText(text)
        println("  ${file.path.removePrefix(findRoot().path + "/")}")
    }

    private fun writePng(file: File, image: BufferedImage) {
        file.parentFile.mkdirs()
        ImageIO.write(image, "png", file)
        println("  ${file.path.removePrefix(findRoot().path + "/")} (${image.width}x${image.height})")
    }

    private fun findRoot(): File {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null) {
            if (File(d, "settings.gradle.kts").isFile) return d
            d = d.parentFile
        }
        return File(System.getProperty("user.dir")).absoluteFile
    }
}
