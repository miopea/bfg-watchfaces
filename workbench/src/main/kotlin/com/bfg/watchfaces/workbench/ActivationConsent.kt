package com.bfg.watchfaces.workbench

/**
 * The one irreversible thing in this system: asking to switch the watch face.
 *
 * ## Why this is a state machine and not an `if`
 *
 * `com.google.wear.permission.SET_PUSHED_WATCH_FACE_AS_ACTIVE` cannot be
 * requested a second time after a denial, and `setWatchFaceAsActive()` is
 * capped besides — the library's own error list carries
 * `ERROR_MAXIMUM_ATTEMPTS_REACHED`, "The maximum number of attempts to set the
 * watch face as active has been reached."
 *
 * Every other mistake in this repo is recoverable by editing a file and running
 * the build again. This one is not: a second ask is not refused with an error
 * you can see, it simply never reaches the user, and that install has spent its
 * only chance. So the rule is expressed as a type with a terminal state rather
 * than as a boolean somebody remembers to check, and [ActivationConsentTest]
 * asserts the terminal state is genuinely terminal.
 *
 * ## Which app asks is NOT settled here
 *
 * Operator decision 01a0495b-dc98-76d2-9e80-92aff51cdec6 says ask at first app
 * open. Verifying that before building it turned up a problem with it: the
 * permission can only be held by the app running ON THE WATCH.
 * `androidx.wear.watchfacepush` declares `<uses-library android:name="wear-sdk"
 * android:required="true" />`, so an app that links it cannot install on a
 * phone or tablet at all, and the permission is checked with
 * `checkSelfPermission` inside `setWatchFaceAsActive` on the watch side.
 *
 * That is with the operator as 01a04987-6498-7820-b7c6-271471f39fb5. Everything
 * in this file is the same whichever way they answer — what the person is told,
 * that they are asked at most once, and what they see after a no. The moment of
 * the ask is the only open part, and it belongs to the caller.
 *
 * ## Pure, so the words are testable
 *
 * Same shape as [WatchDevices]: the judgement lives here and is tested, the
 * Android call is a thin thing at the edge that does not exist yet. When
 * `:wear` is built this class does not change — it gains a caller.
 */
object ActivationConsent {

    /** The Wear permission this whole file is about. */
    const val PERMISSION = "com.google.wear.permission.SET_PUSHED_WATCH_FACE_AS_ACTIVE"

    /**
     * Where an install stands. [DENIED] is terminal on purpose: there is no
     * transition out of it, because Android offers none.
     */
    enum class State {
        /** Nobody has been asked yet. The only state from which asking is allowed. */
        UNASKED,

        /** They said yes. The app may switch to faces it put there itself. */
        GRANTED,

        /** They said no. Terminal — Android will not carry a second request. */
        DENIED
    }

    /**
     * May the app put the request up right now?
     *
     * True exactly once per install. Deliberately not "is the permission
     * missing" — a denial also leaves it missing, and that is the reading that
     * spends the one shot on someone who already said no.
     */
    fun canAsk(state: State): Boolean = state == State.UNASKED

    /**
     * Fold the system's answer into the state.
     *
     * Asking from [State.GRANTED] or [State.DENIED] is a bug in the caller, not
     * a case to handle politely, so it throws. A silent no-op here would be a
     * second request that looks like it worked.
     */
    fun record(state: State, granted: Boolean): State {
        require(canAsk(state)) {
            "asked for activation while $state; the request may only be made once per install"
        }
        return if (granted) State.GRANTED else State.DENIED
    }

    /** Can the app switch a face on for them, or must they do it themselves? */
    fun canActivate(state: State): Boolean = state == State.GRANTED

    // ---- what the person is actually told ------------------------------------
    //
    // The operator asked for two things, and named the second because it is the
    // one that usually gets left out: why it matters, AND what the approval
    // limits the app to. A permission screen that only sells the upside is the
    // shape people have learned to distrust, so the boundary gets equal room.
    //
    // These live here rather than in a template because they are the substance
    // of the decision, they have to read identically on a phone and on a watch,
    // and a test can hold them to covering both halves.

    /** Heading for the ask. Plain question, no jargon, no branding. */
    const val TITLE = "Let this app change your watch face?"

    /** Why it is worth saying yes. Concrete, and honest that no still works. */
    const val WHY =
        "When you send a face to your watch, this lets the app put it on for you. " +
        "Without it the face still arrives — you just switch to it yourself on the watch."

    /**
     * What the approval covers, and nothing more. Both halves matter: the
     * second sentence is the boundary, and it is the reason to believe the
     * first.
     */
    const val LIMITS =
        "It only ever switches to a face you made here. It cannot see the face you are " +
        "wearing now, it cannot touch faces from anywhere else, and it cannot change " +
        "anything else on your watch."

    /**
     * The part nobody enjoys writing.
     *
     * Saying it plainly is the whole reason the explanation has to be good: the
     * person is making a permanent choice and deserves to know that while they
     * are making it, not afterwards.
     */
    const val ONE_SHOT =
        "You will only be asked this once. Android does not allow a second ask, so if you " +
        "say no now the app will not raise it again."

    /** Yes. */
    const val ACCEPT = "Allow"

    /**
     * No. NOT "Not now" or "Maybe later": both promise another chance that does
     * not exist, and a button that lies about being reversible is worse here
     * than anywhere else in the app.
     */
    const val DECLINE = "No thanks"

    /**
     * What they see afterwards if they said no.
     *
     * The operator asked for "a persistent short note on how to activate from
     * the watch instead" — persistent, not a toast. A toast is gone in three
     * seconds and this has to still be there next week, when they have sent a
     * face and are wondering why nothing happened.
     *
     * It is instructions, not a second sales pitch. There is nothing left to
     * sell: the decision cannot be revisited, so anything persuasive here is
     * just nagging someone about a door that is locked.
     */
    const val DENIED_NOTE =
        "Faces you send will arrive on your watch, but you switch to them yourself: " +
        "press and hold your current watch face, then scroll to the one you sent."

    /**
     * The note to show for a given state, or null when there is nothing to say.
     *
     * Only [State.DENIED] produces one. Granted needs no explanation, and
     * unasked has not happened yet.
     */
    fun persistentNote(state: State): String? =
        if (state == State.DENIED) DENIED_NOTE else null
}
