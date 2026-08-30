package com.bfg.watchfaces.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.bfg.watchfaces.generator.AmbientPalette
import com.bfg.watchfaces.generator.DIAL_SIZE
import com.bfg.watchfaces.generator.DateStyle
import com.bfg.watchfaces.generator.DialParams
import com.bfg.watchfaces.generator.EngravedStroke
import com.bfg.watchfaces.generator.SecondsBand
import com.bfg.watchfaces.generator.SlotGeometry
import com.bfg.watchfaces.generator.SlotPosition

/**
 * Composites the dial with the text layers, so a whole face can be judged on
 * the phone instead of on a wrist.
 *
 * The Android twin of the workbench's `FacePreview`, and it keeps the same
 * honesty boundary:
 *
 *  - The DIAL is exact. It is [AndroidDialRenderer] output, which shares every
 *    decision with the workbench renderer through `:generator`.
 *  - The TEXT is an approximation. On the watch it is drawn by the WFF runtime
 *    using the device font (`SYNC_TO_DEVICE`). Here it is a local sans.
 *    Positions and sizes mirror the emitter's arithmetic exactly — the slot
 *    boxes come from [SlotGeometry], the same call `WffEmitter` makes — so
 *    layout is trustworthy; glyph shapes are not pixel-identical.
 *
 * Use it to judge composition, contrast and legibility. Do not use it to sign
 * off on kerning.
 */
object AndroidFacePreview {

    /** A fixed time, so a preview does not change under the person mid-edit. */
    private const val SAMPLE_HOUR = 10
    private const val SAMPLE_MINUTE = 10
    private const val SAMPLE_SECOND = 30

    /** Kept the same as WffEmitter's, so the preview and the face agree. */

    fun render(p: DialParams, ambient: Boolean = false, size: Int = DIAL_SIZE): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Scene backgroundColor is #ff000000 in the emitted WFF.
        canvas.drawColor(Color.BLACK)

        // Dial image: alpha 255 interactive, Variant AMBIENT alpha 0.
        if (!ambient) {
            val dial = AndroidDialRenderer.render(p, size)
            canvas.drawBitmap(dial, 0f, 0f, null)
            dial.recycle()
        }

        val s = size.toFloat() / DIAL_SIZE
        canvas.scale(s, s)

        val l = p.layout
        val ink = EngravedStroke.withAlpha(EngravedStroke.rgb(p.inkColor), 255)

        // The FITTED size, matching the emitter: the boxes come from it too.
        val fitted = SlotGeometry.fittedSize(p)
        val iconSize = SlotGeometry.iconHeight(fitted, p.generatorVersion).toFloat()
        val textH = SlotGeometry.textHeight(fitted, p.generatorVersion)
        val fontSize = SlotGeometry.fontSize(fitted).toFloat()

        // From v3 a complication carries an ambient colour Variant when the ink
        // would be unreadable on black. Mirror it, or the preview would show a
        // legible top slot that the watch renders as an invisible smudge.
        val liftAmbientInk = p.generatorVersion >= 3 &&
            AmbientPalette.contrastOnBlack(p.inkColor) < 4.5
        val ambientSlotInk =
            if (liftAmbientInk) EngravedStroke.withAlpha(EngravedStroke.rgb(AmbientPalette.forAmbient(p.inkColor)), 255)
            else ink

        for ((pos, box) in SlotGeometry.boxes(p)) {
            val source = p.slot(pos)
            val a = if (ambient) (if (pos == SlotPosition.TOP) 140 else 0) else 255
            if (a <= 0) continue
            val c = withAlpha(if (ambient) ambientSlotInk else ink, a)
            // Honours iconSlots, or the toggles appear to do nothing in the one
            // view somebody uses to judge them.
            if (p.hasIcon(pos)) {
                AndroidComplicationIcons.draw(
                    canvas, source,
                    x = box.x + (box.w - iconSize) / 2f,
                    y = box.y.toFloat(),
                    size = iconSize,
                    color = c
                )
            }
            drawCenteredIn(
                canvas, Presentation.sample(source),
                box.x.toFloat(),
                (box.y + SlotGeometry.textOffset(fitted, pos in p.iconSlots, p.generatorVersion)).toFloat(),
                box.w.toFloat(), textH.toFloat(), fontSize, c, bold = false
            )
        }

        // Time: the emitter ships TWO TimeText elements, one interactive
        // (alpha 255 -> ambient 0) and one ambient-only (alpha 0 -> ambient 255,
        // THIN weight, dimmed ink). Reproduce that split rather than dimming one.
        val hh = SAMPLE_HOUR % 12
        // Seconds are an AWAKE-only affordance, so the ambient preview must not
        // show them -- otherwise the toggle appears to do nothing in the one
        // view where it is deliberately absent.
        val timeText = "%02d:%02d".format(if (hh == 0) 12 else hh, SAMPLE_MINUTE)
        // The date the FACE draws, matching WffEmitter's PartText: centred at
        // dateY, dimmed in ambient rather than hidden. Without this the Date
        // control changes nothing in the only view anyone judges it in.
        SlotGeometry.dateBand(p)?.let { band ->
            drawCenteredIn(
                canvas, p.dateStyle.sample(),
                band.x.toFloat(), band.y.toFloat(),
                band.w.toFloat(), band.h.toFloat(),
                l.dateSize.toFloat(),
                if (ambient) withAlpha(ambientSlotInk, 140) else ink,
                bold = false
            )
        }

        val timeColor = if (ambient) {
            // Mirror the emitter's version branch exactly. From v3 the ambient
            // ink clears a contrast floor against black; before that it is the
            // raw ink at alpha 160, dark or not.
            if (p.generatorVersion >= 3)
                EngravedStroke.withAlpha(EngravedStroke.rgb(AmbientPalette.forAmbient(p.inkColor)), 255)
            else withAlpha(ink, 160)
        } else {
            ink
        }
        drawCenteredIn(
            canvas, timeText,
            0f, (l.timeY - l.timeSize / 2).toFloat(),
            DIAL_SIZE.toFloat(), (l.timeSize * 1.4).toFloat(),
            // Same size with or without seconds; see SecondsBand.
            l.timeSize.toFloat(),
            timeColor,
            // AWT only has PLAIN and BOLD, and the workbench renders MEDIUM as
            // PLAIN rather than overstate the weight. Match that here, so the
            // two previews agree about the one thing that matters most --
            // legibility of the time.
            bold = !ambient && l.fontWeight.uppercase() == "BOLD"
        )

        // Seconds in the right gutter, matching WffEmitter: just under half the
        // clock, lightest weight, awake only. Right-aligned rather than centred,
        // because the clock is centred and this sits in the space it leaves.
        if (p.showSeconds && !ambient) {
            drawCenteredIn(
                canvas, "%02d".format(SAMPLE_SECOND),
                0f, SecondsBand.topInDial(l).toFloat(),
                SecondsBand.rightEdge().toFloat(), SecondsBand.height(l).toFloat(),
                SecondsBand.fontSize(l).toFloat(),
                withAlpha(timeColor, SecondsBand.ALPHA), bold = false,
                alignEnd = true
            )
        }
        return bitmap
    }

    private fun withAlpha(argb: Int, a: Int) =
        (argb and 0x00FFFFFF) or ((a.coerceIn(0, 255)) shl 24)

    private fun drawCenteredIn(
        canvas: Canvas, text: String,
        x: Float, y: Float, w: Float, h: Float,
        size: Float, color: Int, bold: Boolean,
        /** Right-align inside the box instead of centring. Used by the seconds. */
        alignEnd: Boolean = false
    ) {
        if (text.isEmpty()) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
        val fm = paint.fontMetrics
        val tx = if (alignEnd) x + w - paint.measureText(text)
                 else x + (w - paint.measureText(text)) / 2f
        // WFF centres text vertically within the element box.
        val ty = y + (h - (fm.descent - fm.ascent)) / 2f - fm.ascent
        canvas.drawText(text, tx, ty, paint)
    }
}
