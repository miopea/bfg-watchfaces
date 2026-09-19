package com.bfg.watchfaces.appcore

/**
 * What a failed send says for itself, when somebody is willing to pass it on.
 *
 * ## Why this is here and not in the phone app
 *
 * Same reason [SubmissionLog.describe] is: it decides what a person is shown,
 * and `:mobile` is the one module with no tests. The sheet that displays this
 * is Android and stays there; the WORDS and their order are a rule, and a rule
 * belongs where it can be pinned.
 *
 * ## The order is the design
 *
 * The app version comes FIRST. "Which build?" is the first question any report
 * needs answered and the last one anybody thinks to include, so it is not left
 * to the person to remember.
 *
 * Then the stage, because "we could not build it" and "we built it and the
 * checker refused it" send an investigation to opposite halves of the code. On
 * 2026-09-18 establishing that distinction by hand cost developer mode, an SSH
 * tunnel and about forty scripted sends; it is one word here.
 *
 * Then the error, VERBATIM. It is ours or Google's, it names elements and line
 * numbers, and softening it is exactly how the useful part went missing the
 * first time. The friendly sentence already happened — this is the part that
 * has to survive being read by whoever fixes it.
 */
object FailureReport {

    /**
     * [errorMessage] is nullable because a Throwable's is: some carry only a
     * type. A missing message must not leave a dangling separator, so the type
     * stands alone rather than being followed by ": ".
     */
    fun text(
        appVersion: String,
        stage: Stage,
        faceName: String,
        errorType: String,
        errorMessage: String? = null
    ): String = buildString {
        append("BFG Watch Faces ").append(appVersion).append('\n')
        append(stage.wording).append(" “").append(faceName).append("”\n")
        append(errorType.ifBlank { "Error" })
        val detail = errorMessage?.trim()
        if (!detail.isNullOrEmpty()) append(": ").append(detail)
    }

    /**
     * Where it stopped, in words rather than in a class name.
     *
     * Two values because the phone has exactly two failure points before the
     * watch is ever contacted, and telling them apart is most of the diagnosis.
     */
    enum class Stage(val wording: String) {
        /** Packing, signing, or rendering — the artefact was never produced. */
        BUILD("Could not build"),

        /** Produced, then refused by Google's on-device watch face checker. */
        VALIDATE("Built, but the watch face checker refused")
    }
}
