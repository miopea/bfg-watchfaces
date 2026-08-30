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

    fun parse(csv: String?): List<ComplicationSource>? {
        if (csv == null) return null
        val parts = csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return emptyList()
        return parts.mapNotNull { token ->
            runCatching { ComplicationSource.valueOf(token.uppercase()) }.getOrNull()
        }
    }

    fun format(list: List<ComplicationSource>): String = list.joinToString(",") { it.name }
}
