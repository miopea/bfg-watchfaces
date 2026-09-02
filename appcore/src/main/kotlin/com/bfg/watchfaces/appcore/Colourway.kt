package com.bfg.watchfaces.appcore

import com.bfg.watchfaces.generator.AmbientPalette
import com.bfg.watchfaces.generator.DialParams

/**
 * A dial colour and an ink colour that were chosen together.
 *
 * ## Why pairs rather than two free choices
 *
 * The app has always offered a dial swatch and an ink swatch separately, which
 * is maximum freedom and the easiest possible way to make an unreadable watch:
 * most people do not pick two colours with enough contrast between them, and the
 * failure is not obvious on a phone screen indoors.
 *
 * A curated pair cannot be got wrong, and it multiplies rather than adds. Eight
 * engines against these is dozens of looks from a few lines of data — which is
 * the best ratio available to a project that has decided, deliberately, not to
 * compete on library size.
 *
 * ## Not stored on a face
 *
 * A face stores `dialColor` and `inkColor`, exactly as it always has. A
 * colourway is a way of SETTING those two, not a third thing to serialize — so
 * this adds no field, needs no `generatorVersion` bump, and a face made before
 * it existed is unchanged. [matching] recovers which one a face is wearing, if
 * any.
 *
 * ## The floor is enforced, not trusted
 *
 * Every pair here clears [MIN_CONTRAST], asserted by `ColourwayTest` using the
 * same luminance maths the ambient palette uses. Curation is only worth
 * something if it is checked; otherwise it is my eye, indoors, on a monitor.
 */
enum class Colourway(val label: String, val dial: String, val ink: String) {
    TAUPE("Taupe", "#7D7369", "#FCF9F1"),
    GRAPHITE("Graphite", "#2B2E33", "#ECEAE5"),
    STEEL("Steel", "#6E7378", "#FCFCFA"),
    NOIR("Noir", "#23262B", "#E8E6E1"),
    OLIVE("Olive", "#3E4A3F", "#EDEFE6"),
    OXBLOOD("Oxblood", "#5C2F2C", "#F2E7E1"),
    MIDNIGHT("Midnight", "#1E2A38", "#DCE6F2"),
    /** The one light dial. A dark ink on a pale face, which no other pair here is. */
    BONE("Bone", "#C9C3B6", "#26282B");

    fun applyTo(p: DialParams): DialParams = p.copy(dialColor = dial, inkColor = ink)

    companion object {
        /**
         * The WCAG floor for large text, and the time is large text.
         *
         * 3:1 rather than 4.5:1 because the numerals are enormous — the ratio
         * that matters for body copy is stricter than a watch face needs, and
         * holding a dial to it would rule out every mid-tone ground and leave
         * only black or white faces.
         */
        const val MIN_CONTRAST = 3.0

        /** Which colourway a face is wearing, or null when the colours are its own. */
        fun matching(p: DialParams): Colourway? = entries.firstOrNull {
            it.dial.equals(p.dialColor, ignoreCase = true) &&
                it.ink.equals(p.inkColor, ignoreCase = true)
        }

        /**
         * Contrast between two colours, as a ratio from 1 to 21.
         *
         * The standard formula, using the luminance the ambient palette already
         * computes — one definition of how bright a colour is, rather than a
         * second one that could disagree with the ambient floor.
         */
        fun contrast(a: String, b: String): Double {
            val la = AmbientPalette.relativeLuminance(a)
            val lb = AmbientPalette.relativeLuminance(b)
            val hi = maxOf(la, lb)
            val lo = minOf(la, lb)
            return (hi + 0.05) / (lo + 0.05)
        }
    }
}
