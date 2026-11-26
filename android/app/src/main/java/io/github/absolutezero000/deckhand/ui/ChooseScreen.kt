package io.github.absolutezero000.deckhand.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.absolutezero000.deckhand.data.CardEntity
import io.github.absolutezero000.deckhand.data.DeckEntity
import io.github.absolutezero000.deckhand.data.Progress
import io.github.absolutezero000.deckhand.data.Repository
import io.github.absolutezero000.deckhand.data.SectionEntity
import io.github.absolutezero000.deckhand.domain.Filter
import io.github.absolutezero000.deckhand.domain.Mode
import io.github.absolutezero000.deckhand.domain.MORE_BATCH
import io.github.absolutezero000.deckhand.domain.MoreKind
import io.github.absolutezero000.deckhand.domain.Queue
import kotlinx.coroutines.launch

class ChooseViewModel(private val repo: Repository, private val deckId: String) : ViewModel() {
    var deck by mutableStateOf<DeckEntity?>(null); private set
    var sections by mutableStateOf<List<SectionEntity>>(emptyList()); private set
    var tags by mutableStateOf<List<Pair<String, Int>>>(emptyList()); private set
    var filter by mutableStateOf(Filter()); private set
    var mode by mutableStateOf(Mode.DUE)

    /** Everything the counts depend on, held in one observable state so counts refresh once it loads. */
    private class Loaded(val cards: List<CardEntity>, val progress: Map<String, Progress>, val allowance: Int)
    private var loaded by mutableStateOf(Loaded(emptyList(), emptyMap(), 0))
    val newToday: Int get() = loaded.allowance

    init {
        viewModelScope.launch {
            val today = repo.today()
            val cards = repo.dao.cards(deckId)
            val progress = repo.dao.allProgress().associateBy { it.cardId }
            val allowance = (repo.settings.current().newPerDay - repo.dao.firstSeenOn(today)).coerceAtLeast(0)
            loaded = Loaded(cards, progress, allowance)
            sections = repo.dao.sections(deckId)
            tags = cards.flatMap { it.tags }.groupingBy { it }.eachCount().toList()
                .sortedWith(compareBy({ -it.second }, { it.first }))
            deck = repo.dao.deck(deckId)
        }
    }

    fun toggleSection(id: String) {
        filter = filter.copy(sections = filter.sections.toggle(id))
    }

    fun toggleTag(t: String) {
        filter = filter.copy(tags = filter.tags.toggle(t))
    }

    fun clear() { filter = Filter() }

    fun moreKind(): MoreKind = loaded.let { Queue.more(it.cards, it.progress, filter, repo.today()).first }

    fun count(mode: Mode): Int = loaded.let { Queue.build(it.cards, it.progress, filter, mode, repo.today(), it.allowance).size }

    private fun <T> Set<T>.toggle(x: T) = if (x in this) this - x else this + x
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChooseScreen(
    repo: Repository,
    deckId: String,
    onBack: () -> Unit,
    onBrowse: () -> Unit,
    onStart: (Mode, Filter) -> Unit,
) {
    val vm = viewModel(key = "choose-$deckId") { ChooseViewModel(repo, deckId) }
    val dueCount = vm.count(Mode.DUE)
    val practiceCount = vm.count(Mode.PRACTICE)
    // "New" only offers unseen cards; once none are left, Practice covers the rest.
    val moreCount = if (vm.moreKind() == MoreKind.NEW) vm.count(Mode.MORE) else 0
    val count = when (vm.mode) {
        Mode.DUE -> dueCount
        Mode.MORE -> moreCount
        Mode.PRACTICE -> practiceCount
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(vm.deck?.name.orEmpty()) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (vm.filter != Filter()) TextButton(onClick = vm::clear) { Text("Clear") }
                    IconButton(onClick = onBrowse) { Icon(Icons.Outlined.EditNote, "Browse and edit cards") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Button(
                    onClick = { onStart(vm.mode, vm.filter) },
                    enabled = count > 0,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.navigationBarsPadding().fillMaxWidth().padding(16.dp).height(56.dp),
                ) {
                    Text(
                        if (count > 0) "Study $count card${if (count == 1) "" else "s"}" else "Nothing to study",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(Mode.DUE to "Due · $dueCount", Mode.MORE to "New · $moreCount", Mode.PRACTICE to "Practice · $practiceCount")
                    .forEachIndexed { i, (m, label) ->
                        SegmentedButton(
                            selected = vm.mode == m, onClick = { vm.mode = m },
                            shape = SegmentedButtonDefaults.itemShape(i, 3),
                            icon = {},
                        ) { Text(label, maxLines = 1) }
                    }
            }
            Text(
                when (vm.mode) {
                    Mode.DUE -> "Cards due today plus up to ${vm.newToday} new. Grades update the schedule."
                    Mode.MORE -> if (moreCount == 0) "No new cards left here. Use Practice to go over the rest."
                    else "Past today's limit: anything due plus the next $MORE_BATCH new cards. Grades update the schedule."
                    Mode.PRACTICE -> "Every matching card, soonest due first. Go over them as often as you like; the schedule isn't changed."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("Sections", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                vm.sections.forEach { s ->
                    FilterChip(
                        selected = s.id in vm.filter.sections,
                        onClick = { vm.toggleSection(s.id) },
                        label = { Text(s.name) },
                    )
                }
            }
            if (vm.tags.isNotEmpty()) {
                Text("Tags", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                Text(
                    "Cards must have every selected tag.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    vm.tags.forEach { (t, n) ->
                        FilterChip(
                            selected = t in vm.filter.tags,
                            onClick = { vm.toggleTag(t) },
                            label = { Text("#$t  $n") },
                        )
                    }
                }
            }
        }
    }
}
