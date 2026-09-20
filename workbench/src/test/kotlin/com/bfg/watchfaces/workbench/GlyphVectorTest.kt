package com.bfg.watchfaces.workbench

import com.bfg.watchfaces.generator.ComplicationGlyphs
import com.bfg.watchfaces.generator.GlyphVector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The checked-in watch drawable still matches the description it came from.
 *
 * This is the whole reason the drawable may be checked in at all. The Android
 * build must not depend on a JVM task having been run -- the same rule the
 * launcher icons follow -- so the file is committed, and committed files drift.
 * Here, drift means the ring on the wrist stops being the ring in the previews,
 * and nothing else in the build would notice.
 */
class GlyphVectorTest {

    private fun repoRoot(): File {
        // Gradle runs tests with the MODULE as the working directory.
        var dir = File(".").absoluteFile
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
        }
        return dir
    }

    @Test
    fun `the checked-in cycle ring matches ComplicationGlyphs`() {
        val file = File(repoRoot(), Glyphs.CYCLE_RING_PATH)
        assertTrue(file.isFile) { "${Glyphs.CYCLE_RING_PATH} is missing; run ./gradlew :workbench:glyphs" }
        assertEquals(Glyphs.cycleRingXml(), file.readText()) {
            "${Glyphs.CYCLE_RING_PATH} has drifted from ComplicationGlyphs. " +
                "Run ./gradlew :workbench:glyphs rather than editing the XML."
        }
    }

    /**
     * The ring's gap survives the conversion.
     *
     * An arc that quietly became a full circle is the failure that would be
     * hardest to see: it still renders, still tints, still sits above the
     * number, and is simply the wrong mark. A closed path would carry no `A`
     * sweep at all, or start and end at the same point.
     */
    @Test
    fun `the ring is open, not a closed circle`() {
        val d = Regex("""android:pathData="([^"]+)"""")
            .find(Glyphs.cycleRingXml())!!.groupValues[1]
        val (sx, sy, ex, ey) = Regex("""M([\d.]+),([\d.]+) A[\d.]+,[\d.]+ 0 \d,\d ([\d.]+),([\d.]+)""")
            .find(d)!!.destructured
        assertTrue(sx.toDouble() != ex.toDouble()) { "the ring closed up: $d" }
        // Symmetric about the vertical centre line, so the gap sits at the
        // bottom rather than off to one side.
        assertEquals(ComplicationGlyphs.GRID / 2, (sx.toDouble() + ex.toDouble()) / 2, 0.01)
        assertEquals(sy.toDouble(), ey.toDouble(), 0.01)
        // Below the centre: the gap is at 6 o'clock, where the value sits.
        assertTrue(sy.toDouble() > ComplicationGlyphs.GRID / 2) { "the gap is not at the bottom: $d" }
    }

    /**
     * An unsupported shape THROWS rather than being skipped.
     *
     * The bell glyph is the precedent: a WFF writer silently dropped an arc it
     * did not understand, and the bug was a bell with no bell in it, invisible
     * until it reached a watch face.
     */
    @Test
    fun `a shape the converter cannot draw is refused, not dropped`() {
        val oval = ComplicationGlyphs.Shape.Oval(0.0, 0.0, 10.0, 10.0, fill = true)
        assertThrows(IllegalArgumentException::class.java) {
            GlyphVector.toVectorDrawable(listOf(oval))
        }
    }

    /**
     * No locale can turn a coordinate into "12,5", which aapt2 rejects.
     *
     * Asserted as "the same bytes in any locale" rather than by hunting commas:
     * the path is FULL of legitimate commas -- `M15.804,20.157` separates x
     * from y -- so a pattern looking for a stray one matches the correct output
     * too. That mistake was made here first, and the test failed while the code
     * was right.
     */
    @Test
    fun `the drawable is byte-identical under a comma-decimal locale`() {
        val root = Glyphs.cycleRingXml()
        val was = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals(root, Glyphs.cycleRingXml()) {
                "the default locale changed the drawable; Locale.ROOT is missing somewhere"
            }
        } finally {
            java.util.Locale.setDefault(was)
        }
        assertTrue(root.contains("15.804")) { "expected a decimal POINT in the path data" }
    }
}
