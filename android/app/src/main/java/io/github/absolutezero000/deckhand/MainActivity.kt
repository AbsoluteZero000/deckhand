package io.github.absolutezero000.deckhand

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.absolutezero000.deckhand.data.ThemeMode
import io.github.absolutezero000.deckhand.ui.DeckhandNav
import io.github.absolutezero000.deckhand.ui.theme.DeckhandTheme
import io.github.absolutezero000.deckhand.ui.theme.isDark
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = (application as App).repository
        val themeFlow = repo.settings.state.map { it.theme }
        setContent {
            val mode by themeFlow.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            val dark = isDark(mode)
            // Status and navigation bar icons follow the app's theme, not only the system's.
            DisposableEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            DeckhandTheme(dark = dark) { DeckhandNav(repo) }
        }
    }
}
