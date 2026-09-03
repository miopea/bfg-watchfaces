package com.bfg.watchfaces.appcore

import com.bfg.watchfaces.generator.ComplicationSource
import com.bfg.watchfaces.generator.SlotPosition

/**
 * Presentation for complication slots: what to call them, and what to draw in a
 * preview where no real provider exists.
 *
 * Deliberately in :workbench rather than :generator. The generator defines the
 * STORED FORMAT -- which provider token a slot carries -- and nothing about how
 * a slot looks. Putting sample strings in there would make the file format
 * depend on presentation choices that have no business being versioned.
 */
object Complications {

    /** Human label. Also written into strings.xml as the slot's displayName. */
    fun label(s: ComplicationSource): String = when (s) {
        ComplicationSource.NONE -> "Off"
        ComplicationSource.STEP_COUNT -> "Steps"
        ComplicationSource.HEART_RATE -> "Heart rate"
        ComplicationSource.DAY_AND_DATE -> "Day and date"
        ComplicationSource.TIME_AND_DATE -> "Time and date"
        ComplicationSource.DATE -> "Date"
        ComplicationSource.DAY_OF_WEEK -> "Day of week"
        ComplicationSource.WATCH_BATTERY -> "Battery"
        ComplicationSource.WORLD_CLOCK -> "World clock"
        ComplicationSource.NEXT_EVENT -> "Next event"
        ComplicationSource.SUNRISE_SUNSET -> "Sunrise and sunset"
        ComplicationSource.UNREAD_NOTIFICATION_COUNT -> "Notifications"
        ComplicationSource.APP_SHORTCUT -> "App shortcut"
        ComplicationSource.FAVORITE_CONTACT -> "Favourite contact"
        ComplicationSource.WEATHER_TEMPERATURE -> "Weather"
        ComplicationSource.WEATHER_CONDITION -> "Conditions"
        ComplicationSource.WEATHER_TEMP_CONDITION -> "Weather and conditions"
        ComplicationSource.WEATHER_HIGH_LOW -> "High and low"
        ComplicationSource.WEATHER_LATER -> "In a few hours"
        ComplicationSource.WEATHER_TOMORROW -> "Tomorrow"
        ComplicationSource.WEATHER_TOMORROW_SKY -> "Tomorrow's sky"
        ComplicationSource.WEATHER_RAIN -> "Chance of rain"
        ComplicationSource.WEATHER_UV -> "UV index"
        ComplicationSource.SHORTCUT_MUSIC -> "Music"
        ComplicationSource.SHORTCUT_ALARM -> "Alarms"
        ComplicationSource.SHORTCUT_SETTINGS -> "Settings"
        ComplicationSource.SHORTCUT_PHONE -> "Phone"
        ComplicationSource.SHORTCUT_CALENDAR -> "Calendar"
        ComplicationSource.SHORTCUT_MESSAGES -> "Messages"
        ComplicationSource.SHORTCUT_APP -> "Open an app"
    }

    /**
     * What the preview shows in a slot.
     *
     * These are plausible values of a REPRESENTATIVE WIDTH, which is the only
     * thing the preview can honestly tell you: on the watch a real provider
     * fills the slot, and the question a designer needs answered here is whether
     * the layout survives a value of about this size. They are not live data and
     * are not pretending to be.
     */
    fun sample(s: ComplicationSource): String = when (s) {
        ComplicationSource.NONE -> ""
        ComplicationSource.STEP_COUNT -> "8,412"
        ComplicationSource.HEART_RATE -> "62"
        ComplicationSource.DAY_AND_DATE -> "MAR 10"
        ComplicationSource.TIME_AND_DATE -> "10:10"
        ComplicationSource.DATE -> "10"
        ComplicationSource.DAY_OF_WEEK -> "TUE"
        ComplicationSource.WATCH_BATTERY -> "78%"
        ComplicationSource.WORLD_CLOCK -> "14:10"
        ComplicationSource.NEXT_EVENT -> "Standup"
        ComplicationSource.SUNRISE_SUNSET -> "6:42"
        ComplicationSource.UNREAD_NOTIFICATION_COUNT -> "3"
        ComplicationSource.APP_SHORTCUT -> "Maps"
        ComplicationSource.FAVORITE_CONTACT -> "Ann"
        ComplicationSource.WEATHER_TEMPERATURE -> "72°"
        ComplicationSource.WEATHER_CONDITION -> "Cloudy"
        ComplicationSource.WEATHER_TEMP_CONDITION -> "72° Cloudy"
        ComplicationSource.WEATHER_HIGH_LOW -> "78° / 61°"
        ComplicationSource.WEATHER_LATER -> "69°"
        ComplicationSource.WEATHER_TOMORROW -> "81° / 64°"
        ComplicationSource.WEATHER_TOMORROW_SKY -> "Partly cloudy"
        ComplicationSource.WEATHER_RAIN -> "30%"
        ComplicationSource.WEATHER_UV -> "UV 6"
        // A shortcut shows only its glyph; there is nothing to read.
        ComplicationSource.SHORTCUT_MUSIC,
        ComplicationSource.SHORTCUT_ALARM,
        ComplicationSource.SHORTCUT_SETTINGS,
        ComplicationSource.SHORTCUT_PHONE,
        ComplicationSource.SHORTCUT_CALENDAR,
        ComplicationSource.SHORTCUT_MESSAGES,
        ComplicationSource.SHORTCUT_APP -> ""
    }

    /**
     * What the WATCH'S OWN EDITOR calls each slot.
     *
     * Shared, and shipped, because it ends up as a string resource INSIDE every
     * built face -- `WffEmitter` writes `displayName="@string/slot_right"` and
     * the APK has to carry that string. Both builders emit it from here so the
     * phone and the workbench cannot disagree about a name a wearer reads.
     */
    fun slotLabel(pos: SlotPosition): String = when (pos) {
        SlotPosition.TOP -> "Top"
        SlotPosition.LEFT -> "Left"
        SlotPosition.MIDDLE -> "Middle"
        SlotPosition.RIGHT -> "Right"
        SlotPosition.BOTTOM -> "Bottom"
    }

    /** Every string the emitted WFF references for slots, ready for strings.xml. */
    fun slotStrings(): List<Pair<String, String>> =
        SlotPosition.entries.map { it.resource to slotLabel(it) }

    /** Everything the UI offers, NONE first so "Off" reads as the neutral choice. */
    val all: List<ComplicationSource> = ComplicationSource.entries.toList()

    /**
     * The stored form of one slot.
     *
     * A bare enum name for anything this build understands, and `app:<component>`
     * when a specific provider app was chosen for that position. One string per
     * slot, so a slot's content is one value in the file rather than two fields
     * that have to agree -- which is how `dateStyle` and `generatorVersion` each
     * went missing from a saved face.
     */
    fun token(source: ComplicationSource, component: String?, launcher: String? = null): String =
        buildString {
            append(source.name)
            if (!component.isNullOrBlank()) append(APP).append(component)
            if (!launcher.isNullOrBlank()) append(OPEN).append(launcher)
        }

    /** The provider component in a token, or null when it names a system source. */
    fun componentIn(token: String): String? =
        token.trim().substringAfter(APP, "").substringBefore(OPEN).takeIf { it.isNotBlank() }

    /** The app a shortcut slot opens, or null when it names none. */
    fun launcherIn(token: String): String? =
        token.trim().substringAfter(OPEN, "").takeIf { it.isNotBlank() }

    /** The system source in a token, which is the fallback when an app is named. */
    fun sourceIn(token: String): String = token.trim().substringBefore(APP).substringBefore(OPEN)

    /**
     * Separates the chosen provider app from the source behind it.
     *
     * A slot's content is one value in the file, but it has to carry TWO
     * things when an app is named: the app, and what shows on a watch that does
     * not have it. `defaultSystemProvider` is required by the schema, so the
     * fallback is not optional and cannot be inferred later -- dropping it made
     * round trips lossy, turning every app slot into the same arbitrary source.
     */
    private const val APP = "+app:"

    /**
     * Separates the app a shortcut OPENS from the source.
     *
     * A different marker from [APP] on purpose: filling a slot with a reading
     * and opening something when it is pressed are different jobs, and one
     * token has to be able to say which it meant.
     */
    private const val OPEN = "+open:"

    fun parse(csv: String?): List<ComplicationSource>? {
        if (csv == null) return null
        val parts = csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return emptyList()
        return parts.map { token ->
            // An `app:` slot still needs a SYSTEM source as its fallback -- the
            // schema requires defaultSystemProvider, and a watch without that
            // app has to show something. NONE would turn the slot off entirely,
            // so the fallback is deliberately a neutral one.
            // An unknown name becomes NONE rather than being DROPPED. Dropping
            // shifted every later slot up a position, which silently rearranged
            // a face saved by a newer build.
            runCatching { ComplicationSource.valueOf(sourceIn(token).uppercase()) }
                .getOrNull() ?: ComplicationSource.NONE
        }
    }

    fun format(list: List<ComplicationSource>): String = list.joinToString(",") { it.name }

    /** The stored list, with any chosen provider apps folded into the tokens. */
    fun format(
        list: List<ComplicationSource>,
        providers: Map<SlotPosition, String>,
        launchers: Map<SlotPosition, String> = emptyMap()
    ): String = list.mapIndexed { i, source ->
        val pos = SlotPosition.entries.getOrNull(i)
        token(source, providers[pos], launchers[pos])
    }.joinToString(",")

    /** The apps shortcut slots open, by position. */
    fun launchersIn(csv: String?): Map<SlotPosition, String> {
        if (csv == null) return emptyMap()
        return csv.split(",").mapIndexedNotNull { i, token ->
            val pos = SlotPosition.entries.getOrNull(i) ?: return@mapIndexedNotNull null
            launcherIn(token)?.let { pos to it }
        }.toMap()
    }

    /** The provider components in a stored list, by position. */
    fun providersIn(csv: String?): Map<SlotPosition, String> {
        if (csv == null) return emptyMap()
        return csv.split(",").mapIndexedNotNull { i, token ->
            val pos = SlotPosition.entries.getOrNull(i) ?: return@mapIndexedNotNull null
            componentIn(token)?.let { pos to it }
        }.toMap()
    }
}
