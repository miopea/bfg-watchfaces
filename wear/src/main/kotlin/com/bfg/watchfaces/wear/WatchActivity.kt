package com.bfg.watchfaces.wear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.bfg.watchfaces.appcore.ActivationConsent
import kotlinx.coroutines.launch

/**
 * The watch app's one screen.
 *
 * ## Why this exists at all
 *
 * Until now `:wear` had no launcher activity. It was a `WearableListenerService`
 * and a permission-request activity, which is enough to RECEIVE a face and
 * nothing else — installed from Play it was invisible. A tester in the internal
 * ring would install it, find nothing in the launcher, and have no way to tell
 * whether it worked. An app nobody can open cannot be tested by anybody.
 *
 * So this screen answers the three questions a tester actually has, in order:
 * is the app installed and running, has the one-shot activation permission been
 * dealt with, and how many faces have arrived.
 *
 * ## It does not ask for the permission on its own
 *
 * `ActivationConsent` is a ONE-SHOT rule and it guards the only unrecoverable
 * action in the system: once the watch has asked and been refused, nothing can
 * reopen the dialog. The button here is only offered while
 * [ActivationConsent.canAsk] is true, and it routes through the same
 * [ActivationRequestActivity] the install path uses rather than requesting
 * anything itself — one path to the dialog, not two.
 *
 * Being launched by a person is also the one context where `startActivity` is
 * not blocked, which is the whole problem recorded in `DECISIONS.md`
 * 2026-08-29: the install path cannot raise this dialog because a
 * `WearableListenerService` is a background context.
 */
class WatchActivity : ComponentActivity() {

    /**
     * Notifications, which the activation prompt cannot live without.
     *
     * `POST_NOTIFICATIONS` was declared in the manifest and never requested, and
     * the failure was completely silent: a face arrives, installs, and
     * [ActivationPrompt] finds `areNotificationsEnabled()` false and posts
     * nothing. Nobody is ever asked whether the watch may switch faces, so the
     * face sits there installed and inactive with no sign anything happened.
     *
     * Asked here because this is the only screen, and because a person opening
     * the app is the one moment when asking is not an ambush. The install path
     * cannot ask: it is a background context, which is the same wall that stops
     * it raising the activation dialog itself.
     */
    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun notificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!notificationsGranted()) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            var consent by remember { mutableStateOf(Activation.state(this)) }
            var notifications by remember { mutableStateOf(notificationsGranted()) }

            // The permission may be granted or refused in another activity, so
            // re-read on every resume rather than trusting the value we started
            // with -- otherwise the screen keeps offering a button for a dialog
            // that has already been answered.
            LaunchedOnResume {
                consent = Activation.state(this)
                notifications = notificationsGranted()
            }

            MaterialTheme {
                AppScaffold {
                    val listState = rememberTransformingLazyColumnState()
                    ScreenScaffold(scrollState = listState) { contentPadding ->
                        TransformingLazyColumn(
                            state = listState,
                            contentPadding = contentPadding,
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            item {
                                Text(
                                    "BFG Watch Faces",
                                    style = MaterialTheme.typography.titleMedium,
                                    textAlign = TextAlign.Center
                                )
                            }
                            item { Spacer(Modifier.height(6.dp)) }
                            item {
                                Text(
                                    statusFor(consent),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                )
                            }
                            if (!notifications) {
                                item { Spacer(Modifier.height(10.dp)) }
                                item {
                                    Text(
                                        "Turn notifications on, or you will not be asked " +
                                            "when a face arrives.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(horizontal = 12.dp)
                                    )
                                }
                                item {
                                    Button(
                                        onClick = {
                                            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("Allow notifications") }
                                }
                            }
                            if (ActivationConsent.canAsk(consent)) {
                                item { Spacer(Modifier.height(10.dp)) }
                                item {
                                    Button(
                                        onClick = {
                                            startActivity(
                                                Intent(this@WatchActivity, ActivationRequestActivity::class.java)
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("Allow switching") }
                                }
                            }
                            item { Spacer(Modifier.height(10.dp)) }
                            item {
                                Text(
                                    "Design faces on your phone and send them here.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Runs [block] now and on every resume.
     *
     * Small on purpose: the alternative is a lifecycle observer per state, and
     * this screen has exactly one thing that can change behind its back.
     */
    @Composable
    private fun LaunchedOnResume(block: () -> Unit) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.RESUMED) { block() }
            }
        }
    }

    /**
     * The state in words a person can act on.
     *
     * Deliberately not "GRANTED"/"DENIED": the person does not have the model,
     * they have a watch that either switches faces by itself or does not.
     */
    private fun statusFor(state: ActivationConsent.State): String = when (state) {
        ActivationConsent.State.UNASKED ->
            "Ready. The first face you send will ask whether it may switch your watch face for you."
        ActivationConsent.State.ASKING ->
            "Waiting on your answer to the permission dialog."
        ActivationConsent.State.GRANTED ->
            "Ready. Faces you send will appear and switch on automatically."
        ActivationConsent.State.DENIED ->
            "Faces you send will still arrive, but you will need to pick them yourself " +
                "from the watch face picker."
    }
}
