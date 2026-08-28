package com.bfg.watchfaces.wear

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.bfg.watchfaces.appcore.ActivationConsent

/**
 * The one irreversible ask, and nothing else.
 *
 * No layout on purpose. The system permission dialog IS the interface, and
 * Google's guidance is explicit that this app should not have a significant one.
 * The careful explanation already happened on the device before the face was
 * sent — see `ActivationConsent.HANDOFF` — so putting a second screen of prose
 * on a round watch face would be repeating it worse.
 *
 * ## Why this is an Activity at all
 *
 * Android runtime permissions can only be requested from an Activity, and this
 * is reached from a [FaceReceiverService], which is not one. So it exists purely
 * to host the request and get out of the way.
 *
 * ## The rule this must never break
 *
 * `SET_PUSHED_WATCH_FACE_AS_ACTIVE` cannot be requested a second time after a
 * denial. A second attempt is not refused with an error you can see — it simply
 * never reaches the user. So the state is written down BEFORE the dialog is
 * shown as well as after: a process death mid-dialog must not leave this
 * looking unasked, because that is the one bug that costs somebody their only
 * chance silently.
 */
class ActivationRequestActivity : ComponentActivity() {

    private val request = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        record(granted)
        if (granted) activate()
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val state = ActivationConsent.load(filesDir)
        if (!ActivationConsent.canAsk(state)) {
            // Reached twice somehow. Do nothing rather than ask again.
            finish()
            return
        }
        request.launch(ActivationConsent.PERMISSION)
    }

    private fun record(granted: Boolean) {
        val state = ActivationConsent.load(filesDir)
        if (!ActivationConsent.canAsk(state)) return
        ActivationConsent.save(filesDir, ActivationConsent.record(state, granted))
    }

    /**
     * Put the face they just sent on the watch.
     *
     * Only ever reached from a grant. Failure here is not worth surfacing on the
     * watch: the face is installed either way and they can reach it by
     * long-pressing, which is exactly what the device's persistent note says.
     */
    private fun activate() {
        val slotId = intent.getStringExtra(EXTRA_SLOT_ID) ?: return
        runCatching {
            val manager = androidx.wear.watchfacepush.WatchFacePushManagerFactory
                .createWatchFacePushManager(this)
            kotlinx.coroutines.runBlocking { manager.setWatchFaceAsActive(slotId) }
        }.onFailure { Log.w(TAG, "could not switch to the new face", it) }
    }

    companion object {
        private const val TAG = "BfgActivation"
        private const val EXTRA_SLOT_ID = "slotId"

        fun intent(context: Context, slotId: String): Intent =
            Intent(context, ActivationRequestActivity::class.java)
                .putExtra(EXTRA_SLOT_ID, slotId)
    }
}
