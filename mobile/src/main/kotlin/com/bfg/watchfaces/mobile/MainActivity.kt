package com.bfg.watchfaces.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.isSystemInDarkTheme
import android.os.Build
import com.bfg.watchfaces.appcore.ActivationConsent

/**
 * The design app's entry point.
 *
 * Deliberately thin. `DECISIONS.md` 2026-08-28 commits to the localhost app
 * being the exact specification for this one, so the screens arrive by being
 * ported from it rather than invented here, and the first one ported is the
 * activation handoff because it is the only screen whose absence is
 * unrecoverable — the permission it leads to can be asked once, ever.
 *
 * This does NOT request the activation permission and never will. That belongs
 * to `:wear`; see [ActivationHandoffScreen].
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BfgTheme {
                var consent by remember { mutableStateOf(readConsent()) }
                ActivationHandoffScreen(
                    faceName = "your face",
                    onContinue = { /* Data Layer send lands here; see :wear. */ },
                    onCancel = { finish() }
                )
                // Referenced so the denial path is wired rather than dead code
                // waiting to be discovered wrong later.
                ActivationDeniedNote(state = consent)
            }
        }
    }

    /**
     * The remembered answer, which must outlive the process: "have we asked yet"
     * is meaningless if it resets on restart, and re-asking is the one thing
     * that cannot be undone.
     *
     * `filesDir` is the Android equivalent of the directory the localhost app
     * uses, so [ActivationConsent] needs no Android-specific branch.
     */
    private fun readConsent(): ActivationConsent.State = ActivationConsent.load(filesDir)
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
