package com.bfg.watchfaces.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bfg.watchfaces.generator.ControlInventory
import com.bfg.watchfaces.generator.DialParams

/**
 * The eleven sliders, in a sheet rather than down the page.
 *
 * ## The dial has to stay visible while you drag
 *
 * This is the whole reason the sheet exists, and the reason the localhost app
 * marks it `short`. Inline, the sliders sat below the preview: by the time you
 * reached "Line width" the dial had scrolled off the top, so you were adjusting
 * an engraving you could not see and scrolling up after every nudge to find out
 * what you had done.
 *
 * A partially-expanded sheet leaves the watch on screen above it. The point is
 * not tidiness — it is that fine tuning is a feedback loop, and a loop you
 * cannot see is just guessing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TuneSheet(
    params: DialParams,
    onParams: (DialParams) -> Unit,
    sheetState: SheetState,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text("Fine tune", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "The watch stays visible above. Drag and watch it change.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            for (target in ControlInventory.Target.entries) {
                val controls = ControlInventory.forFace(params).filter { it.target == target }
                if (controls.isEmpty()) continue
                Spacer(Modifier.height(18.dp))
                SectionHeading(
                    when (target) {
                        ControlInventory.Target.PATTERN -> Presentation.SECTION_PATTERN
                        ControlInventory.Target.LAYOUT -> Presentation.SECTION_LAYOUT
                    }
                )
                for (control in controls) ControlSlider(control, params, onParams)
            }
        }
    }
}

@Composable
private fun ControlSlider(
    control: ControlInventory.Control,
    params: DialParams,
    onParams: (DialParams) -> Unit
) {
    val value = ControlInventory.valueOf(params, control.id).toFloat()
    Column(Modifier.padding(top = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(Presentation.label(control.id), style = MaterialTheme.typography.bodyMedium)
            Text(
                text = if (control.integral) value.toInt().toString()
                else String.format("%.2f", value),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = value,
            // Continuous, with the value snapped on the way in rather than
            // `steps` set. Compose draws a tick per step, and `scale` has 152 of
            // them -- the track came out as a striped bar you could not read a
            // position off. ControlInventory.snap keeps the grid honest without
            // rendering it.
            onValueChange = {
                onParams(ControlInventory.with(params, control.id, ControlInventory.snap(control, it.toDouble())))
            },
            valueRange = control.min.toFloat()..control.max.toFloat()
        )
    }
}
