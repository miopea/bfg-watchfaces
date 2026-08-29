package com.bfg.watchfaces.mobile

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.bfg.watchfaces.generator.ComplicationGlyphs
import com.bfg.watchfaces.generator.ComplicationSource

/**
 * Draws complication glyphs on Android, and decides nothing.
 *
 * The shapes come from [ComplicationGlyphs] in `:generator`; this is the Canvas
 * executor, the twin of the workbench's AWT one. If an icon looks different in
 * the two previews the bug is a drawing call in one of these files, not a
 * judgement, because neither holds any.
 */
object AndroidComplicationIcons {

    fun draw(
        canvas: Canvas,
        source: ComplicationSource,
        x: Float, y: Float, size: Float,
        color: Int
    ) {
        if (!source.enabled) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = ComplicationGlyphs.STROKE_WIDTH.toFloat()
        }
        val saved = canvas.save()
        canvas.translate(x, y)
        val k = size / ComplicationGlyphs.GRID.toFloat()
        canvas.scale(k, k)
        for (shape in ComplicationGlyphs.shapes(source)) render(canvas, shape, paint)
        canvas.restoreToCount(saved)
    }

    private fun render(canvas: Canvas, shape: ComplicationGlyphs.Shape, paint: Paint) {
        paint.style = if (shape.fill) Paint.Style.FILL else Paint.Style.STROKE
        when (shape) {
            is ComplicationGlyphs.Shape.Oval ->
                canvas.drawOval(rect(shape.x, shape.y, shape.w, shape.h), paint)

            is ComplicationGlyphs.Shape.RoundRect ->
                canvas.drawRoundRect(
                    rect(shape.x, shape.y, shape.w, shape.h),
                    shape.rx.toFloat(), shape.ry.toFloat(), paint
                )

            is ComplicationGlyphs.Shape.Line -> {
                paint.style = Paint.Style.STROKE
                canvas.drawLine(
                    shape.x1.toFloat(), shape.y1.toFloat(),
                    shape.x2.toFloat(), shape.y2.toFloat(), paint
                )
            }

            // The glyphs carry AWT angles: counter-clockwise from 3 o'clock.
            // Canvas.drawArc measures clockwise, so both are negated. Getting
            // this wrong draws the sunrise arc as a sunset.
            is ComplicationGlyphs.Shape.Arc -> {
                paint.style = Paint.Style.STROKE
                canvas.drawArc(
                    rect(shape.x, shape.y, shape.w, shape.h),
                    (-shape.startDeg).toFloat(), (-shape.extentDeg).toFloat(),
                    false, paint
                )
            }

            is ComplicationGlyphs.Shape.Curve -> {
                val path = Path()
                path.moveTo(shape.start.x.toFloat(), shape.start.y.toFloat())
                for (s in shape.segments) {
                    path.cubicTo(
                        s.c1.x.toFloat(), s.c1.y.toFloat(),
                        s.c2.x.toFloat(), s.c2.y.toFloat(),
                        s.to.x.toFloat(), s.to.y.toFloat()
                    )
                }
                if (shape.closed) path.close()
                canvas.drawPath(path, paint)
            }

            is ComplicationGlyphs.Shape.Rotated -> {
                val saved = canvas.save()
                canvas.translate(shape.cx.toFloat(), shape.cy.toFloat())
                canvas.rotate(Math.toDegrees(shape.radians).toFloat())
                for (inner in shape.of) render(canvas, inner, paint)
                canvas.restoreToCount(saved)
            }
        }
    }

    private fun rect(x: Double, y: Double, w: Double, h: Double) =
        RectF(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat())
}
