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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
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
import com.bfg.watchfaces.generator.DateScale
import com.bfg.watchfaces.generator.DateStyle
import com.bfg.watchfaces.generator.HourFormat
import com.bfg.watchfaces.generator.RingSource
import com.bfg.watchfaces.generator.DialParams
import com.bfg.watchfaces.generator.ComplicationSource
import com.bfg.watchfaces.generator.Engine
import com.bfg.watchfaces.generator.EngravedStroke
import com.bfg.watchfaces.generator.SlotGeometry
import kotlin.math.roundToInt
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
    modifier: Modifier = Modifier,
    /**
     * Save and Send, which scroll WITH the controls rather than sitting under
     * the pinned dial. A slot rather than siblings in the caller, because this
     * screen owns the scrolling now.
     */
    footer: @Composable () -> Unit = {}
) {
    Column(modifier = modifier.fillMaxSize()) {
        // PINNED. Every control below changes the dial, and judging a change
        // meant scrolling up to look and back down to adjust -- on a phone
        // that is most of the interaction. The preview stays put and only the
        // controls move.
        //
        // Smaller than it was, deliberately: at full width the dial and one
        // toggle filled the screen, which is what made the scrolling so
        // costly in the first place.
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.fillMaxWidth(0.62f)) { DialPreview(params, ambient) }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
        AmbientToggle(ambient, onAmbient)
        ChoiceRow(
            label = "Time",
            options = HourFormat.entries.map { it.label to it },
            selected = params.hourFormat
        ) { onParams(params.copy(hourFormat = it)) }
        SwitchRow(
            title = "Show seconds",
            detail = "Only while the watch is awake",
            checked = params.showSeconds
        ) { onParams(params.copy(showSeconds = it)) }
        OptionRow(
            label = "Ring",
            value = params.ring.label,
            title = "Ring around the edge",
            options = RingSource.entries.map { it to it.label },
            selected = params.ring
        ) { onParams(params.copy(ring = it)) }
        OptionRow(
            label = "Date",
            value = if (params.dateStyle == DateStyle.NONE) params.dateStyle.label
                    else "${params.dateStyle.label} · ${params.dateScale.label}",
            title = "Date",
            options = DateStyle.entries.map { it to it.label },
            selected = params.dateStyle,
            header = {
                // Size sits with the style, because it is the same decision:
                // the date is sized to the clock, and this says how much of
                // that to take. Useful smaller for a busy dial and larger for
                // anyone who cannot read the fitted size.
                if (params.dateStyle != DateStyle.NONE) {
                    ChoiceRow(
                        label = "Size",
                        options = DateScale.entries.map { it.label to it },
                        selected = params.dateScale
                    ) { onParams(params.copy(dateScale = it)) }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                }
            }
        ) { onParams(params.copy(dateStyle = it)) }

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
        // Derived from what THIS face can actually take, not a fixed 14/20/28.
        //
        // Five slots and a 104pt clock on a 456 dial is a tight budget and the
        // ceiling moves with the layout -- so a fixed "Large" was sometimes a
        // number the layout refused, and SlotGeometry silently clamped it. That
        // is why Large looked identical to Medium. Large now means as large as
        // this face allows; turn a slot or a glyph off and all three grow.
        val sizes = SlotGeometry.sizeOptions(params)
        ChoiceRow(
            label = "Size",
            options = listOf("Small", "Medium", "Large").zip(sizes),
            selected = sizes.minByOrNull {
                kotlin.math.abs(it - params.layout.complicationSize)
            } ?: params.layout.complicationSize
        ) { onParams(params.copy(layout = params.layout.copy(complicationSize = it))) }
        // Derived, like the size. Fixed numbers stopped meaning anything once
        // the boxes grew: at complication size 27, Tight 84, Normal 92 and Wide
        // 110 all came out as 115, because the layout's own minimum had passed
        // all three. Three controls, one result.
        val spreadOptions = listOf("Tight", "Normal", "Wide")
            .zip(SlotGeometry.spreadOptions(params))
        ChoiceRow(
            label = "Spacing",
            options = spreadOptions,
            // The stored value is a REQUEST and rarely equals an option, so
            // match the nearest -- otherwise nothing looks selected and the
            // control reads as broken.
            selected = spreadOptions.minByOrNull {
                kotlin.math.abs(it.second - params.layout.complicationSpread)
            }?.second ?: params.layout.complicationSpread
        ) { onParams(params.copy(layout = params.layout.copy(complicationSpread = it))) }

        Spacer(Modifier.height(8.dp))
        for (pos in SlotPosition.entries) {
            SlotPicker(
                pos = pos,
                selected = params.slot(pos),
                iconOn = pos in params.iconSlots,
                component = params.providers[pos],
                launcher = params.launchers[pos],
                onSelect = {
                    // Choosing a system or drawn source clears any provider
                    // app: a slot holds ONE thing.
                    onParams(params.withSlot(pos, it).copy(providers = params.providers - pos))
                },
                onOpenApp = { component ->
                    onParams(
                        params.withSlot(pos, ComplicationSource.SHORTCUT_APP)
                            .copy(launchers = params.launchers + (pos to component))
                    )
                },
                onApp = { component ->
                    // The system source stays as the fallback for a watch that
                    // does not have the app. DefaultProviderPolicy requires one.
                    val withFallback =
                        if (params.slot(pos).enabled) params
                        else params.withSlot(pos, ComplicationSource.DATE)
                    onParams(withFallback.copy(providers = withFallback.providers + (pos to component)))
                },
                onIcon = { on ->
                    val next = if (on) params.iconSlots + pos else params.iconSlots - pos
                    onParams(params.copy(iconSlots = next))
                }
            )
        }
        Text(
            "Turn one off and the others move over to fill the space.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )
        Spacer(Modifier.height(16.dp))
            footer()
            Spacer(Modifier.height(24.dp))
        }
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
    iconOn: Boolean,
    /** The provider app chosen for this slot, if any. */
    component: String?,
    /** The app this slot opens, when it is a shortcut to one. */
    launcher: String?,
    onSelect: (ComplicationSource) -> Unit,
    onApp: (String) -> Unit,
    onOpenApp: (String) -> Unit,
    onIcon: (Boolean) -> Unit
) {
    var open by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { open = true }
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Dimmed rather than hidden when the glyph is off: the row still has to
        // say WHICH complication this is, and an empty space says nothing.
        Box(Modifier.alpha(if (iconOn) 1f else 0.3f)) { SourceGlyph(selected) }
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
                    // Per slot, and inside the slot's own dialog: the reason to
                    // hide a glyph is almost always about one complication, and
                    // this is where someone is already deciding about that one.
                    if (selected != ComplicationSource.NONE) {
                        SwitchRow(
                            title = "Show the icon",
                            detail = "The small symbol above the value",
                            checked = iconOn,
                            onChange = onIcon
                        )
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    }
                    val ctx = LocalContext.current
                    val fromWatch = ProviderCache.load(ctx)
                    val openable = ProviderCache.launchers(ctx)
                    for ((heading, group) in listOf(
                        null to Presentation.PICKER_COMMON,
                        // Shortcuts open something when pressed rather than
                        // showing a reading, so they read better apart.
                        "Tap to open" to Presentation.PICKER_SHORTCUTS,
                        "More" to Presentation.PICKER_REST
                    )) {
                        if (heading != null) {
                            HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            Text(
                                heading,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                        for (source in group) {
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

                    // Apps this slot could OPEN, when it is set to do that.
                    if (selected == ComplicationSource.SHORTCUT_APP && openable.isNotEmpty()) {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text(
                            "Open which app",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        for (a in openable) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenApp(a.component); open = false }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = launcher == a.component,
                                    onClick = { onOpenApp(a.component); open = false }
                                )
                                Spacer(Modifier.size(4.dp))
                                Text(a.label, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }

                    // What the WATCH reported it has. Empty until the first
                    // successful send, because the list comes back on one --
                    // a provider is a service on the watch and the phone cannot
                    // see it.
                    if (fromWatch.isNotEmpty()) {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text(
                            "From your watch",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        for (p in fromWatch) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onApp(p.component); open = false }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = component == p.component,
                                    onClick = { onApp(p.component); open = false }
                                )
                                Spacer(Modifier.size(4.dp))
                                Column {
                                    Text(p.label, style = MaterialTheme.typography.bodyLarge)
                                    if (p.app.isNotBlank() && p.app != p.label) {
                                        Text(
                                            p.app,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
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
/**
 * A row that opens a list, for a choice with more options than fit a segmented
 * control.
 *
 * [ChoiceRow] lays its options out side by side, which works for three short
 * words and falls apart at five long ones: "Weekday, month and day" next to
 * four siblings is unreadable at any phone width. Same job, different shape.
 */
@Composable
private fun <T> OptionRow(
    label: String,
    value: String,
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    /** Shown above the list, for a choice that belongs WITH this one. */
    header: @Composable () -> Unit = {},
    onSelect: (T) -> Unit
) {
    var open by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { open = true }
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
        Text("\u203A", style = MaterialTheme.typography.titleMedium,
             color = MaterialTheme.colorScheme.outline)
    }

    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    header()
                    for ((option, text) in options) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(option); open = false }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = option == selected,
                                onClick = { onSelect(option); open = false }
                            )
                            Spacer(Modifier.size(4.dp))
                            Text(text, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } }
        )
    }
}

@Composable
private fun <T> ChoiceRow(
    label: String,
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit
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
