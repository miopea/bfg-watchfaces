package com.bfg.watchfaces.generator

/**
 * A point in dial space. Dial space is always 456x456 with the origin at the
 * top-left, matching the WFF canvas. Renderers scale from here.
 */
data class Pt(val x: Double, val y: Double)

/** An open polyline. Engines emit these; renderers stroke them. */
typealias Polyline = List<Pt>

/**
 * TEXTURE is the odd one out and deliberately so: it emits NO geometry. The
 * dial comes from an image the user supplied, composited by the renderer.
 *
 * That makes a TEXTURE face un-shareable. docs/SPEC.md's catalog is
 * parametric-only -- both because a face has to stay ~5KB of JSON and because
 * parameters are the IP shield: you cannot encode someone's logo as "knotwork,
 * scale 26, pewter", but you certainly can upload it. The SPEC already carves
 * out exactly this case: "Users import their own photos locally; those never
 * enter the shared catalog."
 */
enum class Engine {
    LATTICE, CLOUS, ROSETTE, BARLEYCORN, SUNBURST, BOTANICAL, KNOTWORK,
    // Generated surfaces. Like TEXTURE they emit no geometry, but unlike
    // TEXTURE they are parameters, so a face using one CAN be shared to the
    // catalog. See TextureField.
    GRAIN, BRUSHED, CARBON, LINEN,
    TEXTURE, NONE
}

/**
 * What a complication slot shows.
 *
 * [wff] is the WFF `defaultSystemProvider` token, and these are EXACTLY the
 * values the schema's `defaultProviderListType` enumerates -- they are not a
 * guess and not a superset. An unlisted provider fails schema validation, which
 * means the face installs and then never appears in the carousel.
 *
 * Presentation (labels, sample values for a preview) deliberately lives in the
 * workbench, not here. :generator defines the stored format; what a slot looks
 * like on screen is a renderer's problem.
 */
enum class ComplicationSource(val wff: String?) {
    NONE(null),
    STEP_COUNT("STEP_COUNT"),
    HEART_RATE("HEART_RATE"),
    DAY_AND_DATE("DAY_AND_DATE"),
    DATE("DATE"),
    DAY_OF_WEEK("DAY_OF_WEEK"),
    WATCH_BATTERY("WATCH_BATTERY"),
    WORLD_CLOCK("WORLD_CLOCK"),
    NEXT_EVENT("NEXT_EVENT"),
    SUNRISE_SUNSET("SUNRISE_SUNSET"),
    UNREAD_NOTIFICATION_COUNT("UNREAD_NOTIFICATION_COUNT"),
    APP_SHORTCUT("APP_SHORTCUT"),
    FAVORITE_CONTACT("FAVORITE_CONTACT");

    val enabled: Boolean get() = wff != null
}

/**
 * Where a complication sits on the dial.
 *
 * Five positions, and the ORDER of this enum is the order
 * [DialParams.complications] is stored in -- adding one in the middle would
 * re-map every stored face, so append only.
 *
 * TOP and BOTTOM used to be hardcoded PartText elements: the date and the
 * battery percentage. They were not configurable and could not be turned off,
 * which meant the face had five information areas but only advertised three.
 * They are ordinary slots now.
 */
enum class SlotPosition { TOP, LEFT, MIDDLE, RIGHT, BOTTOM }

/**
 * Everything needed to reproduce a dial.
 *
 * IMPORTANT - [generatorVersion] is load-bearing.
 *
 * Community faces are distributed as these parameters, not as rendered images.
 * That means this class IS the file format, and the engine code IS the renderer
 * for that format. If an engine's output changes, every face pinned to the old
 * version renders differently than its author intended.
 *
 * So: never change an engine's geometry in place. Add a new branch keyed on
 * [generatorVersion] and leave the old path alone. See [PatternEngines.paths].
 * GeneratorVersionTest fails if CURRENT changes without golden updates.
 */
data class DialParams(
    val generatorVersion: Int = CURRENT_GENERATOR_VERSION,

    val engine: Engine = Engine.BOTANICAL,
    val scale: Double = 40.0,
    val depth: Double = 5.0,
    val freq: Int = 7,
    val stroke: Double = 1.2,
    val relief: Double = 1.4,
    val contrast: Double = 30.0,
    val rotate: Double = 45.0,
    val vignette: Double = 18.0,

    val dialColor: String = "#7D7369",
    val inkColor: String = "#FCF9F1",
    val sheen: Double = 30.0,

    /**
     * Id of an imported image, used only by [Engine.TEXTURE]. Empty means none,
     * and TEXTURE with no image falls back to a plain dial rather than failing.
     *
     * This is a LOCAL reference, not content: the bytes live outside the face.
     * A face carrying one cannot be shared, which [Engine.TEXTURE] documents.
     */
    val texture: String = "",

    /** Draw the pattern OVER the numerals rather than behind them. */
    val lens: Boolean = true,
    val lensAmount: Double = 38.0,

    val layout: Layout = Layout(),

    /**
     * The complication slots, left to right. Slots set to
     * [ComplicationSource.NONE] are not emitted at all -- an empty slot still
     * costs a tap target and a frame budget on the watch, so it is omitted
     * rather than rendered blank. The enabled ones are re-centred, so turning
     * one off closes the gap instead of leaving a hole.
     */
    val complications: List<ComplicationSource> = listOf(
        ComplicationSource.DAY_AND_DATE,               // TOP
        ComplicationSource.STEP_COUNT,                 // LEFT
        ComplicationSource.HEART_RATE,                 // MIDDLE
        ComplicationSource.UNREAD_NOTIFICATION_COUNT,  // RIGHT
        ComplicationSource.WATCH_BATTERY               // BOTTOM
    )
) {
    init {
        require(generatorVersion in 1..CURRENT_GENERATOR_VERSION) {
            "unknown generatorVersion=$generatorVersion (this build supports up to $CURRENT_GENERATOR_VERSION)"
        }
        require(scale >= 4.0) { "scale must be >= 4" }
        require(HEX.matches(dialColor)) { "dialColor must be #RRGGBB, got $dialColor" }
        require(HEX.matches(inkColor)) { "inkColor must be #RRGGBB, got $inkColor" }
    }

    /** True when this face depends on a local image and cannot enter the catalog. */
    val isLocalOnly: Boolean get() = engine == Engine.TEXTURE && texture.isNotBlank()

    /** The source at [pos], or NONE when the stored list is short/absent. */
    fun slot(pos: SlotPosition): ComplicationSource =
        complications.getOrElse(pos.ordinal) { ComplicationSource.NONE }

    companion object {
        private val HEX = Regex("^#[0-9A-Fa-f]{6}$")
    }
}

data class Layout(
    /** Y of the TOP complication slot. Was the fixed date line. */
    val dateY: Int = 99,
    val dateSize: Int = 21,
    val timeY: Int = 196,
    val timeSize: Int = 104,
    val tracking: Double = 0.0,
    val complicationY: Int = 273,
    val complicationSpread: Int = 92,
    val complicationSize: Int = 19,
    /** Y of the BOTTOM complication slot. Was the fixed battery line. */
    val batteryY: Int = 344,
    val fontFamily: String = "SYNC_TO_DEVICE",
    val fontWeight: String = "MEDIUM"
)

/**
 * Bump ONLY when adding an engine or a parameter. Never when changing geometry.
 *
 * v5 (2026-08-28) makes complicationSpread drive VERTICAL spacing as well as
 * horizontal, so one control loosens the whole layout. No engine changed:
 * PatternEngines.v5 delegates to v4.
 *
 * v4 (2026-08-28) added the generated-surface engines GRAIN, BRUSHED, CARBON and
 * LINEN. No existing engine changed: PatternEngines.v4 delegates to v3.
 *
 * v3 (2026-08-28) makes the AMBIENT ink colour readable on a black screen (see
 * [AmbientPalette]). No geometry changed: PatternEngines.v3 delegates to v2
 * wholesale. It is a version bump because it changes what a STORED face renders
 * as in ambient, which is exactly what this number protects against.
 *
 * v2 (2026-08-27) added [Engine.KNOTWORK]. Every other engine is UNCHANGED --
 * PatternEngines.v2 delegates to v1 for them rather than copying the code, so
 * they cannot drift. A face stored with generatorVersion=1 still renders through
 * the v1 branch, byte for byte.
 */
const val CURRENT_GENERATOR_VERSION = 5

/** WFF canvas. Correct for Pixel Watch 4 and 5, both case sizes. */
const val DIAL_SIZE = 456
const val DIAL_RADIUS = DIAL_SIZE / 2.0
const val DIAL_CENTER = DIAL_SIZE / 2.0
