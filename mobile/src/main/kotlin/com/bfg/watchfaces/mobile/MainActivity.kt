package com.bfg.watchfaces.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bfg.watchfaces.appcore.ActivationConsent
import com.bfg.watchfaces.appcore.FaceLibrary
import com.bfg.watchfaces.appcore.Presets
import com.bfg.watchfaces.generator.DialParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The design app.
 *
 * `DECISIONS.md` 2026-08-28 commits to the localhost app being the exact
 * specification for this one, and until 2026-08-29 this was a single scrolling
 * studio against that app's four screens. It now has the same shape: Designs,
 * Studio, My faces, About, behind a bottom bar — because "make a face" starts
 * with picking one that already looks like a watch, not with a slider panel.
 *
 * This does NOT request the activation permission and never will. That belongs
 * to `:wear` — `androidx.wear.watchfacepush` declares `wear-sdk` as a required
 * library, so an app linking it will not install on a phone at all.
 */
class MainActivity : ComponentActivity() {

    private enum class Tab(val label: String, val icon: ImageVector) {
        DESIGNS("Designs", IconDesigns),
        STUDIO("Studio", IconStudio),
        MINE("My faces", IconMine),
        ABOUT("About", IconAbout)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BfgTheme {
                val scope = rememberCoroutineScopeCompat()
                val context = LocalContext.current
                val consent = remember { ActivationConsent.load(filesDir) }
                val snackbar = remember { SnackbarHostState() }

                // rememberSaveable so a rotation does not throw away a design.
                var engineName by rememberSaveable { mutableStateOf(Presets.OPENING.engine.name) }
                var params by remember { mutableStateOf(Presets.OPENING) }
                var tab by rememberSaveable { mutableStateOf(Tab.DESIGNS) }
                var ambient by rememberSaveable { mutableStateOf(false) }
                var handoff by rememberSaveable { mutableStateOf(false) }
                var status by remember { mutableStateOf<String?>(null) }
                var faces by remember { mutableStateOf(FaceStorage.list(context)) }

                var tuning by remember { mutableStateOf(false) }
                var naming by remember { mutableStateOf(false) }
                val tuneState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
                val nameState = rememberModalBottomSheetState()

                BackHandler(enabled = handoff) { handoff = false }
                BackHandler(enabled = !handoff && tab != Tab.DESIGNS) { tab = Tab.DESIGNS }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(title = { Text(if (handoff) "Send to your watch" else tab.label) })
                    },
                    bottomBar = {
                        if (!handoff) {
                            NavigationBar {
                                for (t in Tab.entries) {
                                    NavigationBarItem(
                                        selected = tab == t,
                                        onClick = { tab = t },
                                        icon = { Icon(t.icon, contentDescription = null) },
                                        label = { Text(t.label) }
                                    )
                                }
                            }
                        }
                    },
                    snackbarHost = { SnackbarHost(snackbar) }
                ) { inner ->
                    if (handoff) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .padding(inner)
                                .verticalScroll(rememberScrollState())
                        ) {
                            ActivationHandoffScreen(
                                faceName = "your face",
                                onContinue = {
                                    status = "Looking for your watch…"
                                    scope.launch {
                                        // Off the main thread: findTarget blocks
                                        // on the Data Layer.
                                        status = withContext(Dispatchers.IO) {
                                            runCatching { describe(FaceSender.findTarget(context)) }
                                                .getOrElse { "Could not reach your watch. Is Bluetooth on?" }
                                        }
                                    }
                                },
                                onCancel = { handoff = false }
                            )
                            status?.let {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = it,
                                    modifier = Modifier.padding(horizontal = 24.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            ActivationDeniedNote(consent, Modifier.padding(24.dp))
                        }
                        return@Scaffold
                    }

                    when (tab) {
                        Tab.DESIGNS -> DesignsScreen(
                            onPick = { params = it; engineName = it.engine.name; tab = Tab.STUDIO },
                            modifier = Modifier.padding(inner)
                        )

                        Tab.STUDIO -> Column(
                            Modifier
                                .fillMaxSize()
                                .padding(inner)
                                .verticalScroll(rememberScrollState())
                        ) {
                            StudioScreen(
                                params = params,
                                onParams = { params = it; engineName = it.engine.name },
                                ambient = ambient,
                                onAmbient = { ambient = it },
                                onTune = { tuning = true }
                            )
                            Column(Modifier.padding(horizontal = 16.dp)) {
                                Button(
                                    onClick = { naming = true },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Save to My faces") }
                                Spacer(Modifier.height(8.dp))
                                Button(
                                    onClick = { handoff = true },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Send to watch") }
                            }
                            Spacer(Modifier.height(24.dp))
                        }

                        Tab.MINE -> MyFacesScreen(
                            faces = faces,
                            onOpen = { params = it.params; engineName = it.params.engine.name; tab = Tab.STUDIO },
                            onDelete = { FaceStorage.delete(context, it.slug); faces = FaceStorage.list(context) },
                            modifier = Modifier.padding(inner)
                        )

                        Tab.ABOUT -> AboutScreen(Modifier.padding(inner))
                    }
                }

                if (tuning) {
                    TuneSheet(
                        params = params,
                        onParams = { params = it; engineName = it.engine.name },
                        sheetState = tuneState,
                        onDismiss = { tuning = false }
                    )
                }
                if (naming) {
                    NameSheet(
                        existing = { FaceStorage.existing(context, it) },
                        sheetState = nameState,
                        onDismiss = { naming = false },
                        onSave = { name ->
                            FaceStorage.save(context, name, params)
                            faces = FaceStorage.list(context)
                            naming = false
                            scope.launch { snackbar.showSnackbar("Saved “$name”") }
                        }
                    )
                }

                // Keep the engine choice across a rotation. params itself is not
                // Saveable -- DialParams is a plain data class, not Parcelable --
                // so the engine is the one thing worth restoring cheaply.
                LaunchedEffect(engineName) {
                    if (params.engine.name != engineName) {
                        params = params.copy(engine = enumValueOf(engineName))
                    }
                }
            }
        }
    }

    /**
     * What the Data Layer found, in words.
     *
     * The three outcomes get three different sentences on purpose: "no watch"
     * and "watch without the app" need different actions from the person, and
     * the second is the entire reason the first handoff step exists.
     *
     * The Ready case is honest that nothing is sent yet. There is no APK to send
     * — building one on the device needs `google/pack` through JNI, and the
     * validation token needs the validator wiring — so claiming a face went
     * anywhere would be a lie the person could check.
     */
    private fun describe(target: FaceSender.Target): String = when (target) {
        is FaceSender.Target.Ready ->
            "Found ${target.name}, with the app installed. Sending is not built yet."
        is FaceSender.Target.AppMissing ->
            "${target.name} is connected, but it does not have the app yet. " +
                "Install BFG Watch Faces on the watch and try again."
        FaceSender.Target.NoWatch ->
            "No watch connected. Pair one, then try again."
    }
}

@Composable
private fun rememberCoroutineScopeCompat() = androidx.compose.runtime.rememberCoroutineScope()
