package com.bfg.watchfaces.mobile

import android.content.Context
import java.io.File

/**
 * A setting that changes what a BUILT face shows, for diagnosing the wrist.
 *
 * ## Why this is not a debug build
 *
 * The obvious home for this is `BuildConfig.DEBUG`, and it does not work. The
 * builds people actually load — including the operator's, from the internal
 * testing track — are RELEASE builds. A debug-only diagnostic is a diagnostic
 * that is never present when the thing being diagnosed happens.
 *
 * ## What it turns on
 *
 * [com.bfg.watchfaces.generator.DialParams.debugRanged]: a ranged complication
 * renders its raw value, minimum and maximum instead of its reading. It exists
 * because a progress bar's fill is a Watch Face Format expression, and WFF
 * validates every expression that is a string — so an empty bar cannot be told
 * apart from a provider sending a useless range anywhere except on a watch.
 *
 * ## Why it lives here and not in Studio
 *
 * It is not a design choice and it must not read like one. A person browsing
 * the controls should never meet it; someone being walked through a problem can
 * be told where it is. Same reason it is deliberately not stored in the face —
 * see `FaceCodec`, which does not know the field, and the test that pins that.
 *
 * Off by default, and a face built with it on is not saved differently. Turning
 * it off and sending again restores the normal reading.
 */
object Diagnostics {

    private const val FILE = "diagnostics-ranged"

    /** Whether a face built right now should carry the ranged readout. */
    fun rangedReadout(context: Context): Boolean =
        runCatching { File(context.filesDir, FILE).exists() }.getOrDefault(false)

    /**
     * Turn it on or off.
     *
     * A file's existence rather than its contents: there is no partially
     * written state to read back, and a failed write leaves the previous answer
     * rather than an ambiguous one.
     */
    fun setRangedReadout(context: Context, on: Boolean) {
        runCatching {
            val f = File(context.filesDir, FILE)
            if (on) f.writeText("1") else f.delete()
        }
    }

    /**
     * Everything the watch last told us about its complication providers, as
     * text somebody can paste into a message.
     *
     * The DECLARED types are the point. This app keeps two booleans per
     * provider — can it fill a short text slot, can it fill a ranged one — and
     * those are an interpretation of a metadata string. The interpretation is
     * the part that can be wrong: a type this parser does not recognise reads
     * as absent rather than as unknown. The raw string settles it.
     *
     * It also settles a guess carried in `ComplicationSource.ranged`, which
     * marks the system providers whose range is assumed certain and was written
     * without any way to check.
     */
    fun providerReport(context: Context): String {
        val providers = ProviderCache.load(context)
        if (providers.isEmpty()) {
            return "No complication sources have been reported by the watch yet. " +
                "Open a complication slot with the watch nearby, which is what asks it."
        }
        return buildString {
            append("${providers.size} complication sources reported by the watch\n")
            append("(component | short text | ranged | declared)\n\n")
            for (p in providers.sortedWith(compareBy({ it.app.lowercase() }, { it.label.lowercase() }))) {
                append("${p.app} — ${p.label}\n")
                append("  ${p.component}\n")
                append("  shortText=${p.shortText} ranged=${p.ranged}\n")
                // Empty means the provider declared nothing at all, which is a
                // different answer from "declared something we did not parse".
                append("  declared=${p.declaredTypes.ifBlank { "(nothing)" }}\n\n")
            }
        }
    }
}
