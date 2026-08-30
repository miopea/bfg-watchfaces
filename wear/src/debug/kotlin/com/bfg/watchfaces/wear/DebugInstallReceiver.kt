package com.bfg.watchfaces.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Drives [FaceInstaller] from `adb`, so Watch Face Push can be exercised on a
 * watch with no phone paired to it.
 *
 * ## Why this exists
 *
 * `addWatchFace`, the validation token, the slot handling and the one-shot
 * activation permission are the only genuinely novel things in this project,
 * and until now every one of them sat behind a Data Layer channel — which needs
 * a paired phone. Pairing two emulators turned out to need a factory reset of
 * the watch, because a Wear device only advertises while it is inside its setup
 * wizard (`user_setup_complete` was already 1). That is a real cost to pay for
 * reaching code that does not actually depend on the phone at all.
 *
 * So this is the short route: put an APK and its token on the watch by hand and
 * call the same installer the channel calls. It proves the Push half. It proves
 * nothing whatsoever about the transport half, and must not be read as doing so.
 *
 * ## It is in `src/debug`, deliberately
 *
 * Not behind a `BuildConfig.DEBUG` check in shipped code. An exported receiver
 * that installs an arbitrary APK is exactly the thing that must not exist in a
 * release build at all, and a source set is the only version of that guarantee
 * the compiler enforces.
 *
 * ## Usage
 *
 * The APK has to be somewhere this app can read, which `/data/local/tmp` is not:
 *
 * ```
 * adb push watchface.apk /data/local/tmp/face.apk
 * adb shell run-as com.bfg.watchfaces cp /data/local/tmp/face.apk files/face.apk
 * adb shell am broadcast -a com.bfg.watchfaces.DEBUG_INSTALL \
 *     -n com.bfg.watchfaces/.wear.DebugInstallReceiver \
 *     --es file face.apk --es token '<token from validator-push-cli>'
 * adb logcat -s BfgDebugInstall BfgFaceInstaller BfgActivation
 * ```
 */
class DebugInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra("file") ?: "face.apk"
        val token = intent.getStringExtra("token")
        if (token.isNullOrBlank()) {
            // The failure mode this guards is worth naming: an empty token is
            // not rejected early, it fails inside addWatchFace after everything
            // else has succeeded, and the error does not say "no token".
            Log.e(TAG, "no --es token given; refusing to spend an addWatchFace call")
            return
        }
        val apk = File(context.filesDir, name)
        if (!apk.isFile) {
            Log.e(TAG, "no such file: ${apk.absolutePath} (did run-as cp succeed?)")
            return
        }

        Log.i(TAG, "installing ${apk.absolutePath} (${apk.length()} bytes)")
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (val result = FaceInstaller.install(
                    context, apk, token,
                    resetComplications = intent.getBooleanExtra("reset", false)
                )) {
                    is FaceInstaller.Result.Installed ->
                        Log.i(TAG, "OK slot=${result.slotId} replaced=${result.replaced}")
                    is FaceInstaller.Result.Unsupported ->
                        Log.e(TAG, "FAIL Watch Face Push unsupported on this watch")
                    is FaceInstaller.Result.Failed ->
                        Log.e(TAG, "FAIL ${result.cause}", result.cause)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BfgDebugInstall"
    }
}
