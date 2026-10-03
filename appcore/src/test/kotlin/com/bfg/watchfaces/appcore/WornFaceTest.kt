package com.bfg.watchfaces.appcore

import com.bfg.watchfaces.generator.DialParams
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WornFaceTest {

    private val app = "com.bfg.watchfaces"

    private fun saved(slug: String, name: String) =
        FaceLibrary.StoredFace(slug, name, "2026-10-03T00:00:00Z", DialParams())

    /**
     * The two directions must agree, because nothing else checks them.
     *
     * The forward rule decides what gets installed on a wrist; the reverse
     * decides which saved design the app believes that installation IS. A
     * disagreement is silent — an unrecognised package looks exactly like a
     * face somebody else installed.
     */
    @Test
    fun `the package rule runs in both directions`() {
        for (slug in listOf("trail_day", "midnight_7f3a", "face_2", "a")) {
            val pkg = FacePackage.nameFor(app, slug)
            assertEquals(slug, FacePackage.slugIn(pkg)) { "round trip failed for $slug" }
        }
    }

    /** A package that is not ours yields null, which is an answer and not an empty slug. */
    @Test
    fun `a foreign package has no slug`() {
        assertNull(FacePackage.slugIn("com.example.someone.elses.face"))
        assertNull(FacePackage.slugIn(""))
        assertNull(FacePackage.slugIn(null))
        // Marker present but nothing after it is not a slug either.
        assertNull(FacePackage.slugIn("$app.watchfacepush."))
    }

    /** The reconcile case the operator described: "if it matches, update the existing one". */
    @Test
    fun `a face the phone still has reconciles to that entry`() {
        val worn = WornFace(FacePackage.nameFor(app, "trail_day"), "slot-1", active = true)
        val plan = WornFacePlan.of(worn, listOf(saved("other", "Other"), saved("trail_day", "Trail Day")))
        assertTrue(plan is WornFacePlan.Saved)
        assertEquals("Trail Day", (plan as WornFacePlan.Saved).saved.name)
        assertEquals("slot-1", plan.worn.slotId)
    }

    /**
     * The reinstall case, which is what prompted the feature.
     *
     * The phone's library is empty because an uninstall wiped filesDir; the
     * watch is still wearing the face. The app knows the slug and nothing else.
     */
    @Test
    fun `a face the phone has lost is reported as not saved`() {
        val worn = WornFace(FacePackage.nameFor(app, "trail_day"), "slot-1", active = true)
        val plan = WornFacePlan.of(worn, emptyList())
        assertTrue(plan is WornFacePlan.NotSaved)
        assertEquals("trail_day", (plan as WornFacePlan.NotSaved).worn.slug)
    }

    /**
     * Silence is not "you are wearing nothing".
     *
     * A watch out of range, asleep, or on a build that predates this must not
     * be reported as a bare wrist. Same rule WatchReadiness already follows.
     */
    @Test
    fun `no answer is distinct from wearing none of ours`() {
        assertEquals(WornFacePlan.NoAnswer, WornFacePlan.of(null, listOf(saved("trail_day", "Trail Day"))))

        val foreign = WornFace("com.example.other.face", "slot-9", active = true)
        assertEquals(WornFacePlan.NoneOfOurs, WornFacePlan.of(foreign, listOf(saved("trail_day", "Trail Day"))))
    }

    @Test
    fun `the wire form round trips`() {
        val w = WornFace(FacePackage.nameFor(app, "trail_day"), "slot-1", active = true)
        assertEquals(w, WornFace.decode(w.encode()))

        val inactive = w.copy(active = false)
        assertEquals(inactive, WornFace.decode(inactive.encode()))
        assertEquals(false, WornFace.decode(inactive.encode())?.active)
    }

    /**
     * Nothing unreadable becomes a face.
     *
     * An empty payload is how the watch says "none of ours", and it must decode
     * to null rather than to a WornFace with an empty package that would then
     * match nothing and read as NotSaved.
     */
    @Test
    fun `nothing unreadable decodes to a face`() {
        for (bad in listOf(null, "", "   ", "pkg", "pkg|slot", "|slot|1", "||")) {
            assertNull(WornFace.decode(bad)) { "decoded $bad into a face" }
        }
    }

    /**
     * Matching is slug to slug, never name to name.
     *
     * slugify is lossy, so "Trail Day" and "trail day" and "TRAIL  DAY" all
     * reach the same slug and must all reconcile to the same saved entry. A
     * name comparison would miss every one of them.
     */
    @Test
    fun `matching survives however the name was capitalised`() {
        val worn = WornFace(FacePackage.nameFor(app, FaceLibrary.slugify("Trail Day")), "s", active = true)
        for (name in listOf("Trail Day", "trail day", "TRAIL  DAY", " Trail   Day ")) {
            val plan = WornFacePlan.of(worn, listOf(saved(FaceLibrary.slugify(name), name)))
            assertTrue(plan is WornFacePlan.Saved) { "did not reconcile for '$name'" }
        }
    }

    /**
     * THREE empty cases, not two, and collapsing them is the live hazard.
     *
     * A blank payload is the watch saying "none of ours is installed". null is
     * the watch saying nothing. Route a blank through WornFace.decode and it
     * returns null, so the two become one answer and somebody is told their
     * watch is bare while they are looking at the face on it.
     *
     * I wrote that exact conflation into the phone reader before this test
     * existed. It is the same shape as PushAvailability.decode turning an
     * unreadable reply into a refusal.
     */
    @Test
    fun `a blank reply means none of ours and a missing reply means no answer`() {
        val lib = listOf(saved("trail_day", "Trail Day"))

        assertEquals(WornFacePlan.NoAnswer, WornFacePlan.fromReply(null, lib))
        assertEquals(WornFacePlan.NoneOfOurs, WornFacePlan.fromReply("", lib))
        assertEquals(WornFacePlan.NoneOfOurs, WornFacePlan.fromReply("   ", lib))

        // Unreadable must not become a confident claim about a bare wrist.
        assertEquals(WornFacePlan.NoAnswer, WornFacePlan.fromReply("nonsense", lib))

        val worn = WornFace(FacePackage.nameFor(app, "trail_day"), "slot-1", active = true)
        assertTrue(WornFacePlan.fromReply(worn.encode(), lib) is WornFacePlan.Saved)
    }
}
