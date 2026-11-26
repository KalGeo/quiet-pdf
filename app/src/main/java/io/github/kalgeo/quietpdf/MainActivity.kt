// SPDX-License-Identifier: GPL-3.0-only

package io.github.kalgeo.quietpdf

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key

class MainActivity : ComponentActivity() {
    private val vm: ReaderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            AppTheme { App(vm, onExit = ::finish) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        intent.data?.let { vm.open(it, external = true) }
    }
}

@Composable
private fun App(vm: ReaderViewModel, onExit: () -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.open(uri)
    }
    when (val screen = vm.screen) {
        is Screen.Reader -> key(screen) {
            ReaderScreen(
                reader = screen,
                nightMode = vm.nightMode,
                onToggleNightMode = vm::toggleNightMode,
                onBack = { if (screen.external) onExit() else vm.closeDocument() },
                onPositionChanged = { vm.savePosition(screen.doc, it) },
            )
        }

        else -> HomeScreen(
            recents = vm.recents,
            loading = screen is Screen.Loading,
            error = vm.error,
            onErrorShown = vm::clearError,
            onOpenPicker = { picker.launch(arrayOf("application/pdf")) },
            onOpenRecent = { vm.open(it) },
            onRemoveRecent = vm::removeRecent,
        )
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    // Always the app's own purple (#6750A4, Material's baseline), not colours taken from the
    // wallpaper, so the app looks the same on every phone.
    val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colors, content = content)
}
