package com.bfg.watchfaces.workbench

import com.bfg.watchfaces.workbench.ActivationConsent.State
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The activation state has to outlive the process.
 *
 * "Have we asked yet" is meaningless if it resets on restart, and re-asking is
 * the one thing in this system that cannot be undone. On Android this is
 * DataStore; here it is a file. The rules in [ActivationConsent] do not care
 * which, which is the point of keeping them apart.
 */
class ActivationStoreTest {

    @TempDir
    lateinit var root: File

    @Test
    fun `a fresh install has not been asked`() {
        assertEquals(State.UNASKED, ActivationConsent.load(root))
        assertTrue(ActivationConsent.needsHandoff(ActivationConsent.load(root)))
    }

    @Test
    fun `a no survives a restart`() {
        ActivationConsent.save(root, State.DENIED)
        // A "restart" is just a fresh read: nothing is cached in memory.
        assertEquals(State.DENIED, ActivationConsent.load(root))
        assertFalse(ActivationConsent.canAsk(ActivationConsent.load(root)))
    }

    @Test
    fun `a yes survives a restart`() {
        ActivationConsent.save(root, State.GRANTED)
        assertEquals(State.GRANTED, ActivationConsent.load(root))
        assertTrue(ActivationConsent.canActivate(ActivationConsent.load(root)))
    }

    @Test
    fun `a corrupt file reads as unasked, never as denied`() {
        // Reading garbage as DENIED would silently consume the one ask because
        // of a bad file. UNASKED is the safe failure: worst case somebody is
        // asked when they could have been spared it.
        File(root, "activation.txt").writeText(" nonsense ")
        assertEquals(State.UNASKED, ActivationConsent.load(root))
    }

    @Test
    fun `an empty file reads as unasked`() {
        File(root, "activation.txt").writeText("")
        assertEquals(State.UNASKED, ActivationConsent.load(root))
    }

    @Test
    fun `whitespace around a stored value is tolerated`() {
        File(root, "activation.txt").writeText("\n GRANTED \n")
        assertEquals(State.GRANTED, ActivationConsent.load(root))
    }

    @Test
    fun `the handoff stops being shown once they have been asked`() {
        assertTrue(ActivationConsent.needsHandoff(State.UNASKED))
        assertFalse(ActivationConsent.needsHandoff(State.GRANTED)) { "the watch has already asked" }
        assertFalse(ActivationConsent.needsHandoff(State.DENIED)) { "the watch has already asked" }
    }
}
