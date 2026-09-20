package com.bfg.watchfaces.workbench

import com.bfg.watchfaces.generator.ComplicationGlyphs
import com.bfg.watchfaces.generator.GlyphVector
import java.io.File

/**
 * Write the watch's glyph drawables from [ComplicationGlyphs].
 *
 * Same contract as [Brand]: generated here, CHECKED IN, and the Android build
 * never depends on this having been run. `GlyphVectorTest` is what keeps the
 * checked-in file honest -- running this task is how you fix that test, not how
 * you pass the build.
 *
 * One drawable so far. The dial's other icons do not need one: they come from
 * Google's providers, which supply their own.
 */
object Glyphs {

    /** Where the checked-in drawable lives, relative to the repo root. */
    const val CYCLE_RING_PATH = "wear/src/main/res/drawable/ic_cycle_ring.xml"

    /** The exact bytes that file must contain. */
    fun cycleRingXml(): String = GlyphVector.toVectorDrawable(ComplicationGlyphs.cycleRing())

    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(CYCLE_RING_PATH)
        out.parentFile.mkdirs()
        out.writeText(cycleRingXml())
        println("wrote ${out.path}")
    }
}
