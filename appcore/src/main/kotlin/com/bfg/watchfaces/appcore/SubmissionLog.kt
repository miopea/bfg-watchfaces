package com.bfg.watchfaces.appcore

import java.io.File
import java.time.Instant

/**
 * What this device has sent to the community catalog.
 *
 * ## Why the device has to remember at all
 *
 * The catalog is PRE-moderated: a submitted face is invisible until a person
 * publishes it, and that can be weeks. Without a local record the app can only
 * say "sent" and then forget, so the author has no way to ask what happened,
 * no way to withdraw, and — worst — no way to tell whether they already sent
 * this face. They would submit it again, and the queue would fill with
 * duplicates from people being reasonable.
 *
 * The record is a POINTER, not a copy: a submission id and the local slug it
 * came from. The face itself already lives in [FaceLibrary]; storing it twice
 * would give the two copies a chance to disagree.
 *
 * ## Why this is not authoritative
 *
 * The service decides what state a submission is in; this only remembers that
 * one exists and what it was last seen to be. [state] is therefore a CACHE,
 * refreshed from `CatalogService.submissionState`, and the UI must treat a
 * stale value as stale rather than as truth. The alternative — asking the
 * service for every row on every render — is a request per face per screen
 * open, for information that changes about once a week.
 *
 * In `:appcore` rather than the phone app because the rule about what a
 * submission IS belongs with the rule about what a face is, and because a pure
 * JVM object can be tested without an emulator. The phone supplies only the
 * directory; see `SubmissionStore`.
 */
object SubmissionLog {

    /**
     * The states the catalog can report, plus the one this file adds.
     *
     * [PENDING] through [REMOVED] are the service's own words. [WITHDRAWN] is
     * ours: it is what the author did, and the service answers 404 afterwards
     * rather than describing it, so nothing else would remember why the face
     * stopped being in the gallery.
     */
    enum class State(val wire: String) {
        PENDING("pending"),
        PUBLISHED("published"),
        REJECTED("rejected"),
        REMOVED("removed"),
        WITHDRAWN("withdrawn");

        companion object {
            /**
             * Unknown wire values become [PENDING], deliberately.
             *
             * A state this build has never heard of means the service learned a
             * new one, and the safe reading of "something is happening to your
             * face that I cannot name" is that it is still in the queue — not
             * that it was rejected, which is the reading that would make the app
             * tell someone bad news that never happened.
             */
            fun of(wire: String): State =
                entries.firstOrNull { it.wire == wire.trim().lowercase() } ?: PENDING
        }
    }

    data class Record(
        /** The local face this was made from — the key into [FaceLibrary]. */
        val slug: String,
        /** The catalog's id for the submission. The only handle for withdrawing. */
        val id: String,
        val submitted: String,
        val state: State
    ) {
        val settled: Boolean get() = state != State.PENDING
    }

    /**
     * What a person is told about a submission, in words they would use.
     *
     * Here rather than in the phone's share sheet because it is a RULE about
     * the states this file defines, and because it decides what somebody is
     * told about work they made — which is exactly the kind of thing that
     * should not live in the one module with no tests. The face row and the
     * share sheet both read it, so the two cannot describe one state
     * differently.
     *
     * [State.REJECTED] deliberately does NOT say why. The service records a
     * reason for the moderator and there is no route to deliver it to the
     * author yet; `MODERATION.md` says that gap exists in as many words.
     * Inventing a reason here would be worse than admitting there is not one.
     */
    fun describe(state: State): String = when (state) {
        State.PENDING -> "Waiting for someone to check it. That can take a while."
        State.PUBLISHED -> "It is in the gallery for other people to find."
        State.REJECTED -> "It was not added to the gallery."
        State.REMOVED -> "It was taken out of the gallery."
        State.WITHDRAWN -> "You took this one back."
    }

    /**
     * The same fact as [describe], short enough to sit in a chip.
     *
     * ## Why both exist
     *
     * [describe] is a sentence, and a sentence rendered in the same small grey
     * as everything else around it reads as more metadata rather than as a
     * STATE. On the face row the shared line sat directly under the install
     * name in identical type, so a face waiting on moderation looked exactly
     * like a face that was not shared at all — the only other signal being a
     * button that says "Shared" rather than "Share", which you have to already
     * know to look for. The operator reported precisely that.
     *
     * So this is not a second vocabulary. It is the same five states said in
     * two lengths, in ONE function each, so the chip and the sentence cannot
     * disagree about what is happening. Whoever changes one is looking at the
     * other.
     *
     * Kept short deliberately: a chip that wraps is a worse chip than no chip.
     * Three words is the ceiling.
     */
    fun label(state: State): String = when (state) {
        State.PENDING -> "In review"
        State.PUBLISHED -> "In the gallery"
        State.REJECTED -> "Not added"
        State.REMOVED -> "Taken down"
        State.WITHDRAWN -> "You took it back"
    }

    private fun file(root: File): File = File(root, "submissions.json")

    /**
     * ONE file, not one per submission.
     *
     * [FaceLibrary] uses a file per face because a face is big, is edited
     * individually, and a corrupt one should cost you that face rather than the
     * library. A submission record is four short strings and is only ever read
     * as a whole list, so a directory of them would be the same data with more
     * ways to end up half-written.
     */
    fun list(root: File): List<Record> {
        val f = file(root)
        if (!f.isFile) return emptyList()
        val text = runCatching { f.readText() }.getOrNull() ?: return emptyList()
        return runCatching { parse(text) }.getOrElse {
            // A record of what you submitted is a convenience; a crash on
            // opening "My faces" is not. Unreadable means empty, and the next
            // write repairs it.
            emptyList()
        }
    }

    fun parse(text: String): List<Record> =
        Json.arr(Json.obj(Json.parse(text))["submissions"]).map { Json.obj(it) }.map {
            Record(
                slug = Json.str(it, "slug"),
                id = Json.str(it, "id"),
                submitted = Json.str(it, "submitted"),
                state = State.of(Json.str(it, "state", State.PENDING.wire))
            )
        }

    fun toJson(records: List<Record>): String =
        """{"submissions":[""" + records.joinToString(",") {
            """{"slug":${Json.quote(it.slug)},"id":${Json.quote(it.id)},""" +
                """"submitted":${Json.quote(it.submitted)},"state":${Json.quote(it.state.wire)}}"""
        } + "]}"

    fun forSlug(root: File, slug: String): Record? = list(root).firstOrNull { it.slug == slug }

    /**
     * Record a submission, replacing any earlier one for the same face.
     *
     * Replacing rather than appending: a face has one live submission at a
     * time. Somebody who withdrew and sent again should see the new one, and a
     * list that grew a row per attempt would turn "have I shared this?" back
     * into a question.
     */
    fun record(root: File, slug: String, id: String, now: Instant = Instant.now()): Record {
        val record = Record(slug, id, now.toString(), State.PENDING)
        write(root, list(root).filterNot { it.slug == slug } + record)
        return record
    }

    /** Update what the service last said about a submission. */
    fun setState(root: File, id: String, state: State) {
        val all = list(root)
        if (all.none { it.id == id }) return
        write(root, all.map { if (it.id == id) it.copy(state = state) else it })
    }

    /** Forget a submission entirely. For a withdrawal the author confirmed. */
    fun forget(root: File, id: String) {
        val all = list(root)
        if (all.none { it.id == id }) return
        write(root, all.filterNot { it.id == id })
    }

    private fun write(root: File, records: List<Record>) {
        // Written whole to a temp file and moved, so an interrupted write
        // cannot leave the one file holding every record half-parsed.
        val target = file(root)
        val tmp = File(root, "submissions.json.tmp")
        tmp.writeText(toJson(records))
        if (!tmp.renameTo(target)) {
            target.writeText(tmp.readText())
            tmp.delete()
        }
    }
}
