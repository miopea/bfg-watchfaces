package com.bfg.watchfaces.appcore

import com.bfg.watchfaces.generator.DialParams
import com.bfg.watchfaces.generator.Engine

/**
 * The starting library.
 *
 * No face is "the" face. There is no hardcoded default identity in this project
 * any more -- that is what "Silver Sand" was, and it went away on 2026-08-27.
 * These are STARTING POINTS: a design becomes a watch face when someone names
 * it, and the name they give is what the carousel shows.
 *
 * They live in :appcore because the phone's Designs screen and the workbench's
 * gallery have to offer the same ten. Kept in one list, a preset added for one
 * surface shows up on the other; kept in two, they drift and a face someone
 * started from cannot be found again.
 *
 * Order matters: the first entry is what the app opens on.
 */
object Presets {

    val ALL: LinkedHashMap<String, DialParams> = linkedMapOf(
        "Knotwork Taupe" to DialParams(
            engine = Engine.KNOTWORK, scale = 26.0, depth = 3.0, freq = 7, stroke = 1.05,
            relief = 1.5, contrast = 36.0, rotate = 45.0, vignette = 20.0,
            dialColor = "#7D7369", inkColor = "#FCF9F1", sheen = 28.0
        ),
        "Knotwork Graphite" to DialParams(
            engine = Engine.KNOTWORK, scale = 22.0, depth = 2.6, freq = 3, stroke = 1.0,
            relief = 1.4, contrast = 30.0, rotate = 45.0, vignette = 30.0,
            dialColor = "#2B2E33", inkColor = "#ECEAE5", sheen = 16.0
        ),
        "Brushed Steel" to DialParams(
            engine = Engine.BRUSHED, scale = 20.0, contrast = 34.0, relief = 2.2,
            rotate = 20.0, vignette = 22.0, sheen = 24.0,
            dialColor = "#6E7378", inkColor = "#FCFCFA"
        ),
        "Carbon Black" to DialParams(
            engine = Engine.CARBON, scale = 16.0, contrast = 30.0, relief = 1.8,
            rotate = 45.0, vignette = 30.0, sheen = 12.0,
            dialColor = "#26282B", inkColor = "#E8E6E1"
        ),
        "Botanical Sand" to DialParams(engine = Engine.BOTANICAL),
        "Clous de Paris" to DialParams(
            engine = Engine.CLOUS, scale = 22.0, depth = 4.0, contrast = 34.0,
            dialColor = "#6E6A66", inkColor = "#F5F2EC", rotate = 45.0, vignette = 22.0
        ),
        "Rosette Noir" to DialParams(
            engine = Engine.ROSETTE, scale = 16.0, depth = 7.0, freq = 11, contrast = 26.0,
            dialColor = "#23262B", inkColor = "#E8E6E1", sheen = 18.0, vignette = 30.0
        ),
        "Barleycorn Brass" to DialParams(
            engine = Engine.BARLEYCORN, scale = 26.0, depth = 5.5, freq = 5, contrast = 32.0,
            dialColor = "#8A7343", inkColor = "#FDF8E9", sheen = 38.0, rotate = 20.0
        ),
        "Sunburst Ice" to DialParams(
            engine = Engine.SUNBURST, scale = 9.0, depth = 6.0, freq = 3, contrast = 22.0,
            dialColor = "#5A6B77", inkColor = "#FFFFFF", sheen = 44.0, vignette = 26.0
        ),
        "Flat" to DialParams(engine = Engine.NONE, sheen = 22.0, vignette = 20.0)
    )

    /** What a surface with nothing chosen yet opens on. Never an identity, only a start. */
    val OPENING: DialParams get() = ALL.values.first()

    fun byName(name: String): DialParams? =
        ALL.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
