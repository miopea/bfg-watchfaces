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

    // ---- the steps the device shows before the watch asks --------------------

    @Test
    fun `the device explains the handoff in steps, before anything is sent`() {
        val steps = ActivationConsent.HANDOFF
        assertTrue(steps.size >= 3) { "the operator asked for a multi-step instruction, got ${steps.size}" }
        for (s in steps) {
            assertTrue(s.title.isNotBlank() && s.detail.isNotBlank()) { "empty step: $s" }
        }
    }

    @Test
    fun `the steps say a companion app is needed on the watch`() {
        // Without it nothing can be sent at all, and "nothing happened" is the
        // worst failure available here. Google's own guidance is that the phone
        // app detects the watch app's absence and offers to install it.
        val all = ActivationConsent.HANDOFF.joinToString(" ") { it.title + " " + it.detail }.lowercase()
        assertTrue(all.contains("watch") && (all.contains("install") || all.contains("companion"))) {
            "the steps never mention the watch needing the app: $all"
        }
    }

    @Test
    fun `the steps say the face is being sent to the watch`() {
        // The operator's words: "saying it is pushing to the watch, needs approval".
        val all = ActivationConsent.HANDOFF.joinToString(" ") { it.detail }.lowercase()
        assertTrue(all.contains("send") || all.contains("sent") || all.contains("goes")) {
            "nothing in the steps says the face is sent anywhere: $all"
        }
    }

    @Test
    fun `the steps warn that the watch will ask, and that it asks once`() {
        val all = ActivationConsent.HANDOFF.joinToString(" ") { it.title + " " + it.detail }.lowercase()
        assertTrue(all.contains("ask")) { "the steps never warn an approval is coming: $all" }
        assertTrue(all.contains("once")) {
            "the steps must say the watch asks ONCE -- it is the irreversible part: $all"
        }
    }

    @Test
    fun `the steps say what a no means, not just what a yes means`() {
        // Same rule as the permission screen itself. Selling only the upside is
        // the shape people have learned to distrust.
        val all = ActivationConsent.HANDOFF.joinToString(" ") { it.detail }.lowercase()
        assertTrue(all.contains("no")) { "the steps never say what declining does: $all" }
    }

    @Test
    fun `the steps do not promise an upload or an account`() {
        // The whole design is on-device. Copy that implies otherwise is wrong
        // about the product, not just badly worded.
        val all = ActivationConsent.HANDOFF.joinToString(" ") { it.detail }.lowercase()
        assertFalse(Regex("""\bsign (in|up)\b""").containsMatchIn(all)) { "the steps imply an account: $all" }
        assertFalse(all.contains("our server")) { "the steps imply a server: $all" }
    }

    @Test
    fun `every string a person reads is free of developer vocabulary`() {
        val shown = listOf(
            ActivationConsent.TITLE, ActivationConsent.WHY, ActivationConsent.LIMITS,
            ActivationConsent.ONE_SHOT, ActivationConsent.ACCEPT, ActivationConsent.DECLINE,
            ActivationConsent.DENIED_NOTE
        ) + ActivationConsent.HANDOFF.flatMap { listOf(it.title, it.detail) }
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
