package io.github.absolutezero000.deckhand.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.absolutezero000.deckhand.data.CardEntity
import io.github.absolutezero000.deckhand.data.CardRules
import io.github.absolutezero000.deckhand.data.Repository
import io.github.absolutezero000.deckhand.data.SectionEntity
import io.github.absolutezero000.deckhand.ui.theme.Missed
import io.github.absolutezero000.deckhand.ui.theme.Overline
import kotlinx.coroutines.launch

class EditViewModel(
    private val repo: Repository,
    private val cardId: String?,
    private val deckId: String?,
    sectionId: String?,
) : ViewModel() {
    var loaded by mutableStateOf(false); private set
    var original: CardEntity? = null; private set
    var sections by mutableStateOf<List<SectionEntity>>(emptyList()); private set
    var suggestions by mutableStateOf<List<String>>(emptyList()); private set
    var repoName by mutableStateOf<String?>(null); private set
    var demo by mutableStateOf(false); private set

    var front by mutableStateOf("")
    var back by mutableStateOf("")
    var why by mutableStateOf("")
    var tags by mutableStateOf("")
    var section by mutableStateOf(sectionId)
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)

    val isNew get() = cardId == null

    init {
        viewModelScope.launch {
            val card = cardId?.let { repo.dao.card(it) }
            original = card
            val deck = card?.deckId ?: deckId!!
            sections = repo.dao.sections(deck)
            if (card != null) {
                front = card.front; back = card.back; why = card.why; tags = card.tags.joinToString(", ")
                section = card.sectionId
            } else if (section == null || sections.none { it.id == section }) {
                section = sections.firstOrNull()?.id
            }
            suggestions = repo.dao.cards(deck).flatMap { it.tags }.groupingBy { it }.eachCount()
                .entries.sortedByDescending { it.value }.map { it.key }
            val s = repo.settings.current()
            repoName = s.repo; demo = s.demo
            loaded = true
        }
    }

    fun toggleTag(t: String) {
        val list = CardRules.splitTags(tags).toMutableList()
        if (t in list) list.remove(t) else list.add(t)
        tags = list.joinToString(", ")
    }

    fun save(done: () -> Unit) {
        val tagList = CardRules.splitTags(tags)
        CardRules.problems(front, back, tagList).firstOrNull()?.let { error = it; return }
        val sec = section ?: run { error = "Pick a section."; return }
        busy = true; error = null
        viewModelScope.launch {
            val o = original
            val card = if (o != null) o.copy(front = front, back = back, why = why, tags = tagList)
            else CardEntity(
                id = repo.newCardId(deckId!!, front), deckId = deckId, sectionId = sec,
                front = front, back = back, why = why, tags = tagList, ord = Int.MAX_VALUE,
            )
            val e = repo.saveCard(card, isNew = o == null)
            busy = false
            if (e == null) done() else error = e
        }
    }

    fun delete(done: () -> Unit) {
        val o = original ?: return
        busy = true; error = null
        viewModelScope.launch {
            val e = repo.deleteCard(o)
            busy = false
            if (e == null) done() else error = e
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditScreen(repo: Repository, cardId: String?, deckId: String?, sectionId: String?, onDone: () -> Unit) {
    val vm = viewModel(key = "edit-$cardId-$deckId") { EditViewModel(repo, cardId, deckId, sectionId) }
    val ctx = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }

    fun saved(msg: String) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (vm.isNew) "New card" else "Edit card") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.Filled.Close, "Cancel") } },
                actions = {
                    Button(
                        onClick = { vm.save { saved(if (vm.demo) "Saved on this phone" else "Saved to GitHub") } },
                        enabled = vm.loaded && !vm.busy,
                        modifier = Modifier.padding(end = 12.dp),
                    ) {
                        if (vm.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text("Save")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        if (!vm.loaded) return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (vm.isNew) SectionPicker(vm.sections, vm.section) { vm.section = it }
            else Text(
                "SECTION · " + (vm.sections.firstOrNull { it.id == vm.section }?.name ?: vm.section.orEmpty()).uppercase(),
                style = Overline, color = MaterialTheme.colorScheme.primary,
            )
            OutlinedTextField(
                value = vm.front, onValueChange = { vm.front = it; vm.error = null },
                label = { Text("Front (question)") }, minLines = 2, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = vm.back, onValueChange = { vm.back = it; vm.error = null },
                label = { Text("Back (answer)") }, minLines = 2, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = vm.why, onValueChange = { vm.why = it },
                label = { Text("Why (optional)") }, minLines = 2, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("One or two sentences of reasoning. Use `backticks` for code.") },
            )
            OutlinedTextField(
                value = vm.tags, onValueChange = { vm.tags = it; vm.error = null },
                label = { Text("Tags") }, placeholder = { Text("routing, exam-trap") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            if (vm.suggestions.isNotEmpty()) {
                val chosen = CardRules.splitTags(vm.tags).toSet()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    vm.suggestions.take(12).forEach { t ->
                        FilterChip(selected = t in chosen, onClick = { vm.toggleTag(t) }, label = { Text("#$t") })
                    }
                }
            }
            vm.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Preview(vm.front, vm.back, vm.why)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    if (vm.demo) Icons.Outlined.Info else Icons.Outlined.CloudUpload, null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp),
                )
                HSpace(10.dp)
                Text(
                    if (vm.demo) "This is the example deck, so changes stay on this phone."
                    else "Saving commits this card to ${vm.repoName} on GitHub. Other phones get it after the cards workflow publishes, usually within a minute.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!vm.isNew) {
                OutlinedButton(
                    onClick = { confirmDelete = true }, enabled = !vm.busy,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = Missed),
                ) {
                    Icon(Icons.Outlined.Delete, null); HSpace(8.dp); Text("Delete card")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this card?") },
        text = { Text("It's removed from your cards repo, and its study progress is lost on every phone.") },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                vm.delete { saved("Card deleted") }
            }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SectionPicker(sections: List<SectionEntity>, selected: String?, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = sections.firstOrNull { it.id == selected }?.name.orEmpty(),
            onValueChange = {}, readOnly = true, label = { Text("Section") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            sections.forEach { s ->
                DropdownMenuItem(text = { Text(s.name) }, onClick = { onPick(s.id); open = false })
            }
        }
    }
}

@Composable
private fun Preview(front: String, back: String, why: String) {
    if (front.isBlank() && back.isBlank()) return
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("PREVIEW", style = Overline, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (front.isNotBlank()) CardText(front, MaterialTheme.typography.titleLarge)
        if (back.isNotBlank()) {
            Box(Modifier.size(width = 32.dp, height = 3.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.outlineVariant))
            CardText(back, MaterialTheme.typography.titleMedium)
        }
        if (why.isNotBlank()) CardText(why, MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
