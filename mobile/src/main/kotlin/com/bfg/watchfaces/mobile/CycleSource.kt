package com.bfg.watchfaces.mobile

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.bfg.watchfaces.appcore.CycleFacts
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The one place in this app that touches health data.
 *
 * Everything it does is in its return type: it reads `MenstruationPeriodRecord`
 * from Health Connect and hands back a single [LocalDate], the start of the
 * most recent period. No other record type is read, nothing is written, and
 * nothing derived from it goes anywhere except the wearer's own watch.
 *
 * Keeping that true is the whole reason this is one small object rather than a
 * capability sprinkled through the app. The Play declaration in
 * `docs/specs/cycle-complication-declarations.md` describes exactly this, and a
 * justification that stops describing the code is worse than none.
 *
 * ## Where the data comes from, and why it may not be there
 *
 * Health Connect is a STORE, not a source. Something has to have written those
 * records. The operator's wife logs in Google Health, which writes Cycle health
 * (Periods, Flow, Intermenstrual bleeding) into Health Connect once she allows
 * it — verified, and the reason this feature works at all.
 *
 * It does NOT generalise. Clue has no Health Connect integration and writes
 * nothing, so a Clue user would find this permanently empty however the
 * permission is set. That is why [Availability] distinguishes "she said no"
 * from "there is nothing there": for a large group of people the empty case is
 * the ONLY case, and telling them to grant a permission they have already
 * granted would be the app blaming them for its own blind spot.
 */
object CycleSource {

    private const val TAG = "BfgCycle"

    /** The single permission this app asks for, and the only one it will accept. */
    val PERMISSIONS: Set<String> = setOf(HealthPermission.getReadPermission(MenstruationPeriodRecord::class))

    /**
     * How far back to look for a period.
     *
     * A year, which is generously past any plausible cycle. It is a bound on
     * the QUERY rather than on what is shown: [com.bfg.watchfaces.appcore.CycleDay]
     * deliberately shows a large day count rather than blanking, because a big
     * number tells her the tracking has lapsed and a blank tells her nothing.
     * This only stops the read walking years of history to find the same answer.
     */
    private const val LOOKBACK_DAYS = 365L

    /** What the phone can say about this feature right now. */
    sealed interface Availability {
        /** Health Connect is not on this device, or is too old to talk to. */
        data object Unsupported : Availability

        /** Installed, but she has not granted the read. */
        data object NotGranted : Availability

        /** Granted, and her own logged periods say something. */
        data class Ready(val facts: CycleFacts) : Availability

        /**
         * Granted, and Health Connect holds no period records.
         *
         * Distinct from [NotGranted] on purpose. It usually means the app she
         * tracks in is not sharing with Health Connect, which is not something
         * she can fix by granting us anything, and an app that responds by
         * asking again for a permission it already has looks broken.
         */
        data object NoRecords : Availability
    }

    /** Whether Health Connect exists on this device at all. */
    fun isSupported(context: Context): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    private fun client(context: Context): HealthConnectClient? =
        if (!isSupported(context)) null
        else runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull()

    /** Whether she has already granted the one read this app wants. */
    suspend fun isGranted(context: Context): Boolean {
        val c = client(context) ?: return false
        return runCatching {
            c.permissionController.getGrantedPermissions().containsAll(PERMISSIONS)
        }.getOrElse { false }
    }

    /**
     * Read the start of the most recent period.
     *
     * Every failure is caught and reported as an [Availability] rather than
     * thrown. This runs behind a screen the wearer is looking at, and Health
     * Connect can be absent, disabled, mid-update or simply refuse — none of
     * which is an exceptional circumstance worth a crash.
     *
     * The record's `startTime` is an INSTANT; the day it falls on depends on a
     * zone. `startZoneOffset` is used when the record carries one, because that
     * is the offset she was actually in when the period started, and falling
     * back to this phone's current zone otherwise. Getting this wrong shifts
     * the whole count by a day, which is invisible here and obvious to her.
     */
    suspend fun read(context: Context): Availability {
        val c = client(context) ?: return Availability.Unsupported
        if (!isGranted(context)) return Availability.NotGranted
        return runCatching {
            val now = Instant.now()
            val records = c.readRecords(
                ReadRecordsRequest(
                    recordType = MenstruationPeriodRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(
                        now.minus(LOOKBACK_DAYS, ChronoUnit.DAYS), now
                    )
                )
            ).records
            if (records.isEmpty()) return Availability.NoRecords
            // EVERY period in the window, not just the latest. The extra facts
            // the carousel card shows -- how long her last period ran, how far
            // apart her recent ones started -- are arithmetic over the set, and
            // they cost no additional permission because they come from the
            // same records this already reads.
            val periods = records.map { r ->
                // startZoneOffset is the offset she was actually in when the
                // period began; this phone's current zone is the fallback.
                // Getting it wrong shifts the whole count by a day, which is
                // invisible here and obvious to her.
                val startZone = r.startZoneOffset ?: ZoneId.systemDefault()
                val endZone = r.endZoneOffset ?: startZone
                LocalDate.ofInstant(r.startTime, startZone) to
                    LocalDate.ofInstant(r.endTime, endZone)
            }
            CycleFacts.from(periods)?.let { Availability.Ready(it) } ?: Availability.NoRecords
        }.getOrElse {
            // No date, no record count, nothing about her in the log. The fact
            // that a read failed is ours; what it would have contained is hers.
            Log.w(TAG, "could not read from Health Connect: ${it.javaClass.simpleName}")
            Availability.NoRecords
        }
    }
}
