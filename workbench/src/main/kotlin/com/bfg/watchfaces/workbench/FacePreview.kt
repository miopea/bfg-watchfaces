package com.bfg.watchfaces.workbench

import com.bfg.watchfaces.generator.DIAL_SIZE
import com.bfg.watchfaces.generator.ComplicationSource
import com.bfg.watchfaces.generator.DialParams
import com.bfg.watchfaces.generator.SlotGeometry
import com.bfg.watchfaces.generator.SlotPosition
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.font.TextAttribute
import java.awt.geom.Ellipse2D
import java.awt.image.BufferedImage
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * Composites the dial with the text layers, so the whole face can be judged in a
 * browser instead of on a wrist.
 *
 * IMPORTANT -- the honesty boundary of this class:
 *
 *  - The DIAL is exact. It is [DialRenderer] output, the same bytes that get
 *    baked into dial_bg.png and shipped.
 *  - The TEXT is an approximation. On the watch it is drawn by the WFF runtime
 *    using the device font (`SYNC_TO_DEVICE`). Here it is drawn by AWT with a
 *    local sans. Positions and sizes mirror WffEmitter's arithmetic exactly, so
 *    layout is trustworthy; glyph shapes and metrics are not pixel-identical.
 *
 * Use it to judge composition, contrast, legibility and ambient behaviour. Do
 * not use it to sign off on kerning.
 */
object FacePreview {

    /** Mirrors the emitter's ambient rules so the preview tells the truth about ambient. */
    fun render(
        p: DialParams, ambient: Boolean = false, size: Int = DIAL_SIZE,
        time: LocalDateTime? = null, texture: BufferedImage? = null
    ): BufferedImage {
        val now = time ?: LocalDateTime.of(2026, 3, 10, 10, 10, 0)
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

        // Scene backgroundColor is #ff000000 in the emitted WFF.
        g.color = Color.BLACK
        g.fill(Ellipse2D.Double(0.0, 0.0, size.toDouble(), size.toDouble()))

        val s = size.toDouble() / DIAL_SIZE
        val l = p.layout
        val ink = DialRenderer.hex(p.inkColor)

        // Dial image: alpha 255 interactive, Variant AMBIENT alpha 0.
        if (!ambient) g.drawImage(DialRenderer.render(p, size, texture), 0, 0, null)

        g.scale(s, s)

        // Slot boxes come from SlotGeometry -- the same call WffEmitter makes.
        // Previously both computed this independently and a test asserted they
        // matched, which guarded a copy rather than removing it.
        val iconSize = SlotGeometry.iconHeight(l.complicationSize).toDouble()
        val textY = SlotGeometry.textOffset(l.complicationSize)
        val textH = SlotGeometry.textHeight(l.complicationSize)
        val fontSize = SlotGeometry.fontSize(l.complicationSize).toDouble()

        for ((pos, box) in SlotGeometry.boxes(p)) {
            val source = p.slot(pos)
            val a = if (ambient) (if (pos == SlotPosition.TOP) 140 else 0) else 255
            if (a <= 0) continue
            val c = withAlpha(ink, a)
            ComplicationIcons.draw(g, source, box.x + (box.w - iconSize) / 2.0, box.y.toDouble(), iconSize, c)
            drawCenteredIn(g, Complications.sample(source), box.x, box.y + textY, box.w, textH,
                fontSize, Font.PLAIN, c)
        }

        // Time: the emitter ships TWO TimeText elements, one interactive
        // (alpha 255 -> ambient 0) and one ambient-only (alpha 0 -> ambient 255,
        // THIN weight, dimmed ink). Reproduce that split rather than dimming one.
        val hh = now.hour % 12
        val timeText = "%02d:%02d".format(if (hh == 0) 12 else hh, now.minute)
        if (ambient) {
            drawCentered(g, timeText, l.timeY - l.timeSize / 2, (l.timeSize * 1.4).toInt(),
                l.timeSize.toDouble(), Font.PLAIN, withAlpha(ink, 160), thin = true)
        } else {
            drawCentered(g, timeText, l.timeY - l.timeSize / 2, (l.timeSize * 1.4).toInt(),
                l.timeSize.toDouble(), awtStyle(l.fontWeight), ink)
        }

        g.dispose()
        return img
    }

    private fun withAlpha(c: Color, a: Int) = Color(c.red, c.green, c.blue, a.coerceIn(0, 255))

    /**
     * WFF weight -> AWT style. AWT only has PLAIN and BOLD, so MEDIUM (which on
     * the device sits between the two) renders PLAIN here rather than BOLD --
     * overstating weight would make the preview lie in the direction that
     * matters most, legibility of the time.
     */
    private fun awtStyle(weight: String): Int = if (weight.uppercase() == "BOLD") Font.BOLD else Font.PLAIN

    private fun font(size: Double, style: Int, thin: Boolean, letterSpacing: Double): Font {
        // DejaVu Sans is the closest widely-present stand-in for a Wear device
        // font. SYNC_TO_DEVICE resolves on the watch, never here.
        var f = Font("DejaVu Sans", if (thin) Font.PLAIN else style, size.toInt())
            .deriveFont(size.toFloat())
        if (letterSpacing != 0.0) {
            @Suppress("UNCHECKED_CAST")
            val attrs = HashMap(f.attributes as Map<TextAttribute, Any?>)
            attrs[TextAttribute.TRACKING] = letterSpacing.toFloat()
            f = Font(attrs)
        }
        return f
    }

    private fun drawCentered(
        g: java.awt.Graphics2D, text: String, y: Int, h: Int, size: Double,
        style: Int, color: Color, thin: Boolean = false, letterSpacing: Double = 0.0
    ) = drawCenteredIn(g, text, 0, y, DIAL_SIZE, h, size, style, color, thin, letterSpacing)

    private fun drawCenteredIn(
        g: java.awt.Graphics2D, text: String, x: Int, y: Int, w: Int, h: Int, size: Double,
        style: Int, color: Color, thin: Boolean = false, letterSpacing: Double = 0.0
    ) {
        g.font = font(size, style, thin, letterSpacing)
        g.color = color
        val fm = g.fontMetrics
        val tx = x + (w - fm.stringWidth(text)) / 2.0
        // WFF centres text vertically within the element box.
        val ty = y + (h - fm.height) / 2.0 + fm.ascent
        g.drawString(text, tx.toFloat(), ty.toFloat())
    }
}
