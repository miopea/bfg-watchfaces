package com.bfg.watchfaces.mobile

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bfg.watchfaces.generator.ControlInventory
import com.bfg.watchfaces.generator.DIAL_SIZE
import com.bfg.watchfaces.generator.DialParams
import com.bfg.watchfaces.generator.ComplicationSource
import com.bfg.watchfaces.generator.Engine
import com.bfg.watchfaces.generator.EngravedStroke
import com.bfg.watchfaces.generator.SlotPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The design surface, ported from the localhost app.
 *
 * `DECISIONS.md` 2026-08-28 makes that app the exact specification for this
 * one, so nothing here is invented: the controls come from
 * [ControlInventory], the labels and order from [Presentation], and the pixels
 * from [AndroidDialRenderer], which shares every decision with the workbench
 * renderer through `:generator`.
 *
 * ## The preview renders off the main thread, and that is not optional
 *
 * A dial is 456x456 and the pattern engines walk real geometry. Doing that
 * inside recomposition would drop frames on every slider drag — the one
 * interaction the whole screen exists for. [produceState] keyed on the
 * parameters moves it to [Dispatchers.Default] and cancels the in-flight render
 * when the finger moves again, so a fast drag costs one render and not thirty.
 */
@Composable
fun StudioScreen(
    params: DialParams,
    onParams: (DialParams) -> Unit,
    ambient: Boolean,
    onAmbient: (Boolean) -> Unit,
    onTune: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        DialPreview(params, ambient)
        AmbientToggle(ambient, onAmbient)

        Spacer(Modifier.height(20.dp))
        SectionHeading("Style")
        EngineChips(params.engine) { onParams(params.copy(engine = it)) }

        Spacer(Modifier.height(20.dp))
        SectionHeading("Dial")
        Swatches(Presentation.DIALS, params.dialColor) { onParams(params.copy(dialColor = it)) }

        Spacer(Modifier.height(16.dp))
        SectionHeading("Ink")
        Swatches(Presentation.INKS, params.inkColor) { onParams(params.copy(inkColor = it)) }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onTune,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Fine tune") }

        Spacer(Modifier.height(20.dp))
        SectionHeading("Complications")
        ChoiceRow(
            label = "Size",
            options = listOf("Small" to 16, "Medium" to 19, "Large" to 23),
            selected = params.layout.complicationSize
        ) { onParams(params.copy(layout = params.layout.copy(complicationSize = it))) }
        ChoiceRow(
            label = "Spacing",
            options = listOf("Tight" to 84, "Normal" to 92, "Wide" to 110),
            selected = params.layout.complicationSpread
        ) { onParams(params.copy(layout = params.layout.copy(complicationSpread = it))) }

        Spacer(Modifier.height(8.dp))
        for (pos in SlotPosition.entries) {
            SlotPicker(pos, params.slot(pos)) { onParams(params.withSlot(pos, it)) }
        }
        Text(
            "Turn one off and the others move over to fill the space.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * The dial, at the size it will actually be worn at.
 *
 * Round, because the watch is: clipping here is not decoration, it is the
 * difference between judging a design and judging a square crop of one.
 */
@Composable
private fun DialPreview(params: DialParams, ambient: Boolean) {
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = params, key2 = ambient) {
        value = withContext(Dispatchers.Default) {
            runCatching { AndroidFacePreview.render(params, ambient, DIAL_SIZE) }.getOrNull()
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(CircleShape)
            // The dial colour behind the render, so the first frame after a
            // change is the right colour rather than a flash of nothing. In
            // ambient the face IS black -- the dial image is not drawn at all --
            // so borrowing the dial colour there would overstate how much is lit.
            .background(
                if (ambient) Color.Black
                else Color(EngravedStroke.withAlpha(EngravedStroke.rgb(params.dialColor), 255))
            ),
        contentAlignment = Alignment.Center
    ) {
        bitmap?.let {
            androidx.compose.foundation.Image(
                painter = BitmapPainter(it.asImageBitmap()),
                contentDescription = "Preview of your watch face",
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth
            )
        }
    }
}

/**
 * Always-on preview.
 *
 * Not decoration: a face is legible in ambient or it is not, and that cannot be
 * judged from the interactive render. The dial image is dropped entirely and
 * most slots go to zero alpha, which is the emitter's own behaviour rather than
 * a dimming effect applied here.
 */
@Composable
private fun AmbientToggle(ambient: Boolean, onAmbient: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("Always-on", style = MaterialTheme.typography.bodyMedium)
            Text(
                "How it looks when your wrist is down",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = ambient, onCheckedChange = onAmbient)
    }
}

/**
 * One complication slot.
 *
 * ## This is an ExposedDropdownMenuBox and it matters that it is
 *
 * The first version was a bare `Row` with a coloured value on the right and a
 * raw `DropdownMenu` hung off it. It looked close enough in a screenshot and was
 * wrong in every way that does not show up in one: no affordance that it opens
 * anything, a touch target that was whatever the text happened to be, no field
 * semantics for TalkBack, and a menu that anchored to the Box rather than to the
 * control, so it opened over the label on a short screen.
 *
 * The Material 3 exposed dropdown is the standard Android answer and supplies
 * all of that: a read-only text field, the trailing chevron people already know,
 * a full-width target, and `menuAnchor` so the list opens where it should. It is
 * also the control this pattern is FOR — one value chosen from a fixed list,
 * with the current value always visible.
 *
 * A dropdown rather than chips because thirteen sources across five slots is
 * sixty-five chips; the localhost app uses a native `select` for the same reason.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SlotPicker(
    pos: SlotPosition,
    selected: ComplicationSource,
    onSelect: (ComplicationSource) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = open,
        onExpandedChange = { open = it },
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    ) {
        OutlinedTextField(
            value = Presentation.label(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(Presentation.label(pos)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (source in ComplicationSource.entries) {
                DropdownMenuItem(
                    text = { Text(Presentation.label(source)) },
                    onClick = { onSelect(source); open = false },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}

/**
 * Complication size and spacing.
 *
 * Present in the localhost app and missing here until now, which mattered more
 * than it sounds: [SlotGeometry] positions the slots from these two numbers, so
 * without them a face could only ever have the one arrangement. Three sizes and
 * three spacings is nine layouts, and they are the difference between a dial
 * that is legible on a 41mm watch and one that is not.
 *
 * A [SingleChoiceSegmentedButtonRow] rather than a slider: these are three named
 * choices, not a continuum, and the localhost app draws them as three buttons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceRow(
    label: String,
    options: List<Pair<String, Int>>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (text, value) ->
                SegmentedButton(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size)
                ) { Text(text) }
            }
        }
    }
}

@Composable
private fun EngineChips(selected: Engine, onSelect: (Engine) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Presentation.ENGINE_ORDER, never Engine.entries -- see Presentation.
        for (engine in Presentation.ENGINE_ORDER) {
            FilterChip(
                selected = engine == selected,
                onClick = { onSelect(engine) },
                label = { Text(Presentation.label(engine)) },
                colors = FilterChipDefaults.filterChipColors()
            )
        }
    }
}

@Composable
private fun Swatches(colors: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        for (hex in colors) {
            val chosen = hex.equals(selected, ignoreCase = true)
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(EngravedStroke.withAlpha(EngravedStroke.rgb(hex), 255)))
                    .border(
                        width = if (chosen) 3.dp else 1.dp,
                        color = if (chosen) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        shape = CircleShape
                    )
                    .clickable { onSelect(hex) }
            )
        }
    }
}

/**
 * One slider, built entirely from the inventory.
 *
 * Range, step and whether it is a whole number all come from `:generator`. A
 * slider that went too far used to be invisible until somebody dragged it,
 * which is why none of those numbers are written here.
 */
@Composable
internal fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}
