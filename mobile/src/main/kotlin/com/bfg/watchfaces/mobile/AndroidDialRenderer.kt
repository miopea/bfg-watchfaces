package com.bfg.watchfaces.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import com.bfg.watchfaces.generator.DIAL_CENTER
import com.bfg.watchfaces.generator.DIAL_RADIUS
import com.bfg.watchfaces.generator.DIAL_SIZE
import com.bfg.watchfaces.generator.DialParams
import com.bfg.watchfaces.generator.DialShading
import com.bfg.watchfaces.generator.EngravedStroke
import com.bfg.watchfaces.generator.PatternEngines
import com.bfg.watchfaces.generator.Polyline

/**
 * The dial, drawn on Android.
 *
 * ## This is the second rasterizer, and it is meant to be boring
 *
 * `DECISIONS.md` 2026-08-27 recorded the risk in having two: they "start
 * identical and drift". The answer, per 2026-08-28, was not to share a canvas
 * abstraction — that is a large surface to maintain and most of it is platform
 * detail nobody would notice diverging. It was to move every DECISION into
 * `:generator`, tested on the JVM, and leave each platform only the mechanical
 * act of drawing.
 *
 * So this file contains no colour arithmetic, no offsets, no gradient stops and
 * no stroke widths. [EngravedStroke] decides what the three passes are;
 * [DialShading] decides the sheen and the vignette; [PatternEngines] decides the
 * geometry. If a dial looks different here than in the workbench, the bug is a
 * drawing call in this file, not a judgement — because there are no judgements
 * left in it.
 *
 * ## What is NOT here yet
 *
 * Imported images and the generated surfaces (`GRAIN`, `BRUSHED`, `CARBON`,
 * `LINEN`) fall back to a plain dial. `TextureField` is pure and already in
 * `:generator`, so the field itself ports directly; what has to be written is
 * the per-pixel shading loop, which is a real piece of work and deserves its own
 * pass rather than being rushed in alongside the first draw.
 *
 * The lens is also absent, and deliberately: `DECISIONS.md` 2026-08-27 records
 * that it is a preview-only effect which never reaches the shipped WFF, so
 * copying it here would make the on-device preview differ from the installed
 * face in exactly the direction that matters.
 *
 * ## Never run
 *
 * Compiled, never executed: there is no emulator on the build machine and Watch
 * Face Push needs Wear OS 6 hardware. Every claim about how this LOOKS is
 * inherited from the workbench renderer and the shared descriptions, not
 * observed.
 */
object AndroidDialRenderer {

    /**
     * Rasterize a dial.
     *
     * [size] is the output size; geometry is always authored in 456x456 dial
     * space and scaled here, so a larger render is the same image rather than a
     * different one — the same contract the workbench renderer keeps.
     */
    fun render(p: DialParams, size: Int = DIAL_SIZE): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val scale = size.toFloat() / DIAL_SIZE
        canvas.scale(scale, scale)

        // The watch is round. Clipping here means square corners are never
        // drawn, never compressed and never sent over Bluetooth.
        val circle = Path().apply {
            addCircle(DIAL_CENTER.toFloat(), DIAL_CENTER.toFloat(), DIAL_RADIUS.toFloat(), Path.Direction.CW)
        }
        canvas.clipPath(circle)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = EngravedStroke.withAlpha(EngravedStroke.rgb(p.dialColor), 255)
        }
        canvas.drawPaint(fill)

        drawSheen(canvas, p)
        drawPattern(canvas, p)
        drawVignette(canvas, p)
        return bitmap
    }

    /**
     * The engraved look: three passes per polyline, exactly as
     * [EngravedStroke] describes them.
     *
     * The paths are built once and re-stroked, rather than rebuilt per pass. At
     * the scales the engines produce that is the difference between one pass
     * over the geometry and three.
     */
    private fun drawPattern(canvas: Canvas, p: DialParams) {
        val paths = PatternEngines.paths(p).map { toPath(it) }
        if (paths.isEmpty()) return

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        for (pass in EngravedStroke.passes(p)) {
            paint.color = pass.argb
            paint.strokeWidth = pass.width.toFloat()
            canvas.save()
            canvas.translate(pass.dx.toFloat(), pass.dy.toFloat())
            for (path in paths) canvas.drawPath(path, paint)
            canvas.restore()
        }
    }

    private fun drawSheen(canvas: Canvas, p: DialParams) {
        val spec = DialShading.sheen(p) ?: return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                spec.fromX.toFloat(), spec.fromY.toFloat(),
                spec.toX.toFloat(), spec.toY.toFloat(),
                spec.stops.map { it.argb }.toIntArray(),
                spec.stops.map { it.at }.toFloatArray(),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawPaint(paint)
    }

    private fun drawVignette(canvas: Canvas, p: DialParams) {
        val spec = DialShading.vignette(p) ?: return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                spec.centerX.toFloat(), spec.centerY.toFloat(), spec.radius.toFloat(),
                spec.stops.map { it.argb }.toIntArray(),
                spec.stops.map { it.at }.toFloatArray(),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawPaint(paint)
    }

    private fun toPath(polyline: Polyline): Path {
        val path = Path()
        if (polyline.isEmpty()) return path
        path.moveTo(polyline[0].x.toFloat(), polyline[0].y.toFloat())
        for (i in 1 until polyline.size) path.lineTo(polyline[i].x.toFloat(), polyline[i].y.toFloat())
        return path
    }
}
