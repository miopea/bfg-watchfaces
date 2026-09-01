package com.bfg.watchfaces.wear

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.bfg.watchfaces.appcore.ActivationConsent

/**
 * The activation state this watch is ACTUALLY in.
 *
 * Every read of [ActivationConsent] on the watch goes through here, because the
 * stored answer can outlive the permission it records.
 *
 * `activation.txt` lives in `filesDir` and the app does not opt out of Android
 * Auto Backup, so the file is restored on reinstall. The permission is not — a
 * fresh install holds none. That left the app reading GRANTED from a file
 * written by an install that no longer existed, unable to ask again because the
 * one shot was recorded as spent, and unable to bind to Watch Face Push. Every
 * send failed with `ERROR_UNKNOWN`, which reads as "the service could not be
 * accessed" and names nothing.
 *
 * Measured on a Pixel Watch 5, 2026-09-01: the same reply carried GRANTED and a
 * bind failure.
 *
 * The manifest now also excludes the file from backup, so this stops happening
 * to new installs. This exists for the ones it already happened to — including
 * the operator's watch, which cannot be fixed by a manifest change it has
 * already restored past.
 */
object Activation {

    private const val TAG = "Activation"

    /** The Watch Face Push permission, held or not. */
    fun permissionHeld(context: Context): Boolean =
        context.checkSelfPermission("com.google.wear.permission.PUSH_WATCH_FACES") ==
            PackageManager.PERMISSION_GRANTED

    /** The stored state, reconciled against the permission it claims. */
    fun state(context: Context): ActivationConsent.State {
        val stored = ActivationConsent.load(context.filesDir)
        val held = permissionHeld(context)
        val real = ActivationConsent.reconcile(stored, held)
        if (real != stored) {
            Log.w(
                TAG,
                "stored consent said $stored but the permission is not held; " +
                    "treating as $real so the wearer can be asked again"
            )
        }
        return real
    }
}
