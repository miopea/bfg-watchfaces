package com.bfg.watchfaces.appcore

/**
 * What the watch is actually wearing, as opposed to what the phone last sent.
 *
 * ## Why this is not [CurrentFace]
 *
 * `CurrentFace` records an INTENTION: the design this phone last pushed. That
 * is the right thing to remember for most purposes and it is not an
 * observation. It is wrong whenever the two have drifted — a send that failed
 * after the transfer, a face installed from a different phone, or the case that
 * prompted this feature: the phone app reinstalled, `filesDir` wiped, and a
 * watch still happily wearing something the phone no longer has any record of.
 *
 * This type is the observation. It is only ever built from what the watch
 * reports, never from what the phone believes.
 *
 * ## It can only ever describe OUR faces
 *
 * `listWatchFaces()` returns the faces the calling app pushed and nothing else,
 * which is why `FaceInstaller` logs that count as `ours=`. So this cannot see a
 * face from another app, and the absence of one of ours is not evidence that
 * the wrist is bare.
 */
data class WornFace(
    /** The installed package, `<app package>.watchfacepush.<slug>`. */
    val packageName: String,
    /** The Push slot it occupies. Opaque; carried so a later action can name it. */
    val slotId: String,
    /** Whether this is the face currently on the screen, not merely installed. */
    val active: Boolean
) {

    /** The slug inside [packageName], or null when it is not one of ours. */
    val slug: String? get() = FacePackage.slugIn(packageName)

    /**
     * The wire form, pipe-separated like [PushAvailability.encode].
     *
     * Neither field can contain a pipe — a package name is dotted identifiers
     * and a slot id is opaque but short — so the separators are stripped
     * defensively rather than because anything is expected to carry one.
     */
    fun encode(): String = listOf(
        packageName.replace('|', '/'),
        slotId.replace('|', '/'),
        if (active) "1" else "0"
    ).joinToString("|")

    companion object {

        /**
         * Read a reply, or null when there is nothing usable in it.
         *
         * Null covers both "the watch is wearing none of ours" (an empty
         * payload, which is a real answer) and "this did not parse". The caller
         * cannot act differently on those two, and inventing a distinction the
         * data does not carry is how [PushAvailability.decode] ended up
         * treating an unreadable reply as a refusal.
         */
        fun decode(text: String?): WornFace? {
            val parts = text?.trim().orEmpty().split("|")
            if (parts.size < 3) return null
            val pkg = parts[0].trim()
            if (pkg.isEmpty()) return null
            return WornFace(pkg, parts[1].trim(), parts[2].trim() == "1")
        }
    }
}

/**
 * What the app should say about the face on the wrist.
 *
 * ## Why this is a type and not an `if` in a screen
 *
 * Every branch here was only ever reachable with a watch in range, which is the
 * same reason `InstallPlan` exists. Written as conditions inside a composable
 * they would be untestable and would get a second, slightly different copy the
 * first time another screen needed the same answer.
 */
sealed interface WornFacePlan {

    /**
     * The watch is wearing a design this phone still has.
     *
     * The operator's words were "if it matches it should just update the
     * existing one" — so this is the reconcile case, and it must never add a
     * second library entry for a face already in it.
     */
    data class Saved(val worn: WornFace, val saved: FaceLibrary.StoredFace) : WornFacePlan

    /**
     * The watch is wearing one of ours that this phone has no record of.
     *
     * The reinstall case, and the one worth being careful about. The app knows
     * the slug and nothing else: no parameters, and no trustworthy name, because
     * [FaceLibrary.slugify] cannot be run backwards. Operator decision on
     * 2026-10-03: show it and say plainly it is not saved here, rather than
     * manufacture a library entry that cannot be opened, edited or re-sent.
     */
    data class NotSaved(val worn: WornFace) : WornFacePlan

    /** The watch answered, and is wearing none of this app's faces. */
    data object NoneOfOurs : WornFacePlan

    /**
     * The watch did not answer.
     *
     * Distinct from [NoneOfOurs] on purpose, and the distinction is the whole
     * point: a watch out of range, asleep, or running a build that predates
     * this must not be reported as "you are wearing nothing". Same rule as
     * `WatchReadiness`, where silence is not a refusal.
     */
    data object NoAnswer : WornFacePlan

    companion object {

        /**
         * Decide, from what the watch said and what the phone has saved.
         *
         * [worn] is null when the watch did not answer; an answer naming no
         * face is [WornFace] with a package that is not ours, which resolves to
         * a null slug and so to [NoneOfOurs].
         */
        fun of(worn: WornFace?, saved: List<FaceLibrary.StoredFace>): WornFacePlan {
            if (worn == null) return NoAnswer
            val slug = worn.slug ?: return NoneOfOurs
            val match = saved.firstOrNull { it.slug == slug } ?: return NotSaved(worn)
            return Saved(worn, match)
        }

        /**
         * The same decision, from the RAW reply, which has three empty cases
         * and not two.
         *
         * - `null` — the watch said nothing. Out of range, asleep, or a build
         *   that predates this path. [NoAnswer].
         * - blank — the watch answered and is wearing none of ours. A fact.
         *   [NoneOfOurs].
         * - unparseable — it answered with something we cannot read. [NoAnswer],
         *   because the one thing it must not become is a confident claim about
         *   an empty wrist.
         *
         * Those first two collapse into one if you route a blank payload
         * through [WornFace.decode], which returns null for it. Doing so would
         * tell somebody their watch is bare while they are looking at the face
         * on it. This is the same shape of mistake [PushAvailability.decode]
         * made by turning an unreadable reply into a refusal, and it is here as
         * a function rather than as three lines in a screen so that it can be
         * tested at all.
         */
        fun fromReply(reply: String?, saved: List<FaceLibrary.StoredFace>): WornFacePlan {
            if (reply == null) return NoAnswer
            if (reply.isBlank()) return NoneOfOurs
            return of(WornFace.decode(reply), saved)
        }
    }
}
