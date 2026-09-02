package com.bfg.watchfaces.workbench

import com.bfg.watchfaces.generator.DIAL_SIZE
import com.bfg.watchfaces.generator.AmbientPalette
import com.bfg.watchfaces.generator.ComplicationSource
import com.bfg.watchfaces.generator.ClockText
import com.bfg.watchfaces.generator.DateStyle
import com.bfg.watchfaces.generator.DialParams
import com.bfg.watchfaces.generator.SecondsBand
import com.bfg.watchfaces.generator.SlotGeometry
import com.bfg.watchfaces.generator.StepRing
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
import com.bfg.watchfaces.appcore.Complications

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

    /** Both kept identical to WffEmitter's, so the preview and the face agree. */

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
        // Sizes are PER SLOT and computed inside the loop below, matching the
        // emitter: the top slot can be smaller than the row when a drawn date
        // is in its way.

        // From v3 a complication carries an ambient colour Variant when the ink
        // would be unreadable on black. Mirror it, or the preview would show a
        // legible top slot that the watch renders as an invisible smudge.
        val liftAmbientInk = p.generatorVersion >= 3 &&
            AmbientPalette.contrastOnBlack(p.inkColor) < 4.5
        val ambientSlotInk =
            if (liftAmbientInk) DialRenderer.hex(AmbientPalette.forAmbient(p.inkColor)) else ink

        for ((pos, box) in SlotGeometry.boxes(p)) {
            val fitted = SlotGeometry.sizeAt(p, pos)
            val iconSize = SlotGeometry.iconHeight(fitted, p.generatorVersion).toDouble()
            val textH = SlotGeometry.textHeight(fitted, p.generatorVersion)
            val fontSize = SlotGeometry.fontSize(fitted).toDouble()
            val source = p.slot(pos)
            val a = if (ambient) (if (pos == SlotPosition.TOP) 140 else 0) else 255
            if (a <= 0) continue
            val c = withAlpha(if (ambient) ambientSlotInk else ink, a)
            // Honours iconSlots, or the toggles appear to do nothing in the one
            // view somebody uses to judge them.
            if (p.hasIcon(pos)) {
                ComplicationIcons.draw(g, source, box.x + (box.w - iconSize) / 2.0, box.y.toDouble(), iconSize, c)
            }
            val textY = SlotGeometry.textOffset(fitted, pos in p.iconSlots, p.generatorVersion)
            // The emitter asks SlotGeometry which wording fits and at what
            // size; so does this. A preview that shortened differently from
            // the watch would be a preview of a different face.
            val drawn = SlotGeometry.drawnText(source, box, fontSize.toInt(), p.generatorVersion, pos)
            drawCenteredIn(g, drawn.sample ?: Complications.sample(source),
                box.x, box.y + textY, box.w, textH,
                drawn.fontSize.toDouble(), Font.PLAIN, c)
        }

        // The date the FACE draws, matching WffEmitter's PartText: centred at
        // dateY, dimmed in ambient rather than hidden. Without this the Date
        // control changes nothing in the only view anyone judges it in.
        SlotGeometry.dateBand(p)?.let { band ->
            val dateInk = if (ambient) withAlpha(ambientSlotInk, 140) else ink
            // The render's OWN date, so the drawn date agrees with the clock
            // beside it. Defaulting to today made this preview non-deterministic
            // and put the build date into every baked preview.png.
            drawCentered(g, p.dateStyle.sample(now.toLocalDate()), band.y, band.h,
                SlotGeometry.fittedDateSize(p).toDouble(), Font.PLAIN, dateInk)
        }

        // The step ring, matching WffEmitter: a faint full circle with a
        // bright arc over it. Hidden in ambient, like the emitted one.
        if (p.ring.enabled && !ambient) {
            val b = StepRing.box()
            val sweep = StepRing.sweepDegrees(StepRing.SAMPLE_PERCENT)
            g.stroke = java.awt.BasicStroke(
                StepRing.THICKNESS.toFloat(),
                java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND
            )
            g.color = withAlpha(ink, StepRing.TRACK_ALPHA)
            g.drawArc(b.x, b.y, b.w, b.h, 0, 360)
            g.color = ink
            // AWT measures counter-clockwise from 3 o'clock; the ring fills
            // clockwise from 12, which is 90 minus the sweep.
            g.drawArc(b.x, b.y, b.w, b.h, 90, -sweep.toInt())
        }

        // Time: the emitter ships TWO TimeText elements, one interactive
        // (alpha 255 -> ambient 0) and one ambient-only (alpha 0 -> ambient 255,
        // THIN weight, dimmed ink). Reproduce that split rather than dimming one.
        val hh = now.hour % 12
        // Awake only, matching the emitter and the Android preview.
        val timeText = ClockText.of(p, now.hour, now.minute)
        if (ambient) {
            // Mirror the emitter's version branch exactly. From v3 the ambient
            // ink clears a contrast floor against black; before that it is the
            // raw ink at alpha 160, dark or not.
            val ambientInk =
                if (p.generatorVersion >= 3) DialRenderer.hex(AmbientPalette.forAmbient(p.inkColor))
                else withAlpha(ink, 160)
            drawCentered(g, timeText, l.timeY - l.timeSize / 2, (l.timeSize * 1.4).toInt(),
                l.timeSize.toDouble(), Font.PLAIN, ambientInk, thin = true)
        } else {
            // Same size with or without seconds: turning them on must not
            // resize the face. See SecondsBand.
            val clockSize = l.timeSize.toDouble()
            drawCentered(g, timeText, l.timeY - l.timeSize / 2, (l.timeSize * 1.4).toInt(),
                clockSize, awtStyle(l.fontWeight), ink)
        }

        // Seconds in the right gutter, matching WffEmitter: just under half the
        // clock, lightest weight, awake only. The clock is centred, so this uses
        // the empty dial it leaves rather than widening the time itself.
        if (p.showSeconds && !ambient) {
            val secs = "%02d".format(now.second)
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, SecondsBand.fontSizeFor(p))
            g.color = withAlpha(ink, SecondsBand.ALPHA)
            val fm = g.fontMetrics
            // Centred in the clock's own band, so the seconds sit ON the time's
            // line rather than under it.
            val top = SecondsBand.topInDial(l)
            val baseline = top + (SecondsBand.height(l) + fm.ascent - fm.descent) / 2
            // Left-anchored when a ring crowds them, matching the emitter: an
            // end-aligned run hangs its left edge off a width ESTIMATE, and
            // that estimate being wrong is what pushed the seconds back toward
            // the ring.
            g.drawString(
                secs,
                if (SecondsBand.alignFor(p) == "START") SecondsBand.leftEdgeFor(p, timeText.length)
                else SecondsBand.rightEdgeFor(p) - fm.stringWidth(secs),
                baseline
            )
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
