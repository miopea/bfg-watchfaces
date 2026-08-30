package com.bfg.watchfaces.mobile

import android.os.Bundle
import android.util.Log
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
import androidx.compose.material3.TextButton
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
import com.bfg.watchfaces.appcore.ComplicationChange
import com.bfg.watchfaces.appcore.FaceLibrary
import com.bfg.watchfaces.appcore.Presets
import com.bfg.watchfaces.generator.DialParams
import com.bfg.watchfaces.mobile.pack.FaceBuilder
import com.bfg.watchfaces.mobile.pack.PackBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration

/** Logcat tag for the Studio’s send path. `adb logcat -s BfgStudio`. */
private const val TAG = "BfgStudio"

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
                // Studio opens on the face that is ON THE WATCH, not on a
                // preset nobody chose. CurrentFace is a record of what this
                // phone last sent -- see the note there about when it is right.
                val onWatch = remember { CurrentFace.load(context) }
                var engineName by rememberSaveable {
                    mutableStateOf((onWatch?.params ?: Presets.OPENING).engine.name)
                }
                var params by remember { mutableStateOf(onWatch?.params ?: Presets.OPENING) }
                /**
                 * The saved face being edited, if any.
                 *
                 * Without this, opening a face from My faces kept its
                 * parameters and lost its identity, so Save asked for a name
                 * again and made a SECOND face rather than updating the one
                 * that was open.
                 */
                var openSlug by rememberSaveable { mutableStateOf<String?>(null) }
                var tab by rememberSaveable { mutableStateOf(Tab.DESIGNS) }
                var ambient by rememberSaveable { mutableStateOf(false) }
                // The explanation is a one-time modal now, not a screen in the
                // way of every send.
                var explaining by remember { mutableStateOf(false) }
                var pendingSend by remember { mutableStateOf<Pair<String, DialParams>?>(null) }
                val explainState = rememberModalBottomSheetState()
                // A face gets its identity when somebody names it. Sending an
                // unnamed design would put "Untitled" in the carousel and in the
                // package name, so the send path reuses the last name given.
                var sendingName by rememberSaveable { mutableStateOf(onWatch?.name ?: "My Face") }
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

                /**
                 * Send [name], explaining the flow the first time only.
                 *
                 * Everything that sends a face goes through here -- the studio
                 * button and every row of My faces -- so there is one place
                 * where the explanation, the progress and the result are decided.
                 */
                fun requestSend(name: String, face: DialParams) {
                    if (!Onboarding.hasExplainedSend(context)) {
                        pendingSend = name to face
                        explaining = true
                        return
                    }
                    scope.launch {
                        snackbar.showSnackbar("Building “$name”…", duration = SnackbarDuration.Short)
                    }
                    scope.launch {
                        // Off the main thread: packing walks the resource table
                        // and the Data Layer calls block.
                        val result = withContext(Dispatchers.IO) { buildThenSend(context, name, face) }
                        // Only a send that actually landed changes what Studio
                        // opens on next time.
                        if (result.startsWith("Sent ")) CurrentFace.record(context, name, face)
                        snackbar.showSnackbar(result, duration = SnackbarDuration.Long)
                    }
                }

                BackHandler(enabled = tab != Tab.DESIGNS) { tab = Tab.DESIGNS }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(title = { Text(tab.label) })
                    },
                    bottomBar = {
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
                    },
                    snackbarHost = { SnackbarHost(snackbar) }
                ) { inner ->
                    when (tab) {
                        Tab.DESIGNS -> DesignsScreen(
                            // A style is a starting point, not a face: it has
                            // no identity yet, so Save must ask for a name.
                            onPick = { params = it; engineName = it.engine.name; openSlug = null; tab = Tab.STUDIO },
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
                                val open = openSlug?.let { slug -> faces.firstOrNull { it.slug == slug } }
                                Button(
                                    onClick = {
                                        if (open != null) {
                                            FaceStorage.save(context, open.name, params)
                                            faces = FaceStorage.list(context)
                                            scope.launch {
                                                snackbar.showSnackbar("Saved “${open.name}”.")
                                            }
                                        } else {
                                            naming = true
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text(if (open != null) "Save “${open.name}”" else "Save to My faces") }
                                if (open != null) {
                                    Spacer(Modifier.height(8.dp))
                                    // Still reachable, because "make a copy" is
                                    // a real thing to want -- it is just not
                                    // what Save should mean.
                                    TextButton(
                                        onClick = { naming = true },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("Save as a new face") }
                                }
                                Spacer(Modifier.height(8.dp))
                                Button(
                                    onClick = { requestSend(sendingName, params) },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Send to watch") }
                                Spacer(Modifier.height(8.dp))
                                // Choosing which face is active, and which
                                // provider fills each complication, happens in
                                // the companion app or on the watch. This app
                                // cannot do either, so it should at least take
                                // you there.
                                TextButton(
                                    onClick = {
                                        if (!WatchCompanion.open(context)) {
                                            scope.launch {
                                                snackbar.showSnackbar(
                                                    "Could not open the watch app on this phone."
                                                )
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Open the watch app to pick complications") }
                            }
                            Spacer(Modifier.height(24.dp))
                        }

                        Tab.MINE -> MyFacesScreen(
                            faces = faces,
                            onOpen = {
                                params = it.params
                                engineName = it.params.engine.name
                                openSlug = it.slug
                                sendingName = it.name
                                tab = Tab.STUDIO
                            },
                            onSend = { requestSend(it.name, it.params) },
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
                if (explaining) {
                    val (name, face) = pendingSend ?: ("your face" to params)
                    ModalBottomSheet(
                        onDismissRequest = { explaining = false; pendingSend = null },
                        sheetState = explainState
                    ) {
                        ActivationHandoffScreen(
                            faceName = name,
                            onContinue = {
                                Onboarding.markSendExplained(context)
                                explaining = false
                                pendingSend = null
                                requestSend(name, face)
                            },
                            onCancel = { explaining = false; pendingSend = null },
                            footer = { ActivationDeniedNote(consent, Modifier.padding(top = 12.dp)) }
                        )
                    }
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
            .getOrElse {
                Log.e(TAG, "build failed for “$name”", it)
                return ours(name)
            }

        // Validate BEFORE looking for a watch. A schema-invalid face installs
        // and then never appears in the carousel with no error anywhere, so
        // finding out here costs a second and finding out later costs the whole
        // transfer plus one of the watch's addWatchFace calls.
        val token = runCatching { FaceBuilder.validate(context, built.apk) }
            .getOrElse {
                Log.e(TAG, "validation failed for “$name”", it)
                return ours(name)
            }

        val target = runCatching { FaceSender.findTarget(context) }
            .getOrElse {
                Log.e(TAG, "could not look for a watch", it)
                return "Couldn’t reach your watch. Check it’s nearby and connected, then try again."
            }
        if (target !is FaceSender.Target.Ready) return describe(target)

        // Rebuild the slots only when the complications actually changed. See
        // ComplicationChange: the reset is what makes the app's choices apply,
        // and it costs one of a finite number of activation calls.
        val reset = ComplicationChange.needsReset(CurrentFace.load(context)?.params, params)
        Log.i(TAG, "sending \u201C$name\u201D (reset complications: $reset)")

        return runCatching { FaceSender.send(context, target, built.apk, token, reset) }
            .fold(
                onSuccess = { "Sent “$name” to ${target.name}." },
                onFailure = {
                    Log.e(TAG, "transfer to ${target.name} failed", it)
                    "“$name” didn’t make it to ${target.name}. " +
                        "Check the watch is nearby, then try again."
                }
            )
    }

    /**
     * What we say when the failure is the app's fault, not the person's.
     *
     * A packing or validation failure has no user-facing cause and no user
     * action: the design is fine, our emitter or our build is not. Before this
     * existed the person saw the validator's own dump, opening with
     * `CheckFailure(name=Watch Face Format, category=WATCH_FACE_FORMAT ...)`
     * and running to a SAXParseException. That tells them nothing they can act
     * on and reads like they broke something.
     *
     * The detail goes to the log, where it is useful to us. They get a sentence
     * that says whose problem it is and that their work is safe.
     */
    private fun ours(name: String): String =
        "“$name” couldn’t be sent — something went wrong on our end. " +
            "Your design is saved, so nothing is lost. Please try again."

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
