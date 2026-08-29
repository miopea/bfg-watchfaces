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
import com.bfg.watchfaces.mobile.pack.FaceBuilder
import com.bfg.watchfaces.mobile.pack.PackBridge
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
                // A face gets its identity when somebody names it. Sending an
                // unnamed design would put "Untitled" in the carousel and in the
                // package name, so the send path reuses the last name given.
                var sendingName by rememberSaveable { mutableStateOf("My Face") }
                var status by remember { mutableStateOf<String?>(null) }
                var faces by remember { mutableStateOf(FaceStorage.list(context)) }

                var tuning by remember { mutableStateOf(false) }
                var picking by remember { mutableStateOf(false) }
                var pickingDial by remember { mutableStateOf(true) }
                var naming by remember { mutableStateOf(false) }
                val tuneState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
                // Opens fully rather than half: the hex field and the buttons sit below the
                // pad, and at the partial height they were off screen with no hint that
                // scrolling would reach them.
                val colorState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
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
                                faceName = sendingName,
                                onContinue = {
                                    status = "Building your face…"
                                    scope.launch {
                                        // Off the main thread: packing walks the
                                        // resource table and findTarget blocks on
                                        // the Data Layer.
                                        status = withContext(Dispatchers.IO) {
                                            buildThenSend(context, sendingName, params)
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
                                onTune = { tuning = true },
                                onCustomColor = { dial -> pickingDial = dial; picking = true }
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
                if (picking) {
                    ColorSheet(
                        title = if (pickingDial) "Dial colour" else "Hands & text",
                        initial = if (pickingDial) params.dialColor else params.inkColor,
                        sheetState = colorState,
                        onDismiss = { picking = false },
                        onPick = { hex ->
                            params = if (pickingDial) params.copy(dialColor = hex)
                            else params.copy(inkColor = hex)
                            picking = false
                        }
                    )
                }
                if (naming) {
                    NameSheet(
                        existing = { FaceStorage.existing(context, it) },
                        sheetState = nameState,
                        onDismiss = { naming = false },
                        onSave = { name ->
                            FaceStorage.save(context, name, params)
                            sendingName = name
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
    /**
     * Build the APK, then look for a watch.
     *
     * In that order on purpose: building is the step that can fail for a reason
     * the person can do nothing about, and telling them "no watch found" when
     * the real problem is that this build cannot pack a face would send them
     * looking at their Bluetooth settings for an hour.
     */
    private fun buildThenSend(
        context: android.content.Context,
        name: String,
        params: DialParams
    ): String {
        if (!PackBridge.isAvailable) return PackBridge.UNAVAILABLE

        val built = runCatching { FaceBuilder.build(context, name, params) }
            .getOrElse { return "Could not build the face: ${it.message}" }
        val kb = built.apk.length() / 1024

        // Validate BEFORE looking for a watch. A schema-invalid face installs
        // and then never appears in the carousel with no error anywhere, so
        // finding out here costs a second and finding out later costs the whole
        // transfer plus one of the watch's addWatchFace calls.
        val token = runCatching { FaceBuilder.validate(context, built.apk) }
            .getOrElse { return "Built ${built.slug}, but it is not a valid face: ${it.message}" }

        val target = runCatching { FaceSender.findTarget(context) }
            .getOrElse { return "Built ${built.slug} (${kb}KB), but could not reach your watch." }
        if (target !is FaceSender.Target.Ready) return "Built ${built.slug} (${kb}KB). " + describe(target)

        return runCatching { FaceSender.send(context, target, built.apk, token) }
            .fold(
                onSuccess = { "Sent “$name” (${kb}KB) to ${target.name}." },
                onFailure = { "Built ${built.slug} (${kb}KB), but the transfer to ${target.name} failed: ${it.message}" }
            )
    }

    private fun describe(target: FaceSender.Target): String = when (target) {
        is FaceSender.Target.Ready ->
            "Found ${target.name}, with the app installed. The transfer is the last piece."
        is FaceSender.Target.AppMissing ->
            "${target.name} is connected, but it does not have the app yet. " +
                "Install BFG Watch Faces on the watch and try again."
        FaceSender.Target.NoWatch ->
            "No watch connected. Pair one, then try again."
    }
}

@Composable
private fun rememberCoroutineScopeCompat() = androidx.compose.runtime.rememberCoroutineScope()
