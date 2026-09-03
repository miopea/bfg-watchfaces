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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            var faces by remember { mutableStateOf<InstalledFaces?>(null) }

            // The permission may be granted or refused in another activity, so
            // re-read on every resume rather than trusting the value we started
            // with -- otherwise the screen keeps offering a button for a dialog
            // that has already been answered.
            LaunchedOnResume {
                consent = Activation.state(this)
                notifications = notificationsGranted()
                // Off the main thread: listWatchFaces binds to a system service.
                faces = withContext(Dispatchers.IO) { readInstalledFaces() }
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

                            // WHAT IS ON THE WATCH, first.
                            //
                            // The screen used to open on a status sentence about
                            // a permission. That is the app talking about itself.
                            // The thing somebody came here to find out is whether
                            // their faces arrived.
                            item { Spacer(Modifier.height(8.dp)) }
                            item { FacesSummary(faces) }

                            item { Spacer(Modifier.height(12.dp)) }
                            item { SectionLabel("Permissions") }

                            // BOTH permissions, ALWAYS, whatever their state.
                            //
                            // Previously each line appeared only when something
                            // was wrong, so a working app showed nothing and
                            // there was nowhere to look when it stopped working.
                            // A permission screen that hides granted permissions
                            // cannot answer "is it still on?".
                            item {
                                PermissionLine(
                                    name = "Switching faces",
                                    state = switchingSummary(consent)
                                )
                            }
                            if (ActivationConsent.canAsk(consent)) {
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
                            if (consent == ActivationConsent.State.DENIED) {
                                // There is no second ask -- ActivationConsent is
                                // one-shot and the system will not show the
                                // dialog again. Saying so is the only honest
                                // thing; offering a button that cannot work is
                                // worse than offering none.
                                item {
                                    Hint(
                                        "This cannot be asked again. Faces still arrive — " +
                                            "pick them from the watch face list."
                                    )
                                }
                            }

                            item { Spacer(Modifier.height(4.dp)) }
                            item {
                                PermissionLine(
                                    name = "Notifications",
                                    state = if (notifications) "On" else "Off"
                                )
                            }
                            if (!notifications) {
                                item {
                                    Hint("Without these you will not be asked when a face arrives.")
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

                            item { Spacer(Modifier.height(12.dp)) }
                            item { SectionLabel("On your phone") }
                            item {
                                Hint("Faces are designed on the phone and sent here.")
                            }
                            item {
                                Button(
                                    onClick = { openOnPhone() },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Open on phone") }
                            }

                            item { Spacer(Modifier.height(14.dp)) }
                            item {
                                // Version last and quiet. Nobody opens the app
                                // for it, and everybody is asked for it the
                                // moment something goes wrong.
                                Text(
                                    "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                )
                            }
                            item { Spacer(Modifier.height(8.dp)) }
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
    private fun LaunchedOnResume(block: suspend () -> Unit) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.RESUMED) { block() }
            }
        }
    }

    /**
     * What `listWatchFaces` sees, in the two numbers this screen shows.
     *
     * [active] is the name of ours being worn right now, or null. Read here
     * rather than in the composable because it binds to a system service.
     */
    data class InstalledFaces(val count: Int, val free: Int, val active: String?)

    private suspend fun readInstalledFaces(): InstalledFaces? = runCatching {
        val manager = androidx.wear.watchfacepush.WatchFacePushManagerFactory
            .createWatchFacePushManager(this)
        val listed = manager.listWatchFaces()
        val details = listed.installedWatchFaceDetails
        val worn = details.firstOrNull {
            runCatching { manager.isWatchFaceActive(it.packageName) }.getOrDefault(false)
        }
        InstalledFaces(
            count = details.size,
            free = listed.remainingSlotCount,
            // The package suffix IS the face's slug -- see FaceLibrary. It is
            // not the display name, but it is the only name available here
            // without reading the other package's resources, and it is the name
            // the person typed.
            active = worn?.packageName?.substringAfterLast('.')?.replace('_', ' ')
        )
    }.getOrNull()

    /**
     * Open the phone app from the watch.
     *
     * `RemoteActivityHelper` is the only supported route: an app cannot reach
     * across the Data Layer and start an activity itself. The intent is the
     * private `bfgwatchfaces://open` scheme the phone manifest declares, so
     * this lands IN the app rather than on its Play listing.
     */
    private fun openOnPhone() {
        // A ListenableFuture, not a coroutine. Awaiting it would mean adding
        // kotlinx-coroutines-guava for one call, so this reads the result in a
        // listener instead.
        val future = androidx.wear.remote.interactions.RemoteActivityHelper(this)
            .startRemoteActivity(
                Intent(Intent.ACTION_VIEW)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .setData(android.net.Uri.parse("bfgwatchfaces://open"))
            )
        future.addListener({
            // No phone in range, or nothing there to open it. Never a crash:
            // this is a convenience and the watch works without it.
            runCatching { future.get() }.onFailure {
                android.util.Log.w("BfgWatchActivity", "could not open the phone app", it)
            }
        }, ContextCompat.getMainExecutor(this))
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

    /** A quiet heading, so the screen has structure instead of a wall of lines. */
    @Composable
    private fun SectionLabel(text: String) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
        )
    }

    /** Secondary explanatory text. */
    @Composable
    private fun Hint(text: String) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
        )
    }

    /** One permission and whether it is on, stated the same way every time. */
    @Composable
    private fun PermissionLine(name: String, state: String) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(name, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            Text(
                state,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }

    /**
     * What has arrived, in a sentence.
     *
     * Null means the service could not be read, which is DIFFERENT from zero
     * faces and must not be shown as "none" -- that would be the screen making
     * a claim it cannot support.
     */
    @Composable
    private fun FacesSummary(faces: InstalledFaces?) {
        if (faces == null) {
            Hint("Checking what is installed...")
            return
        }
        val headline = when (faces.count) {
            0 -> "No faces yet"
            1 -> "1 face from this app"
            else -> "${faces.count} faces from this app"
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(headline, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            if (faces.active != null) {
                Text(
                    "Wearing ${faces.active}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )
            }
            // Slots are finite and the limit is 1 on some watches. Somebody
            // whose next send will replace a face should be able to see that
            // coming rather than discover it.
            Text(
                if (faces.free == 0) "No free slots — the next face replaces one"
                else "${faces.free} free slot${if (faces.free == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }

    /** The activation permission, said as an outcome rather than a state name. */
    private fun switchingSummary(state: ActivationConsent.State): String = when (state) {
        ActivationConsent.State.UNASKED -> "Not asked yet"
        ActivationConsent.State.ASKING -> "Waiting on your answer"
        ActivationConsent.State.GRANTED -> "On — faces switch automatically"
        ActivationConsent.State.DENIED -> "Off — you pick faces yourself"
    }
}
