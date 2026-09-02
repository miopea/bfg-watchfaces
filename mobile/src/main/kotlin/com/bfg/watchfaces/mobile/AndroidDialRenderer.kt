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
import com.bfg.watchfaces.generator.ClockMode
import com.bfg.watchfaces.generator.HandStyle
import com.bfg.watchfaces.generator.Hands
import com.bfg.watchfaces.generator.EngravedStroke
import com.bfg.watchfaces.generator.PatternEngines
import com.bfg.watchfaces.generator.ProceduralDial
import com.bfg.watchfaces.generator.TextureField
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
 * Imported images. `Engine.TEXTURE` needs a bitmap the face only references by
 * id, so it falls back to a plain dial until there is somewhere on the device to
 * resolve that id from.
 *
 * The generated surfaces used to be missing too. They are not any more: the
 * shading loop moved to [ProceduralDial] in `:generator`, which is what made
 * porting them a blit rather than a second copy of the lighting model.
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

        // Order mirrors the workbench renderer exactly: a generated surface
        // replaces the stroked pattern and draws its own sheen on top, and the
        // vignette goes over whichever happened.
        val procedural = TextureField.kindFor(p.engine)
        if (procedural != null) drawProcedural(canvas, procedural, p, size) else drawSheen(canvas, p)
        if (procedural == null) drawPattern(canvas, p)
        // Indices belong to the DIAL: they do not rotate, so baking them here
        // costs nothing extra over Bluetooth, where a separate PartImage would
        // cost a second full-size PNG for something that never changes.
        if (p.clockMode == ClockMode.ANALOG) drawIndices(canvas, p, p.handStyle)
        drawVignette(canvas, p)
        return bitmap
    }

    /**
     * A generated surface — GRAIN, BRUSHED, CARBON, LINEN.
     *
     * The lighting is [ProceduralDial]'s, computed as raw ARGB in `:generator`;
     * this only turns the array into a bitmap and puts it down. These four
     * styles used to fall back to a plain dial here, because the arithmetic
     * lived in the AWT renderer and copying it would have been a fourth chance
     * to drift.
     */
    private fun drawProcedural(canvas: Canvas, kind: TextureField.Kind, p: DialParams, size: Int) {
        val px = ProceduralDial.pixels(kind, p, EngravedStroke.rgb(p.dialColor), size)
        val surface = Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
        // The pixels are already at render scale, and the canvas is scaled to
        // dial space -- so undo that for the blit and put it back after.
        val saved = canvas.save()
        canvas.scale(DIAL_SIZE.toFloat() / size, DIAL_SIZE.toFloat() / size)
        canvas.drawBitmap(surface, 0f, 0f, null)
        canvas.restoreToCount(saved)
        surface.recycle()
        drawSheen(canvas, p)
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

    /**
     * One hand, alone on a transparent dial-sized bitmap, pointing at twelve.
     *
     * The mirror of `DialRenderer.renderHand` in `:workbench`. Two executions,
     * one definition: the shape comes from [Hands] and the passes from
     * [EngravedStroke], so neither renderer decides anything of its own. That
     * is the same arrangement [PatternEngines] already has, and the reason a
     * preview and a shipped face cannot drift apart.
     *
     * Full canvas so the pivot is 0.5/0.5 for every style, forever.
     */
    fun renderHand(
        p: DialParams,
        style: HandStyle,
        hand: Hands.Hand,
        size: Int = DIAL_SIZE,
        color: String = p.inkColor
    ): Bitmap = renderShapes(p, Hands.shapes(style, hand), size, color)

    /** The hub the hands turn on. Static; it does not rotate with anything. */
    fun renderHub(p: DialParams, style: HandStyle, size: Int = DIAL_SIZE): Bitmap =
        renderShapes(p, listOf(Hands.hub(style)), size, p.inkColor)

    private fun renderShapes(
        p: DialParams,
        shapes: List<Hands.HandShape>,
        size: Int,
        color: String
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val scale = size.toFloat() / DIAL_SIZE
        canvas.scale(scale, scale)
        drawShapes(canvas, p, shapes, color)
        return bitmap
    }

    /** The chapter ring, drawn into the dial rather than as a rotating overlay. */
    fun drawIndices(canvas: Canvas, p: DialParams, style: HandStyle) =
        drawShapes(canvas, p, Hands.indices(style), p.inkColor)

    /**
     * One way to cut a hand, an index or a hub — see the workbench twin.
     *
     * An UNFILLED shape still gets a real ink edge. The engraved passes alone
     * are tuned for a dial pattern, where subtlety is the point; on a bare
     * outline they produce a skeleton hand too faint to read the time from.
     */
    private fun drawShapes(
        canvas: Canvas,
        p: DialParams,
        shapes: List<Hands.HandShape>,
        color: String
    ) {
        val paths = shapes.map { it to toPath(it.outline) }
        val ink = EngravedStroke.withAlpha(EngravedStroke.rgb(color), 255)

        val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.color = ink
        }
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = OUTLINE_WIDTH
            this.color = ink
        }
        for ((shape, path) in paths) {
            canvas.drawPath(path, if (shape.filled) solid else edge)
        }

        val relief = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        for (pass in EngravedStroke.passes(p)) {
            relief.color = pass.argb
            relief.strokeWidth = pass.width.toFloat()
            canvas.save()
            canvas.translate(pass.dx.toFloat(), pass.dy.toFloat())
            for ((_, path) in paths) canvas.drawPath(path, relief)
            canvas.restore()
        }
    }

    /** Matches the workbench renderer; see its note on why an outline needs an edge. */
    private const val OUTLINE_WIDTH = 3.0f

    private fun toPath(polyline: Polyline): Path {
        val path = Path()
        if (polyline.isEmpty()) return path
        path.moveTo(polyline[0].x.toFloat(), polyline[0].y.toFloat())
        for (i in 1 until polyline.size) path.lineTo(polyline[i].x.toFloat(), polyline[i].y.toFloat())
        return path
    }
}
