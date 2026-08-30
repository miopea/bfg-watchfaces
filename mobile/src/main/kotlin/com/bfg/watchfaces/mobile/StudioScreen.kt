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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.TextButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.Canvas

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
    /** true for the dial, false for the ink. */
    onCustomColor: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        DialPreview(params, ambient)
        AmbientToggle(ambient, onAmbient)
        SwitchRow(
            title = "Show seconds",
            detail = "Only while the watch is awake",
            checked = params.showSeconds
        ) { onParams(params.copy(showSeconds = it)) }
        SwitchRow(
            title = "Complication icons",
            detail = "The small symbol above each value",
            checked = params.showComplicationIcons
        ) { onParams(params.copy(showComplicationIcons = it)) }

        Spacer(Modifier.height(20.dp))
        SectionHeading("Style")
        EngineChips(params.engine) { onParams(params.copy(engine = it)) }

        Spacer(Modifier.height(20.dp))
        SectionHeading("Dial")
        Swatches(Presentation.DIALS, params.dialColor, onCustom = { onCustomColor(true) }) {
            onParams(params.copy(dialColor = it))
        }

        Spacer(Modifier.height(16.dp))
        SectionHeading("Ink")
        Swatches(Presentation.INKS, params.inkColor, onCustom = { onCustomColor(false) }) {
            onParams(params.copy(inkColor = it))
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onTune,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Fine tune") }

        Spacer(Modifier.height(20.dp))
        SectionHeading("Complications")
        ChoiceRow(
            label = "Size",
            // 14/20/28 rather than 16/19/23. SlotGeometry allows 10..40 and the
            // old band used a fifth of it, so Small and Large differed by four
            // points of font size -- a real change that nobody could see, which
            // reads as a broken control rather than a subtle one.
            options = listOf("Small" to 14, "Medium" to 20, "Large" to 28),
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
/** A labelled switch. Two of these now, so it is one composable rather than two. */
@Composable
private fun SwitchRow(
    title: String,
    detail: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

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
/**
 * One complication slot, chosen from a list with icons.
 *
 * ## Why a list and not the dropdown that was here
 *
 * The dropdown was the standard Material control for "one of a fixed set", and
 * it was still the wrong one: on the watch, choosing a complication means the
 * system picker — a full list, each source with its own glyph, because people
 * recognise the shape faster than they read the word. A phone control that looks
 * nothing like the thing it is configuring makes you translate between them.
 *
 * The glyphs are [ComplicationGlyphs] in `:generator` — the same shapes the dial
 * preview draws in the slot, so what you pick here is literally what appears
 * there.
 *
 * This sets the face's DEFAULT provider. The watch's own editor can still change
 * it afterwards, which is what `isCustomizable="TRUE"` on the emitted
 * `ComplicationSlot` is for.
 */
@Composable
private fun SlotPicker(
    pos: SlotPosition,
    selected: ComplicationSource,
    onSelect: (ComplicationSource) -> Unit
) {
    var open by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { open = true }
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SourceGlyph(selected)
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                Presentation.label(pos),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(Presentation.label(selected), style = MaterialTheme.typography.bodyLarge)
        }
        Text("›", style = MaterialTheme.typography.titleMedium,
             color = MaterialTheme.colorScheme.outline)
    }

    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("${Presentation.label(pos)} complication") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    for (source in ComplicationSource.entries) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(source); open = false }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = source == selected,
                                onClick = { onSelect(source); open = false }
                            )
                            SourceGlyph(source)
                            Spacer(Modifier.size(12.dp))
                            Text(Presentation.label(source),
                                 style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } }
        )
    }
}

/** The same glyph the dial preview draws in that slot. */
@Composable
private fun SourceGlyph(source: ComplicationSource) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(24.dp)) {
        if (!source.enabled) return@Canvas
        drawIntoCanvas { canvas ->
            AndroidComplicationIcons.draw(
                canvas.nativeCanvas, source, 0f, 0f, size.minDimension, tint.toArgb()
            )
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

/**
 * The colour row, plus the door to any other colour.
 *
 * The nine dial swatches are the fast path and cover most designs. The trailing
 * "+" is the localhost app's custom picker, and it matters more than it looks:
 * a dial colour is the thing somebody is most likely to have already decided,
 * from a brand or a photo, and a fixed palette simply cannot express it.
 */
@Composable
private fun Swatches(
    colors: List<String>,
    selected: String,
    onCustom: () -> Unit,
    onSelect: (String) -> Unit
) {
    // The "+" is OUTSIDE the scrolling row, pinned to the end. Nine 44dp
    // swatches do not fit a 411dp screen, so inside the scroller the custom
    // colour would sit past the right edge -- an affordance nobody would find,
    // and shrinking the swatches to fit would put them under the 48dp touch
    // target. Pinning costs one swatch of width and makes it always reachable.
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
    Row(
        modifier = Modifier
            .weight(1f)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        for (hex in colors) {
            val chosen = hex.equals(selected, ignoreCase = true)
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(EngravedStroke.withAlpha(EngravedStroke.rgb(hex), 255)))
                    .border(
                        width = if (chosen) 3.dp else 1.dp,
                        color = if (chosen) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                        shape = CircleShape
                    )
                    .clickable { onSelect(hex) }
            )
        }
    }
        Spacer(Modifier.padding(horizontal = 5.dp))
        // Shown as selected when the current colour is not one of the swatches,
        // so a custom colour does not look like nothing is chosen.
        val custom = colors.none { it.equals(selected, ignoreCase = true) }
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    if (custom) Color(EngravedStroke.withAlpha(EngravedStroke.rgb(selected), 255))
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .border(
                    width = if (custom) 3.dp else 1.dp,
                    color = if (custom) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                    shape = CircleShape
                )
                .clickable(onClick = onCustom),
            contentAlignment = Alignment.Center
        ) {
            if (!custom) {
                Text("+", style = MaterialTheme.typography.titleMedium,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}
