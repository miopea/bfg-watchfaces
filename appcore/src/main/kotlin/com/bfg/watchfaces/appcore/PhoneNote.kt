package com.bfg.watchfaces.appcore

import java.io.File

/**
 * A short line of text set on the phone and shown on the watch.
 *
 * ## Why this exists at all
 *
 * Everything else about a face is baked into an APK: colours, layout, which
 * complication sits where. Changing any of it means rebuilding the face and
 * sending it over Bluetooth, which costs one of the watch's finite
 * `addWatchFace` calls and takes the better part of a minute.
 *
 * A COMPLICATION is different. The face names a provider and the watch asks it
 * for a value whenever it likes, so anything delivered that way can change
 * without the face changing at all. Google's own Watch Face Push guidance
 * points at exactly this:
 *
 * > The examples favor companion phone apps using `WearableListenerService` and
 * > the Data Layer to send information to watches, then triggering updates via
 * > `ComplicationDataSourceUpdateRequester`.
 *
 * So this is the first thing in the app that a person can change and see on
 * their wrist **without a rebuild, without a send, and without spending a slot**.
 *
 * ## Why the text lives here rather than in either app
 *
 * Both sides need the same rules — how long it may be, what an empty one means,
 * what happens to a newline — and a second copy of those is how the phone and
 * the watch end up disagreeing about what was typed. Same reason [FaceLibrary]
 * and [ActivationConsent] are here.
 */
object PhoneNote {

    /**
     * The longest note that is still a complication rather than a paragraph.
     *
     * A `SHORT_TEXT` complication renders in a slot a few characters wide, and
     * the watch truncates past that anyway. Refusing the rest HERE means the
     * person sees the limit while typing rather than discovering it on their
     * wrist, and it keeps what is stored equal to what is shown.
     */
    const val MAX_LENGTH = 20

    private fun file(root: File): File = File(root, "phone-note.txt")

    /**
     * Tidy a note into the one line a complication can hold.
     *
     * Newlines and runs of whitespace collapse to single spaces rather than
     * being rejected: somebody pasting two lines meant the words, not the break,
     * and a complication has nowhere to put a break.
     */
    fun clean(raw: String): String =
        raw.replace(Regex("\\s+"), " ").trim().take(MAX_LENGTH)

    /**
     * Store a note. An empty one REMOVES it rather than storing emptiness.
     *
     * A slot showing nothing and a slot showing a blank are different things on
     * a watch: the first falls back to whatever the face would otherwise draw,
     * the second is a gap where a value should be.
     */
    fun save(root: File, raw: String): String {
        val text = clean(raw)
        val f = file(root)
        if (text.isEmpty()) {
            f.delete()
            return ""
        }
        f.writeText(text)
        return text
    }

    /** The stored note, or empty when there is none. Never throws. */
    fun load(root: File): String =
        runCatching { file(root).takeIf { it.isFile }?.readText().orEmpty() }
            .getOrDefault("")
            .let { clean(it) }

    fun has(root: File): Boolean = load(root).isNotEmpty()

    /**
     * What the watch shows when there is no note.
     *
     * Deliberately not blank. A complication that renders nothing looks like a
     * provider that failed, and somebody who has just chosen this source needs
     * to see that it is working before they have typed anything.
     */
    const val EMPTY_PLACEHOLDER = "—"
}
