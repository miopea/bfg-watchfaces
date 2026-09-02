package com.bfg.watchfaces.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.bfg.watchfaces.appcore.Complications
import com.bfg.watchfaces.generator.AmbientPalette
import com.bfg.watchfaces.generator.DIAL_SIZE
import com.bfg.watchfaces.generator.ClockText
import com.bfg.watchfaces.generator.DateStyle
import com.bfg.watchfaces.generator.ClockMode
import com.bfg.watchfaces.generator.Glare
import com.bfg.watchfaces.generator.Hands
import com.bfg.watchfaces.generator.DialParams
import com.bfg.watchfaces.generator.EngravedStroke
import com.bfg.watchfaces.generator.SecondsBand
import com.bfg.watchfaces.generator.SlotGeometry
import com.bfg.watchfaces.generator.StepRing
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
    /** Rotate a full-canvas hand about the dial centre, as WFF does with pivot 0.5. */
    private fun drawRotated(canvas: android.graphics.Canvas, bmp: Bitmap, degrees: Double) {
        canvas.save()
        canvas.rotate(degrees.toFloat(), DIAL_SIZE / 2f, DIAL_SIZE / 2f)
        canvas.drawBitmap(bmp, 0f, 0f, null)
        canvas.restore()
        bmp.recycle()
    }

    private const val SAMPLE_HOUR = 10
    private const val SAMPLE_MINUTE = 10
    private const val SAMPLE_SECOND = 30

    /** Kept the same as WffEmitter's, so the preview and the face agree. */

    fun render(
        p: DialParams,
        ambient: Boolean = false,
        size: Int = DIAL_SIZE,
        /**
         * The imported image, when the face uses one.
         *
         * Passed in rather than resolved here, because this object has no
         * Context and every caller already has one. `Textures.forFace` is the
         * one place that decides.
         */
        texture: Bitmap? = null
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Scene backgroundColor is #ff000000 in the emitted WFF.
        canvas.drawColor(Color.BLACK)

        // Dial image: alpha 255 interactive, Variant AMBIENT alpha 0.
        if (!ambient) {
            val dial = AndroidDialRenderer.render(p, size, texture)
            canvas.drawBitmap(dial, 0f, 0f, null)
            dial.recycle()
        }

        val s = size.toFloat() / DIAL_SIZE
        canvas.scale(s, s)

        val l = p.layout
        val ink = EngravedStroke.withAlpha(EngravedStroke.rgb(p.inkColor), 255)

        // Sizes are PER SLOT and computed inside the loop below, matching the
        // emitter: the top slot can be smaller than the row when a drawn date
        // is in its way.

        // From v3 a complication carries an ambient colour Variant when the ink
        // would be unreadable on black. Mirror it, or the preview would show a
        // legible top slot that the watch renders as an invisible smudge.
        val liftAmbientInk = p.generatorVersion >= 3 &&
            AmbientPalette.contrastOnBlack(p.inkColor) < 4.5
        val ambientSlotInk =
            if (liftAmbientInk) EngravedStroke.withAlpha(EngravedStroke.rgb(AmbientPalette.forAmbient(p.inkColor)), 255)
            else ink

        for ((pos, box) in SlotGeometry.boxes(p)) {
            val fitted = SlotGeometry.sizeAt(p, pos)
            val iconSize = SlotGeometry.iconHeight(fitted, p.generatorVersion).toFloat()
            val textH = SlotGeometry.textHeight(fitted, p.generatorVersion)
            val fontSize = SlotGeometry.fontSize(fitted).toFloat()
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
            // Which wording and what size both come from SlotGeometry, the
            // same call the emitter makes. It shortens before it shrinks.
            val drawn = SlotGeometry.drawnText(source, box, fontSize.toInt(), p.generatorVersion, pos)
            drawCenteredIn(
                canvas, drawn.sample ?: Complications.sample(source),
                box.x.toFloat(),
                (box.y + SlotGeometry.textOffset(fitted, pos in p.iconSlots, p.generatorVersion)).toFloat(),
                box.w.toFloat(), textH.toFloat(),
                drawn.fontSize.toFloat(), c, bold = false
            )
        }

        // Time: the emitter ships TWO TimeText elements, one interactive
        // (alpha 255 -> ambient 0) and one ambient-only (alpha 0 -> ambient 255,
        // THIN weight, dimmed ink). Reproduce that split rather than dimming one.
        val timeText = ClockText.of(p, SAMPLE_HOUR, SAMPLE_MINUTE)
        val hh = SAMPLE_HOUR % 12
        // Seconds are an AWAKE-only affordance, so the ambient preview must not
        // show them -- otherwise the toggle appears to do nothing in the one
        // view where it is deliberately absent.
        // The date the FACE draws, matching WffEmitter's PartText: centred at
        // dateY, dimmed in ambient rather than hidden. Without this the Date
        // control changes nothing in the only view anyone judges it in.
        SlotGeometry.dateBand(p)?.let { band ->
            drawCenteredIn(
                // The same fixed day the workbench preview uses, so the two
                // agree and neither draws today's date beside a 10:10 clock.
                canvas, p.dateStyle.sample(DateStyle.SAMPLE_DATE),
                band.x.toFloat(), band.y.toFloat(),
                band.w.toFloat(), band.h.toFloat(),
                SlotGeometry.fittedDateSize(p).toFloat(),
                if (ambient) withAlpha(ambientSlotInk, 140) else ink,
                bold = false
            )
        }

        if (p.ring.enabled && !ambient) {
            val b = StepRing.box()
            val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = StepRing.THICKNESS.toFloat()
                strokeCap = Paint.Cap.ROUND
            }
            val oval = android.graphics.RectF(
                b.x.toFloat(), b.y.toFloat(), (b.x + b.w).toFloat(), (b.y + b.h).toFloat()
            )
            ringPaint.color = withAlpha(ink, StepRing.TRACK_ALPHA)
            canvas.drawArc(oval, 0f, 360f, false, ringPaint)
            ringPaint.color = ink
            // Android measures clockwise from 3 o'clock, so 12 is -90.
            canvas.drawArc(
                oval, -90f,
                StepRing.sweepDegrees(StepRing.SAMPLE_PERCENT).toFloat(), false, ringPaint
            )
        }

        // HANDS INSTEAD OF NUMERALS — and this is the preview Studio shows.
        //
        // The workbench preview learned this first and this one did not, so
        // choosing Hands changed the face and not the picture of it. Exactly
        // the split that bit `drawIndices`: two executions of one definition,
        // and only one of them updated.
        val analog = p.clockMode == ClockMode.ANALOG
        if (analog) {
            AndroidDialRenderer.drawIndices(canvas, p, p.handStyle)
            val hourDeg = (hh + SAMPLE_MINUTE / 60.0 + SAMPLE_SECOND / 3600.0) / 12.0 * 360.0
            val minuteDeg = (SAMPLE_MINUTE + SAMPLE_SECOND / 60.0) / 60.0 * 360.0
            drawRotated(canvas, AndroidDialRenderer.renderHand(p, p.handStyle, Hands.Hand.HOUR, DIAL_SIZE, p.inkColor, ambient), hourDeg)
            drawRotated(canvas, AndroidDialRenderer.renderHand(p, p.handStyle, Hands.Hand.MINUTE, DIAL_SIZE, p.inkColor, ambient), minuteDeg)
            if (p.showSeconds && !ambient && !analog) {
                drawRotated(
                    canvas,
                    AndroidDialRenderer.renderHand(
                        p, p.handStyle, Hands.Hand.SECOND, DIAL_SIZE,
                        p.secondHandColor ?: p.inkColor
                    ),
                    SAMPLE_SECOND / 60.0 * 360.0
                )
            }
            drawRotated(canvas, AndroidDialRenderer.renderHub(p, p.handStyle, DIAL_SIZE, ambient), 0.0)
            // The same box the emitter and the workbench preview use.
            SlotGeometry.analogDigitalBand(p)?.takeIf { !ambient }?.let { band ->
                drawCenteredIn(
                    canvas, timeText,
                    band.x.toFloat(), band.y.toFloat(),
                    band.w.toFloat(), band.h.toFloat(),
                    SlotGeometry.analogDigitalSize(p).toFloat(),
                    ink,
                    bold = l.fontWeight.uppercase() == "BOLD"
                )
            }
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
        // ENGRAVED from v13, the mirror of the workbench preview. Static here:
        // a preview cannot tilt, and the resting position is what the watch
        // shows lying on a table.
        if (!analog && p.generatorVersion >= 13 && !ambient) {
            for (pass in EngravedStroke.textPasses(p, l.timeSize).take(2)) {
                drawCenteredIn(
                    canvas, timeText,
                    pass.dx.toFloat(), (l.timeY - l.timeSize / 2 + pass.dy).toFloat(),
                    DIAL_SIZE.toFloat(), (l.timeSize * 1.4).toFloat(),
                    l.timeSize.toFloat(),
                    pass.argb,
                    bold = l.fontWeight.uppercase() == "BOLD"
                )
            }
        }
        if (!analog) drawCenteredIn(
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
        // clock, lightest weight, awake only.
        //
        // The BOX and the alignment both come from SecondsBand, because with a
        // ring drawn they are anchored to the clock rather than to the rim. An
        // end-aligned run hangs its left edge off a width estimate, and that is
        // what pushed the seconds back toward the ring after a change meant to
        // move them away from it.
        if (p.showSeconds && !ambient && !analog) {
            val startAligned = SecondsBand.alignFor(p) == "START"
            drawCenteredIn(
                canvas, "%02d".format(SAMPLE_SECOND),
                SecondsBand.boxLeftFor(p, timeText.length).toFloat(), SecondsBand.topInDial(l).toFloat(),
                SecondsBand.boxWidthFor(p, timeText.length).toFloat(), SecondsBand.height(l).toFloat(),
                SecondsBand.fontSizeFor(p).toFloat(),
                withAlpha(timeColor, SecondsBand.ALPHA), bold = false,
                alignEnd = !startAligned,
                alignStart = startAligned
            )
        }
        // The glare, last and over everything -- the mirror of the workbench
        // preview. Static: a preview cannot tilt.
        if (Glare.enabledFor(p) && !ambient) {
            val m = Glare.TRAVEL.toInt()
            val glare = AndroidDialRenderer.renderGlare(p, DIAL_SIZE)
            canvas.drawBitmap(glare, -m.toFloat(), -m.toFloat(), null)
            glare.recycle()
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
        alignEnd: Boolean = false,
        /** Left-align instead. The seconds use this when a ring crowds them. */
        alignStart: Boolean = false
    ) {
        if (text.isEmpty()) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
        val fm = paint.fontMetrics
        val tx = when {
            alignStart -> x
            alignEnd -> x + w - paint.measureText(text)
            else -> x + (w - paint.measureText(text)) / 2f
        }
        // WFF centres text vertically within the element box.
        val ty = y + (h - (fm.descent - fm.ascent)) / 2f - fm.ascent
        canvas.drawText(text, tx, ty, paint)
    }
}
