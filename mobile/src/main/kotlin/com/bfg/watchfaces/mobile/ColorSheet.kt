package com.bfg.watchfaces.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Any colour, not just the nine.
 *
 * The swatches are the fast path and cover most designs. This is the one the
 * localhost app has and this app did not: a saturation/value pad over a hue
 * slider, with the hex readable and typeable.
 *
 * ## Why hex is an input and not a label
 *
 * A dial colour is the thing somebody is most likely to have already decided —
 * from a brand, a photo, another watch. Making them find `#6E6A66` by dragging
 * a thumb is busywork when they can type it. It also makes the sheet the one
 * place a colour can be copied out of and back into.
 *
 * ## The pad is drawn, not sampled
 *
 * Saturation runs left to right and value top to bottom over the current hue,
 * which is the standard arrangement and the same one the localhost app draws.
 * `Color.hsv` does the conversion, so the pad and the readout cannot disagree.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColorSheet(
    title: String,
    initial: String,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    val start = remember(initial) { hsvOf(initial) }
    var hue by remember(initial) { mutableFloatStateOf(start[0]) }
    var sat by remember(initial) { mutableFloatStateOf(start[1]) }
    var value by remember(initial) { mutableFloatStateOf(start[2]) }
    var typed by remember(initial) { mutableStateOf(initial.uppercase()) }

    // The pad and the field are two views of one colour. Dragging updates the
    // field; typing a valid hex moves the thumb. Whichever moved last wins.
    val current = Color.hsv(hue, sat, value)
    val hex = "#%02X%02X%02X".format(
        (current.red * 255).roundToInt(),
        (current.green * 255).roundToInt(),
        (current.blue * 255).roundToInt()
    )

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(14.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2.0f)
                    .clip(RoundedCornerShape(12.dp))
                    .pointerInput(hue) {
                        fun set(o: Offset) {
                            sat = (o.x / size.width).coerceIn(0f, 1f)
                            value = 1f - (o.y / size.height).coerceIn(0f, 1f)
                            typed = ""
                        }
                        detectTapGestures { set(it) }
                    }
                    .pointerInput(hue) {
                        detectDragGestures { change, _ ->
                            sat = (change.position.x / size.width).coerceIn(0f, 1f)
                            value = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                            typed = ""
                        }
                    }
            ) {
                Canvas(Modifier.fillMaxWidth().aspectRatio(2.0f)) {
                    drawRect(
                        Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f)))
                    )
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                }
            }

            Spacer(Modifier.height(14.dp))
            Slider(
                value = hue,
                onValueChange = { hue = it; typed = "" },
                valueRange = 0f..360f
            )

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(current)
                )
                Spacer(Modifier.padding(horizontal = 7.dp))
                OutlinedTextField(
                    value = if (typed.isBlank()) hex else typed,
                    onValueChange = { text ->
                        typed = text.uppercase().take(7)
                        hsvOrNull(typed)?.let { hue = it[0]; sat = it[1]; value = it[2] }
                    },
                    singleLine = true,
                    label = { Text("Hex") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.padding(horizontal = 4.dp))
                Button(onClick = { onPick(hex) }) { Text("Use colour") }
            }
        }
    }
}

/** `#RRGGBB` to HSV, falling back to a mid grey rather than throwing on junk. */
private fun hsvOf(hex: String): FloatArray = hsvOrNull(hex) ?: floatArrayOf(0f, 0f, 0.5f)

private fun hsvOrNull(hex: String): FloatArray? {
    val v = hex.trim().removePrefix("#")
    if (v.length != 6 || v.any { it.digitToIntOrNull(16) == null }) return null
    val out = FloatArray(3)
    android.graphics.Color.colorToHSV(
        android.graphics.Color.parseColor("#$v"), out
    )
    return out
}
