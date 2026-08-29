package com.bfg.watchfaces.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bfg.watchfaces.appcore.ActivationConsent

/**
 * What the device says before the first face is sent.
 *
 * Operator decision 01a049a1-390b-7b50-a5d3-cc082037bb55 splits the flow: the
 * DEVICE explains, and the WATCH puts the actual permission dialog up the first
 * time a face lands on it. So there is no permission request anywhere in this
 * file, and there cannot be — `androidx.wear.watchfacepush` declares
 * `<uses-library android:name="wear-sdk" android:required="true" />`, so an app
 * linking it will not install on a phone at all.
 *
 * That split is the reason this screen has to be good. It is the only place with
 * room to explain, and by the time the watch asks, its one-line system dialog is
 * all the person will see.
 *
 * Every string comes from [ActivationConsent] rather than a local `strings.xml`,
 * for a reason worth keeping: the same words have to read identically here, in
 * the localhost app that specifies this screen, and in whatever the watch shows.
 * A copy per surface is three copies that drift.
 */
@Composable
fun ActivationHandoffScreen(
    faceName: String,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // safeDrawing, not a fixed top pad: enableEdgeToEdge() draws
                // behind the status bar, and without this the title sits on top
                // of the clock and the signal icons. Seen on a real screen, not
                // reasoned about -- it does not show up in any test.
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            Text(
                text = "Send “$faceName” to your watch",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Here is what happens.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(20.dp))
            ActivationConsent.HANDOFF.forEachIndexed { index, step ->
                HandoffStep(number = index + 1, title = step.title, detail = step.detail)
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onCancel) { Text("Not yet") }
                Spacer(Modifier.size(8.dp))
                Button(onClick = onContinue) { Text("Send to watch") }
            }
        }
    }
}

@Composable
private fun HandoffStep(number: Int, title: String, detail: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .let { it },
            contentAlignment = Alignment.Center
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = number.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
        Spacer(Modifier.size(14.dp))
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Shown wherever a saved face lives, once the watch has been asked and refused.
 *
 * Persistent, as the operator asked — not a toast. It has to still be there next
 * week, when someone has sent a face and is wondering why nothing happened. It
 * is instructions, not a second sales pitch: nothing can reopen the choice, so
 * anything persuasive here is nagging about a locked door.
 *
 * Returns nothing at all unless the state is DENIED, which is
 * [ActivationConsent.persistentNote]'s job to decide rather than this screen's.
 */
@Composable
fun ActivationDeniedNote(state: ActivationConsent.State, modifier: Modifier = Modifier) {
    val note = ActivationConsent.persistentNote(state) ?: return
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium
    ) {
        Text(
            text = note,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
