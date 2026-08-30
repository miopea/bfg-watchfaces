package com.bfg.watchfaces.appcore

import com.bfg.watchfaces.generator.DialParams

/**
 * Whether a face's complication SLOTS have to be rebuilt on the watch.
 *
 * ## Why this is a question at all
 *
 * `updateWatchFace` keeps the slot, and the watch keeps whatever data source is
 * assigned to that slot — `DefaultProviderPolicy` only fills a slot nothing has
 * been assigned to. So re-sending a design with different complications
 * installed it and showed the old ones. Removing and re-adding gives fresh
 * slots and the design wins.
 *
 * That reset is not free. It deactivates the face, so the watch has to be told
 * to switch back, and `setWatchFaceAsActive` has an undocumented and finite
 * allowance this project has already exhausted once. When it runs out the old
 * face is gone and the new one is not active — worse than the bug being fixed.
 *
 * So the reset is spent only when it buys something, and this is what decides.
 * The phone is the only side that can: the watch has no record of what the
 * previous face declared.
 */
object ComplicationChange {

    /**
     * True when [next] would draw different complications than [previous].
     *
     * Compares only what the watch's slot assignment depends on. Colour, engine,
     * size and layout all change the face without changing which provider fills
     * which slot, and resetting for those would spend the activation allowance
     * on nothing.
     */
    fun needsReset(previous: DialParams?, next: DialParams): Boolean {
        // Nothing sent from this phone before: the first install has nothing
        // assigned anyway, so there is nothing to reset.
        if (previous == null) return false

        // The provider in each position.
        if (previous.complications != next.complications) return true
        // A specific app named for a slot is part of the assignment too.
        if (previous.providers != next.providers) return true

        return false
    }
}
