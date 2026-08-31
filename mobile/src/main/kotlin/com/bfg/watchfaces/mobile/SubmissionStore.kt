package com.bfg.watchfaces.mobile

import android.content.Context
import com.bfg.watchfaces.appcore.SubmissionLog

/**
 * The phone's seat for [SubmissionLog], on `filesDir`.
 *
 * The same shape as [FaceStorage] and for the same reason: every rule about
 * what a submission record IS lives in `:appcore` where it can be tested
 * without an emulator, and this supplies only the directory.
 */
object SubmissionStore {

    fun forSlug(context: Context, slug: String): SubmissionLog.Record? =
        SubmissionLog.forSlug(context.filesDir, slug)

    fun all(context: Context): List<SubmissionLog.Record> =
        SubmissionLog.list(context.filesDir)

    fun record(context: Context, slug: String, id: String): SubmissionLog.Record =
        SubmissionLog.record(context.filesDir, slug, id)

    fun setState(context: Context, id: String, state: SubmissionLog.State) =
        SubmissionLog.setState(context.filesDir, id, state)

    fun forget(context: Context, id: String) =
        SubmissionLog.forget(context.filesDir, id)
}
