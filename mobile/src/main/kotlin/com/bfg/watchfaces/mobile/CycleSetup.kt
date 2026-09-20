package com.bfg.watchfaces.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.health.connect.client.PermissionController
import com.bfg.watchfaces.generator.DialParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Whether a component name is this app's cycle complication on the watch.
 *
 * Matched on the class rather than the whole component, because the package is
 * the watch app's and that is asserted in one place already. A slot pointing
 * here is what makes the cycle setup worth showing at all.
 *
 * The class name comes from [DialParams.CYCLE_PROVIDER_CLASS] rather than a
 * literal here: the same string decides whether a face can be SHARED, and two
 * copies of it would agree right up until somebody renamed the service.
 */
fun isCycleProvider(component: String?): Boolean =
    component != null &&
        component.substringAfter('/').endsWith(DialParams.CYCLE_PROVIDER_CLASS)

/**
 * The explanation and the permission, on the phone, where there is room.
 *
 * ## Why this is here and not on the watch
 *
 * The dial shows an em dash when there is nothing, like every other empty slot.
 * That is deliberate: a round screen is a poor place to read anything careful,
 * and the same split already governs `ActivationConsent`. Everything that needs
 * explaining is explained here, next to the control that fixes it.
 *
 * ## Why the permission is asked HERE
 *
 * Only once she has pointed a slot at the cycle source. An app that asks for
 * menstrual data before being told what for has no answer to "why", and this
 * way the ask arrives with its reason already on screen.
 *
 * ## The empty state is not an edge case
 *
 * [CycleSource.Availability.NoRecords] is the permanent state for anyone whose
 * tracking app does not write to Health Connect — Clue writes nothing at all.
 * So "no records" must never be phrased as her having done something wrong, and
 * must never be answered by asking again for a permission she has already
 * granted. It says the app she tracks in may not be sharing, because that is
 * usually the truth.
 */
@Composable
fun CycleSetup(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<CycleSource.Availability?>(null) }
    var working by remember { mutableStateOf(false) }

    fun refresh() {
        working = true
        scope.launch {
            // Health Connect reads and the Data Layer send both block. On the
            // main thread Tasks.await throws, and this codebase has already
            // lost a day to that exact mistake being swallowed by a
            // runCatching. See WatchProviders.
            state = withContext(Dispatchers.IO) { CycleSender.sync(context) }
            working = false
        }
    }

    val ask = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { _ ->
        // Re-read whatever she chose, and push the result either way. A DENIAL
        // has to reach the watch too: it clears the date, so a slot cannot keep
        // counting from something she has just withdrawn. The granted set is
        // ignored on purpose -- CycleSource.read asks the permission controller
        // itself, and trusting one answer over the other is how the two drift.
        refresh()
    }

    LaunchedEffect(Unit) { refresh() }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Cycle day", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))

            // Said HERE, once, rather than discovered as a missing Share
            // button -- the same reasoning PhotoRow gives for saying it beside
            // the photo picker. A face with this slot is isLocalOnly, and the
            // reason is not obvious: no reading is ever stored in a face, but
            // the provider's NAME is, and publishing it would tell everyone who
            // downloaded the face that its author tracks a cycle.
            Text(
                "A design with this on it stays on your phone and can't be shared.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))

            when (val s = state) {
                null -> Text("Checking…", style = MaterialTheme.typography.bodyMedium)

                CycleSource.Availability.Unsupported -> Text(
                    "This phone does not have Health Connect, so there is nowhere to read " +
                        "your cycle from. The slot will stay empty.",
                    style = MaterialTheme.typography.bodyMedium
                )

                CycleSource.Availability.NotGranted -> {
                    Text(
                        "To show the day of your cycle on your watch, this app needs to read " +
                            "your period dates from Health Connect. It reads nothing else, and " +
                            "only the day number is sent to your watch.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { ask.launch(CycleSource.PERMISSIONS) }) { Text("Allow") }
                }

                CycleSource.Availability.NoRecords -> {
                    // Never "you have no records" and never "grant the
                    // permission". She granted it; the app she logs in probably
                    // is not sharing, and that is not something she did wrong.
                    Text(
                        "No period dates have reached Health Connect yet. If you track in " +
                            "another app, check that it is allowed to share Cycle health with " +
                            "Health Connect — Google Health does this under its Health Connect " +
                            "settings. Some apps cannot share at all.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.Start) {
                        TextButton(onClick = { refresh() }, enabled = !working) { Text("Check again") }
                    }
                }

                is CycleSource.Availability.Ready -> {
                    Text(
                        "Your watch is showing day " +
                            "${com.bfg.watchfaces.appcore.CycleDay.dayNumber(s.start, java.time.LocalDate.now())}" +
                            ". It counts on from the last period you logged, so it stays right " +
                            "without this app running.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { refresh() }, enabled = !working) { Text("Update now") }
                }
            }
        }
    }
}
