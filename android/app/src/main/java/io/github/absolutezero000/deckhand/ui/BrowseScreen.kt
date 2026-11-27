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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import io.github.absolutezero000.deckhand.domain.Filter
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
    /** Null when browsing every deck (search). */
    val deck: DeckEntity?,
    val accent: androidx.compose.ui.graphics.Color,
    val sections: List<SectionEntity>,
    val cards: List<CardEntity>,
    val progress: Map<String, Progress>,
    /** "deck/section" -> label shown on each card row. */
    val labels: Map<String, String>,
    val accents: Map<String, androidx.compose.ui.graphics.Color>,
)

class BrowseViewModel(private val repo: Repository, deckId: String) : ViewModel() {
    val all = deckId == ALL_DECKS
    val data: StateFlow<BrowseData?> = combine(
        repo.dao.decks(),
        if (all) repo.dao.allCards() else repo.dao.cardsFlow(deckId),
        repo.dao.progressFlow(),
        if (all) repo.dao.allSections() else flow { emit(repo.dao.sections(deckId)) },
    ) { decks, cards, progress, sections ->
        val i = decks.indexOfFirst { it.id == deckId }
        val deckOrder = decks.withIndex().associate { (n, d) -> d.id to n }
        val deckNames = decks.associate { it.id to it.name }
        val labels = sections.associate { s ->
            "${s.deckId}/${s.id}" to if (all && decks.size > 1) "${deckNames[s.deckId]} · ${s.name}" else s.name
        }
        BrowseData(
            deck = decks.getOrNull(i),
            accent = deckAccent(i.coerceAtLeast(0)),
            sections = if (all) emptyList() else sections,
            cards = if (all) cards.sortedWith(compareBy({ deckOrder[it.deckId] ?: Int.MAX_VALUE }, { it.ord })) else cards,
            progress = progress.associateBy { it.cardId },
            labels = labels,
            accents = decks.withIndex().associate { (n, d) -> d.id to deckAccent(n) },
        )
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
    onPractice: (query: String, section: String?) -> Unit,
) {
    val vm = viewModel(key = "browse-$deckId") { BrowseViewModel(repo, deckId) }
    val data by vm.data.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var section by rememberSaveable { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    // Opened as search from Home: put the cursor in the box right away.
    LaunchedEffect(data != null) { if (vm.all && data != null) runCatching { focus.requestFocus() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            if (vm.all) "Search all cards" else data?.deck?.name.orEmpty(),
                            style = MaterialTheme.typography.titleLarge,
                        )
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
            if (!vm.all) ExtendedFloatingActionButton(
                onClick = { onNew(section) },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("New card") },
                containerColor = data?.accent ?: MaterialTheme.colorScheme.primary,
                contentColor = androidx.compose.ui.graphics.Color.White,
            )
        },
    ) { pad ->
        val d = data ?: return@Scaffold
        val q = query.trim()
        val shown = d.cards.filter { c -> (section == null || c.sectionId == section) && Filter.matchesText(c, q) }
        Column(Modifier.fillMaxSize().padding(pad)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text("Search cards") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(50),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).focusRequester(focus),
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Clear search") }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
            )
            if (q.isNotEmpty() && shown.isNotEmpty()) {
                FilledTonalButton(
                    onClick = { onPractice(q, section) },
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Text(
                        "  Practice ${shown.size} matching card${if (shown.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (d.sections.isNotEmpty()) item {
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
                        when {
                            q.isNotEmpty() -> "No cards match \"$query\"."
                            vm.all -> "No cards yet."
                            else -> "No cards here yet. Tap New card to add one."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
                items(shown, key = { it.id }) { c ->
                    CardRow(
                        c, d.labels["${c.deckId}/${c.sectionId}"].orEmpty(), d.progress[c.id], vm.today(),
                        d.accents[c.deckId] ?: d.accent,
                    ) { onEdit(c.id) }
                }
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
