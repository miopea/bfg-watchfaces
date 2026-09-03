package com.bfg.watchfaces.appcore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The install decisions, pinned — including the two that reached a wrist.
 *
 * These ran on hardware before they could run anywhere else, which is the gap
 * this file narrows: not the transport, which still needs a watch, but every
 * choice the transport carries out.
 */
class InstallPlanTest {

    // ---- Routing -----------------------------------------------------------

    @Test
    fun `our own face is updated in place by default`() {
        assertEquals(
            InstallPlan.Route.UpdateInPlace("slot-1"),
            InstallPlan.route(oursSlotId = "slot-1", resetComplications = false, freeSlots = 0)
        )
    }

    /**
     * Asking to reset complications means the slot must GO.
     *
     * `updateWatchFace` keeps the slot, and the watch keeps the complication
     * sources assigned to it — `DefaultProviderPolicy` only fills a slot
     * nothing has been assigned to. Proven on a watch: three different faces
     * rendered the assignments of a build before them.
     */
    @Test
    fun `resetting complications replaces the slot rather than writing over it`() {
        assertEquals(
            InstallPlan.Route.ReplaceOurs("slot-1"),
            InstallPlan.route(oursSlotId = "slot-1", resetComplications = true, freeSlots = 0)
        )
    }

    @Test
    fun `a first install with room adds a fresh face`() {
        assertEquals(
            InstallPlan.Route.AddFresh,
            InstallPlan.route(oursSlotId = null, resetComplications = false, freeSlots = 1)
        )
    }

    /**
     * Full, and nothing of ours to replace.
     *
     * This check existed, a rewrite dropped it, and that turned a reportable
     * situation into a silent one: addWatchFace was called into a full slot,
     * failed, and the phone still said "Sent". Every send appeared to work and
     * no face ever appeared.
     */
    @Test
    fun `a full slot holding nothing of ours is refused rather than attempted`() {
        assertEquals(
            InstallPlan.Route.NoSlotAvailable,
            InstallPlan.route(oursSlotId = null, resetComplications = false, freeSlots = 0)
        )
    }

    /** Having our own slot beats having none free — replacing ours is the way out. */
    @Test
    fun `owning a slot is what makes a full watch installable`() {
        val full = InstallPlan.route("slot-1", resetComplications = true, freeSlots = 0)
        assertEquals(InstallPlan.Route.ReplaceOurs("slot-1"), full) {
            "a watch with no free slots is only installable because one of them is ours"
        }
    }

    // ---- The invariant that cost a watch face ------------------------------

    /**
     * **Never remove the face somebody is wearing.**
     *
     * Remove-and-add deletes before it adds, and deleting the active face
     * deactivates it — while activation is spendable once per install, so once
     * that is gone nothing can switch it back. The first version of this
     * fallback made that trade and left the operator on the system default.
     */
    @Test
    fun `a failed update never falls back to removing the worn face`() {
        assertFalse(InstallPlan.mayRemoveAfterFailedUpdate(wearingOurs = true)) {
            "the fallback would delete the face on the wearer's wrist with no way to restore it"
        }
    }

    /** On a face nobody is wearing the same fallback is safe, and is the point. */
    @Test
    fun `a failed update may fall back when the face is not being worn`() {
        assertTrue(InstallPlan.mayRemoveAfterFailedUpdate(wearingOurs = false))
    }

    // ---- The one-per-install activation budget ------------------------------

    /**
     * An update inherits active status, so a worn face needs no activation.
     *
     * Spending it here is what exhausted the budget and produced "long press
     * and set it" on a watch already showing the face that had just arrived.
     */
    @Test
    fun `updating a face already on the wrist spends no activation`() {
        assertFalse(
            InstallPlan.spendActivation(
                InstallPlan.Route.UpdateInPlace("slot-1"), faceWasOnWrist = true
            )
        ) { "activation was spent to put a face where it already was" }
    }

    /** An update to a face NOT being worn still has to ask, or it never switches. */
    @Test
    fun `updating a face nobody is wearing does ask`() {
        assertTrue(
            InstallPlan.spendActivation(
                InstallPlan.Route.UpdateInPlace("slot-1"), faceWasOnWrist = false
            )
        ) { "dropping this was a regression: the face silently stopped switching" }
    }

    /**
     * The asymmetry, stated as its own fact because it is easy to get backwards.
     *
     * UpdateInPlace spends activation when the face was NOT worn.
     * ReplaceOurs spends it when the face WAS worn.
     *
     * Both are right: an update inherits active status, and a replace destroys
     * the slot and with it whatever was active in it.
     */
    @Test
    fun `update and replace want activation in opposite circumstances`() {
        for (worn in listOf(true, false)) {
            val update = InstallPlan.spendActivation(InstallPlan.Route.UpdateInPlace("s"), worn)
            val replace = InstallPlan.spendActivation(InstallPlan.Route.ReplaceOurs("s"), worn)
            assertTrue(update != replace) {
                "with worn=$worn both routes chose $update; the asymmetry has been flattened"
            }
        }
    }

    /** Replacing a face nobody was wearing must not spend the budget either. */
    @Test
    fun `replacing an unworn face spends no activation`() {
        assertFalse(
            InstallPlan.spendActivation(InstallPlan.Route.ReplaceOurs("slot-1"), faceWasOnWrist = false)
        ) { "activation was spent on a face that was sitting in the picker anyway" }
    }

    /** A first install always asks: there is nothing of ours to inherit from. */
    @Test
    fun `a fresh add always asks`() {
        assertTrue(InstallPlan.spendActivation(InstallPlan.Route.AddFresh, faceWasOnWrist = false))
        assertTrue(InstallPlan.spendActivation(InstallPlan.Route.AddFresh, faceWasOnWrist = true))
    }

    /** Nothing was installed, so there is nothing to activate. */
    @Test
    fun `a refused install spends nothing`() {
        for (worn in listOf(true, false)) {
            assertFalse(InstallPlan.spendActivation(InstallPlan.Route.NoSlotAvailable, worn))
        }
    }

    /** The dead end names the only way out, because there is no API for one. */
    @Test
    fun `the no-slot message tells somebody what to actually do`() {
        assertTrue(InstallPlan.NO_SLOT_MESSAGE.contains("Reinstall")) {
            "a wearer cannot act on this without being told the remedy"
        }
    }
}
