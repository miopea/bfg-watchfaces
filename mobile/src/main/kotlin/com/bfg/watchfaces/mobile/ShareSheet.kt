package com.bfg.watchfaces.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bfg.watchfaces.appcore.SubmissionLog

/**
 * Sharing a face you made.
 *
 * ## The words here are for the person, not the system
 *
 * There is no "submission", no "moderation queue", no "slug" and no id on this
 * screen. Somebody sharing a watch face is not administering a service: they
 * put a thing they made somewhere other people can find it, someone checks it,
 * and they can take it back. That is the whole model and it is the only model
 * this sheet describes.
 *
 * ## Signing in, and saying why before asking
 *
 * The account is asked for AFTER the explanation and immediately before it is
 * needed, because a sign-in sheet that appears with no reason attached is the
 * moment most people cancel. And the reason is a real one: it is what lets the
 * author take the face back later. Nothing else in the app needs an account —
 * browsing, installing and reporting all stay anonymous — so this is the one
 * place it can appear at all.
 *
 * ## Why this sheet holds no state and starts no work
 *
 * IT CANNOT. Signing in opens Google's own activity, which takes focus, which
 * makes `ModalBottomSheet` fire `onDismissRequest` — and a sheet that removes
 * itself takes its `rememberCoroutineScope` with it, cancelling the submit
 * half way through. Measured on an emulator: the account picker appeared, the
 * account was chosen, and nothing ever reached the catalog. No crash, no
 * error, an empty queue.
 *
 * So the work and every piece of state it touches live in the caller, whose
 * composition outlives this sheet, and the sheet is a view of them. The same
 * reason keeps [onDismiss] from closing anything while [busy].
 *
 * ## What it must not promise
 *
 * Not "your face is live", because it is not: nothing appears until a person
 * looks, and that can be weeks. Saying "shared!" and showing nothing in the
 * gallery would read as a bug in the app rather than a queue working, and the
 * author would submit it again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(
    faceName: String,
    existing: SubmissionLog.Record?,
    /** What just finished, if anything. Null while there is still a choice to make. */
    outcome: ShareOutcome?,
    busy: Boolean,
    failure: String?,
    onShare: (author: String) -> Unit,
    onTakeBack: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var author by remember { mutableStateOf("") }

    ModalBottomSheet(
        // Refuses to close mid-flight. Google's account picker taking focus is
        // a dismiss request, and honouring it here is what silently dropped
        // the first real submission.
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = sheetState
    ) {
        // Scrolls, for the same reason ReportSheet does: on a short screen the
        // buttons otherwise sit below the fold with no way to reach them.
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            when {
                outcome == ShareOutcome.WITHDRAWN -> Finished(
                    title = "Taken back",
                    body = "“$faceName” is no longer shared. It is still saved on this phone, " +
                        "and anyone who already installed it keeps it.",
                    onDismiss = onDismiss
                )

                outcome == ShareOutcome.SENT -> Finished(
                    title = "Sent",
                    body = "Someone will look at “$faceName” before it appears for other " +
                        "people. That can take a while. You can take it back any time.",
                    onDismiss = onDismiss
                )

                existing != null -> Shared(
                    faceName = faceName,
                    record = existing,
                    busy = busy,
                    failure = failure,
                    onDismiss = onDismiss,
                    onTakeBack = onTakeBack
                )

                else -> Offer(
                    faceName = faceName,
                    author = author,
                    onAuthorChange = { author = it.take(40) },
                    busy = busy,
                    failure = failure,
                    onDismiss = onDismiss,
                    onShare = { onShare(author) }
                )
            }
        }
    }
}

/** What a share attempt ended as, for the sheet to report. */
enum class ShareOutcome { SENT, WITHDRAWN }

@Composable
private fun Finished(title: String, body: String, onDismiss: () -> Unit) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(20.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Button(onClick = onDismiss) { Text("Done") }
    }
}

@Composable
private fun Offer(
    faceName: String,
    author: String,
    onAuthorChange: (String) -> Unit,
    busy: Boolean,
    failure: String?,
    onDismiss: () -> Unit,
    onShare: () -> Unit
) {
    Text("Share “$faceName”", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    Text(
        "Other people will be able to find this face and put it on their own watch. " +
            "Only its settings are shared — the pattern, the colours, the layout — never " +
            "anything else from your phone.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(12.dp))
    Text(
        "Someone checks every face before it appears, so it will not show up straight away.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = author,
        onValueChange = onAuthorChange,
        label = { Text("Your name on it (optional)") },
        // Said next to the field rather than after the fact: this is the one
        // thing on this screen that other people will read.
        supportingText = { Text("Shown publicly. Leave it empty to share without a name.") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = !busy
    )

    Spacer(Modifier.height(12.dp))
    Text(
        "You will be asked to pick a Google account. That is only so you can take this " +
            "face back later — nothing is posted to it.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    failure?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }

    Spacer(Modifier.height(20.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.height(20.dp))
            Spacer(Modifier.height(0.dp))
        }
        TextButton(onClick = onDismiss, enabled = !busy) { Text("Not now") }
        Button(enabled = !busy, onClick = onShare) { Text("Share") }
    }
}

@Composable
private fun Shared(
    faceName: String,
    record: SubmissionLog.Record,
    busy: Boolean,
    failure: String?,
    onDismiss: () -> Unit,
    onTakeBack: () -> Unit
) {
    Text("“$faceName” is shared", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    Text(
        SubmissionLog.describe(record.state),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(12.dp))
    Text(
        "Taking it back removes it from the gallery. Anyone who already installed it keeps " +
            "it — nothing here can reach a watch it is already on.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    failure?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }

    Spacer(Modifier.height(20.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.height(20.dp))
            Spacer(Modifier.height(0.dp))
        }
        TextButton(onClick = onDismiss, enabled = !busy) { Text("Close") }
        Button(enabled = !busy, onClick = onTakeBack) { Text("Take it back") }
    }
}
