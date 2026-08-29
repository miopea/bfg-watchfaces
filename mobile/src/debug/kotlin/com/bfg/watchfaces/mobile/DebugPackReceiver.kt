package com.bfg.watchfaces.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.bfg.watchfaces.appcore.Presets
import com.bfg.watchfaces.mobile.pack.FaceBuilder
import com.bfg.watchfaces.mobile.pack.PackBridge

/**
 * Builds a face from the command line, so the pack pipeline can be exercised
 * without touching the screen.
 *
 * Debug only, and modelled on `:wear`'s `DebugInstallReceiver` for the same
 * reason: driving a multi-step pipeline through taps means every UI change
 * breaks the test, and a system dialog stealing focus looks identical to the
 * pipeline failing.
 *
 *     adb shell am broadcast -n com.bfg.watchfaces/.mobile.DebugPackReceiver \
 *       -a com.bfg.watchfaces.DEBUG_PACK --es preset "Rosette Noir" --es name "Test Face"
 *
 * Then `adb logcat -s BFGPack`.
 */
class DebugPackReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra("name") ?: "Debug Face"
        val presetName = intent.getStringExtra("preset")
        val params = presetName?.let { Presets.byName(it) } ?: Presets.OPENING

        Log.i(TAG, "pack available: ${PackBridge.isAvailable}")
        if (!PackBridge.isAvailable) {
            Log.e(TAG, PackBridge.UNAVAILABLE)
            return
        }
        val started = System.currentTimeMillis()
        runCatching { FaceBuilder.build(context, name, params) }
            .onSuccess {
                Log.i(TAG, "BUILT ${it.apk.absolutePath}")
                Log.i(TAG, "  package  ${it.packageName}")
                Log.i(TAG, "  slug     ${it.slug}")
                Log.i(TAG, "  bytes    ${it.apk.length()}")
                Log.i(TAG, "  took     ${System.currentTimeMillis() - started}ms")
                runCatching { FaceBuilder.validate(context, it.apk) }
                    .onSuccess { token -> Log.i(TAG, "  TOKEN    $token") }
                    .onFailure { e -> Log.e(TAG, "  VALIDATION FAILED: ${e.message}") }
            }
            .onFailure { Log.e(TAG, "BUILD FAILED", it) }
    }

    private companion object {
        const val TAG = "BFGPack"
    }
}
