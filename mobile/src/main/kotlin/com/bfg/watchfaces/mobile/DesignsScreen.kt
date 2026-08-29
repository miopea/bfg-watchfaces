package com.bfg.watchfaces.mobile

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bfg.watchfaces.appcore.Presets
import com.bfg.watchfaces.generator.DialParams
import com.bfg.watchfaces.generator.EngravedStroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where a design starts.
 *
 * The localhost app opens here, not in the studio, and that ordering is the
 * product: you pick something that already looks like a watch and then make it
 * yours. Opening on a slider panel asks somebody to design from nothing.
 *
 * ## The tiles are real renders
 *
 * Each one is [AndroidFacePreview] output from the preset's own [DialParams] —
 * the same call the studio preview and the baked `dial_bg.png` make. Thumbnails
 * drawn any other way would be a second renderer, which `DECISIONS.md`
 * 2026-08-27 is explicit about not having.
 *
 * They are rendered small (a tile is never shown large) and off the main thread,
 * for the same reason the studio preview is.
 */
private const val TILE_PX = 220

@Composable
fun DesignsScreen(onPick: (DialParams) -> Unit, modifier: Modifier = Modifier) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(modifier = modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Styles") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Community") })
        }
        when (tab) {
            0 -> StyleGrid(onPick)
            else -> CommunityEmpty()
        }
    }
}

@Composable
private fun StyleGrid(onPick: (DialParams) -> Unit) {
    val presets = remember { Presets.ALL.entries.toList() }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(presets, key = { it.key }) { (name, params) ->
            PresetTile(name, params) { onPick(params) }
        }
    }
}

@Composable
private fun PresetTile(name: String, params: DialParams, onClick: () -> Unit) {
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = params) {
        value = withContext(Dispatchers.Default) {
            runCatching { AndroidFacePreview.render(params, ambient = false, size = TILE_PX) }
                .onFailure { android.util.Log.e("BFG", "tile render failed for $name", it) }
                .getOrNull()
        }
    }
    Column(
        modifier = Modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(CircleShape)
                // The dial colour first, so a tile is the right colour before
                // its render lands rather than a grey hole that fills in.
                .background(Color(EngravedStroke.withAlpha(EngravedStroke.rgb(params.dialColor), 255))),
            contentAlignment = Alignment.Center
        ) {
            bitmap?.let {
                Image(
                    painter = BitmapPainter(it.asImageBitmap()),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * The community tab, honest about being empty.
 *
 * The catalog service does not exist yet — it is its own piece of work, and the
 * operator is running the interview that specifies it. A tab that pretended to
 * be loading, or showed invented faces, would be worse than one that says so.
 */
@Composable
private fun CommunityEmpty() {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Nothing shared yet",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "When people start sharing the faces they make, they will show up here. " +
                    "Yours can be one of them.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
