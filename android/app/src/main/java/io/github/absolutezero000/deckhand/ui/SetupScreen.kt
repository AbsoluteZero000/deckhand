package io.github.absolutezero000.deckhand.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.absolutezero000.deckhand.data.Repository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(repo: Repository, canGoBack: Boolean, onBack: () -> Unit, onDone: () -> Unit) {
    var repoName by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }
    var showToken by rememberSaveable { mutableStateOf(false) }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun connect() {
        busy = true; error = null
        scope.launch {
            val e = repo.connect(repoName, token)
            busy = false
            if (e == null) onDone() else error = e
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (canGoBack) "Change repo" else "") },
                navigationIcon = {
                    if (canGoBack) IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!canGoBack) BrandHeader()
            OutlinedTextField(
                value = repoName, onValueChange = { repoName = it.trim(); error = null },
                label = { Text("Cards repo") }, placeholder = { Text("owner/name") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                value = token, onValueChange = { token = it.trim(); error = null },
                label = { Text("GitHub token") }, placeholder = { Text("github_pat_…") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                trailingIcon = {
                    IconButton(onClick = { showToken = !showToken }) {
                        Icon(
                            if (showToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (showToken) "Hide token" else "Show token",
                        )
                    }
                },
                supportingText = { Text("Stored encrypted on this phone. Never shown again.") },
            )
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Button(
                onClick = ::connect, enabled = !busy && repoName.isNotBlank() && token.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(12.dp))
                    Text("Checking…")
                } else Text("Connect")
            }
            TextButton(onClick = { showHelp = !showHelp }) {
                Text(if (showHelp) "Hide token help" else "How do I make a token?")
            }
            if (showHelp) TokenHelp()
            if (!canGoBack) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("No repo yet?", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { scope.launch { repo.loadDemo(); onDone() } },
                    ) { Text("Try the example deck") }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun BrandHeader() {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF1D6B7F), Color(0xFF2A4F86))))
            .padding(24.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Three fanned cards: a small mark for the app.
            Box(Modifier.size(64.dp)) {
                listOf(-14f to 0.45f, 0f to 0.7f, 14f to 1f).forEach { (angle, alpha) ->
                    Box(
                        Modifier.size(width = 40.dp, height = 54.dp).align(Alignment.Center)
                            .graphicsLayer { rotationZ = angle }
                            .clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = alpha)),
                    )
                }
            }
            Text("deckhand", style = MaterialTheme.typography.displaySmall, color = Color.White)
            Text(
                "Flashcards from your own private GitHub repo. Swipe to study, edit on the go, works offline.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}

@Composable
private fun TokenHelp() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("On GitHub, open:", style = MaterialTheme.typography.titleSmall)
            Text("Settings → Developer settings → Personal access tokens → Fine-grained tokens → Generate new token")
            Text("• Repository access: Only select repositories → your cards repo")
            Text("• Permissions: Contents → Read and write to edit cards in the app, or Read-only to just study (nothing else)")
            Text("• Expiration: your choice; the app tells you when it stops working")
            Text(
                "Your cards repo needs a published branch. Push to main once and its cards workflow creates it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
