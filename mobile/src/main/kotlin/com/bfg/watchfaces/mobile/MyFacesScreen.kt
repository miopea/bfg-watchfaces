package com.bfg.watchfaces.mobile

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bfg.watchfaces.appcore.FaceLibrary
import com.bfg.watchfaces.appcore.SubmissionLog
import com.bfg.watchfaces.generator.EngravedStroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The faces this person has made.
 *
 * Saved on this device and nowhere else. There is no account, so there is
 * nothing to sync and nothing to lose access to — and no server holding a copy
 * of anybody's designs.
 *
 * Deleting is the one destructive action on this screen, so it asks first. Not
 * ceremony: a face is a thing somebody made, and the list is the only copy.
 */
private const val ROW_PX = 160

@Composable
fun MyFacesScreen(
    faces: List<FaceLibrary.StoredFace>,
    onOpen: (FaceLibrary.StoredFace) -> Unit,
    onSend: (FaceLibrary.StoredFace) -> Unit,
    onDelete: (FaceLibrary.StoredFace) -> Unit,
    onShare: (FaceLibrary.StoredFace) -> Unit,
    /**
     * What each face has been shared as, keyed by slug.
     *
     * Passed in rather than read here so the list does not hit the disk once
     * per row per recomposition, and so a share or a withdrawal updates every
     * row at once instead of whichever happened to redraw.
     */
    shared: Map<String, SubmissionLog.Record>,
    /**
     * Whether sharing is possible at all right now.
     *
     * False hides the button entirely rather than disabling it. A share button
     * that cannot work is worse than no button -- that is why there was no
     * share UI for so long, and it stays true when the service is the thing
     * that is switched off.
     */
    canShare: Boolean,
    modifier: Modifier = Modifier
) {
    var confirming by remember { mutableStateOf<FaceLibrary.StoredFace?>(null) }

    if (faces.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No faces yet", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Pick a style, make it yours, and give it a name. It will be here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
        return
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(faces, key = { it.slug }) { face ->
            FaceRow(
                face,
                shared = shared[face.slug],
                canShare = canShare,
                onOpen = { onOpen(face) },
                onSend = { onSend(face) },
                onShare = { onShare(face) },
                onDelete = { confirming = face }
            )
            HorizontalDivider()
        }
    }

    confirming?.let { face ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Delete “${face.name}”?") },
            text = { Text("This is the only copy. It cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { onDelete(face); confirming = null }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) { Text("Keep") }
            }
        )
    }
}

@Composable
private fun FaceRow(
    face: FaceLibrary.StoredFace,
    shared: SubmissionLog.Record?,
    canShare: Boolean,
    onOpen: () -> Unit,
    onSend: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    // A face can name an app this watch does not have. It still renders, just
    // differently from its preview, and nothing else would say so.
    val missing = MissingApps.note(LocalContext.current, face.params)
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = face.slug) {
        value = withContext(Dispatchers.Default) {
            runCatching { AndroidFacePreview.render(face.params, ambient = false, size = ROW_PX) }.getOrNull()
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Color(EngravedStroke.withAlpha(EngravedStroke.rgb(face.params.dialColor), 255))),
            contentAlignment = Alignment.Center
        ) {
            bitmap?.let {
                Image(
                    painter = BitmapPainter(it.asImageBitmap()),
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    contentScale = ContentScale.FillWidth
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(face.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                // The slug is what the watch will call this face's package, so
                // it is worth showing: two names can collide into one.
                face.slug,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Quiet, not a warning: the face works, it just will not look the
            // way its preview does.
            missing?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            // Said in the SAME words the share sheet uses, from one function,
            // so the row and the sheet cannot describe one state two ways.
            shared?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    statusOf(it.state),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // Send is the point of having saved it. Putting it behind "open in the
        // studio, then send" made the list a staging area rather than a library.
        TextButton(onClick = onSend) { Text("Send") }
        // "Shared" rather than "Share" once it is: the button still opens the
        // same sheet, but it is now the way back to taking it down, and
        // labelling that "Share" would invite somebody to send it twice.
        if (canShare) TextButton(onClick = onShare) { Text(if (shared == null) "Share" else "Shared") }
        TextButton(onClick = onDelete) { Text("Delete") }
    }
}
