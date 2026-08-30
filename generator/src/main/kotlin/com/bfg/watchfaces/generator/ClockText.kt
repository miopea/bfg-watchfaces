package com.bfg.watchfaces.generator

/**
 * The time as the face will show it, for the previews.
 *
 * The watch formats its own clock from `format` and `hourFormat`; a preview has
 * to reproduce that or it shows a different face from the one being designed.
 * Shared so the two previews cannot disagree, which is this project's most
 * repeated bug.
 */
object ClockText {

    /**
     * [hour24] is 0..23. [deviceIs24Hour] only matters for
     * [HourFormat.DEVICE] — a preview has to guess what the watch is set to,
     * and guessing the phone's own setting is the closest it can get.
     */
    fun of(p: DialParams, hour24: Int, minute: Int, deviceIs24Hour: Boolean = false): String {
        val twentyFour = when (p.hourFormat) {
            HourFormat.TWENTY_FOUR -> true
            HourFormat.TWELVE -> false
            HourFormat.DEVICE -> deviceIs24Hour
        }
        return if (twentyFour) {
            "%02d:%02d".format(hour24, minute)
        } else {
            // No leading zero on a 12-hour clock, and 0 reads as 12.
            val h = hour24 % 12
            "%d:%02d".format(if (h == 0) 12 else h, minute)
        }
    }
}
