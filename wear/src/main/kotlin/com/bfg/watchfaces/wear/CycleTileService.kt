package com.bfg.watchfaces.wear

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.FONT_WEIGHT_BOLD
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.bfg.watchfaces.appcore.CycleDay
import com.bfg.watchfaces.appcore.CycleFacts
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.time.LocalDate

/**
 * The cycle day as a CARD IN THE CAROUSEL, swiped to from the watch face.
 *
 * ## Why this exists separately from the complication
 *
 * They are two different surfaces and the operator had to say so twice before
 * it landed: a complication is a value ON the watch face, and a widget is a
 * card you swipe to FROM it. [CycleDayService] does the first. This does the
 * second. Neither replaces the other — a face has four small slots competing
 * for room, and this has the whole screen.
 *
 * ## Tiles, not "widgets", on this hardware
 *
 * Wear OS 7 renames the carousel to widgets and migrates tiles into it. The
 * operator's Pixel Watch 5 reports Android 17 / SDK 37 and its carousel is
 * still served by `TileService` — Fitbit's and Maps' tiles are in it right now,
 * read off the device rather than assumed. The APIs stay backward compatible,
 * so this is the thing that actually appears today.
 *
 * ## It holds no health data, same as everything else on this watch
 *
 * One date, sent by her own phone, read through [CycleDay]. This service never
 * touches Health Connect and holds no permission. Same file the complication
 * reads, so the card and the dial cannot disagree about the day.
 *
 * ## Freshness
 *
 * `setFreshnessIntervalMillis` asks the system to re-request around the day
 * boundary rather than polling. The value is derived from a stored DATE, so a
 * missed refresh shows yesterday's number rather than nothing — the same
 * trade-off [CycleDayService] documents, and the same thing only a real
 * overnight can confirm.
 */
class CycleTileService : TileService() {

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> {
        // The WATCH's own date: she is looking at the watch, so the day it is
        // here is the day she means.
        val today = LocalDate.now()
        // The richer facts when the phone has sent them, falling back to the
        // bare date the complication uses. A watch paired with an older phone
        // therefore still shows the day count and simply says less.
        val facts = CycleFacts.load(applicationContext.filesDir)
            ?: CycleDay.load(applicationContext.filesDir)?.let { CycleFacts(it) }
        val day = facts?.dayOfCycle(today)

        // Bare, like the dial, and for the same reason -- decision 01a0ba48.
        // The card has room for a caption to say what the number IS, so the
        // word does not have to ride on the number itself.
        val headline = day?.toString() ?: CycleDay.EMPTY_PLACEHOLDER

        // Never "you have no records" and never "grant a permission". The watch
        // cannot know which is true -- it only ever receives a date -- and the
        // phone is where the explanation lives and where anything can be fixed.
        //
        // While she is actually bleeding, which day OF THE PERIOD it is says
        // more than "since your last period", which would be counting from
        // something still happening.
        val bleedingDay = facts?.dayOfPeriod(today)
        //
        // "since your last period" was WRONG once the number lost its "Day".
        // Day 18 is seventeen days after the start, not eighteen, and a bare
        // 18 over "since your last period" states the elapsed count -- off by
        // one, in the one place being off by one is noticed.
        val caption = when {
            day == null -> "Set up on your phone"
            bleedingDay != null -> "day $bleedingDay of your period"
            else -> "day of your cycle"
        }

        // Only what her own records support. A first period has no average and
        // an open one has no length; inventing either would be the app making
        // something up about her. Description only -- nothing here says when
        // the next period is due.
        val detail = if (facts == null) emptyList() else listOfNotNull(
            facts.periodLengthDays?.let { "Last period ${CycleFacts.dayCount(it)}" },
            facts.averageCycleDays?.let { "Average cycle ${CycleFacts.dayCount(it)}" }
        )

        val layout = LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            // The WHOLE card is the tap target, not a button in it. There is
            // one thing to do here and a card this sparse has nowhere sensible
            // to put a control; a 200px target also beats a 48dp one on a
            // wrist. See CycleOpenActivity for where it goes and why the phone
            // rather than the watch.
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setId("open-cycle")
                            .setOnClick(
                                ActionBuilders.LaunchAction.Builder()
                                    .setAndroidActivity(
                                        ActionBuilders.AndroidActivity.Builder()
                                            .setPackageName(packageName)
                                            .setClassName(CycleOpenActivity::class.java.name)
                                            .build()
                                    )
                                    .build()
                            )
                            .build()
                    )
                    .setSemantics(
                        ModifiersBuilders.Semantics.Builder()
                            .setContentDescription(semantics(day, headline, caption))
                            .build()
                    )
                    .build()
            )
            .addContent(
                LayoutElementBuilders.Column.Builder()
                    // The same ring the dial draws, from the same description
                    // in ComplicationGlyphs -- a card and a dial showing
                    // different marks for one feature is how a person learns
                    // they are two features.
                    .addContent(
                        // Built against the REQUEST'S scope, which is how a
                        // tile ships an image now: the resource registers
                        // itself and the service merges it into the resource
                        // set. The older spelling -- a string id here and a
                        // matching addIdToImageMapping in
                        // onTileResourcesRequest -- is deprecated, and it is
                        // deprecated because the two halves could disagree and
                        // the only symptom was a blank where the image goes.
                        LayoutElementBuilders.Image.Builder(requestParams.scope)
                            .setImageResource(ringResource())
                            .setWidth(dp(RING_DP))
                            .setHeight(dp(RING_DP))
                            .setColorFilter(
                                LayoutElementBuilders.ColorFilter.Builder()
                                    .setTint(argb(MUTED))
                                    .build()
                            )
                            .build()
                    )
                    .addContent(
                        LayoutElementBuilders.Text.Builder()
                            .setText(headline)
                            .setFontStyle(
                                LayoutElementBuilders.FontStyle.Builder()
                                    .setSize(sp(38f))
                                    .setWeight(FONT_WEIGHT_BOLD)
                                    .setColor(argb(INK))
                                    .build()
                            )
                            .build()
                    )
                    .addContent(
                        LayoutElementBuilders.Text.Builder()
                            .setText(caption)
                            .setFontStyle(
                                LayoutElementBuilders.FontStyle.Builder()
                                    .setSize(sp(13f))
                                    .setColor(argb(MUTED))
                                    .build()
                            )
                            .setModifiers(
                                ModifiersBuilders.Modifiers.Builder()
                                    .setPadding(
                                        ModifiersBuilders.Padding.Builder().setTop(
                                            androidx.wear.protolayout.DimensionBuilders.dp(6f)
                                        ).build()
                                    )
                                    .build()
                            )
                            .build()
                    )
                    .apply {
                        for (line in detail) {
                            addContent(
                                LayoutElementBuilders.Text.Builder()
                                    .setText(line)
                                    .setFontStyle(
                                        LayoutElementBuilders.FontStyle.Builder()
                                            .setSize(sp(12f))
                                            .setColor(argb(MUTED))
                                            .build()
                                    )
                                    .setModifiers(
                                        ModifiersBuilders.Modifiers.Builder()
                                            .setPadding(
                                                ModifiersBuilders.Padding.Builder().setTop(
                                                    androidx.wear.protolayout.DimensionBuilders.dp(4f)
                                                ).build()
                                            )
                                            .build()
                                    )
                                    .build()
                            )
                        }
                    }
                    .build()
            )
            .build()

        return Futures.immediateFuture(
            TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                // Re-asked around the day boundary rather than polled. A missed
                // refresh shows yesterday's number, not a blank, because the
                // value is derived from a stored date.
                .setFreshnessIntervalMillis(FRESHNESS_MS)
                .setTileTimeline(
                    TimelineBuilders.Timeline.fromLayoutElement(layout)
                )
                .build()
        )
    }

    /** The ring drawable, as a tile image resource. */
    private fun ringResource() = ResourceBuilders.ImageResource.Builder()
        .setAndroidResourceByResId(
            ResourceBuilders.AndroidImageResourceByResId.Builder()
                .setResourceId(R.drawable.ic_cycle_ring)
                .build()
        )
        .build()

    /**
     * What a screen reader says, as one sentence rather than three fragments.
     *
     * The card is written to be GLANCED at -- "18" over "day of your
     * cycle" reads at arm's length and says nothing to somebody listening to
     * it in pieces. Read aloud it should be a sentence, and it should say what
     * the tap does, because a tap target that announces nothing is a tap target
     * nobody finds. Same reasoning as CycleDayService's contentDescription.
     */
    private fun semantics(day: Int?, headline: String, caption: String): String =
        if (day == null) "Cycle day not available. $caption."
        else "Cycle day $day, $caption. Opens Google Health on your phone."

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(
            ResourceBuilders.Resources.Builder()
                .setVersion(RESOURCES_VERSION)
                .build()
        )

    companion object {
        /**
         * Bumped when the card's IMAGES change. It went to "2" when the ring
         * arrived: a tile that kept saying "1" would have been served the
         * cached, empty resource set and drawn a blank where the mark goes.
         *
         * Still declared even though the ring registers itself through the
         * request's scope, because the scope's resources are MERGED with this
         * version rather than replacing it.
         */
        private const val RESOURCES_VERSION = "2"

        /**
         * Big enough to read as a ring rather than a dot, small enough that the
         * number stays the thing you see first. The headline is 38sp; this is
         * deliberately well under half of it.
         */
        private const val RING_DP = 16f

        /**
         * An hour.
         *
         * The number changes once a day, so an hour is far more often than it
         * needs and still cheap. Asking for exactly midnight would be precise
         * and wrong: the system is free to honour a freshness request late, and
         * a card that is only correct if one wake-up lands on time is a card
         * that is sometimes a day out with no way to tell.
         */
        private const val FRESHNESS_MS = 60L * 60L * 1000L

        /**
         * Ask the carousel to re-render, when new facts arrive from the phone.
         *
         * Without it the card keeps whatever it last drew until its freshness
         * window elapses, so a period logged this morning would appear at an
         * unpredictable time. Same reasoning as
         * [CycleDayService.notifyChanged], different mechanism: tiles are
         * refreshed by asking the updater, not the complication requester.
         */
        fun notifyChanged(context: android.content.Context) {
            runCatching {
                androidx.wear.tiles.TileService.getUpdater(context)
                    .requestUpdate(CycleTileService::class.java)
            }.onFailure { android.util.Log.w("BfgCycleTile", "could not refresh the tile", it) }
        }

        /** The app's ink and a muted second line, matching the dial's palette. */
        private const val INK = 0xFFFCF9F1.toInt()
        private const val MUTED = 0xFF9E9186.toInt()
    }
}
