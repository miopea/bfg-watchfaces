package com.bfg.watchfaces.mobile

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bfg.watchfaces.appcore.ActivationConsent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The design app's entry point.
 *
 * `DECISIONS.md` 2026-08-28 commits to the localhost app being the exact
 * specification for this one, so screens arrive by being ported from it rather
 * than invented here. [StudioScreen] is that port; the activation handoff came
 * first only because it is the one screen whose absence is unrecoverable.
 *
 * This does NOT request the activation permission and never will. That belongs
 * to `:wear` — `androidx.wear.watchfacepush` declares `wear-sdk` as a required
 * library, so an app linking it will not install on a phone at all.
 */
class MainActivity : ComponentActivity() {

    private enum class Screen { STUDIO, HANDOFF }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BfgTheme {
                val scope = rememberCoroutineScope()
                val context = LocalContext.current
                val consent = remember { ActivationConsent.load(filesDir) }

                // rememberSaveable so a rotation does not throw away a design.
                var engineName by rememberSaveable { mutableStateOf(Presentation.STARTING_FACE.engine.name) }
                var params by remember {
                    mutableStateOf(Presentation.STARTING_FACE.copy(engine = enumValueOf(engineName)))
                }
                var screen by rememberSaveable { mutableStateOf(Screen.STUDIO) }
                var status by remember { mutableStateOf<String?>(null) }
                var ambient by rememberSaveable { mutableStateOf(false) }

                BackHandler(enabled = screen == Screen.HANDOFF) { screen = Screen.STUDIO }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            // safeDrawing, not a fixed pad: enableEdgeToEdge draws
                            // behind the status bar, and without this the content
                            // sits under the clock. Seen on a real screen -- it
                            // does not show up in any test.
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .verticalScroll(rememberScrollState())
                    ) {
                        when (screen) {
                            Screen.STUDIO -> {
                                StudioScreen(
                                    params = params,
                                    onParams = { params = it; engineName = it.engine.name },
                                    ambient = ambient,
                                    onAmbient = { ambient = it }
                                )
                                Button(
                                    onClick = { screen = Screen.HANDOFF },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp)
                                ) { Text("Send to watch") }
                                Spacer(Modifier.height(24.dp))
                            }

                            Screen.HANDOFF -> {
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
                                    onCancel = { screen = Screen.STUDIO }
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
                                ActivationDeniedNote(
                                    state = consent,
                                    modifier = Modifier.padding(24.dp)
                                )
                            }
                        }
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

/**
 * Material 3, following the system where it can.
 *
 * Dynamic colour on Android 12+ so a watch-face design tool sits inside the
 * user's own palette rather than imposing one; a fixed fallback below that.
 */
@Composable
fun BfgTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
