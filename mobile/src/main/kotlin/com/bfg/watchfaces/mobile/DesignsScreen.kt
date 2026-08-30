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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bfg.watchfaces.appcore.CatalogService
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
            else -> CommunityGrid(onPick)
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
 * The community tab, reading the live catalog service.
 *
 * ## Why a tile fetches its own face
 *
 * `index.json` carries name, author, engine and the two colours — enough to
 * BROWSE, deliberately not enough to render, because full parameters for ten
 * thousand faces is not an index any more. Building a tile from the engine and
 * the colours alone would draw a face that is not the one somebody submitted,
 * which reads as a promise and is not one.
 *
 * So each tile fetches its own parameters and renders them with
 * [AndroidFacePreview] — the same call the studio preview and the baked
 * `dial_bg.png` make, because there is one rasterizer. A `LazyVerticalGrid`
 * only composes what is visible, so that is a handful of requests rather than
 * one per face in the catalog, and the service serves them from the edge cache.
 *
 * ## Offline
 *
 * The cached index answers, marked as possibly out of date. A face can be
 * removed after it was cached and the app cannot know — `MODERATION.md` already
 * says it cannot reach a face on a wrist, and this is the same honesty one
 * layer up.
 */
@Composable
private fun CommunityGrid(onPick: (DialParams) -> Unit) {
    val context = LocalContext.current
    val service = remember { CatalogAccess.service(context) }

    // Parameters, once per face, for as long as the tab is open. Scrolling back
    // to a tile must not fetch it again.
    val faceCache = remember { mutableStateMapOf<String, DialParams>() }

    val state by produceState<CatalogService.Result<CatalogService.Index>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { service.index() }
    }

    when (val result = state) {
        null -> CommunityMessage("Loading…")

        // The service's own words, which are written for a person. The
        // exception underneath said `Unable to resolve host ...: No address
        // associated with hostname`, which is what a developer wants and not
        // what somebody holding a phone wants.
        is CatalogService.Result.Failed -> CommunityMessage(
            title = "The community catalog is not answering",
            detail = result.message
        )

        is CatalogService.Result.Ok -> {
            val faces = result.value.faces
            if (faces.isEmpty()) {
                // "Nothing shared yet" is a claim about the catalog. From a
                // CACHED list it is a claim this phone cannot make -- the list
                // is a snapshot of unknown age and something may have been
                // published since. Caught on an emulator with the network off,
                // where the empty state looked identical online and offline.
                CommunityMessage(
                    title = if (result.stale) "Nothing shared, last time this phone looked"
                            else "Nothing shared yet",
                    detail = if (result.stale)
                        "This phone is offline, so it is showing what it downloaded last time. " +
                            "There may be faces here that it has not seen."
                    else
                        "When people start sharing the faces they make, they will show up " +
                            "here. Yours can be one of them."
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    if (result.stale) {
                        // Said plainly rather than shown as a spinner that never
                        // resolves: this is yesterday's list.
                        Text(
                            "Showing the last list this phone downloaded. It may be out of date.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(faces, key = { it.slug }) { face ->
                            CommunityTile(face, service, faceCache, onPick)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommunityTile(
    face: CatalogService.Face,
    service: CatalogService,
    cache: MutableMap<String, DialParams>,
    onPick: (DialParams) -> Unit
) {
    val params by produceState<DialParams?>(initialValue = cache[face.slug], key1 = face.slug) {
        if (value != null) return@produceState
        val fetched = withContext(Dispatchers.IO) {
            (service.face(face.slug) as? CatalogService.Result.Ok)?.value
        }
        if (fetched != null) cache[face.slug] = fetched
        value = fetched
    }

    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = params) {
        val p = params ?: return@produceState
        value = withContext(Dispatchers.Default) {
            runCatching { AndroidFacePreview.render(p, ambient = false, size = TILE_PX) }.getOrNull()
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(enabled = params != null) { params?.let(onPick) }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(CircleShape)
                // The dial's own colour while its render is on the way, so the
                // tile settles into place rather than flashing from grey.
                .background(Color(EngravedStroke.withAlpha(EngravedStroke.rgb(face.dialColor), 255))),
            contentAlignment = Alignment.Center
        ) {
            bitmap?.let {
                Image(
                    painter = BitmapPainter(it.asImageBitmap()),
                    contentDescription = "${face.name}, a watch face by ${authorOf(face)}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = face.name,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Text(
            text = authorOf(face),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/** An author name is optional, and "Anonymous" is better than a blank line. */
private fun authorOf(face: CatalogService.Face): String =
    face.author.ifBlank { "Anonymous" }

@Composable
private fun CommunityMessage(title: String, detail: String? = null) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (detail != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
