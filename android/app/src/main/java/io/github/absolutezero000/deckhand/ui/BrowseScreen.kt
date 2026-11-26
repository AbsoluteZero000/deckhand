package io.github.absolutezero000.deckhand.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.absolutezero000.deckhand.data.CardEntity
import io.github.absolutezero000.deckhand.data.DeckEntity
import io.github.absolutezero000.deckhand.data.Progress
import io.github.absolutezero000.deckhand.data.Repository
import io.github.absolutezero000.deckhand.data.SectionEntity
import io.github.absolutezero000.deckhand.ui.theme.Knew
import io.github.absolutezero000.deckhand.ui.theme.LocalDarkTheme
import io.github.absolutezero000.deckhand.ui.theme.Missed
import io.github.absolutezero000.deckhand.ui.theme.deckAccent
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

data class BrowseData(
    val deck: DeckEntity?,
    val accent: androidx.compose.ui.graphics.Color,
    val sections: List<SectionEntity>,
    val cards: List<CardEntity>,
    val progress: Map<String, Progress>,
)

class BrowseViewModel(private val repo: Repository, deckId: String) : ViewModel() {
    val data: StateFlow<BrowseData?> = combine(
        repo.dao.decks(), repo.dao.cardsFlow(deckId), repo.dao.progressFlow(),
        flow { emit(repo.dao.sections(deckId)) },
    ) { decks, cards, progress, sections ->
        val i = decks.indexOfFirst { it.id == deckId }
        BrowseData(decks.getOrNull(i), deckAccent(i), sections, cards, progress.associateBy { it.cardId })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun today() = repo.today()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    repo: Repository,
    deckId: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onNew: (section: String?) -> Unit,
) {
    val vm = viewModel(key = "browse-$deckId") { BrowseViewModel(repo, deckId) }
    val data by vm.data.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var section by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(data?.deck?.name.orEmpty(), style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${data?.cards?.size ?: 0} cards",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onNew(section) },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("New card") },
                containerColor = data?.accent ?: MaterialTheme.colorScheme.primary,
                contentColor = androidx.compose.ui.graphics.Color.White,
            )
        },
    ) { pad ->
        val d = data ?: return@Scaffold
        val q = query.trim().lowercase()
        val shown = d.cards.filter { c ->
            (section == null || c.sectionId == section) &&
                (q.isEmpty() || listOf(c.front, c.back, c.why, c.tags.joinToString(" ")).any { it.lowercase().contains(q) })
        }
        val sectionNames = d.sections.associate { it.id to it.name }
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    placeholder = { Text("Search cards") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                )
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selected = section == null, onClick = { section = null }, label = { Text("All") }) }
                    items(d.sections, key = { it.id }) { s ->
                        FilterChip(
                            selected = section == s.id,
                            onClick = { section = if (section == s.id) null else s.id },
                            label = { Text(s.name) },
                        )
                    }
                }
            }
            if (shown.isEmpty()) item {
                Text(
                    if (q.isEmpty()) "No cards here yet. Tap New card to add one." else "No cards match \"$query\".",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
            items(shown, key = { it.id }) { c ->
                CardRow(c, sectionNames[c.sectionId].orEmpty(), d.progress[c.id], vm.today(), d.accent) { onEdit(c.id) }
            }
        }
    }
}

@Composable
private fun CardRow(
    card: CardEntity, section: String, progress: Progress?, today: Long,
    accent: androidx.compose.ui.graphics.Color, onClick: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = if (LocalDarkTheme.current) 0.dp else 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    section, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                StatusPill(progress, today, accent)
            }
            CardText(
                card.front, MaterialTheme.typography.titleMedium,
                align = androidx.compose.ui.text.style.TextAlign.Start,
            )
            Text(
                card.back.replace("`", ""), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (card.tags.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                card.tags.take(4).forEach { Pill("#$it", accent) }
            }
        }
    }
}

@Composable
private fun StatusPill(p: Progress?, today: Long, accent: androidx.compose.ui.graphics.Color) {
    when {
        p == null -> Pill("New", MaterialTheme.colorScheme.tertiary)
        p.dueDay <= today && p.intervalDays == 0 -> Pill("Missed", Missed)
        p.dueDay <= today -> Pill("Due", accent)
        else -> {
            val days = p.dueDay - today
            Pill(if (days == 1L) "Tomorrow" else "In $days days", Knew)
        }
    }
}
