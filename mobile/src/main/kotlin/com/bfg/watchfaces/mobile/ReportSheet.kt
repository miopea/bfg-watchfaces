package com.bfg.watchfaces.mobile

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.bfg.watchfaces.appcore.CatalogService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reporting a community face.
 *
 * ## Why this needs no sign-in, and must not gain one
 *
 * Publishing a face needs a Google account. Complaining never does, and that
 * asymmetry is deliberate: requiring an account to report became intolerable
 * the moment submitting did not — "anyone could publish and only developers
 * could complain" is what moved this catalog off GitHub in the first place.
 * Google Play also requires a REACHABLE complaint path for any app showing
 * user content, and one behind a sign-in is less reachable.
 *
 * It is safe to leave open because a report is a MESSAGE, not an action.
 * Nothing here hides a face; a person reads it. With no accounts, "N people
 * reported it" would be one person and a loop, so auto-hiding on a count would
 * hand anyone a takedown button.
 *
 * ## What it says afterwards
 *
 * It promises a person will look, and does NOT promise the face will go. Most
 * of what gets reported is somebody disagreeing, and a report that reads like a
 * delete button teaches people to use it as one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportSheet(
    slug: String,
    faceName: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val service = remember { CatalogAccess.service(context) }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var reason by remember { mutableStateOf(CatalogService.ReportReason.INTELLECTUAL_PROPERTY) }
    var detail by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // SCROLLS. Six reasons, a text field and two buttons do not fit a
        // sheet on a short screen, and without this the Send button sits below
        // the fold with no way to reach it -- the complaint path silently
        // unusable. Found by opening it on a phone, not by reading it.
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            if (sent) {
                Text("Thank you", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                // Careful not to promise the outcome. A report is a message.
                Text(
                    "Someone will look at “$faceName”. We cannot say in advance what will " +
                        "happen to it, but every report is read.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(onClick = onDismiss) { Text("Done") }
                }
                return@Column
            }

            Text("Report “$faceName”", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "No account needed. Tell us what is wrong and someone will look.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            for (option in CatalogService.ReportReason.entries) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = reason == option, onClick = { reason = option })
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = reason == option, onClick = { reason = option })
                    Spacer(Modifier.height(0.dp))
                    Text(option.label, style = MaterialTheme.typography.bodyLarge)
                }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = detail,
                onValueChange = { detail = it.take(2000) },
                label = { Text("Anything else? (optional)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 5,
                enabled = !sending
            )

            failure?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (sending) {
                    CircularProgressIndicator(Modifier.height(20.dp))
                    Spacer(Modifier.height(0.dp))
                }
                TextButton(onClick = onDismiss, enabled = !sending) { Text("Cancel") }
                Button(
                    enabled = !sending,
                    onClick = {
                        sending = true
                        failure = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                service.report(slug, reason, detail)
                            }
                            sending = false
                            when (result) {
                                is CatalogService.Result.Ok -> sent = true
                                is CatalogService.Result.Failed -> failure = result.message
                            }
                        }
                    }
                ) { Text("Send report") }
            }
        }
    }
}
