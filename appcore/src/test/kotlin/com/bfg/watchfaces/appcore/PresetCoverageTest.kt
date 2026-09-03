package com.bfg.watchfaces.appcore

import com.bfg.watchfaces.generator.Engine
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Every style somebody can reach has a preset introducing it.
 *
 * A preset is how this app shows what a style looks like. Lattice, grain and
 * linen shipped with none: all three are in the engine list and all three are
 * named in the Play listing — "lattice" among the seven patterns, "Grain,
 * brushed, carbon and linen" among the surfaces — so the store described styles
 * the gallery had no example of, and the only way to see one was to guess at
 * the engine picker.
 *
 * This fails when a new engine is added without one, which is the moment to
 * decide what it should look like rather than months later.
 */
class PresetCoverageTest {

    /**
     * [Engine.TEXTURE] is the one exception, and it is not an oversight.
     *
     * It renders an imported photograph, so a preset for it would be a preset
     * with nothing to draw. It has no way in from the gallery BY DESIGN — the
     * way in is the photo picker.
     */
    private val needsNoPreset = setOf(Engine.TEXTURE)

    @Test
    fun `every engine has a preset that introduces it`() {
        val covered = Presets.ALL.values.map { it.engine }.toSet()
        val missing = Engine.entries.toSet() - covered - needsNoPreset
        assertTrue(missing.isEmpty()) {
            "$missing can be chosen but the gallery shows no example. " +
                "Add a preset, or add it to needsNoPreset with a reason."
        }
    }

    /** A preset with a name nobody could read would be no introduction at all. */
    @Test
    fun `every preset is named like a face and not like a setting`() {
        for (name in Presets.ALL.keys) {
            assertTrue(name.isNotBlank()) { "a preset has no name" }
            assertTrue(name.length <= 24) { "'$name' is too long to sit under a dial" }
            assertTrue(name.none { it == '_' }) { "'$name' reads as an identifier" }
        }
    }

    /** The gallery is the shop window; a handful of entries is not one. */
    @Test
    fun `the gallery offers a real spread`() {
        assertTrue(Presets.ALL.size >= 12) {
            "only ${Presets.ALL.size} presets; the gallery is the first thing anybody sees"
        }
    }
}
