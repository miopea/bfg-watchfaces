package com.bfg.watchfaces.generator

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class PatternEnginesTest {

    // TEXTURE is excluded with NONE: it is a raster engine whose dial is an
    // imported image, so "produces geometry" is not a property it has.
    @ParameterizedTest
    @EnumSource(value = Engine::class, names = ["NONE", "TEXTURE"], mode = EnumSource.Mode.EXCLUDE)
    fun `every engine produces geometry covering the dial`(engine: Engine) {
        val paths = PatternEngines.paths(DialParams(engine = engine))
        assertTrue(paths.isNotEmpty()) { "$engine produced no paths" }
        assertTrue(PatternEngines.coverage(paths) > 0.15) {
            "$engine covers only ${PatternEngines.coverage(paths)} of the dial"
        }
    }

    @ParameterizedTest
    @EnumSource(Engine::class)
    fun `engines are deterministic`(engine: Engine) {
        val p = DialParams(engine = engine)
        assertEquals(PatternEngines.paths(p), PatternEngines.paths(p)) {
            "$engine is not deterministic -- community faces would not reproduce"
        }
    }

    @ParameterizedTest
    @EnumSource(value = Engine::class, names = ["NONE", "TEXTURE"], mode = EnumSource.Mode.EXCLUDE)
    fun `engines stay within a sane point budget`(engine: Engine) {
        // Every point is stroked three times for the emboss pass. Blowing this
        // budget shows up as a janky preview on a mid-range phone.
        val total = PatternEngines.paths(DialParams(engine = engine)).sumOf { it.size }
        assertTrue(total < 400_000) { "$engine emitted $total points" }
    }

    @Test
    fun `finer scale yields more geometry`() {
        val coarse = PatternEngines.paths(DialParams(engine = Engine.CLOUS, scale = 40.0)).sumOf { it.size }
        val fine = PatternEngines.paths(DialParams(engine = Engine.CLOUS, scale = 12.0)).sumOf { it.size }
        assertTrue(fine > coarse) { "scale is inverted: fine=$fine coarse=$coarse" }
    }

    @Test
    fun `NONE emits nothing`() {
        assertTrue(PatternEngines.paths(DialParams(engine = Engine.NONE)).isEmpty())
    }

    @Test
    fun `TEXTURE emits no geometry -- its dial is an imported image`() {
        assertTrue(PatternEngines.paths(DialParams(engine = Engine.TEXTURE)).isEmpty())
    }
}
