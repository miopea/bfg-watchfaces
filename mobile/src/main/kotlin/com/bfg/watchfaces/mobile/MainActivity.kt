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
import androidx.compose.material3.Snackbar
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bfg.watchfaces.appcore.ActivationConsent
import com.bfg.watchfaces.appcore.WatchLink
import com.bfg.watchfaces.appcore.FaceLibrary
import com.bfg.watchfaces.appcore.FaceCodec
import com.bfg.watchfaces.appcore.Json
import com.bfg.watchfaces.appcore.CatalogService
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
                val scope = rememberCoroutineScope()
                val context = LocalContext.current
                val consent = remember { ActivationConsent.load(filesDir) }
                val snackbar = remember { SnackbarHostState() }

                // rememberSaveable so a rotation does not throw away a design.
                // Studio opens on the face that is ON THE WATCH, not on a
                // preset nobody chose. CurrentFace is a record of what this
                // phone last sent -- see the note there about when it is right.
                val onWatch = remember { CurrentFace.load(context) }
                /**
                 * The whole design, and it SURVIVES the Activity restarting.
                 *
                 * It did not. The comment above promised "rememberSaveable so a
                 * rotation does not throw away a design" and only `engineName`
                 * was saveable — so a rotation, or the system reclaiming a
                 * backgrounded process, reverted every slider, both colours,
                 * the layout, the ring and the seconds back to the opening
                 * face, restoring nothing but the engine. Silently: no error,
                 * no message, just somebody's work gone.
                 *
                 * `DialParams` is not Parcelable and should not become it —
                 * that would put an Android type in the way of a class
                 * `:generator` owns. It does not need to be: `FaceCodec` already
                 * round-trips it to JSON, because that is the stored file
                 * format, so the saver is the format the app already trusts.
                 */
                var params by rememberSaveable(stateSaver = FaceParamsSaver) {
                    mutableStateOf(onWatch?.params ?: Presets.OPENING)
                }
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
                // Whether the naming sheet was opened by Save or by Send.
                //
                // Replaces `sendingName`, which was ambient state nothing tied
                // to the screen. Naming is now the only way an unsaved design
                // acquires an identity, and this remembers what the person was
                // trying to do when they were asked for one.
                var nameThenSend by remember { mutableStateOf(false) }
                var faces by remember { mutableStateOf(FaceStorage.list(context)) }
                // What has been shared, and whether sharing is possible at all.
                //
                // `canShare` is asked of the SERVICE rather than assumed: it
                // answers false when no sign-in is configured, and the button
                // stays hidden rather than appearing and then failing. That was
                // the whole reason there was no share UI until the client id
                // existed, and it stays true if the id is ever lost.
                var shared by remember { mutableStateOf(SubmissionStore.all(context).associateBy { it.slug }) }
                var canShare by remember { mutableStateOf(false) }
                // WHICH face is being shared, by slug, so it SURVIVES the
                // Activity being recreated.
                //
                // Sharing state lives here rather than in the sheet because
                // signing in opens Google's own activity, which takes focus,
                // which makes the sheet fire onDismissRequest -- and a sheet
                // that removes itself cancels the coroutine doing the work.
                // That was measured and fixed.
                //
                // It was only HALF the bug. Moving the state out of the sheet
                // saves it from a sheet dismissal, because the caller's
                // composition outlives the sheet. It does NOT save it from the
                // ACTIVITY being recreated, which Android does under memory
                // pressure while another app's activity -- like Google's
                // account picker -- is in front. Then `remember` resets, the
                // sheet reopens on nothing, and the person is back where they
                // started. Reported from a phone: "I select an account, it goes
                // back to the share, click on share it shows account again."
                //
                // A slug is a String, so it survives. The face is looked up
                // from it, the same idiom `openSlug` already uses.
                var sharingSlug by rememberSaveable { mutableStateOf<String?>(null) }
                val sharing = sharingSlug?.let { slug -> faces.firstOrNull { it.slug == slug } }
                var shareError by rememberSaveable { mutableStateOf<String?>(null) }
                var shareOutcomeName by rememberSaveable { mutableStateOf<String?>(null) }
                val shareOutcome = shareOutcomeName?.let { ShareOutcome.valueOf(it) }
                // NOT saved, deliberately.
                //
                // The coroutine doing the work does not survive the Activity, so
                // restoring `busy = true` would show a spinner that can never
                // finish. False is the honest restored value: the work is gone,
                // and the sheet comes back on the right face ready to try again.
                //
                // Nothing is lost by retrying, because SubmissionStore is on
                // DISK: if the submit did land before the process died, the row
                // already reads "Shared" and the sheet opens on the taken-back
                // state rather than offering to send it twice.
                var shareBusy by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    val config = withContext(Dispatchers.IO) { CatalogAccess.service(context).config() }
                    canShare = (config as? CatalogService.Result.Ok)?.value?.acceptsSubmissions == true
                }

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
                    snackbarHost = {
                        // The app's own colours, not Material's default.
                        //
                        // A stock Snackbar is `inverseSurface` -- near-black,
                        // floating, and identical to every system toast on the
                        // phone. Reported from a device: the send messages
                        // "don't look like they are part of the app". They were
                        // also the LONGEST text the app shows, so the one
                        // surface most likely to be read carefully was the one
                        // that looked least like it belonged.
                        SnackbarHost(snackbar) { data ->
                            Snackbar(
                                snackbarData = data,
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                actionColor = MaterialTheme.colorScheme.primary,
                                shape = MaterialTheme.shapes.large
                            )
                        }
                    }
                ) { inner ->
                    when (tab) {
                        Tab.DESIGNS -> DesignsScreen(
                            // A style is a starting point, not a face: it has
                            // no identity yet, so Save must ask for a name.
                            onPick = { params = it; openSlug = null; tab = Tab.STUDIO },
                            modifier = Modifier.padding(inner)
                        )

                        // No outer scroll: StudioScreen pins the dial and
                        // scrolls only the controls, so wrapping it in a second
                        // scrolling container would both fight it and crash
                        // (a scrollable measured with infinite height).
                        Tab.STUDIO -> StudioScreen(
                            params = params,
                            onParams = { params = it },
                            ambient = ambient,
                            onAmbient = { ambient = it },
                            onTune = { tuning = true },
                            onCustomColor = { dial -> pickingDial = dial; picking = true },
                            modifier = Modifier.padding(inner),
                            footer = {
                            Column {
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
                                    // The name comes from the face that is
                                    // OPEN, or is asked for. Never `sendingName`.
                                    //
                                    // `sendingName` was ambient state: set when
                                    // a face was opened, when one was saved, or
                                    // defaulted to whatever was last on the
                                    // watch. Nothing tied it to what is on the
                                    // screen. So editing an open face and
                                    // sending sent the NEW design under the OLD
                                    // face's name — same package, so it
                                    // replaced a face on the watch that the
                                    // wearer had not touched. Reported as
                                    // "sending from My faces is different from
                                    // sending from the Studio", and it was:
                                    // My faces sends a face, the Studio sent a
                                    // name that had drifted away from one.
                                    //
                                    // A design with no name is not a face yet —
                                    // CLAUDE.md is explicit that a face gets its
                                    // identity when somebody names it — so an
                                    // unsaved one goes through the same naming
                                    // sheet Save uses rather than borrowing an
                                    // identity that belongs to something else.
                                    onClick = {
                                        if (open != null) requestSend(open.name, params)
                                        else { nameThenSend = true; naming = true }
                                    },
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
                            }
                        )

                        Tab.MINE -> MyFacesScreen(
                            faces = faces,
                            onOpen = {
                                params = it.params
                                openSlug = it.slug
                                tab = Tab.STUDIO
                            },
                            onSend = { requestSend(it.name, it.params) },
                            onShare = { sharingSlug = it.slug },
                            shared = shared,
                            canShare = canShare,
                            onDelete = { FaceStorage.delete(context, it.slug); faces = FaceStorage.list(context) },
                            modifier = Modifier.padding(inner)
                        )

                        Tab.ABOUT -> AboutScreen(Modifier.padding(inner))
                    }
                }

                if (tuning) {
                    TuneSheet(
                        params = params,
                        onParams = { params = it },
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
                        onDismiss = { naming = false; nameThenSend = false },
                        onSave = { name ->
                            FaceStorage.save(context, name, params)
                            faces = FaceStorage.list(context)
                            naming = false
                            // Naming was the price of sending, so sending is
                            // what happens next. Saving and then stopping would
                            // leave the button that was pressed unfulfilled.
                            if (nameThenSend) {
                                nameThenSend = false
                                openSlug = FaceLibrary.slugify(name)
                                requestSend(name, params)
                            } else {
                                scope.launch { snackbar.showSnackbar("Saved “$name”") }
                            }
                        }
                    )
                }

                /**
                 * Sign in, then do the thing, on a scope that outlives the sheet.
                 *
                 * The token is used for this one call and never stored: the
                 * account is a way to prove the same person is taking a face
                 * back, not a session the app keeps.
                 */
                fun withAccount(action: suspend (String) -> Unit) {
                    shareBusy = true
                    shareError = null
                    scope.launch {
                        try {
                            val config = withContext(Dispatchers.IO) {
                                CatalogAccess.service(context).config()
                            }
                            val clientId =
                                (config as? CatalogService.Result.Ok)?.value?.googleClientId.orEmpty()
                            when (val outcome = GoogleSignIn.idToken(this@MainActivity, clientId)) {
                                is GoogleSignIn.Outcome.Ok -> action(outcome.idToken)
                                // Changing your mind is not an error and must not look like one.
                                GoogleSignIn.Outcome.Cancelled -> Unit
                                is GoogleSignIn.Outcome.Failed -> shareError = outcome.message
                            }
                        } finally {
                            shareBusy = false
                        }
                    }
                }

                sharing?.let { face ->
                    ShareSheet(
                        faceName = face.name,
                        existing = shared[face.slug],
                        outcome = shareOutcome,
                        busy = shareBusy,
                        failure = shareError,
                        onShare = { author ->
                            withAccount { token ->
                                val result = withContext(Dispatchers.IO) {
                                    CatalogAccess.service(context)
                                        .submit(face.name, author, face.params, token)
                                }
                                when (result) {
                                    is CatalogService.Result.Ok -> {
                                        SubmissionStore.record(context, face.slug, result.value.id)
                                        // Re-read from disk rather than patching the
                                        // map by hand: the store is what changed, and
                                        // a second opinion here is how the row and the
                                        // sheet end up describing one state two ways.
                                        shared = SubmissionStore.all(context).associateBy { it.slug }
                                        shareOutcomeName = ShareOutcome.SENT.name
                                    }
                                    is CatalogService.Result.Failed -> shareError = result.message
                                }
                            }
                        },
                        onTakeBack = {
                            val record = shared[face.slug] ?: return@ShareSheet
                            withAccount { token ->
                                val result = withContext(Dispatchers.IO) {
                                    CatalogAccess.service(context).withdraw(record.id, token)
                                }
                                when (result) {
                                    is CatalogService.Result.Ok -> {
                                        SubmissionStore.forget(context, record.id)
                                        shared = SubmissionStore.all(context).associateBy { it.slug }
                                        shareOutcomeName = ShareOutcome.WITHDRAWN.name
                                    }
                                    is CatalogService.Result.Failed -> shareError = result.message
                                }
                            }
                        },
                        onDismiss = {
                            sharingSlug = null
                            shareOutcomeName = null
                            shareError = null
                        }
                    )
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

        // NEVER ask the watch to rebuild the slots.
        //
        // The reset exists because `isCustomizable="TRUE"` let the watch's
        // editor own a slot forever, so only a fresh slot would take the
        // design's complications. From v8 the definition is authoritative and a
        // plain `updateWatchFace` applies it -- the reset buys nothing.
        //
        // And it can destroy the face. Resetting REMOVES the installed face
        // before adding the new one, so a failure in between leaves the watch
        // with none at all: "I sent it several times and mine is not even in
        // the list". It was requested on every complication change, which is
        // the single most common edit anyone makes.
        //
        // The watch still understands the request, and the debug receiver can
        // still make it. Nothing in the normal path does.
        val previouslySent = CurrentFace.load(context)?.params
        Log.i(TAG, "sending \u201C$name\u201D")

        return runCatching { FaceSender.send(context, target, built.apk, token) }
            .fold(
                // The WATCH's verdict now, not this side's guess. Null means
                // it said nothing -- an older build, or it never picked the
                // transfer up -- and that is reported as unknown rather than
                // as either outcome. See WatchLink.Report.
                onSuccess = { report ->
                    // The watch sends its provider catalog back with the
                    // verdict. Keeping it is what lets the picker offer what is
                    // actually installed rather than only what this build knows.
                    WatchLink.Report.catalogIn(report)?.let { ProviderCache.save(context, it) }
                    WatchLink.Report.launchersIn(report)?.let { ProviderCache.saveLaunchers(context, it) }
                    // Said ONCE, and only to someone who had a face from before
                    // the definition became authoritative: their on-watch
                    // complication picks have just been replaced by the design,
                    // and that change with no explanation is indistinguishable
                    // from a bug. Appended to the result rather than shown
                    // separately, because that is what they are already reading.
                    val moved = Onboarding.shouldExplainComplications(
                        context, previouslySent?.generatorVersion
                    )
                    if (moved) Onboarding.markComplicationsExplained(context)
                    WatchLink.Report.describe(name, target.name, report) +
                        if (moved) " Complications are chosen here now, not on the watch." else ""
                },
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

/**
 * How a design survives the Activity being recreated.
 *
 * `DialParams` is not `Parcelable` and should not become it: that would put an
 * Android type in the way of a class `:generator` owns, and `:generator` is
 * deliberately free of Android so the file format can be tested on the JVM.
 *
 * It does not need to be. `FaceCodec` already round-trips `DialParams` to JSON,
 * because that IS the stored file format — the same bytes a saved face and a
 * catalog submission are made of. Saving the edit in progress is therefore the
 * format the app already trusts, not a second serialisation invented for the
 * UI, and `FaceCodecTest` already covers it.
 */
private val FaceParamsSaver: Saver<DialParams, String> = Saver(
    save = { FaceCodec.toJson(it) },
    restore = { runCatching { FaceCodec.fromJson(Json.obj(Json.parse(it))) }.getOrNull() }
)
