package com.bfg.watchfaces.generator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.math.abs

/**
 * Generated surfaces. The point of them is that they are PARAMETERS: unlike an
 * imported image, a face using one can be shared to the community catalog.
 * That only holds if the field is reproducible from those parameters alone.
 */
class TextureFieldTest {

    private val proceduralEngines =
        listOf(Engine.GRAIN, Engine.BRUSHED, Engine.CARBON, Engine.LINEN)

    private fun grid(kind: TextureField.Kind, p: DialParams, step: Int = 37): List<Double> =
        (0 until DIAL_SIZE step step).flatMap { y ->
            (0 until DIAL_SIZE step step).map { x ->
                TextureField.sample(kind, x.toDouble(), y.toDouble(), p)
            }
        }

    @ParameterizedTest
    @EnumSource(TextureField.Kind::class)
    fun `sampling is deterministic`(kind: TextureField.Kind) {
        // A stored face must re-render identically on someone else's device
        // years later. This is why the field hashes integers instead of using
        // a seeded Random, whose reproducibility depends on call order.
        val p = DialParams(generatorVersion = 4, engine = Engine.GRAIN)
        assertEquals(grid(kind, p), grid(kind, p))
    }

    @ParameterizedTest
    @EnumSource(TextureField.Kind::class)
    fun `the field stays in range`(kind: TextureField.Kind) {
        // The renderer maps this to a colour shift; out-of-range values would
        // clip to flat black or white patches rather than fail loudly.
        val p = DialParams(generatorVersion = 4, engine = Engine.GRAIN)
        for (v in grid(kind, p, step = 13)) {
            assertTrue(v in 0.0..1.0) { "$kind produced $v, outside [0,1]" }
        }
    }

    @ParameterizedTest
    @EnumSource(TextureField.Kind::class)
    fun `the field actually varies`(kind: TextureField.Kind) {
        // A constant field would render as a plain dial and look like the
        // feature silently not working.
        val vs = grid(kind, DialParams(generatorVersion = 4, engine = Engine.GRAIN), step = 11)
        val spread = (vs.max() - vs.min())
        assertTrue(spread > 0.15) { "$kind is nearly flat (spread ${"%.3f".format(spread)})" }
    }

    @Test
    fun `each surface is distinguishable from the others`() {
        // Four names that render the same picture would be four lies in the UI.
        val p = DialParams(generatorVersion = 4, engine = Engine.GRAIN)
        val kinds = TextureField.Kind.entries.toList()
        for (i in kinds.indices) for (j in i + 1 until kinds.size) {
            val a = grid(kinds[i], p, step = 17)
            val b = grid(kinds[j], p, step = 17)
            val diff = a.zip(b).map { abs(it.first - it.second) }.average()
            assertTrue(diff > 0.04) {
                "${kinds[i]} and ${kinds[j]} differ by only ${"%.4f".format(diff)} on average"
            }
        }
    }

    @Test
    fun `freq reseeds the surface and rotate turns it`() {
        val base = DialParams(generatorVersion = 4, engine = Engine.GRAIN)
        assertNotEquals(
            grid(TextureField.Kind.GRAIN, base),
            grid(TextureField.Kind.GRAIN, base.copy(freq = 11))
        ) { "freq did not change the surface" }
        assertNotEquals(
            grid(TextureField.Kind.BRUSHED, base),
            grid(TextureField.Kind.BRUSHED, base.copy(rotate = 90.0))
        ) { "rotate did not turn the brush direction" }
    }

    @Test
    fun `procedural engines emit no geometry`() {
        // Their dial is a field the renderer shades, exactly like TEXTURE's is
        // an imported image. Returning nothing here is correct, not a stub.
        for (e in proceduralEngines) {
            val paths = PatternEngines.paths(DialParams(generatorVersion = 4, engine = e))
            assertTrue(paths.isEmpty()) { "$e emitted ${paths.size} polylines" }
        }
    }

    @Test
    fun `v4 changes no existing engine`() {
        // The bump must carry the new engines and nothing else, or the version
        // guarantee is worthless.
        val unchanged = Engine.entries.filter {
            it !in proceduralEngines && it != Engine.TEXTURE
        }
        for (e in unchanged) {
            val v3 = PatternEngines.paths(DialParams(generatorVersion = 3, engine = e))
            val v4 = PatternEngines.paths(DialParams(generatorVersion = 4, engine = e))
            assertEquals(v3, v4) { "$e differs between v3 and v4" }
        }
    }

    @Test
    fun `a procedural engine is rejected by an older generator`() {
        // Returning something plausible would corrupt a stored face on a reader
        // that predates the engine. Fail loudly instead.
        for (e in proceduralEngines) {
            assertThrows(IllegalStateException::class.java) {
                PatternEngines.paths(DialParams(generatorVersion = 1, engine = e))
            }
        }
    }

    @Test
    fun `a generated surface can be shared, unlike an imported image`() {
        // This is the whole reason these engines exist.
        for (e in proceduralEngines) {
            assertTrue(!DialParams(generatorVersion = 4, engine = e).isLocalOnly) {
                "$e was treated as local-only; a generated surface is parameters"
            }
        }
        assertTrue(DialParams(generatorVersion = 4, engine = Engine.TEXTURE, texture = "a".repeat(40)).isLocalOnly)
    }
}
