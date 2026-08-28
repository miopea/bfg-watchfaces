package com.bfg.watchfaces.workbench

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * There are two rasterizers now, and DECISIONS.md 2026-08-27 named the danger
 * exactly: they "start identical and drift".
 *
 * The defence is not a pixel comparison — one is AWT on the JVM and the other is
 * Android Canvas on a device that does not exist here, so no such comparison can
 * be run. The defence is that NEITHER renderer decides anything. Colours,
 * offsets, widths and gradient stops all come from :generator, which is tested,
 * and each platform only draws.
 *
 * So this checks the property that makes drift impossible rather than the drift
 * itself: that no decision has crept back into either renderer. It reads the
 * sources as text because that is the only way to assert an absence.
 */
class RendererParityTest {

    private fun source(path: String): String {
        val f = File(path)
        assertTrue(f.isFile) { "$path is gone; renderer parity is no longer being checked" }
        return f.readText()
    }

    private val android = "../mobile/src/main/kotlin/com/bfg/watchfaces/mobile/AndroidDialRenderer.kt"
    private val awt = "src/main/kotlin/com/bfg/watchfaces/workbench/DialRenderer.kt"

    @Test
    fun `both renderers ask generator for the stroke passes`() {
        for (p in listOf(android, awt)) {
            assertTrue(source(p).contains("EngravedStroke.passes")) {
                "$p no longer uses the shared stroke passes; the engraved look can now diverge"
            }
        }
    }

    @Test
    fun `both renderers ask generator for the shading`() {
        for (p in listOf(android, awt)) {
            val s = source(p)
            assertTrue(s.contains("DialShading.sheen") && s.contains("DialShading.vignette")) {
                "$p no longer uses the shared gradients; sheen or vignette can now diverge"
            }
        }
    }

    @Test
    fun `the Android renderer contains no colour arithmetic of its own`() {
        // The specific constants that used to live in the AWT renderer and would
        // be the natural thing to copy across. Their absence is the whole claim
        // AndroidDialRenderer makes about itself.
        val s = source(android)
        for (magic in listOf("0.75", "0.55", "0.62", "0.80", "205", "185", "0.7071")) {
            assertTrue(!s.contains(magic)) {
                "AndroidDialRenderer hardcodes '$magic'. That number belongs to " +
                    ":generator, and a second copy is how two renderers drift."
            }
        }
    }

    @Test
    fun `the Android renderer does not reimplement the lens`() {
        // DECISIONS.md 2026-08-27: the lens is preview-only and never reaches the
        // emitted WFF. Drawing it on device would make the preview differ from
        // the face that actually installs -- the wrong direction to be wrong in.
        assertTrue(!source(android).contains("drawLens")) {
            "the Android renderer draws the lens, which never reaches the shipped face"
        }
    }
}
