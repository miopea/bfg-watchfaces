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
/**
 * What can fill a slot.
 *
 * Two kinds live in one enum on purpose, because the operator asked for exactly
 * that: "anything custom we make gets put into the complications list". A
 * person choosing what goes in the top slot should not have to know whether the
 * watch supplies it or we draw it.
 *
 * - [wff] set: a Watch Face Format SYSTEM PROVIDER. The watch fills the slot.
 * - [drawn] set: a WFF DATA SOURCE we render ourselves. No provider is
 *   involved, and none exists -- weather is the case that forced this. Google's
 *   system provider list has fourteen members and no weather in it, but the
 *   format has `[WEATHER.TEMPERATURE]` as a first-class source, the same kind of
 *   thing as `[DAY]`.
 *
 * A drawn slot has no glyph. The icons come from
 * `[COMPLICATION.MONOCHROMATIC_IMAGE]`, which only exists inside a
 * `<Complication>`, and a drawn source has none -- so the value is centred in
 * its box instead, which [SlotGeometry.textOffset] already knows how to do.
 */
enum class ComplicationSource(
    val wff: String?,
    /**
     * Literal text around the value, with one `%s` per source.
     *
     * Used for a DRAWN source's own text and for a complication's, because the
     * need is the same: the provider hands over a bare number and the unit is
     * ours to add. The battery provider supplies "72" with no per cent sign and
     * no title -- measured on a watch, and asked about twice.
     *
     * Only safe because the face definition is authoritative from v8. While the
     * watch's editor could swap a slot's provider, a hardcoded "%%" could have
     * ended up after a step count.
     */
    val format: String = "%s",
    vararg val drawn: String,
    /**
     * What tapping this slot opens, as a WFF `Launch` target.
     *
     * A SHORTCUT slot has this and nothing else: no provider to read and no
     * source to draw, just a glyph you can press. Watch Face Format has had
     * `<Launch>` on any part all along — ALARM, MUSIC_PLAYER, SETTINGS, PHONE,
     * CALENDAR, MESSAGE and friends — and this app has never used it, which is
     * why a face here could show a step count and not start the timer.
     */
    val launch: String? = null
) {
    NONE(null),
    STEP_COUNT("STEP_COUNT"),
    HEART_RATE("HEART_RATE"),
    DAY_AND_DATE("DAY_AND_DATE"),
    // The one system provider this list used to omit. Google's
    // defaultProviderType has fourteen members; we offered twelve plus NONE,
    // and TIME_AND_DATE was simply missing rather than excluded for a reason.
    TIME_AND_DATE("TIME_AND_DATE"),
    DATE("DATE"),
    DAY_OF_WEEK("DAY_OF_WEEK"),
    WATCH_BATTERY("WATCH_BATTERY", "%s%%"),
    WORLD_CLOCK("WORLD_CLOCK"),
    NEXT_EVENT("NEXT_EVENT"),
    SUNRISE_SUNSET("SUNRISE_SUNSET"),
    UNREAD_NOTIFICATION_COUNT("UNREAD_NOTIFICATION_COUNT"),
    APP_SHORTCUT("APP_SHORTCUT"),
    FAVORITE_CONTACT("FAVORITE_CONTACT"),

    /**
     * Temperature and its unit, drawn by us.
     *
     * `[WEATHER.TEMPERATURE_UNIT]` is a separate source, so the two are
     * concatenated -- the format has no "72 degrees" source that includes it.
     */
    /**
     * The temperature, with a degree sign we write ourselves.
     *
     * NOT `[WEATHER.TEMPERATURE_UNIT]`. That source returns a numeric CODE, not
     * a symbol, so appending it rendered "782" on a watch: 78, then the unit's
     * enum value. A literal degree sign is right in either scale, and the scale
     * itself is the wearer's system setting rather than something a face should
     * be asserting.
     */
    WEATHER_TEMPERATURE(null, "%s°", "[WEATHER.TEMPERATURE]"),

    /** "Cloudy". The condition in words rather than a code. */
    WEATHER_CONDITION(null, "%s", "[WEATHER.CONDITION_NAME]"),

    /**
     * "72° Cloudy" — both, because a slot is wide enough for them.
     *
     * A slot box is around four characters of the value's own width, so a short
     * temperature and a one-word condition fit side by side. Offering it saves
     * spending two of five slots on the weather.
     */
    /**
     * "78° / 61°" — today's high and low.
     *
     * The DAY-INDEXED sources, not the bare `[WEATHER.TEMPERATURE_HIGH]`. That
     * bare form is in Google's enum, validates against their own XSD, and
     * renders NOTHING AT ALL on a watch — the whole face goes black. High and
     * low only exist per day, and day 0 is today.
     */
    WEATHER_HIGH_LOW(
        null, "%s° / %s°",
        "[WEATHER.DAYS.0.TEMPERATURE_HIGH]", "[WEATHER.DAYS.0.TEMPERATURE_LOW]"
    ),

    /** "30%" — how likely rain is. */
    WEATHER_RAIN(null, "%s%%", "[WEATHER.CHANCE_OF_PRECIPITATION]"),

    /**
     * "UV 6", for today.
     *
     * NOT `[WEATHER.WEATHER.UV_INDEX]`, which is what the enum lists — the
     * doubled prefix is a typo in Google's schema. It validates and it renders
     * nothing, taking the rest of the face with it. UV is day-indexed like the
     * high and low.
     */
    WEATHER_UV(null, "UV %s", "[WEATHER.DAYS.0.UV_INDEX]"),

    WEATHER_TEMP_CONDITION(
        null, "%s° %s",
        "[WEATHER.TEMPERATURE]", "[WEATHER.CONDITION_NAME]"
    ),

    // Shortcuts: a glyph you press, with nothing to read. The targets are
    // Watch Face Format's own system shortcut list.
    SHORTCUT_MUSIC(null, "%s", launch = "MUSIC_PLAYER"),
    SHORTCUT_ALARM(null, "%s", launch = "ALARM"),
    SHORTCUT_SETTINGS(null, "%s", launch = "SETTINGS"),
    SHORTCUT_PHONE(null, "%s", launch = "PHONE"),
    SHORTCUT_CALENDAR(null, "%s", launch = "CALENDAR"),
    SHORTCUT_MESSAGES(null, "%s", launch = "MESSAGE"),

    /**
     * A shortcut to a specific app on the watch.
     *
     * The target is per SLOT rather than per source -- `launchTargetType` is a
     * union with `xs:string`, so a ComponentName is a legal target and there is
     * one enum member for "some app" rather than one per app. Which app lives
     * in [DialParams.launchers], the same shape as [DialParams.providers].
     */
    SHORTCUT_APP(null, "%s");

    // isShortcut rather than `launch != null`: SHORTCUT_APP has no fixed
    // target -- the app is per slot -- so testing the field dropped it from
    // the layout entirely and the slot silently vanished.
    val enabled: Boolean get() = wff != null || drawn.isNotEmpty() || isShortcut

    /** True when this is rendered by the face rather than filled by the watch. */
    val isDrawn: Boolean get() = drawn.isNotEmpty()

    /**
     * Roughly how many characters this source's value runs to.
     *
     * Only drawn sources need it, and only so the text can be shrunk to fit its
     * box instead of being clipped. A complication's own text is the provider's
     * problem; ours is ours. "72° Cloudy" is ten characters against a box about
     * four and a bit wide, which is how "° Unknow" reached a watch.
     */
    val widestValue: Int
        get() = when (this) {
            WEATHER_TEMP_CONDITION -> 10
            WEATHER_HIGH_LOW -> 9
            WEATHER_UV -> 5
            WEATHER_RAIN -> 4
            WEATHER_CONDITION -> 7
            WEATHER_TEMPERATURE -> 4
            else -> 0
        }

    /**
     * A shorter way to say the same thing, when the box cannot hold the full
     * form.
     *
     * ## Shorten before shrinking
     *
     * Without this, a value too wide for its box was made SMALLER until it fit,
     * which is how "71° Cloudy" reached a wrist at 19pt beside neighbours at
     * 29 — reported as "almost impossible to read". Shrinking is the wrong
     * lever: the box is about four characters wide and the string is ten, so
     * the font has to come down by a third to buy room for a word you can get
     * by looking out of the window.
     *
     * The temperature is the part that is worth reading, so the condition is
     * what goes. The result renders at the same size as the number beside it,
     * which is the whole point.
     *
     * Null where there is nothing to drop: "Cloudy" is already one word, and a
     * percentage is already three characters.
     */
    val compact: CompactForm?
        get() = when (this) {
            WEATHER_TEMP_CONDITION ->
                CompactForm("%s°", listOf("[WEATHER.TEMPERATURE]"), 4, "72°")
            // "78° / 61°" to "78/61". The slash still separates them and the
            // degree signs are saying the same thing twice.
            WEATHER_HIGH_LOW -> CompactForm(
                "%s/%s",
                listOf("[WEATHER.DAYS.0.TEMPERATURE_HIGH]", "[WEATHER.DAYS.0.TEMPERATURE_LOW]"),
                7, "78/61"
            )
            else -> null
        }

    /** A glyph you press, with no value to read. */
    val isShortcut: Boolean
        get() = (launch != null || this == SHORTCUT_APP) && drawn.isEmpty() && wff == null
}

/**
 * The shortened rendering of a drawn value. See [ComplicationSource.compact].
 *
 * [sample] lives here rather than beside the full-length samples because the
 * two previews keep their own tables of those, and a shortened form defined
 * twice would be a shortened form that disagrees with itself.
 */
data class CompactForm(
    val format: String,
    val drawn: List<String>,
    val widestValue: Int,
    val sample: String
)

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
enum class SlotPosition {
    TOP, LEFT, MIDDLE, RIGHT, BOTTOM;

    /**
     * The string resource holding this slot's name in the built APK.
     *
     * Keyed by POSITION. It used to be keyed by the SOURCE in the slot
     * -- `slot_watch_battery` -- which is wrong twice over. `displayName` is
     * the name the WATCH'S OWN EDITOR shows for a slot, so it has to say where
     * the slot is, not what is in it right now; and two slots holding the same
     * source got the same name, which is indistinguishable to that editor.
     * Reported from a real watch as "the right complication is always the same
     * as the bottom one".
     */
    val resource: String get() = "slot_${name.lowercase()}"
}

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
/**
 * How large the drawn date is, relative to the size that matches the clock.
 *
 * The fitted size is what makes the date span the time's width, and that turned
 * out to be too big for at least one person and useful to someone who cannot
 * read the small one. So it is a SCALE of the fit, not a point size: whatever
 * the style and the clock are, Small is smaller and Large is larger by the same
 * proportion. A stored point size cannot do that — it was tried, and it was
 * right for one date style and wrong for the rest.
 */
/**
 * Whether the clock runs on 12 or 24 hours.
 *
 * [DEVICE] follows the watch's own setting, which is what a face should do
 * unless someone says otherwise — it is the only option that stays right when
 * a person travels or changes their mind in Settings.
 *
 * A 12-hour clock drops the leading zero. "06:10" is a 24-hour habit; on a
 * 12-hour face it reads as a mistake.
 */
/**
 * What the rim ring fills up with.
 *
 * Anything here has to be a PERCENTAGE, because the ring is a proportion of a
 * circle — Watch Face Format offers exactly three: steps against the day's
 * goal, the watch's battery, and the chance of rain. Heart rate is a number,
 * not a fraction of anything, so it is not on this list however often it gets
 * asked for.
 */
enum class RingSource(val label: String, val expression: String?) {
    NONE("Off", null),
    STEPS("Step goal", "[STEP_PERCENT]"),
    BATTERY("Watch battery", "[BATTERY_PERCENT]"),
    RAIN("Chance of rain", "[WEATHER.CHANCE_OF_PRECIPITATION]");

    val enabled: Boolean get() = expression != null
}

enum class HourFormat(val label: String, val wff: String, val pattern: String) {
    // "Automatic", not "Match my watch". Three segments share one row and this
    // was the only label needing two lines, so its button grew taller than the
    // other two and the control looked broken. Seen on a phone, not reasoned
    // about. The row is labelled "Time", which supplies the context the longer
    // wording was carrying.
    DEVICE("Automatic", "SYNC_TO_DEVICE", "hh:mm"),
    TWELVE("12-hour", "12", "h:mm"),
    TWENTY_FOUR("24-hour", "24", "hh:mm")
}

enum class DateScale(val label: String, val factor: Double) {
    SMALL("Small", 0.72),
    NORMAL("Normal", 1.0),
    LARGE("Large", 1.22)
}

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
     * NOT uppercased. The preview used to shout "SUN AUG 30" while the watch
     * drew "Sun Aug 30" from its own date sources -- a preview that disagrees
     * with the thing it is previewing. Seen side by side on an emulator.
     */
    /**
     * The WIDEST this style ever gets, for sizing.
     *
     * Wednesday, 30 September: the longest weekday and a long month with a
     * two-digit day. Sizing to today's date would make the face resize itself
     * on the 1st of the month, which nobody asked for and everybody would
     * notice.
     */
    fun widestSample(): String = sample(java.time.LocalDate.of(2026, 9, 30))

    companion object {
        /**
         * The day a PREVIEW pretends it is.
         *
         * Both preview renderers used to call [sample] with no argument, which
         * defaults to today — so a preview drew a date of whenever it happened
         * to be rendered, beside a clock permanently fixed at 10:10. Two
         * consequences, and the second is the one that mattered:
         *
         * 1. `RenderPipelineTest`'s pinned preview hashes changed every day.
         *    They went red mid-session simply because it passed midnight, which
         *    is the kind of failure that gets blamed on whoever's change is in
         *    flight.
         * 2. `preview.png` is baked into the APK by `Workbench.exportTo` and is
         *    the thumbnail the watch face carousel shows. Every built face
         *    carried its BUILD DATE, frozen, next to a clock saying something
         *    else.
         *
         * 10 March is not arbitrary: it is the moment the rest of the preview
         * already uses. `Complications.sample` renders `DAY_AND_DATE` as
         * "MAR 10" and both renderers put the clock at 10:10. This makes the
         * drawn date agree with them.
         *
         * NOT used by [WffEmitter]. A real watch fills the date in from Watch
         * Face Format's own sources, so nothing here changes what an installed
         * face displays — and [widestSample], which DOES feed the emitted font
         * size, was already pinned to a fixed date for exactly that reason.
         */
        val SAMPLE_DATE: java.time.LocalDate = java.time.LocalDate.of(2026, 3, 10)
    }

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
        }
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
     * A specific provider APP for a slot, by `package/class` ComponentName.
     *
     * Watch Face Format's system provider list has fourteen members and there
     * is NO WEATHER in it. Weather, Google Health, and everything else a person
     * has installed are third-party complication data sources, and the format
     * names those a different way: `DefaultProviderPolicy` carries a
     * `primaryProvider` of type `xs:string`, which is a ComponentName, with
     * `defaultSystemProvider` staying as the required fallback.
     *
     * So a slot has both. The provider here is what the wearer asked for; the
     * [ComplicationSource] in [complications] is what shows if that app is not
     * on this particular watch — which matters for a shared face, because the
     * person opening it may not have the app the author used.
     *
     * Empty means "just use the system provider", which is every face made
     * before this existed.
     */
    val providers: Map<SlotPosition, String> = emptyMap(),

    /**
     * The app a [ComplicationSource.SHORTCUT_APP] slot opens, by ComponentName.
     *
     * Separate from [providers] because they are different jobs: a provider
     * FILLS a slot with a reading, a launcher is what pressing it OPENS. A slot
     * could sensibly have both one day; conflating them would make that
     * impossible and would let a face name an app as a data source by accident.
     */
    val launchers: Map<SlotPosition, String> = emptyMap(),

    /**
     * A date drawn by the face itself. See [DateStyle].
     *
     * Defaults to NONE so every face saved before this existed emits exactly the
     * XML it always did — the top slot's date complication is untouched.
     */
    val dateStyle: DateStyle = DateStyle.NONE,

    /** How big the drawn date is, as a proportion of the fitted size. */
    val dateScale: DateScale = DateScale.NORMAL,

    /**
     * A ring around the rim showing progress toward the day's step goal.
     *
     * Not a slot. It costs none of the five, which is the point: a goal is a
     * shape rather than a reading, and Watch Face Format can draw it without a
     * complication at all — `[STEP_PERCENT]` against an `Arc` whose sweep is
     * bound by a `Transform`.
     */
    /**
     * What the rim ring shows, or [RingSource.NONE].
     *
     * Replaced a `stepRing` boolean: the ring is a proportion drawn round the
     * edge, and steps are only one of the things that IS one. The old flag is
     * still read when opening a face saved with it.
     */
    val ring: RingSource = RingSource.NONE,

    /** 12 or 24 hours, or whatever the watch is set to. See [HourFormat]. */
    val hourFormat: HourFormat = HourFormat.DEVICE,

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
        // A ComponentName goes straight into an XML attribute, and it comes
        // from whatever a watch reported. Validate it at the boundary rather
        // than escaping it later: anything that is not a ComponentName is a bug
        // in discovery, not a face someone should be able to save.
        for ((pos, component) in launchers) {
            require(COMPONENT.matches(component)) {
                "the launcher for $pos is not a ComponentName: \"$component\""
            }
        }
        for ((pos, component) in providers) {
            // A provider for a slot that is off cannot be stored: the slot's
            // content is ONE value in the file, so there is nowhere to put a
            // provider for a slot that has no entry. Rejecting it here beats
            // dropping it silently on the next save.
            require(slot(pos).enabled) {
                "a provider is named for $pos, but that slot is off"
            }
            require(COMPONENT.matches(component)) {
                "provider for $pos is not a ComponentName: \"$component\" " +
                    "(expected package/class, e.g. com.example.app/.WeatherProvider)"
            }
        }
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
    /**
     * Whether this slot draws a glyph above its value.
     *
     * A DRAWN source never does, whatever `iconSlots` says: the icons come from
     * `[COMPLICATION.MONOCHROMATIC_IMAGE]`, which only exists inside a
     * `<Complication>`, and a drawn source has none. Asking `pos in iconSlots`
     * directly would reserve the glyph's height and push the value down inside
     * a box with nothing above it.
     */
    fun hasIcon(pos: SlotPosition): Boolean = pos in iconSlots && !slot(pos).isDrawn

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
        /**
         * What a stored colour looks like: `#RRGGBB`, either case.
         *
         * Public because the catalog service enforces the same rule from
         * JavaScript, having read it out of the generated contract. It was
         * briefly written out again there as uppercase-only, which would have
         * rejected perfectly valid stored faces on a public endpoint — so the
         * pattern is published rather than described.
         */
        val HEX = Regex("^#[0-9A-Fa-f]{6}$")
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
     * 40 after a second look on a real phone: 30 was still reading as a
     * subtitle. The drawn date is the only other line of type on the dial and
     * it sits against a 104pt clock, so it has to hold its own.
     *
     * Changing the default does not touch a saved face: dateSize is stored per
     * face, so anything already designed keeps the size it was designed at.
     */
    val dateSize: Int = 64,
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
 * v8 (2026-08-30) makes the FACE DEFINITION authoritative and adds drawn slot
 * sources. `isCustomizable` goes FALSE, because TRUE lets the watch's editor
 * assign a source to a slot and `DefaultProviderPolicy` is then never consulted
 * again -- so nothing chosen in the app could change what the watch drew. And a
 * slot may now hold a source this face DRAWS (weather) rather than one the
 * watch fills. No dial geometry changed: PatternEngines.v8 delegates to v5.
 *
 * This one is deliberately NOT gated for rendering. Every other version branch
 * preserves how an old face looked; here the old behaviour IS the bug, and a
 * face someone is wearing should stop ignoring them.
 *
 * v7 (2026-08-30) shrinks the complication GLYPH from 1.25x the slot size to
 * 0.85x, so the little symbol is smaller than the number it labels rather than
 * bigger, and the value moves up to meet it. That is a look, and it is also
 * vertical room: the stack of top, clock, row and bottom is what caps
 * complication size, and v6 still ran out at 29. PatternEngines.v7 delegates to
 * v5 -- no dial geometry changed.
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
/**
 * `package/class`, the shape Android writes a ComponentName in.
 *
 * The class half may be relative (`.WeatherProvider`) or fully qualified, and
 * may contain `$` for a nested class. Nothing else is accepted, because this
 * string is written verbatim into a WFF attribute.
 */
val COMPONENT = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)*/\.?[A-Za-z][A-Za-z0-9_$]*(\.[A-Za-z0-9_$]+)*""")

const val CURRENT_GENERATOR_VERSION = 10

/** WFF canvas. Correct for Pixel Watch 4 and 5, both case sizes. */
const val DIAL_SIZE = 456
const val DIAL_RADIUS = DIAL_SIZE / 2.0
const val DIAL_CENTER = DIAL_SIZE / 2.0
