package io.github.absolutezero000.deckhand.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.absolutezero000.deckhand.BuildConfig
import io.github.absolutezero000.deckhand.data.GitHubAuth
import io.github.absolutezero000.deckhand.data.Repository
import io.github.absolutezero000.deckhand.data.SyncException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where "Connect with GitHub" is. */
sealed interface GhState {
    data object Idle : GhState
    data object Starting : GhState
    data class Code(val userCode: String, val uri: String) : GhState
    data class Pick(val login: String, val repos: List<String>) : GhState
    data class Connecting(val repo: String) : GhState
    data class Failed(val message: String) : GhState
}

class SetupViewModel(private val repo: Repository) : ViewModel() {
    var state by mutableStateOf<GhState>(GhState.Idle); private set
    private var token: String? = null
    private var job: Job? = null

    val available get() = BuildConfig.GITHUB_CLIENT_ID.isNotBlank()
    val installUrl get() = "https://github.com/apps/${BuildConfig.GITHUB_APP_SLUG}/installations/new"

    fun connectWithGitHub() {
        job?.cancel()
        job = viewModelScope.launch {
            state = GhState.Starting
            try {
                val clientId = BuildConfig.GITHUB_CLIENT_ID
                val code = repo.auth.start(clientId)
                state = GhState.Code(code.user_code, code.verification_uri)
                var interval = code.interval.coerceAtLeast(5)
                val deadline = System.currentTimeMillis() + code.expires_in * 1000L
                while (true) {
                    delay(interval * 1000L)
                    if (System.currentTimeMillis() > deadline) { state = GhState.Failed("The code expired. Try again."); return@launch }
                    when (val p = repo.auth.poll(clientId, code.device_code)) {
                        GitHubAuth.Poll.Pending -> Unit
                        is GitHubAuth.Poll.SlowDown -> interval = p.interval
                        GitHubAuth.Poll.Expired -> { state = GhState.Failed("The code expired. Try again."); return@launch }
                        GitHubAuth.Poll.Denied -> { state = GhState.Failed("Sign-in was cancelled on GitHub."); return@launch }
                        is GitHubAuth.Poll.Token -> { token = p.token; loadRepos(); return@launch }
                    }
                }
            } catch (e: SyncException) {
                state = GhState.Failed(e.message ?: "Couldn't connect to GitHub.")
            }
        }
    }

    /** (Re)loads the repos the app is installed on, e.g. after the person installs it on another repo. */
    fun loadRepos() {
        val t = token ?: return connectWithGitHub()
        job?.cancel()
        job = viewModelScope.launch {
            try {
                val login = repo.auth.login(t)
                state = GhState.Pick(login, repo.auth.repos(t))
            } catch (e: SyncException) {
                state = GhState.Failed(e.message ?: "Couldn't list your repos.")
            }
        }
    }

    /** Shown under the repo list when picking a repo failed (e.g. no published branch yet). */
    var error by mutableStateOf<String?>(null)

    fun pick(name: String, done: () -> Unit) {
        val t = token ?: return
        val login = (state as? GhState.Pick)?.login
        val back = state
        job?.cancel()
        job = viewModelScope.launch {
            state = GhState.Connecting(name)
            val e = repo.connect(name, t, account = login)
            if (e == null) done() else {
                // Stay on the list (e.g. the repo has no published branch yet) and say why.
                error = e
                state = back
            }
        }
    }

    fun cancel() {
        job?.cancel(); token = null; error = null
        state = GhState.Idle
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(repo: Repository, canGoBack: Boolean, onBack: () -> Unit, onDone: () -> Unit) {
    val vm = viewModel { SetupViewModel(repo) }
    val ctx = LocalContext.current
    var showToken by rememberSaveable { mutableStateOf(!vm.available) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (canGoBack) "Change repo" else "") },
                navigationIcon = {
                    if (canGoBack) IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!canGoBack) BrandHeader()

            if (vm.available) GitHubConnect(vm, ctx, onDone)

            if (vm.available) {
                TextButton(onClick = { showToken = !showToken }) {
                    Text(if (showToken) "Hide token sign-in" else "Use a token instead")
                }
            }
            AnimatedVisibility(showToken) { TokenConnect(repo, onDone) }

            if (!canGoBack) {
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Just looking?", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { scope.launch { repo.loadDemo(); onDone() } }) { Text("Try the example deck") }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun GitHubConnect(vm: SetupViewModel, ctx: Context, onDone: () -> Unit) {
    when (val s = vm.state) {
        GhState.Idle, GhState.Starting -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = vm::connectWithGitHub,
                enabled = s == GhState.Idle,
                modifier = Modifier.fillMaxWidth().height(58.dp),
                shape = RoundedCornerShape(18.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0xFF1F2328), contentColor = Color.White),
            ) {
                if (s == GhState.Starting) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                else {
                    Icon(Icons.Outlined.Lock, null, modifier = Modifier.size(20.dp))
                    HSpace(10.dp)
                    Text("Connect with GitHub", style = MaterialTheme.typography.titleMedium)
                }
            }
            Text(
                "You approve deckhand on github.com and choose which repos it can see. It only gets access to the repos you pick.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is GhState.Code -> Panel {
            Text("STEP 1 OF 2", style = io.github.absolutezero000.deckhand.ui.theme.Overline, color = MaterialTheme.colorScheme.primary)
            Text("Enter this code on GitHub", style = MaterialTheme.typography.titleLarge)
            Text(
                s.userCode,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 34.sp,
                letterSpacing = 4.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(vertical = 14.dp),
            )
            Button(
                onClick = { copy(ctx, s.userCode); open(ctx, s.uri) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("Copy code and open GitHub")
                HSpace(8.dp)
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(18.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                HSpace(10.dp)
                Text(
                    "Waiting for you to approve on GitHub…",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = vm::cancel) { Text("Cancel") }
            }
        }

        is GhState.Pick -> Panel {
            Text("STEP 2 OF 2", style = io.github.absolutezero000.deckhand.ui.theme.Overline, color = MaterialTheme.colorScheme.primary)
            Text("Signed in as @${s.login}", style = MaterialTheme.typography.titleLarge)
            if (s.repos.isEmpty()) {
                Text(
                    "deckhand isn't installed on any of your repos yet. Install it on your cards repo, then come back here.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { open(ctx, vm.installUrl) }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
                    Text("Install on your cards repo"); HSpace(8.dp)
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(18.dp))
                }
                OutlinedButton(onClick = vm::loadRepos, modifier = Modifier.fillMaxWidth()) { Text("I've installed it") }
            } else {
                Text("Pick your cards repo", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                s.repos.forEach { name ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { vm.error = null; vm.pick(name, onDone) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.primary)
                        HSpace(12.dp)
                        Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    }
                }
                vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                TextButton(onClick = { open(ctx, vm.installUrl) }) { Text("Don't see it? Give deckhand access to more repos") }
                TextButton(onClick = vm::loadRepos) { Text("Refresh list") }
            }
            TextButton(onClick = vm::cancel) { Text("Start over") }
        }

        is GhState.Connecting -> Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                HSpace(12.dp)
                Text("Loading cards from ${s.repo}…", style = MaterialTheme.typography.titleMedium)
            }
        }

        is GhState.Failed -> Panel {
            Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
            Button(onClick = vm::connectWithGitHub, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
        }
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

/** The original repo + token form, for people who'd rather paste a fine-grained token. */
@Composable
private fun TokenConnect(repo: Repository, onDone: () -> Unit) {
    var repoName by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }
    var showToken by rememberSaveable { mutableStateOf(false) }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        OutlinedButton(
            onClick = {
                busy = true; error = null
                scope.launch {
                    val e = repo.connect(repoName, token)
                    busy = false
                    if (e == null) onDone() else error = e
                }
            },
            enabled = !busy && repoName.isNotBlank() && token.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Connect with token")
        }
        TextButton(onClick = { showHelp = !showHelp }) { Text(if (showHelp) "Hide token help" else "How do I make a token?") }
        if (showHelp) TokenHelp()
    }
}

private fun copy(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("GitHub code", text))
}

private fun open(ctx: Context, url: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // No browser; the code is still on screen and copied.
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
