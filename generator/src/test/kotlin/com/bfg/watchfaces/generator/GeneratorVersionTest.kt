package com.bfg.watchfaces.generator

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Community faces are distributed as parameters, so the generator IS the file
 * format. These tests exist to make breaking that format loud.
 */
class GeneratorVersionTest {

    @Test
    fun `bumping CURRENT_GENERATOR_VERSION is deliberate`() {
        assertEquals(7, CURRENT_GENERATOR_VERSION,
            "You changed CURRENT_GENERATOR_VERSION. That is fine ONLY if you added a new " +
            "branch in PatternEngines.paths() and left every older branch untouched. " +
            "Existing community faces must keep rendering exactly as their authors saw them. " +
            "Update this assertion and add golden coverage for the new version.")
    }

    @Test
    fun `params reject an unknown generator version`() {
        assertThrows(IllegalArgumentException::class.java) {
            DialParams(generatorVersion = CURRENT_GENERATOR_VERSION + 1)
        }
    }

    @Test
    fun `params reject malformed colours`() {
        assertThrows(IllegalArgumentException::class.java) { DialParams(dialColor = "7D7369") }
        assertThrows(IllegalArgumentException::class.java) { DialParams(inkColor = "#FFF") }
    }

    @Test
    fun `push package names follow the required convention`() {
        assertEquals(
            "com.bfg.watchfaces.watchfacepush.midnight_knot",
            WffEmitter.pushPackageName("com.bfg.watchfaces", "midnight_knot")
        )
        assertThrows(IllegalArgumentException::class.java) {
            WffEmitter.pushPackageName("com.bfg.watchfaces", "Midnight-Knot")
        }
    }
}

/**
 * v1 faces must keep rendering exactly as their authors saw them after the v2
 * bump. This is the whole reason generatorVersion exists.
 */
class GeneratorVersionCompatibilityTest {

    private val v1Engines = listOf(
        Engine.LATTICE, Engine.CLOUS, Engine.ROSETTE,
        Engine.BARLEYCORN, Engine.SUNBURST, Engine.BOTANICAL, Engine.NONE
    )

    @Test
    fun `v2 renders every pre-existing engine identically to v1`() {
        for (e in v1Engines) {
            val a = PatternEngines.paths(DialParams(generatorVersion = 1, engine = e))
            val b = PatternEngines.paths(DialParams(generatorVersion = 2, engine = e))
            assertEquals(a, b) {
                "$e differs between v1 and v2. Every face stored at v1 would silently re-render. " +
                "v2 must delegate to v1 for pre-existing engines, never re-implement them."
            }
        }
    }

    @Test
    fun `KNOTWORK is rejected at v1 rather than silently substituted`() {
        // Returning something plausible here would corrupt a stored face on a
        // reader that predates the engine. Fail loudly instead.
        assertThrows(IllegalStateException::class.java) {
            PatternEngines.paths(DialParams(generatorVersion = 1, engine = Engine.KNOTWORK))
        }
    }

    @Test
    fun `KNOTWORK renders at v2 and is deterministic`() {
        val p = DialParams(engine = Engine.KNOTWORK)
        val a = PatternEngines.paths(p)
        assertTrue(a.isNotEmpty())
        assertEquals(a, PatternEngines.paths(p)) { "KNOTWORK must reproduce byte for byte" }
    }

    @Test
    fun `KNOTWORK freq reseeds the tiling rather than acting as a density knob`() {
        val a = PatternEngines.paths(DialParams(engine = Engine.KNOTWORK, freq = 3))
        val b = PatternEngines.paths(DialParams(engine = Engine.KNOTWORK, freq = 9))
        assertTrue(a != b) { "freq did not change the arrangement at all" }

        // The motif vocabulary emits different polyline counts per tile, so the
        // totals will not match exactly -- but freq picks a different MIX of the
        // same vocabulary over the same grid, so they must stay close. A large
        // divergence would mean freq had quietly become a density control, and
        // `scale` is the parameter for that.
        val ratio = a.size.toDouble() / b.size
        assertTrue(ratio in 0.75..1.33) {
            "freq changed geometry volume by more than a third (${a.size} vs ${b.size}); " +
            "it should re-seed the tiling, not control how much of it there is"
        }
    }

    /**
     * v6 changed the complication BOX and nothing on the dial.
     *
     * The bump exists because [SlotGeometry] box heights changed, which moves
     * every slot on a stored face. The dial pattern must be untouched, so this
     * is the v1-v2 guard again for the new version.
     */
    @Test
    fun `v6 renders every engine identically to v5`() {
        for (engine in Engine.entries) {
            if (engine == Engine.TEXTURE) continue      // needs a bitmap, not geometry
            val v5 = PatternEngines.paths(DialParams(generatorVersion = 5, engine = engine))
            val v6 = PatternEngines.paths(DialParams(generatorVersion = 6, engine = engine))
            assertEquals(v5, v6) { "$engine changed between v5 and v6; the dial must be untouched" }
        }
    }

    /**
     * A face stored at v5 keeps the layout its author saw.
     *
     * This is the whole point of the bump: v6 slots are laid out with shorter
     * boxes, which moves them. If the old numbers are not preserved, every
     * saved face silently re-flows.
     */
    @Test
    fun `v5 slot boxes are exactly what they were before v6 existed`() {
        val p = DialParams(generatorVersion = 5, layout = Layout(complicationSize = 28))
        // The pre-v6 constants: one box height of 3.3x whether or not a glyph
        // is drawn, a text offset of 1.45x, and a text box of 1.7x.
        assertEquals(SlotGeometry.boxHeight(25, withIcon = true, version = 5), Math.round(25 * 3.3).toInt())
        assertEquals(SlotGeometry.boxHeight(25, withIcon = false, version = 5), Math.round(25 * 3.3).toInt())
        assertEquals(SlotGeometry.textHeight(25, version = 5), Math.round(25 * 1.7).toInt())
        assertEquals(SlotGeometry.textOffset(25, withIcon = false, version = 5), Math.round(25 * 1.45).toInt())

        // And the size it could actually reach is the OLD, clamped one.
        assertEquals(25, SlotGeometry.fittedSize(p)) {
            "a v5 face no longer clamps where it used to; stored faces have re-flowed"
        }
    }

    /** The bump did what it was for: v6 reaches a size v5 could not. */
    @Test
    fun `v6 reaches a larger complication than v5 could`() {
        val layout = Layout(complicationSize = 28)
        val v5 = SlotGeometry.fittedSize(DialParams(generatorVersion = 5, layout = layout))
        val v6 = SlotGeometry.fittedSize(DialParams(generatorVersion = 6, layout = layout))
        assertTrue(v6 > v5) { "v6 fitted $v6, v5 fitted $v5 -- the bump bought nothing" }
        assertEquals(28, v6) { "Large should no longer be clamped at all" }
    }


    /** v7 changed the complication GLYPH and nothing on the dial. */
    @Test
    fun `v7 renders every engine identically to v6`() {
        for (engine in Engine.entries) {
            if (engine == Engine.TEXTURE) continue
            val v6 = PatternEngines.paths(DialParams(generatorVersion = 6, engine = engine))
            val v7 = PatternEngines.paths(DialParams(generatorVersion = 7, engine = engine))
            assertEquals(v6, v7) { "$engine changed between v6 and v7" }
        }
    }

    /** A face stored at v6 keeps the glyph size its author saw. */
    @Test
    fun `v6 glyph metrics survive v7`() {
        assertEquals(Math.round(25 * 1.25).toInt(), SlotGeometry.iconHeight(25, version = 6))
        assertEquals(Math.round(25 * 0.85).toInt(), SlotGeometry.iconHeight(25, version = 7))
        assertEquals(29, SlotGeometry.maxSize(DialParams(generatorVersion = 6)))
    }

    /** And v7 reaches further than v6 could, which is why it exists. */
    @Test
    fun `v7 reaches a larger complication than v6`() {
        val v6 = SlotGeometry.maxSize(DialParams(generatorVersion = 6))
        val v7 = SlotGeometry.maxSize(DialParams(generatorVersion = 7))
        assertTrue(v7 > v6) { "v7 max $v7 is no better than v6's $v6" }
    }

}
