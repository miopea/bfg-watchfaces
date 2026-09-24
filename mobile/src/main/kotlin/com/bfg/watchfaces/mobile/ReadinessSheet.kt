package com.bfg.watchfaces.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bfg.watchfaces.appcore.PushAvailability

/**
 * What to do when the watch cannot take a face, shown BEFORE anything is built.
 *
 * ## The failure this replaces
 *
 * A Pixel Watch 4 owner, 2026-09-24, after waiting through a build and a
 * Bluetooth transfer, read this and nothing else:
 *
 * ```
 * Pixel Watch 4 could not install "Default": ListWatchFacesException | Unknown
 * error while listing watch faces. ... | <- ReceiverConnectionException:
 * Binding to the watch face receiver was unsuccessful
 * ```
 *
 * Four clauses of exception chain, no suggestion, and no idea whether it was
 * her fault. The operator's note when asking for this: "most users don't have
 * me troubleshooting."
 *
 * ## What it does differently
 *
 * The sentence names her watch and says what is wrong in ordinary words. Then
 * it says what to TRY, which is the part that was missing entirely. The cause
 * stays available behind a tap, because a report with no cause is expensive to
 * diagnose — the same division [FailureSheet] makes.
 *
 * **Send anyway stays.** The check can be wrong, a watch can recover, and an
 * app that refuses to try is worse than one that warns. This advises; it does
 * not lock the door.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadinessSheet(
    watchName: String,
    availability: PushAvailability,
    sheetState: SheetState,
    onSendAnyway: () -> Unit,
    onShowDetail: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                availability.headline(watchName),
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Your design is saved, and nothing has been sent yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val steps = availability.whatToTry()
            if (steps.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Text("Worth trying", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                // NUMBERED, because these are things to do in order, and the
                // last one on the no-receiver list is deliberately "this watch
                // may simply not support it" -- an honest end to the list
                // rather than an endless one.
                steps.forEachIndexed { i, step ->
                    Text(
                        "${i + 1}.  $step",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
            }

            Spacer(Modifier.height(22.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                // Only when there IS a cause. An empty details button is a
                // dead end dressed as an answer.
                if (availability.detail.isNotBlank()) {
                    TextButton(onClick = { onShowDetail(availability.detail) }) {
                        Text("What went wrong?")
                    }
                }
                TextButton(onClick = onSendAnyway) { Text("Send anyway") }
                Button(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}
