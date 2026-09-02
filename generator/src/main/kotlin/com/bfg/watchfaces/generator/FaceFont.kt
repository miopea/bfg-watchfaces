package com.bfg.watchfaces.generator

/**
 * The typefaces a face may ask for, and every one of them is on the watch.
 *
 * ## Why this list is short and not invented
 *
 * A Watch Face Format `<Font family="...">` is a free string, and the schema
 * cannot say which families exist — that is a property of the DEVICE. A name
 * the watch does not have does not fail: it silently falls back to the default
 * sans, which is a face that quietly ignores a control somebody used.
 *
 * So these were read off a Pixel Watch 5, 2026-09-02, from `/system/etc/fonts.xml`:
 *
 * ```text
 * sans-serif · sans-serif-condensed · sans-serif-light · sans-serif-medium
 * sans-serif-black · sans-serif-thin · sans-serif-smallcaps
 * serif · monospace · cursive · casual · roboto-flex
 * ```
 *
 * Only ones that look meaningfully DIFFERENT are offered. `sans-serif-light` and
 * `sans-serif-medium` are weights, and weight is already its own control — a
 * picker that quietly fights another control is worse than no picker.
 *
 * ## The bug this replaces
 *
 * Every face emitted `family="SYNC_TO_DEVICE"`. That is the value for
 * `hourFormat`, copied into the font attribute, and it is not a typeface. There
 * is no such family on any watch, so every face has been falling back to the
 * default sans since the beginning — the attribute was decoration.
 *
 * Nothing LOOKS different for a face that never changed it, because the fallback
 * and [SANS] resolve to the same typeface. That is why this needs no
 * `generatorVersion` branch: it corrects a name, not a rendering.
 */
enum class FaceFont(val label: String, val wff: String) {
    /** Roboto, the system default. What every face has actually been using. */
    SANS("Modern", "sans-serif"),

    /** Narrower. Buys real room when the time runs to five characters. */
    CONDENSED("Condensed", "sans-serif-condensed"),

    /** A genuine change of character rather than another grotesque. */
    SERIF("Serif", "serif"),

    /** Tabular and even; digits do not shuffle as they change. */
    MONO("Mono", "monospace"),

    /** Small caps, which reads as engraved rather than printed. */
    SMALLCAPS("Small caps", "sans-serif-smallcaps"),

    /** Informal, and the only one here that is not a working typeface. */
    CASUAL("Casual", "casual");

    companion object {
        /** What a face gets when it has never chosen, and what the old value meant. */
        val DEFAULT = SANS

        /**
         * Resolve a stored value, however old or unknown.
         *
         * `SYNC_TO_DEVICE` is the legacy value every existing face carries, and
         * it maps to [SANS] because that is the typeface it has always actually
         * rendered as. Anything unrecognised does the same rather than throwing:
         * a face written by a newer build must still open here, degraded, in the
         * same way an unknown submission state reads as pending.
         */
        fun of(stored: String): FaceFont =
            entries.firstOrNull { it.wff.equals(stored, ignoreCase = true) }
                ?: entries.firstOrNull { it.name.equals(stored, ignoreCase = true) }
                ?: DEFAULT
    }
}
