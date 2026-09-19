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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bfg.watchfaces.appcore.FaceLibrary

/**
 * Naming a face, which is the moment it becomes one.
 *
 * `CLAUDE.md` is explicit: there is no default face, and a design gets its
 * identity from the person who made it. That name becomes three things at once —
 * the carousel label on the watch, the `watchfacepush.<slug>` package, and the
 * APK filename — so this sheet is not a formality, it is where the artefact is
 * decided.
 *
 * Two things are shown rather than discovered later:
 *
 * The SLUG, because [FaceLibrary.slugify] is deliberately ASCII-only. Watch Face
 * Push wants `^[a-z][a-z0-9_]*$`, so "Café Crème" becomes `caf_cr_me` — and
 * finding that out at install time is far too late.
 *
 * A COLLISION, because two different names can slug to one package. Saving over
 * somebody's face silently is the kind of loss there is no undo for.
 *
 * ## [suggested] is the difference between naming and confirming
 *
 * A face from the gallery ALREADY HAS A NAME — somebody chose it, and the
 * catalog carries it beside the parameters. Opening one and being asked to
 * invent a name for it reads as though the app lost it. Reported by the
 * operator on 2026-09-18: "it makes me save a name and that is a different
 * issue — it should use the community name."
 *
 * So a suggestion pre-fills the field and the words change with it: naming when
 * there is nothing to go on, confirming when there is. It is still a FIELD
 * rather than a silent save, because the slug and the collision above do not
 * stop mattering just because a name arrived from somewhere else — two people
 * may both have published "Midnight", and the second one to arrive would
 * otherwise replace the first on the watch with no warning.
 *
 * A PRESET deliberately sends no suggestion. `CLAUDE.md` is explicit that a
 * style is a starting point rather than a face, and "Rosette Noir" is the name
 * of the style, not of the thing somebody is making from it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NameSheet(
    existing: (String) -> FaceLibrary.StoredFace?,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    /** The name this design arrived with, if it had one. Empty for a preset. */
    suggested: String = ""
) {
    // Keyed on the suggestion so reopening the sheet for a DIFFERENT face
    // starts from that face's name rather than the previous one's.
    var name by remember(suggested) { mutableStateOf(suggested) }
    val trimmed = name.trim()
    val slug = if (trimmed.isEmpty()) "" else FaceLibrary.slugify(trimmed)
    val clash = if (trimmed.isEmpty()) null else existing(trimmed)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                if (suggested.isEmpty()) "Name this face" else "Keep this name?",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (suggested.isEmpty())
                    "This is what you will look for on your watch, so give it a name of " +
                        "your own rather than the style you started from."
                else
                    "This is what you will look for on your watch. It came with this " +
                        "name — keep it, or make it your own.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 40) name = it },
                singleLine = true,
                label = { Text("Name") },
                placeholder = { Text("Midnight Knot") },
                isError = clash != null,
                supportingText = {
                    Text(
                        when {
                            clash != null -> "“${clash.name}” already uses this name. Saving replaces it."
                            slug.isNotEmpty() -> "Your watch will install this as $slug"
                            else -> "Up to 40 characters"
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onSave(trimmed) },
                    enabled = trimmed.isNotEmpty()
                ) { Text(if (clash != null) "Replace" else "Save") }
            }
        }
    }
}
