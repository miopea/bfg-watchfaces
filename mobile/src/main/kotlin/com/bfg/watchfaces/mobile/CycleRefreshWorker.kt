package com.bfg.watchfaces.mobile

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bfg.watchfaces.appcore.CycleSyncPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Re-read Health Connect every two hours, so a newly logged period reaches the
 * watch without anyone opening this app.
 *
 * ## The bug this exists to stop
 *
 * On 2026-10-27 the operator's wife logged the start of a period in her health
 * app and her cycle tile did not change. It changed that evening, the moment
 * someone opened BFG Watch Faces on her phone. So the read worked and the
 * record was there; nothing asked for it. The only trigger was
 * [MainActivity.onResume], and the 2026-09-20 assumption that she opens the
 * app anyway did not hold.
 *
 * ## What it will and will not do
 *
 * It reads ONLY when [CycleSource.canReadInBackground] says so: the device
 * supports background reads and she has granted both the read and Health
 * Connect's separate background permission. Without that it does nothing at
 * all, and the app behaves exactly as it did before this existed.
 *
 * It sends ONLY a positive read, and never a clear. See
 * [CycleSyncPlan.backgroundSends]: a failed background read looks exactly like
 * "no records", and sending that as a clear would blank her watch until the
 * phone was next opened. Clearing stays with a launch, where it always was.
 *
 * It sends the same four values a launch sends, through the same
 * [CycleSender.push], so the background path cannot say anything the filed
 * declaration does not already describe.
 */
class CycleRefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!CycleSource.canReadInBackground(context)) return Result.success()
        val facts = (CycleSource.read(context) as? CycleSource.Availability.Ready)?.facts
        if (!CycleSyncPlan.backgroundSends(facts)) return Result.success()
        // Tasks.await inside push blocks, so off the worker's default dispatcher.
        withContext(Dispatchers.IO) { CycleSender.push(context, facts) }
        // That it ran, never what it sent. Same rule as every other log line
        // that comes near her data.
        Log.i(TAG, "background refresh sent")
        return Result.success()
    }

    companion object {
        private const val TAG = "BfgCycleRefresh"

        /** One schedule per install, whatever calls this and however often. */
        private const val WORK_NAME = "cycle-refresh"

        /**
         * Make sure the refresh is scheduled.
         *
         * Called on every launch and cheap to repeat: KEEP leaves an existing
         * schedule alone rather than restarting its clock. WorkManager keeps the
         * schedule across reboots and app updates by itself, so a launch is only
         * needed once, after the update that introduces it.
         *
         * Scheduled whether or not the background grant exists yet. She grants
         * it in Health Connect's settings, not in this app, so the app cannot
         * tell when that happens. A run without it costs one permission check.
         */
        fun schedule(context: Context) {
            if (!CycleSource.isSupported(context)) return
            val request = PeriodicWorkRequestBuilder<CycleRefreshWorker>(
                CycleSyncPlan.BACKGROUND_INTERVAL_HOURS, TimeUnit.HOURS
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
    }
}
