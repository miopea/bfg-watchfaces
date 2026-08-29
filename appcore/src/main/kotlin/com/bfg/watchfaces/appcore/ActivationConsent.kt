package com.bfg.watchfaces.appcore

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
 * ## Where the ask happens, now settled
 *
 * Operator decision 01a049a1-390b-7b50-a5d3-cc082037bb55: **the watch puts the
 * dialog up the first time a face lands on it**, and the DEVICE app explains
 * what is about to happen beforehand, in clear steps. See [HANDOFF].
 *
 * The permission can only be held by the app on the watch — Google's own
 * guidance gives the watch app the job of "requesting necessary permissions and
 * prompting the user", and `androidx.wear.watchfacepush` declares
 * `<uses-library android:name="wear-sdk" android:required="true" />`, so an app
 * linking it cannot install on a phone at all. The device app therefore does
 * the explaining and the watch does the asking.
 *
 * The moment is the caller's business; everything in this file is the same
 * either way.
 *
 * ## Pure, so the words are testable
 *
 * Same shape as `WatchDevices`: the judgement lives here and is tested, the
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
        DENIED,

        /**
         * The dialog has been put up and no answer has come back yet.
         *
         * Written BEFORE the system dialog appears, which is the whole point of
         * it existing. The request may only ever be made once, and an activity
         * hosting a permission dialog can be destroyed while that dialog is on
         * screen — so if the only write happened on the result, a death
         * mid-dialog would leave this reading UNASKED and the app would spend
         * the one shot a second time on someone who had already answered.
         *
         * Nothing asks from here. [settle] turns it into a real answer by
         * looking at whether the permission actually landed.
         */
        ASKING
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
        require(state == State.UNASKED || state == State.ASKING) {
            "asked for activation while $state; the request may only be made once per install"
        }
        return if (granted) State.GRANTED else State.DENIED
    }

    /**
     * Mark the ask as spent, before the dialog is shown.
     *
     * Save the result of this to disk BEFORE calling into the system, not
     * after. See [State.ASKING] for what that buys.
     */
    fun begin(state: State): State {
        require(canAsk(state)) {
            "began an activation request while $state; it may only be made once per install"
        }
        return State.ASKING
    }

    /** Did a previous attempt put the dialog up and never hear back? */
    fun isInterrupted(state: State): Boolean = state == State.ASKING

    /**
     * Turn an interrupted ask into a real answer.
     *
     * [permissionGranted] is the system's own view, which is the only evidence
     * left once the callback is gone. Absent means denied rather than unasked:
     * Android will not show the dialog again either way, so treating it as
     * unasked would produce an app that believes it can still ask and silently
     * never does.
     */
    fun settle(state: State, permissionGranted: Boolean): State =
        if (state == State.ASKING) {
            if (permissionGranted) State.GRANTED else State.DENIED
        } else {
            state
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

    // ---- getting to the ask at all ------------------------------------------
    //
    // Android will not let the install path open the permission dialog. The
    // face lands in a background context -- a WearableListenerService handling
    // onChannelOpened -- and `startActivity` from there is refused outright:
    //
    //     Background activity launch blocked! callingUidProcState: RECEIVER
    //
    // Observed on a Wear OS 6 emulator, 2026-08-29. So something has to carry
    // the ask from "a face landed" to "the person is looking at the watch", and
    // a notification is the only bridge Android offers: the tap is a foreground
    // action, which is exactly what the block is asking for.
    //
    // This is why the copy below is not decoration. It is the entire
    // explanation the person gets on the watch before a one-shot dialog.

    /** Notification channel, shown in the watch's own notification settings. */
    const val NOTIFY_CHANNEL_NAME = "New watch faces"

    /**
     * Leads with the good news, not with a request.
     *
     * The face is already installed by the time this appears. Someone who never
     * taps has lost nothing, and the notification must not imply otherwise.
     */
    const val NOTIFY_TITLE = "Your new watch face is ready"

    /**
     * Says what the tap does AND that it is a one-time question.
     *
     * The one-shot warning belongs here as well as in the dialog: Android's
     * dialog is one line it does not let us write, so this is the last place we
     * control before an irreversible choice.
     */
    const val NOTIFY_BODY =
        "Tap to let the app switch to it for you. You will only be asked once."

    /**
     * The note to show for a given state, or null when there is nothing to say.
     *
     * Only [State.DENIED] produces one. Granted needs no explanation, and
     * unasked has not happened yet.
     */
    fun persistentNote(state: State): String? =
        if (state == State.DENIED) DENIED_NOTE else null

    // ---- what the DEVICE says before the watch asks --------------------------

    /**
     * The steps shown on the device before the first face is sent.
     *
     * Operator: "It should be a clear multi-step instruction on the device app,
     * saying it is pushing to the watch, needs approval."
     *
     * This exists because the explaining and the asking happen on different
     * screens. The watch is where the system dialog has to appear, and a small
     * round screen is a poor place to read anything careful — so the device does
     * the explaining while the person is looking at it, and the watch dialog
     * lands on someone who already knows what it is for.
     *
     * The first step is not padding. Google's guidance is explicit that a phone
     * app should detect the absence of the watch app through `CapabilityClient`
     * and offer to install it; without it nothing can be sent at all, and
     * "nothing happened" is the worst possible failure here.
     */
    val HANDOFF: List<Step> = listOf(
        Step(
            "Your watch needs the app too",
            "A small companion app receives faces on the watch. If it is not there yet, " +
            "we will take you to install it."
        ),
        Step(
            "We send the face over",
            "It goes straight from here to your watch over Bluetooth. Nothing is uploaded " +
            "anywhere and no account is needed."
        ),
        Step(
            "Your watch asks once",
            "The first time, your watch asks whether this app may change your watch face. " +
            "Say yes and it can put faces on for you; say no and you switch to them yourself."
        )
    )

    /** One step of [HANDOFF]: a short heading and a sentence or two under it. */
    data class Step(val title: String, val detail: String)

    // ---- remembering, which is the whole reason this is not a boolean ---------
    //
    // The state has to outlive the process: "have we asked yet" is meaningless
    // if it resets when the app restarts, and re-asking is the one thing that
    // cannot be undone. On Android this is DataStore; here it is a file, and
    // the judgement above does not care which.

    private const val FILE = "activation.txt"

    /** Read the stored state. Anything unreadable is [State.UNASKED]. */
    fun load(root: java.io.File): State {
        val f = java.io.File(root, FILE)
        if (!f.isFile) return State.UNASKED
        // An unrecognised value must NOT become DENIED -- that would silently
        // consume the one ask on a corrupt file. UNASKED is the safe reading:
        // worst case the person is asked when they could have been spared it.
        return runCatching { State.valueOf(f.readText().trim()) }.getOrDefault(State.UNASKED)
    }

    fun save(root: java.io.File, state: State) {
        java.io.File(root, FILE).writeText(state.name)
    }

    /**
     * Whether the device still owes the person the explanation.
     *
     * True until they have been asked. After that the watch has already put its
     * dialog up, so repeating the steps is just noise.
     */
    fun needsHandoff(state: State): Boolean = state == State.UNASKED
}
