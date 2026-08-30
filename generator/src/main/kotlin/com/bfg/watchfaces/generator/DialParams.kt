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
 * The date line the FACE draws, as opposed to a date complication.
 *
 * A complication's wording belongs to whichever system provider fills it — pick
 * `DAY_AND_DATE` and the watch decides whether you get "Aug 29" or "Sat, 29
 * August", and no provider in the schema's list promises a particular shape.
 * Drawing it from Watch Face Format's own date sources is the only way to say
 * exactly what appears.
 *
 * It also frees the top complication slot for something else, and needs no icon
 * above it: a date reads as a date.
 */
enum class DateStyle(val label: String) {
    /** No drawn date. Use a complication in a slot instead, or nothing. */
    NONE("Off"),

    /** `29` */
    DAY("Day"),

    /** `AUG 29` */
    MONTH_DAY("Month and day"),

    /** `SAT AUG 29` */
    WEEKDAY_MONTH_DAY("Weekday, month and day"),

    /** `SATURDAY` */
    WEEKDAY("Weekday");

    /**
     * What this style reads as on a given day.
     *
     * Lives here, in `:generator`, because BOTH previews have to show what the
     * emitter will actually write. A preview that formats the date its own way
     * is a preview of a different watch face -- and the first version of this
     * feature shipped with no preview at all, so the control looked broken.
     *
     * Uppercase short forms, matching what the complication samples beside it
     * already draw. The watch supplies the real strings from WFF's own date
     * sources; this only has to agree about SHAPE.
     */
    fun sample(today: java.time.LocalDate = java.time.LocalDate.now()): String {
        val loc = java.util.Locale.getDefault()
        fun weekdayShort() = today.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, loc)
        fun weekdayFull() = today.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, loc)
        fun monthShort() = today.month.getDisplayName(java.time.format.TextStyle.SHORT, loc)
        return when (this) {
            NONE -> ""
            DAY -> "${today.dayOfMonth}"
            MONTH_DAY -> "${monthShort()} ${today.dayOfMonth}"
            WEEKDAY_MONTH_DAY -> "${weekdayShort()} ${monthShort()} ${today.dayOfMonth}"
            WEEKDAY -> weekdayFull()
        }.uppercase(loc)
    }
}

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

    /**
     * Show seconds while the watch is awake.
     *
     * Awake only, always: a ticking second on an always-on display is the most
     * expensive thing a watch face can draw, and Wear OS updates ambient at most
     * once a minute anyway, so an ambient seconds digit would simply be wrong
     * most of the time.
     *
     * Defaults to false so every face saved before this existed emits exactly
     * the XML it always did.
     */
    val showSeconds: Boolean = false,

    /**
     * Which slots draw the little icon above their value.
     *
     * Per slot rather than one switch for the face, because the reason to turn a
     * glyph off is usually about ONE complication: a date reads as a date
     * without a calendar above it, while a bare number badly wants the footprint
     * that says it is a step count. A single toggle forces that judgement on all
     * five at once.
     *
     * Every position by default, which is what every face emitted before this
     * existed did.
     */
    val iconSlots: Set<SlotPosition> = SlotPosition.entries.toSet(),

    /**
     * A date drawn by the face itself. See [DateStyle].
     *
     * Defaults to NONE so every face saved before this existed emits exactly the
     * XML it always did — the top slot's date complication is untouched.
     */
    val dateStyle: DateStyle = DateStyle.NONE,

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

    /**
     * The same face with one slot changed.
     *
     * Pads with NONE rather than failing on a short list, because [slot] already
     * treats a short list as "the rest are off" and the two have to agree — a
     * face stored with three complications must be editable in its fifth slot
     * without a UI first having to know that.
     */
    fun withSlot(pos: SlotPosition, source: ComplicationSource): DialParams {
        val next = ArrayList(complications)
        while (next.size <= pos.ordinal) next.add(ComplicationSource.NONE)
        next[pos.ordinal] = source
        return copy(complications = next)
    }

    companion object {
        private val HEX = Regex("^#[0-9A-Fa-f]{6}$")
    }
}

data class Layout(
    /** Y of the TOP complication slot. Was the fixed date line. */
    val dateY: Int = 99,
    /**
     * 30, not 21.
     *
     * 21 dates from when the only date was a COMPLICATION's label, sized to sit
     * under a glyph in a slot. The face's own drawn date is a headline sitting
     * against a 104pt clock, and at 21 it read as a caption -- "pretty tiny",
     * which is exactly what it looked like on a real phone.
     *
     * Changing the default does not touch a saved face: dateSize is stored per
     * face, so anything already designed keeps the size it was designed at.
     */
    val dateSize: Int = 30,
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
 * v6 (2026-08-30) reshapes the complication BOX, not the dial. A slot whose
 * glyph is off loses the icon's height and the offset that cleared it, and the
 * value's own box drops from 1.7x the slot size to 1.35x -- it was 1.85x the
 * font, half a line of air under every value. The vertical stack of top, clock,
 * row and bottom is what caps complication size, so that slack was being paid
 * for by the size control: "Large" was silently clamped from 28 to 25, which is
 * why it looked barely different from Medium. At v6 it fits. PatternEngines.v6
 * delegates to v5 -- no dial geometry changed.
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
const val CURRENT_GENERATOR_VERSION = 6

/** WFF canvas. Correct for Pixel Watch 4 and 5, both case sizes. */
const val DIAL_SIZE = 456
const val DIAL_RADIUS = DIAL_SIZE / 2.0
const val DIAL_CENTER = DIAL_SIZE / 2.0
