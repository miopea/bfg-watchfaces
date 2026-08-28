package com.bfg.watchfaces.workbench

import com.bfg.watchfaces.workbench.ActivationConsent.State
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The activation ask is the only unrecoverable action in this system, so the
 * rules around it are asserted rather than trusted.
 *
 * The important test is [`a no is final`][`a no is final`]. Everything else here
 * guards the copy, which the operator named as the substance of the decision.
 */
class ActivationConsentTest {

    @Test
    fun `the app may ask exactly once, before anyone has answered`() {
        assertTrue(ActivationConsent.canAsk(State.UNASKED))
        assertFalse(ActivationConsent.canAsk(State.GRANTED)) { "asking again after a yes is pointless" }
        assertFalse(ActivationConsent.canAsk(State.DENIED)) { "asking again after a no is the whole hazard" }
    }

    @Test
    fun `a no is final`() {
        // Android will not carry a second request, so a second request is not a
        // retry -- it is a call that quietly does nothing while looking like it
        // worked. There must be no path out of DENIED.
        val denied = ActivationConsent.record(State.UNASKED, granted = false)
        assertEquals(State.DENIED, denied)
        assertFalse(ActivationConsent.canAsk(denied))

        val boom = assertThrows(IllegalArgumentException::class.java) {
            ActivationConsent.record(denied, granted = true)
        }
        assertTrue(boom.message!!.contains("once per install")) { "the message should say why: ${boom.message}" }

        // And no sequence of answers reaches a state that would let it re-ask.
        var s = State.UNASKED
        s = ActivationConsent.record(s, granted = false)
        repeat(5) {
            assertFalse(ActivationConsent.canAsk(s))
            assertThrows(IllegalArgumentException::class.java) { ActivationConsent.record(s, granted = true) }
        }
        assertEquals(State.DENIED, s)
    }

    @Test
    fun `a yes is also final, and lets the app switch faces`() {
        val granted = ActivationConsent.record(State.UNASKED, granted = true)
        assertEquals(State.GRANTED, granted)
        assertTrue(ActivationConsent.canActivate(granted))
        assertFalse(ActivationConsent.canAsk(granted))
        assertThrows(IllegalArgumentException::class.java) {
            ActivationConsent.record(granted, granted = true)
        }
    }

    @Test
    fun `nothing switches a face without a yes`() {
        assertFalse(ActivationConsent.canActivate(State.UNASKED))
        assertFalse(ActivationConsent.canActivate(State.DENIED))
    }

    // ---- the note after a no --------------------------------------------------

    @Test
    fun `a no leaves a note that persists, and it says what to do instead`() {
        val note = ActivationConsent.persistentNote(State.DENIED)
        assertNotNull(note) { "a denial with no note leaves someone wondering why nothing happens" }
        // The operator asked for "how to activate from the watch instead", so
        // the note has to carry the actual gesture, not just sympathy.
        assertTrue(note!!.contains("press and hold", ignoreCase = true)) {
            "the note must say how to switch faces on the watch: $note"
        }
    }

    @Test
    fun `no note is shown when there is nothing to say`() {
        assertNull(ActivationConsent.persistentNote(State.UNASKED))
        assertNull(ActivationConsent.persistentNote(State.GRANTED))
    }

    @Test
    fun `the note does not argue with a decision that cannot be revisited`() {
        // Nothing can reopen the choice, so anything persuasive here is nagging
        // about a locked door.
        val note = ActivationConsent.persistentNote(State.DENIED)!!.lowercase()
        for (word in listOf("allow", "permission", "grant", "enable", "settings", "change your mind")) {
            assertFalse(note.contains(word)) { "the note tries to re-sell the permission with '$word': $note" }
        }
    }

    // ---- the explanation ------------------------------------------------------

    @Test
    fun `the explanation says why it is worth allowing`() {
        val why = ActivationConsent.WHY.lowercase()
        assertTrue(why.contains("send") && why.contains("watch")) {
            "WHY should describe what the app does for them: ${ActivationConsent.WHY}"
        }
        // And it must not pretend a no breaks the app, because it does not.
        assertTrue(why.contains("still arrives")) {
            "WHY should be honest that saying no still leaves a working app: ${ActivationConsent.WHY}"
        }
    }

    @Test
    fun `the explanation says what the approval limits the app to`() {
        // This is the half the operator singled out, and the half usually
        // omitted. A screen that only sells the upside is the shape people have
        // learned to distrust.
        val limits = ActivationConsent.LIMITS.lowercase()
        assertTrue(limits.contains("only")) { "LIMITS should bound what it covers: ${ActivationConsent.LIMITS}" }
        assertTrue(limits.contains("cannot")) {
            "LIMITS should say what it does NOT allow, not just what it does: ${ActivationConsent.LIMITS}"
        }
    }

    @Test
    fun `the explanation admits there is only one chance`() {
        val once = ActivationConsent.ONE_SHOT.lowercase()
        assertTrue(once.contains("once")) { "ONE_SHOT must say it is asked once: ${ActivationConsent.ONE_SHOT}" }
        assertTrue(once.contains("not") || once.contains("no ")) {
            "ONE_SHOT must say what happens on a no: ${ActivationConsent.ONE_SHOT}"
        }
    }

    @Test
    fun `the decline button does not promise a second chance`() {
        // "Not now" and "Maybe later" both imply the app will come back. It
        // cannot. A button that lies about being reversible is worse here than
        // anywhere else in the app.
        val decline = ActivationConsent.DECLINE.lowercase()
        for (weasel in listOf("not now", "later", "maybe", "remind")) {
            assertFalse(decline.contains(weasel)) {
                "'${ActivationConsent.DECLINE}' promises another ask that will never come"
            }
        }
    }

    @Test
    fun `every string a person reads is free of developer vocabulary`() {
        val shown = listOf(
            ActivationConsent.TITLE, ActivationConsent.WHY, ActivationConsent.LIMITS,
            ActivationConsent.ONE_SHOT, ActivationConsent.ACCEPT, ActivationConsent.DECLINE,
            ActivationConsent.DENIED_NOTE
        )
        // Same rule as the rest of the app: a person naming a watch face should
        // never meet an API name. "Android" survives -- it is the name of the
        // thing making the rule, and hiding it would be vaguer, not kinder.
        val jargon = listOf(
            "SET_PUSHED", "watchfacepush", "setWatchFaceAsActive", "slot", "API",
            "manifest", "runtime permission", "checkSelfPermission", "wear-sdk"
        )
        for (s in shown) for (j in jargon) {
            assertFalse(s.contains(j, ignoreCase = true)) { "user-facing copy contains '$j': $s" }
        }
    }
}
