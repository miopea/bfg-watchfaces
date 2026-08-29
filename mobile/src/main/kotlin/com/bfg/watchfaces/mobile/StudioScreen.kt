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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
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
import com.bfg.watchfaces.generator.Engine
import com.bfg.watchfaces.generator.EngravedStroke
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
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        DialPreview(params)

        Spacer(Modifier.height(20.dp))
        SectionHeading("Style")
        EngineChips(params.engine) { onParams(params.copy(engine = it)) }

        Spacer(Modifier.height(20.dp))
        SectionHeading("Dial")
        Swatches(Presentation.DIALS, params.dialColor) { onParams(params.copy(dialColor = it)) }

        Spacer(Modifier.height(16.dp))
        SectionHeading("Ink")
        Swatches(Presentation.INKS, params.inkColor) { onParams(params.copy(inkColor = it)) }

        for (target in ControlInventory.Target.entries) {
            val controls = ControlInventory.CONTROLS.filter { it.target == target }
            if (controls.isEmpty()) continue
            Spacer(Modifier.height(20.dp))
            SectionHeading(
                when (target) {
                    ControlInventory.Target.PATTERN -> Presentation.SECTION_PATTERN
                    ControlInventory.Target.LAYOUT -> Presentation.SECTION_LAYOUT
                }
            )
            for (control in controls) {
                ControlSlider(control, params, onParams)
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

/**
 * The dial, at the size it will actually be worn at.
 *
 * Round, because the watch is: clipping here is not decoration, it is the
 * difference between judging a design and judging a square crop of one.
 */
@Composable
private fun DialPreview(params: DialParams) {
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = params) {
        value = withContext(Dispatchers.Default) {
            runCatching { AndroidFacePreview.render(params, size = DIAL_SIZE) }.getOrNull()
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(CircleShape)
            // The dial colour behind the render, so the first frame after a
            // change is the right colour rather than a flash of nothing.
            .background(Color(EngravedStroke.withAlpha(EngravedStroke.rgb(params.dialColor), 255))),
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

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}
