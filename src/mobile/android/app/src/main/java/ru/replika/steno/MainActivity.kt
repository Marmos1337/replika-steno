package ru.replika.steno

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color as AColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import ru.replika.steno.data.ThemeMode
import ru.replika.steno.ui.AppShell
import ru.replika.steno.ui.ReplikaViewModel
import ru.replika.steno.ui.theme.DarkPalette
import ru.replika.steno.ui.theme.LightPalette
import ru.replika.steno.ui.theme.ReplikaTheme

class MainActivity : ComponentActivity() {
    private val vm: ReplikaViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Resolve the theme synchronously before the first frame, so a dark app on a light system
        // (or the other way round) never shows a frame in the wrong colors.
        val mode = (application as ReplikaApp).graph.settings.themeSync()
        ReplikaViewModel.applyNightMode(this, mode)
        super.onCreate(savedInstanceState)
        applyWindow(resolveDark(mode, systemDark()))

        setContent {
            val theme by vm.theme.collectAsState()
            val dark = resolveDark(theme, isSystemInDarkTheme())
            LaunchedEffect(dark) { applyWindow(dark) }
            ReplikaTheme(dark) { AppShell(vm) }
        }
        if (savedInstanceState == null) handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun systemDark(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun resolveDark(mode: ThemeMode, system: Boolean) = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> system
    }

    private fun applyWindow(dark: Boolean) {
        val bg = (if (dark) DarkPalette.bg else LightPalette.bg).toArgb()
        window.decorView.setBackgroundColor(bg)
        val style = if (dark) SystemBarStyle.dark(AColor.TRANSPARENT) else SystemBarStyle.light(AColor.TRANSPARENT, AColor.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
    }

    /** "Share to Реплика" from a recorder or a messenger: import the audio like a picked file. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (uri != null) vm.import(uri) else vm.toast("В отправленном нет аудиофайла.")
        setIntent(Intent(this, MainActivity::class.java))
    }
}
