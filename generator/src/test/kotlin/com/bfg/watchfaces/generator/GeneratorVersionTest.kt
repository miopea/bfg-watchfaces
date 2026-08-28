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
        assertEquals(3, CURRENT_GENERATOR_VERSION,
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
}
