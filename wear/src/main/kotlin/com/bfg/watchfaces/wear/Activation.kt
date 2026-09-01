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
 * `activation.txt` lives in `filesDir` and the app did not opt out of Android
 * Auto Backup, so the file is restored on reinstall. The permission is not — a
 * fresh install holds none. That leaves the app reading GRANTED from a file
 * written by an install that no longer exists, and unable to ask again because
 * the one shot is recorded as spent.
 *
 * ## What that actually breaks, stated narrowly
 *
 * The face still INSTALLS. `SET_PUSHED_WATCH_FACE_AS_ACTIVE` gates switching to
 * a face, not putting one there, so what is lost is the switch: the face lands
 * in the picker and the watch never moves to it. From the wearer's side that is
 * "I sent a face and nothing happened".
 *
 * It does **not** explain a failure to bind to Watch Face Push. This file said
 * for one release that it did — that `updateWatchFace`'s `ERROR_UNKNOWN` came
 * from the stale GRANTED — and that was wrong twice over: binding needs
 * `PUSH_WATCH_FACES`, which is an install-time permission this app declares and
 * therefore always holds; and the check below was reading that permission
 * rather than the activation one, so the reconciliation could never fire at
 * all. The install failure is a separate, still-open question.
 *
 * The manifest also excludes the file from backup, so this stops happening to
 * new installs. This exists for the ones it already happened to.
 */
object Activation {

    private const val TAG = "Activation"

    /**
     * The ACTIVATION permission, held or not.
     *
     * [ActivationConsent.PERMISSION] and not a literal, and specifically not
     * `PUSH_WATCH_FACES` — which is what the first version of this file
     * checked, and which made the whole reconciliation INERT.
     *
     * The two are not interchangeable and confusing them is silent:
     *
     * - `PUSH_WATCH_FACES` is a normal, install-time permission. It is declared
     *   in the manifest, so it is granted the moment the app installs and this
     *   check would return true forever. It gates PUTTING a face on the watch.
     * - `SET_PUSHED_WATCH_FACE_AS_ACTIVE` is the runtime one, asked for with a
     *   dialog, revocable, not restored by backup, and askable ONCE. It gates
     *   SWITCHING to a face. It is the only thing [ActivationConsent] records.
     *
     * With the wrong constant `reconcile` was handed `true` on every call and
     * returned the stored state unchanged, so shipping it changed nothing. The
     * unit tests could not catch that: they call `reconcile` with a boolean
     * directly and never see which permission this asks about.
     */
    fun permissionHeld(context: Context): Boolean =
        context.checkSelfPermission(ActivationConsent.PERMISSION) ==
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
