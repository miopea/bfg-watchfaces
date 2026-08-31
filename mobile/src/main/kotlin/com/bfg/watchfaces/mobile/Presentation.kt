package com.bfg.watchfaces.mobile

import com.bfg.watchfaces.generator.ComplicationSource
import com.bfg.watchfaces.generator.DialParams
import com.bfg.watchfaces.generator.Engine
import com.bfg.watchfaces.generator.Layout
import com.bfg.watchfaces.generator.SlotPosition

/**
 * What things are CALLED and what order they appear in.
 *
 * `CLAUDE.md` draws this line and it is worth restating: which controls exist
 * and what they tolerate comes from `ControlInventory` in `:generator`; labels
 * and the curated order are presentation and stay in the front end. Both front
 * ends therefore agree on substance and are free to differ on wording.
 *
 * Every string here is ported from the localhost app rather than invented,
 * because `DECISIONS.md` 2026-08-28 makes that app the exact specification for
 * this one. Where the two disagree, the demo is right and this is a bug.
 */
object Presentation {

    /**
     * The engine order a person sees. NOT `Engine.entries`.
     *
     * The enum happens to start at LATTICE, and building the chips from it
     * opened the app on Lattice instead of Knotwork — caught by driving the app
     * on 2026-08-28, not by any test, because nothing was wrong with the code.
     * The first chip is a front-page decision and it belongs here.
     */
    val ENGINE_ORDER: List<Engine> = listOf(
        Engine.KNOTWORK, Engine.BOTANICAL, Engine.CLOUS, Engine.ROSETTE,
        Engine.BARLEYCORN, Engine.SUNBURST, Engine.LATTICE,
        // Generated surfaces. Unlike "Your image" these are parameters, so a
        // face using one can still be shared to the community.
        Engine.GRAIN, Engine.BRUSHED, Engine.CARBON, Engine.LINEN,
        Engine.TEXTURE, Engine.NONE
    )

    fun label(engine: Engine): String = when (engine) {
        Engine.KNOTWORK -> "Knotwork"
        Engine.BOTANICAL -> "Botanical"
        Engine.CLOUS -> "Clous de Paris"
        Engine.ROSETTE -> "Rosette"
        Engine.BARLEYCORN -> "Barleycorn"
        Engine.SUNBURST -> "Sunburst"
        Engine.LATTICE -> "Lattice"
        Engine.GRAIN -> "Grain"
        Engine.BRUSHED -> "Brushed metal"
        Engine.CARBON -> "Carbon"
        Engine.LINEN -> "Linen"
        Engine.TEXTURE -> "Your image"
        Engine.NONE -> "Plain"
    }

    /**
     * Slider names are what someone SEES change, not what the parameter is
     * called. "Vignette" and "Relief" are the engraver's words for it; nobody
     * wearing the watch has to learn them to darken an edge or deepen a cut.
     */
    fun label(controlId: String): String = when (controlId) {
        "scale" -> "Pattern size"
        "depth" -> "Depth"
        "freq" -> "Variation"
        "stroke" -> "Line width"
        "relief" -> "Engraving"
        "rotate" -> "Angle"
        "contrast" -> "Contrast"
        "sheen" -> "Sheen"
        "vignette" -> "Edge shading"
        "timeSize" -> "Clock size"
        "timeY" -> "Clock position"
        "complicationSpread" -> "Spacing"
        "complicationY" -> "Middle row"
        "dateY" -> "Top slot"
        "dateSize" -> "Date size limit"
        "batteryY" -> "Bottom slot"
        else -> controlId
    }

    /**
     * Source labels are NOT here either, for the same reason: they are
     * [Complications.label], which is also what gets written into the built
     * face's `strings.xml` as each slot's `displayName`. A second table here
     * would let the app call a slot one thing and the WATCH call it another.
     */

    /**
     * What a slot shows in the preview.
     *
     * Stand-in values, not live data. Their job is to answer "does the layout
     * survive a number of about this width" — a five-digit step count is the
     * case that breaks a narrow slot, so the sample is five digits.
     */
    /**
     * Sample values are NOT here. They are [Complications.sample], in :appcore.
     *
     * There used to be a second copy in this file, and it had already drifted:
     * `DAY_AND_DATE` was "TUE MAR 10" here and "MAR 10" there, `TIME_AND_DATE`
     * "10:10 TUE" against "10:10". So the phone's preview and the workbench's
     * drew DIFFERENT WIDTHS for the same slot -- which is the exact class of bug
     * `SlotGeometry` was created to end, reappearing one layer up in the words
     * rather than the boxes.
     *
     * Sample strings decide whether a value fits its box. Two answers to that is
     * two previews, and only one of them can match the watch.
     */

    /**
     * The handful nearly everyone wants, in the order they want them.
     *
     * The full list is fourteen system sources plus what we draw, and will grow
     * again once the watch reports its installed providers -- 37 of them on a
     * bare emulator. Nine in ten people want one of these six, and a flat
     * alphabetical list would put Battery a long scroll from Steps.
     *
     * Curation is presentation, so it lives here and not in :generator.
     */
    val PICKER_COMMON: List<ComplicationSource> = listOf(
        ComplicationSource.NONE,
        ComplicationSource.DAY_AND_DATE,
        ComplicationSource.STEP_COUNT,
        ComplicationSource.HEART_RATE,
        ComplicationSource.WEATHER_TEMPERATURE,
        ComplicationSource.WATCH_BATTERY
    )

    /** Everything else, in the enum's own order. */
    /** The tappable shortcuts, grouped so they read as buttons not readings. */
    val PICKER_SHORTCUTS: List<ComplicationSource> =
        ComplicationSource.entries.filter { it.isShortcut }

    val PICKER_REST: List<ComplicationSource> =
        ComplicationSource.entries.filter { it !in PICKER_COMMON && !it.isShortcut }

    fun label(pos: SlotPosition): String = when (pos) {
        SlotPosition.TOP -> "Top"
        SlotPosition.LEFT -> "Left"
        SlotPosition.MIDDLE -> "Middle"
        SlotPosition.RIGHT -> "Right"
        SlotPosition.BOTTOM -> "Bottom"
    }

    /** The swatches, exactly as the localhost app offers them. */
    val DIALS = listOf(
        "#7D7369", "#2B2E33", "#23262B", "#6E6A66", "#8A7343",
        "#5A6B77", "#3E4A3F", "#7A4A3C", "#C9C3B6"
    )
    val INKS = listOf("#FCF9F1", "#FFFFFF", "#E8E6E1", "#C9A227", "#1A1A1A")

    /**
     * Where the studio opens.
     *
     * NOT a default face. `CLAUDE.md` is explicit that a hardcoded face identity
     * is what "Silver Sand" was and why it went away — this has no name, no
     * slug and no package. It is the starting point of an editor, the same one
     * the localhost app opens on, and it stops being relevant the moment
     * somebody moves a slider.
     *
     * `DialParams()`'s own defaults are the FORMAT's defaults, which is a
     * different job: they open on Botanical, and the app opening on a different
     * style from the first chip in the list is the regression caught on
     * 2026-08-28.
     */
    val STARTING_FACE: DialParams = DialParams(
        engine = Engine.KNOTWORK,
        scale = 26.0, depth = 3.0, freq = 7, stroke = 1.05, relief = 1.5,
        contrast = 36.0, rotate = 45.0, vignette = 20.0, sheen = 28.0,
        dialColor = "#7D7369", inkColor = "#FCF9F1",
        layout = Layout(
            dateY = 99, timeY = 196, timeSize = 104,
            complicationY = 273, complicationSpread = 92, batteryY = 344
        )
    )

    /** Which sliders belong to the pattern, and which to the layout. */
    const val SECTION_PATTERN = "Pattern"
    const val SECTION_LAYOUT = "Layout"
}
