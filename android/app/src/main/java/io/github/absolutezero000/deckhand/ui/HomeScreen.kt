package io.github.absolutezero000.deckhand.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.absolutezero000.deckhand.data.DeckEntity
import io.github.absolutezero000.deckhand.data.Repository
import io.github.absolutezero000.deckhand.data.SettingsState
import io.github.absolutezero000.deckhand.domain.Filter
import io.github.absolutezero000.deckhand.domain.MoreKind
import io.github.absolutezero000.deckhand.domain.Queue
import io.github.absolutezero000.deckhand.domain.Streak
import io.github.absolutezero000.deckhand.ui.theme.Flame
import io.github.absolutezero000.deckhand.ui.theme.LocalDarkTheme
import io.github.absolutezero000.deckhand.ui.theme.Overline
import io.github.absolutezero000.deckhand.ui.theme.deckAccent
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalTime

/**
 * [mastery] is 0..1: each card counts by how long its review gap has grown, up to 21 days
 * (a card you've known for three weeks counts fully). It grows steadily instead of jumping
 * to 100% after one session.
 */
data class DeckRow(val deck: DeckEntity, val index: Int, val total: Int, val due: Int, val new: Int, val mastery: Float)

private const val MASTERED_DAYS = 21

data class HomeData(
    val decks: List<DeckRow>,
    val dueTotal: Int,
    /** New cards today's limit still allows, across all decks. */
    val newReady: Int,
    val streak: Int,
    val reviewedToday: Int,
    /** What "Keep going" would offer once today's work is done. */
    val more: Pair<MoreKind, Int>,
)

class HomeViewModel(private val repo: Repository) : ViewModel() {
    val data: StateFlow<HomeData?> = combine(
        repo.dao.decks(), repo.dao.allCards(), repo.dao.progressFlow(),
        repo.dao.reviewDays(repo.today() - 400), repo.settings.state,
    ) { decks, cards, progress, days, settings ->
        val today = repo.today()
        val byId = progress.associateBy { it.cardId }
        val byDeck = cards.groupBy { it.deckId }
        val rows = decks.mapIndexed { i, d ->
            val list = byDeck[d.id].orEmpty()
            val (due, new) = Queue.counts(list, byId, today)
            val mastery = if (list.isEmpty()) 0f
            else list.sumOf { minOf(byId[it.id]?.intervalDays ?: 0, MASTERED_DAYS) } / (MASTERED_DAYS * list.size).toFloat()
            DeckRow(d, i, list.size, due, new, mastery)
        }
        val seenToday = progress.count { it.firstSeenDay == today }
        val allowance = (settings.newPerDay - seenToday).coerceAtLeast(0)
        HomeData(
            decks = rows,
            dueTotal = rows.sumOf { it.due },
            newReady = minOf(allowance, rows.sumOf { it.new }),
            streak = Streak.days(days.filter { it.count > 0 }.map { it.day }.toSet(), today),
            reviewedToday = days.firstOrNull { it.day == today }?.count ?: 0,
            more = Queue.more(cards, byId, Filter(), today).let { (k, ids) -> k to ids.size },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val settings: StateFlow<SettingsState?> =
        repo.settings.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    var syncing by mutableStateOf(false)
        private set

    init { refresh(force = false) }

    fun refresh(force: Boolean) {
        if (syncing) return
        viewModelScope.launch {
            syncing = true
            repo.sync(force)
            syncing = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    repo: Repository,
    onDeck: (String) -> Unit,
    onBrowse: (String) -> Unit,
    onStudyAll: () -> Unit,
    onKeepGoing: () -> Unit,
    onSettings: () -> Unit,
) {
    val vm = viewModel { HomeViewModel(repo) }
    val data by vm.data.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    Scaffold { pad ->
        PullToRefreshBox(
            isRefreshing = vm.syncing,
            onRefresh = { vm.refresh(force = true) },
            modifier = Modifier.fillMaxSize().padding(bottom = pad.calculateBottomPadding()),
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Header(
                        showSync = settings?.demo == false,
                        syncing = vm.syncing,
                        onSync = { vm.refresh(force = true) },
                        onSettings = onSettings,
                    )
                }
                val d = data
                if (d != null) {
                    item { TodayCard(d, onStudyAll, onKeepGoing) }
                    item {
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Your decks", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                            settings?.let { SyncLine(it) }
                        }
                    }
                    if (d.decks.isEmpty()) item {
                        Text(
                            "No cards yet. Add some to your cards repo, push, then pull down here to sync.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                    itemsIndexed(d.decks, key = { _, r -> r.deck.id }) { _, row ->
                        DeckTile(row, onClick = { onDeck(row.deck.id) }, onBrowse = { onBrowse(row.deck.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(showSync: Boolean, syncing: Boolean, onSync: () -> Unit, onSettings: () -> Unit) {
    val hour = LocalTime.now().hour
    val greeting = when (hour) {
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        else -> "Good evening"
    }
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(greeting, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Let's study", style = MaterialTheme.typography.headlineLarge)
        }
        if (showSync) IconButton(onClick = onSync, enabled = !syncing) {
            Icon(Icons.Filled.Sync, contentDescription = "Sync now")
        }
        FilledTonalIconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
    }
}

@Composable
private fun TodayCard(d: HomeData, onStudyAll: () -> Unit, onKeepGoing: () -> Unit) {
    val dark = LocalDarkTheme.current
    val brush = if (dark) Brush.linearGradient(listOf(Color(0xFF14505F), Color(0xFF1B3A5C)))
    else Brush.linearGradient(listOf(Color(0xFF1D6B7F), Color(0xFF2A4F86)))
    val ready = d.dueTotal + d.newReady
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(brush).padding(22.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("TODAY", style = Overline, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.weight(1f))
                StreakBadge(d.streak)
            }
            if (ready > 0) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("$ready", style = MaterialTheme.typography.displayMedium, color = Color.White)
                    HSpace(10.dp)
                    Text(
                        if (ready == 1) "card ready" else "cards ready",
                        style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
                Text(
                    "${d.dueTotal} due · ${d.newReady} new" + if (d.reviewedToday > 0) " · ${d.reviewedToday} reviewed today" else "",
                    style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f),
                )
                Button(
                    onClick = onStudyAll,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF14505F)),
                    modifier = Modifier.height(50.dp),
                    contentPadding = PaddingValues(horizontal = 22.dp),
                ) {
                    Text("Study now", style = MaterialTheme.typography.titleSmall)
                    HSpace(8.dp)
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(18.dp))
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CheckCircle, null, tint = Color.White, modifier = Modifier.size(36.dp))
                    HSpace(12.dp)
                    Column {
                        Text("All caught up", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                        Text(
                            if (d.reviewedToday > 0) "${d.reviewedToday} reviewed today. Nice."
                            else "Nothing due right now.",
                            style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f),
                        )
                    }
                }
                if (d.more.first != MoreKind.NONE) KeepGoing(d.more.first, d.more.second, onKeepGoing, onDark = true)
            }
        }
    }
}

@Composable
private fun StreakBadge(days: Int) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.16f)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.LocalFireDepartment, null, tint = if (days > 0) Flame else Color.White.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
        HSpace(4.dp)
        Text(
            when (days) {
                0 -> "Start a streak"
                1 -> "1 day streak"
                else -> "$days day streak"
            },
            style = MaterialTheme.typography.labelLarge, color = Color.White,
        )
    }
}

@Composable
private fun SyncLine(s: SettingsState) {
    val (text, error) = when {
        s.demo -> "Example deck" to false
        s.lastError != null -> "Sync problem" to true
        s.lastSyncMillis > 0 -> "Synced " + DateUtils.getRelativeTimeSpanString(
            s.lastSyncMillis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
        ) to false
        else -> "Not synced" to false
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (error) {
            Icon(Icons.Filled.CloudOff, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
            HSpace(4.dp)
        }
        Text(
            text, style = MaterialTheme.typography.labelMedium,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DeckTile(row: DeckRow, onClick: () -> Unit, onBrowse: () -> Unit) {
    val accent = deckAccent(row.index)
    Card(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = if (LocalDarkTheme.current) 0.dp else 1.dp),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            DeckBadge(row.deck.name, accent)
            HSpace(14.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(row.deck.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (row.deck.description.isNotBlank()) Text(
                    row.deck.description, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    if (row.due > 0) Pill("${row.due} due", accent)
                    if (row.new > 0) Pill("${row.new} new", MaterialTheme.colorScheme.tertiary)
                    Pill("${row.total} cards", MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HSpace(8.dp)
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ProgressRing(row.mastery, accent, size = 52.dp, stroke = 5.dp) {
                    Text("${(row.mastery * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
                Text("mastered", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = onBrowse, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.EditNote, contentDescription = "Browse and edit cards", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
