package io.github.absolutezero000.deckhand.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import io.github.absolutezero000.deckhand.data.ThemeMode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.absolutezero000.deckhand.BuildConfig
import io.github.absolutezero000.deckhand.data.ProgressIo
import io.github.absolutezero000.deckhand.data.Repository
import io.github.absolutezero000.deckhand.data.SettingsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

private enum class Confirm { RESET, DISCONNECT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(repo: Repository, onBack: () -> Unit, onChangeRepo: () -> Unit, onDisconnected: () -> Unit) {
    val s by repo.settings.state.collectAsStateWithLifecycle(initialValue = null as SettingsState?)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var syncing by remember { mutableStateOf(false) }

    fun say(msg: String) = scope.launch { snackbar.showSnackbar(msg) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val rows = repo.dao.allProgress()
            val text = ProgressIo.export(rows, s?.repo)
            val ok = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) } }.isSuccess
            }
            say(if (ok) "Exported progress for ${rows.size} cards." else "Couldn't write the file.")
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                    ProgressIo.parse(text, repo.dao.cardIds().toSet())
                }
            }
            result.fold(
                onSuccess = { (rows, skipped) ->
                    repo.dao.upsertProgressList(rows)
                    say("Imported progress for ${rows.size} cards" + if (skipped > 0) " ($skipped not in your cards, skipped)." else ".")
                },
                onFailure = { say(it.message ?: "Couldn't read that file.") },
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        val st = s ?: return@Scaffold
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
            Header("Cards")
            ListItem(
                headlineContent = { Text(if (st.demo) "Example deck" else st.repo.orEmpty()) },
                supportingContent = {
                    Text(
                        if (st.demo) "Connect your own repo to study your cards."
                        else (st.account?.let { "Signed in as @$it · " } ?: "Token · ") +
                            "content version ${st.contentVersion?.take(7) ?: "–"}" +
                            (st.lastError?.let { "\n$it" } ?: ""),
                    )
                },
                trailingContent = {
                    TextButton(onClick = onChangeRepo) { Text(if (st.demo) "Connect" else "Change") }
                },
            )
            if (!st.demo) Item("Sync now", if (syncing) "Syncing…" else "Get the latest cards from GitHub") {
                if (!syncing) scope.launch {
                    syncing = true
                    val e = repo.sync(force = true)
                    syncing = false
                    say(e ?: "Up to date.")
                }
            }
            HorizontalDivider()
            Header("Appearance")
            ListItem(
                headlineContent = { Text("Theme") },
                supportingContent = {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        val modes = ThemeMode.entries
                        modes.forEachIndexed { i, m ->
                            SegmentedButton(
                                selected = st.theme == m,
                                onClick = { scope.launch { repo.settings.setTheme(m) } },
                                shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                                icon = {
                                    Icon(
                                        when (m) {
                                            ThemeMode.SYSTEM -> Icons.Outlined.BrightnessAuto
                                            ThemeMode.LIGHT -> Icons.Outlined.LightMode
                                            ThemeMode.DARK -> Icons.Outlined.DarkMode
                                        },
                                        null, modifier = Modifier.size(18.dp),
                                    )
                                },
                            ) {
                                Text(m.name.lowercase().replaceFirstChar { it.uppercase() })
                            }
                        }
                    }
                },
            )
            HorizontalDivider()
            Header("Study")
            ListItem(
                headlineContent = { Text("New cards per day") },
                supportingContent = { Text("Due cards are always shown") },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { scope.launch { repo.settings.setNewPerDay(st.newPerDay - 5) } }) {
                            Icon(Icons.Filled.Remove, "Fewer")
                        }
                        Text("${st.newPerDay}", Modifier.width(36.dp), textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = { scope.launch { repo.settings.setNewPerDay(st.newPerDay + 5) } }) {
                            Icon(Icons.Filled.Add, "More")
                        }
                    }
                },
            )
            HorizontalDivider()
            Header("Progress")
            Item("Export progress", "Save a backup file you can import on any phone") {
                exporter.launch("deckhand-progress-${LocalDate.now()}.json")
            }
            Item("Import progress", "Replaces progress for the cards in the file") {
                importer.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
            }
            Item("Reset progress", "Every card becomes new again", danger = true) { confirm = Confirm.RESET }
            HorizontalDivider()
            Item(
                if (st.demo) "Remove example deck" else "Disconnect",
                if (st.demo) "Removes the example cards and their progress"
                else "Removes the token, cards and progress from this phone",
                danger = true,
            ) { confirm = Confirm.DISCONNECT }
            Text(
                "deckhand ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (c == Confirm.RESET) "Reset all progress?" else "Disconnect?") },
            text = {
                Text(
                    if (c == Confirm.RESET) "Every card becomes new. Export first if you might want it back."
                    else "The token, cards and all progress are removed from this phone. Your cards repo is not touched."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    scope.launch {
                        if (c == Confirm.RESET) {
                            repo.dao.clearProgress(); say("Progress reset.")
                        } else {
                            repo.disconnect(); onDisconnected()
                        }
                    }
                }) { Text(if (c == Confirm.RESET) "Reset" else "Disconnect", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun Item(title: String, subtitle: String, danger: Boolean = false, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(title, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        },
        supportingContent = { Text(subtitle) },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}
