package com.bfg.watchfaces.mobile

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * The reason a send failed, for somebody who is not holding a laptop.
 *
 * ## Why this exists
 *
 * A shipped 1.80 build could not send any face whose text was dark. The
 * validator was refusing it with an error naming the element, the line and what
 * it expected instead. What the person saw was "something went wrong on our
 * end", and so what they could tell us was "it doesn't work".
 *
 * Getting from there to the cause took developer mode on their phone, wireless
 * adb pairing, an SSH tunnel to a build box and about forty scripted sends — to
 * read one line they could have copied in five seconds. The person who uses
 * this app most could not have produced any of that, and should not have to.
 *
 * ## Why the sentence still comes first
 *
 * This deliberately does NOT go back to showing the validator's own output. It
 * did that once: people met `CheckFailure(name=Watch Face Format, category=...)`
 * running into a SAXParseException, which tells somebody who broke nothing that
 * they broke something. The sentence stays, stays first, and stays friendly.
 * The cause lives one tap behind it, for the person who wants to help rather
 * than the person who just wants their face.
 *
 * ## Why Copy is the whole point
 *
 * Reading the reason is worth little if it cannot travel. The text is built to
 * be pasted into a message: the app version first, because "which build" is the
 * first question every report needs and the last one anybody thinks to include.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FailureSheet(
    detail: String,
    sheetState: SheetState,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember(detail) { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text("What went wrong", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                "Nothing here is your fault, and your design is safe. If you send " +
                    "this to us it is usually enough to find the problem.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            // Scrollable and height-capped: a validator message can run to
            // several lines, and a sheet that grows past the screen puts its
            // own buttons out of reach.
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState())
            )
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) { Text("Close") }
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    // LocalClipboard rather than the deprecated
                    // LocalClipboardManager: setting a clip suspends now, so
                    // this goes through the composition's scope.
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(ClipData.newPlainText("BFG Watch Faces", detail))
                        )
                        copied = true
                    }
                }) { Text(if (copied) "Copied" else "Copy") }
            }
        }
    }
}
