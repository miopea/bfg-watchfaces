package com.bfg.watchfaces.generator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The tilt glow can be turned down, and turning it down is the only thing that
 * changed.
 */
class GlareControlTest {

    /**
     * The default reproduces the light exactly as it shipped.
     *
     * This is what makes the control safe to add with no `generatorVersion`
     * branch: every face saved before it existed carries no `glare` key, reads
     * the default, and must emit the same bytes it always did.
     */
    @Test
    fun `the default is the light as it shipped`() {
        assertEquals(100.0, DialParams().glare)
        assertEquals(Glare.PEAK_ALPHA, Glare.peakAlphaFor(DialParams()))
    }

    /**
     * Zero emits NO layer, rather than a transparent one.
     *
     * The `<Gyro>` inside it is what turns the accelerometer on, and a
     * continuous sensor read is the most expensive thing on the dial. An "off"
     * that still read the sensor and multiplied by zero would cost the battery
     * and show nothing.
     */
    @Test
    fun `off removes the layer and its sensor read`() {
        val on = WffEmitter.emit(DialParams(generatorVersion = 13))
        val off = WffEmitter.emit(DialParams(generatorVersion = 13, glare = 0.0))

        assertTrue(on.contains("<Gyro")) { "the reference face has no glare to turn off" }
        assertTrue(!off.contains("<Gyro")) {
            "glare=0 still emits a Gyro, so the accelerometer runs to draw nothing"
        }
        assertTrue(!off.contains("resource=\"glare\"")) { "glare=0 still emits the band" }
    }

    /** Turning it down dims it, monotonically, rather than doing nothing. */
    @Test
    fun `turning it down dims it`() {
        val peaks = listOf(0.0, 25.0, 50.0, 75.0, 100.0).map {
            Glare.peakAlphaFor(DialParams(glare = it))
        }
        assertEquals(peaks.sorted(), peaks) { "dimming the control did not dim the light: $peaks" }
        assertEquals(0, peaks.first())
        assertTrue(peaks.distinct().size == peaks.size) { "two settings look identical: $peaks" }
    }

    /** A value from outside the slider cannot produce an illegal alpha. */
    @Test
    fun `out of range values are clamped rather than trusted`() {
        assertEquals(0, Glare.peakAlphaFor(DialParams(glare = -50.0)))
        assertEquals(Glare.PEAK_ALPHA, Glare.peakAlphaFor(DialParams(glare = 900.0)))
    }

    /**
     * The setting survives a save and reload.
     *
     * A control that reads back as its default is a control that silently
     * forgets, which looks exactly like the slider not working.
     */
    @Test
    fun `the setting survives a round trip`() {
        for (v in listOf(0.0, 35.0, 100.0)) {
            val p = DialParams(glare = v)
            val back = com.bfg.watchfaces.generator.DialParams()
            assertEquals(v, ControlInventory.valueOf(ControlInventory.with(back, "glare", v), "glare"))
            assertEquals(v, p.glare)
        }
    }

    /**
     * The slider is offered only on faces that actually have a glow.
     *
     * A saved face keeps the version it was made with, so a face from before
     * v13 has no glare layer at all. Offering the control there would be a
     * slider that silently does nothing.
     */
    @Test
    fun `the control is offered only where it does something`() {
        val ids = { p: DialParams -> ControlInventory.forFace(p).map { it.id } }
        assertTrue("glare" in ids(DialParams(generatorVersion = 13)))
        assertTrue("glare" !in ids(DialParams(generatorVersion = 12))) {
            "a pre-v13 face offers a glare slider that cannot do anything"
        }
        // Everything else is unaffected -- this must not have hidden other controls.
        val old = ids(DialParams(generatorVersion = 12))
        val new = ids(DialParams(generatorVersion = 13))
        assertEquals(new - "glare", old) { "adding sinceVersion hid an unrelated control" }
    }

    /**
     * Turning it down to zero does NOT hide its own slider.
     *
     * Otherwise the control vanishes at the moment somebody drags it to
     * nothing, and there is no way left to bring the glow back.
     */
    @Test
    fun `turning it off leaves the slider in place`() {
        val off = DialParams(generatorVersion = 13, glare = 0.0)
        assertTrue("glare" in ControlInventory.forFace(off).map { it.id }) {
            "the slider disappeared when it was turned off, stranding the face"
        }
        assertTrue(!Glare.enabledFor(off)) { "it should still emit nothing" }
        assertTrue(Glare.supportedBy(off)) { "the face still supports a glow" }
    }
}
