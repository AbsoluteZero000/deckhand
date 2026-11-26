package io.github.absolutezero000.deckhand.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.absolutezero000.deckhand.data.CardEntity
import io.github.absolutezero000.deckhand.data.Progress
import io.github.absolutezero000.deckhand.data.Repository
import io.github.absolutezero000.deckhand.domain.Filter
import io.github.absolutezero000.deckhand.domain.Mode
import io.github.absolutezero000.deckhand.domain.MoreKind
import io.github.absolutezero000.deckhand.domain.Queue
import io.github.absolutezero000.deckhand.domain.Scheduler
import io.github.absolutezero000.deckhand.domain.Streak
import io.github.absolutezero000.deckhand.domain.StudySession
import io.github.absolutezero000.deckhand.ui.theme.Flame
import io.github.absolutezero000.deckhand.ui.theme.Knew
import io.github.absolutezero000.deckhand.ui.theme.LocalDarkTheme
import io.github.absolutezero000.deckhand.ui.theme.Missed
import io.github.absolutezero000.deckhand.ui.theme.Overline
import io.github.absolutezero000.deckhand.ui.theme.deckAccent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import kotlin.math.min

/** "*" as the deck id studies every deck together. */
const val ALL_DECKS = "*"

data class CardLabel(val deck: String, val section: String, val accent: Color)

data class StudyUi(
    val loading: Boolean = true,
    val card: CardEntity? = null,
    val nextCard: CardEntity? = null,
    val label: CardLabel? = null,
    val step: Int = 0,
    val finished: Int = 0,
    val total: Int = 0,
    val canUndo: Boolean = false,
    val done: Boolean = false,
    val knewFirstTime: Int = 0,
    val missed: List<String> = emptyList(),
    val mode: Mode = Mode.DUE,
    val kind: MoreKind = MoreKind.NONE,
    val streak: Int = 0,
    /** What "Keep going" would offer next, shown on the summary. */
    val next: Pair<MoreKind, Int> = MoreKind.NONE to 0,
) {
    val schedules get() = Queue.schedules(mode, kind)
    val modeLabel get() = when {
        mode == Mode.PRACTICE -> "PRACTICE"
        mode == Mode.MORE && kind == MoreKind.AHEAD -> "PRACTICE"
        mode == Mode.MORE -> "EXTRA"
        else -> "STUDY"
    }
}

class StudyViewModel(
    private val repo: Repository,
    private val deckId: String,
    private var mode: Mode,
    private val filter: Filter,
) : ViewModel() {
    var ui by mutableStateOf(StudyUi(mode = mode)); private set
    private var cards: Map<String, CardEntity> = emptyMap()
    private var labels: Map<String, CardLabel> = emptyMap()
    private var session: StudySession? = null
    private var kind = MoreKind.NONE
    private val schedules get() = Queue.schedules(mode, kind)
    /** Progress before each grade, for undo (null = card was new). */
    private val undoStack = ArrayList<Progress?>()
    private val lock = Mutex()
    private var step = 0

    init {
        viewModelScope.launch {
            loadLabels()
            start(queueFor(mode))
            // Keep card text current while studying (e.g. after editing the card on screen).
            repo.dao.allCards().collect { all ->
                lock.withLock {
                    cards = all.associateBy { it.id }
                    val s = session ?: return@withLock
                    var dropped = false
                    while (s.current != null && s.current !in cards) { s.drop(s.current!!); dropped = true }
                    if (dropped) undoStack.clear()
                    publish(newStep = dropped)
                }
            }
        }
    }

    private suspend fun loadLabels() {
        val decks = repo.dao.decks().first()
        val map = HashMap<String, CardLabel>()
        decks.forEachIndexed { i, d ->
            val accent = deckAccent(i)
            for (s in repo.dao.sections(d.id)) map["${d.id}/${s.id}"] = CardLabel(d.name, s.name, accent)
        }
        labels = map
    }

    private suspend fun deckCards() =
        if (deckId == ALL_DECKS) repo.dao.allCardsInOrder() else repo.dao.cards(deckId)

    /** Builds a queue for [m] from fresh data, and sets [mode] and [kind] to match. */
    private suspend fun queueFor(m: Mode): List<String> {
        val list = deckCards()
        cards = list.associateBy { it.id }
        val today = repo.today()
        val progress = repo.dao.allProgress().associateBy { it.cardId }
        mode = m
        return if (m == Mode.MORE) {
            val (k, ids) = Queue.more(list, progress, filter, today)
            kind = k; ids
        } else {
            kind = MoreKind.NONE
            val allowance = (repo.settings.current().newPerDay - repo.dao.firstSeenOn(today)).coerceAtLeast(0)
            Queue.build(list, progress, filter, m, today, allowance)
        }
    }

    /** What "Keep going" would hold right now, without starting it. */
    private suspend fun previewMore(): Pair<MoreKind, Int> {
        val (k, ids) = Queue.more(deckCards(), repo.dao.allProgress().associateBy { it.cardId }, filter, repo.today())
        return k to ids.size
    }

    private suspend fun start(ids: List<String>) {
        session = StudySession(ids)
        undoStack.clear()
        publish(newStep = true)
    }

    private suspend fun publish(newStep: Boolean) {
        val s = session ?: return
        if (newStep) step++
        val card = s.current?.let(cards::get)
        val streak = if (s.isDone) {
            val days = repo.dao.reviewDays(repo.today() - 400).first()
            Streak.days(days.filter { it.count > 0 }.map { it.day }.toSet(), repo.today())
        } else ui.streak
        val nextMore = if (s.isDone && mode != Mode.PRACTICE) previewMore() else MoreKind.NONE to 0
        ui = StudyUi(
            loading = false,
            card = card,
            nextCard = s.next?.let(cards::get),
            label = card?.let { labels["${it.deckId}/${it.sectionId}"] },
            step = step,
            finished = s.finished,
            total = s.total,
            canUndo = s.canUndo,
            done = s.isDone,
            knewFirstTime = s.knewFirstTime.size,
            missed = s.missedOnce,
            mode = mode,
            kind = kind,
            streak = streak,
            next = nextMore,
        )
    }

    /** Start another batch past today's work (see [Queue.more]). */
    fun keepGoing() = viewModelScope.launch {
        lock.withLock { start(queueFor(Mode.MORE)) }
    }

    fun grade(knew: Boolean) = viewModelScope.launch {
        lock.withLock {
            val s = session ?: return@withLock
            val id = s.current ?: return@withLock
            val prev = repo.dao.progress(id)
            if (schedules) {
                val today = repo.today()
                val now = System.currentTimeMillis()
                repo.dao.upsertProgress(
                    if (knew) Scheduler.knew(prev, id, today, now) else Scheduler.missed(prev, id, today, now)
                )
            }
            repo.logReview(+1)
            undoStack.add(prev)
            s.grade(knew)
            publish(newStep = true)
        }
    }

    fun undo() = viewModelScope.launch {
        lock.withLock {
            val s = session ?: return@withLock
            val id = s.undo() ?: return@withLock
            val prev = undoStack.removeAt(undoStack.lastIndex)
            if (schedules) {
                if (prev == null) repo.dao.deleteProgress(id) else repo.dao.upsertProgress(prev)
            }
            repo.logReview(-1)
            publish(newStep = true)
        }
    }

    /** Practice the cards missed in this session. */
    fun reviewMissed() = viewModelScope.launch {
        lock.withLock {
            val missed = ui.missed
            mode = Mode.PRACTICE
            kind = MoreKind.NONE
            start(missed)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyScreen(
    repo: Repository,
    deckId: String,
    mode: Mode,
    filter: Filter,
    onClose: () -> Unit,
    onEdit: (String) -> Unit,
) {
    val vm = viewModel { StudyViewModel(repo, deckId, mode, filter) }
    val ui = vm.ui
    val accent = ui.label?.accent ?: MaterialTheme.colorScheme.primary

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (!ui.done && !ui.loading) Column {
                        Text(
                            ui.modeLabel,
                            style = Overline, color = accent,
                        )
                        Text("${ui.finished} of ${ui.total}", style = MaterialTheme.typography.titleMedium)
                    }
                },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close") } },
                actions = {
                    if (!ui.done && ui.card != null) {
                        IconButton(onClick = { onEdit(ui.card.id) }) { Icon(Icons.Outlined.Edit, "Edit this card") }
                        IconButton(onClick = { vm.undo() }, enabled = ui.canUndo) {
                            Icon(Icons.AutoMirrored.Filled.Undo, "Undo")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (!ui.loading && !ui.done) {
                val progress by animateFloatAsState(
                    if (ui.total == 0) 0f else ui.finished / ui.total.toFloat(), tween(400), label = "progress",
                )
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(6.dp),
                    color = accent,
                    trackColor = accent.copy(alpha = 0.15f),
                    strokeCap = StrokeCap.Round,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
            when {
                ui.loading -> Unit
                ui.done -> Summary(ui, onReviewMissed = { vm.reviewMissed() }, onKeepGoing = { vm.keepGoing() }, onDone = onClose)
                ui.card != null -> StudyCard(ui, onGrade = { vm.grade(it) })
            }
        }
    }
}

@Composable
private fun StudyCard(ui: StudyUi, onGrade: (Boolean) -> Unit) {
    val card = ui.card!!
    val step = ui.step
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val offset = remember(step) { Animatable(0f) }
    val enter = remember(step) { Animatable(0.94f) }
    var flipped by remember(step) { mutableStateOf(false) }
    var pastThreshold by remember(step) { mutableStateOf(0) }
    val rotation by animateFloatAsState(
        if (flipped) 180f else 0f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow), label = "flip",
    )
    LaunchedEffect(step) { enter.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium)) }
    val accent = ui.label?.accent ?: MaterialTheme.colorScheme.primary
    val cardColor = MaterialTheme.colorScheme.surfaceContainer
    val elevation = if (LocalDarkTheme.current) 0.dp else 6.dp

    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
        val width = constraints.maxWidth.toFloat()
        val threshold = width * 0.28f

        fun flyOut(knew: Boolean) = scope.launch {
            if (knew) Haptics.knew(view) else Haptics.missed(view)
            offset.animateTo(if (knew) width * 1.5f else -width * 1.5f, tween(240))
            onGrade(knew)
        }

        Column(Modifier.fillMaxSize()) {
            val drag = offset.value
            val tint = if (drag > 0) Knew else Missed
            val strength = min(abs(drag) / threshold, 1f)
            val shape = RoundedCornerShape(28.dp)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // The next card waits behind, and grows into place as the top one leaves.
                if (ui.nextCard != null) {
                    val grow = 0.92f + 0.08f * strength
                    Card(
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            scaleX = grow; scaleY = grow
                            // Peek out below the top card at rest; slide into place while it leaves.
                            translationY = (size.height * (1 - grow) / 2 + 14.dp.toPx()) * (1 - strength)
                        },
                        shape = shape,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    ) {}
                }
                // Touch handling sits outside every transform so drag directions are never mirrored
                // by the flip. Only the Card itself rotates around Y; tint and label stay readable on top.
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(step) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    if (abs(offset.value) > threshold) flyOut(offset.value > 0)
                                    else scope.launch { offset.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = 500f)) }
                                },
                                onDragCancel = { scope.launch { offset.animateTo(0f, spring()) } },
                            ) { change, dx ->
                                change.consume()
                                scope.launch {
                                    offset.snapTo(offset.value + dx)
                                    val side = when {
                                        offset.value > threshold -> 1
                                        offset.value < -threshold -> -1
                                        else -> 0
                                    }
                                    if (side != 0 && side != pastThreshold) Haptics.tick(view)
                                    pastThreshold = side
                                }
                            }
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { flipped = !flipped }
                        .graphicsLayer {
                            translationX = drag
                            rotationZ = drag / width * 12f
                            scaleX = enter.value; scaleY = enter.value
                        },
                ) {
                    Card(
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            rotationY = rotation
                            cameraDistance = 14f * density
                        },
                        shape = shape,
                        colors = CardDefaults.cardColors(containerColor = cardColor),
                        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
                    ) {
                        if (rotation <= 90f) CardFace(card, ui.label, back = false)
                        else Box(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }) { CardFace(card, ui.label, back = true) }
                    }
                    if (strength > 0.02f) {
                        Box(Modifier.fillMaxSize().clip(shape).background(tint.copy(alpha = 0.20f * strength)))
                        GradeStamp(
                            text = if (drag > 0) "KNEW IT" else "MISSED",
                            color = tint,
                            modifier = Modifier
                                .align(if (drag > 0) Alignment.TopStart else Alignment.TopEnd)
                                .padding(start = 28.dp, end = 28.dp, top = 76.dp)
                                .graphicsLayer { alpha = strength; rotationZ = if (drag > 0) -10f else 10f },
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { flyOut(false) },
                    modifier = Modifier.weight(1f).height(60.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Missed.copy(alpha = 0.14f), contentColor = Missed),
                ) {
                    Icon(Icons.Filled.Close, null); Spacer(Modifier.width(8.dp)); Text("Missed", style = MaterialTheme.typography.titleMedium)
                }
                Button(
                    onClick = { flyOut(true) },
                    modifier = Modifier.weight(1f).height(60.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Knew, contentColor = Color.White),
                ) {
                    Icon(Icons.Filled.Check, null); Spacer(Modifier.width(8.dp)); Text("Knew it", style = MaterialTheme.typography.titleMedium)
                }
            }
            Row(
                Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!flipped) {
                    Icon(Icons.Outlined.TouchApp, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    HSpace(6.dp)
                }
                Text(
                    if (flipped) "Swipe right if you knew it, left if you missed it" else "Tap the card to reveal the answer",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GradeStamp(text: String, color: Color, modifier: Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(text, color = color, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun CardFace(card: CardEntity, label: CardLabel?, back: Boolean) {
    val accent = label?.accent ?: MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 22.dp)) {
        Row(Modifier.align(Alignment.TopStart), verticalAlignment = Alignment.CenterVertically) {
            Dot(accent)
            HSpace(8.dp)
            Text(
                label?.let { "${it.deck} · ${it.section}" }.orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (back) "ANSWER" else "QUESTION",
                style = Overline, color = accent.copy(alpha = 0.9f),
            )
        }
        Column(
            Modifier.align(Alignment.Center).padding(vertical = 40.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!back) {
                CardText(card.front, MaterialTheme.typography.headlineSmall)
            } else {
                CardText(card.front, MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.padding(vertical = 18.dp).width(40.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(accent.copy(alpha = 0.5f)))
                CardText(card.back, MaterialTheme.typography.headlineSmall)
                if (card.why.isNotBlank()) {
                    Spacer(Modifier.height(18.dp))
                    Box(
                        Modifier.clip(RoundedCornerShape(16.dp)).background(accent.copy(alpha = 0.08f)).padding(14.dp),
                    ) {
                        CardText(card.why, MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (card.tags.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.align(Alignment.BottomCenter)) {
                card.tags.forEach { Pill("#$it", accent) }
            }
        }
    }
}

/** "Keep going" past today's work: more new cards, or practice ahead once none are left. */
@Composable
fun KeepGoing(kind: MoreKind, count: Int, onClick: () -> Unit, onDark: Boolean = false) {
    val (title, sub) = when (kind) {
        MoreKind.NEW -> "Keep going" to "$count more card${if (count == 1) "" else "s"}, past today's limit"
        else -> "Practice" to "$count card${if (count == 1) "" else "s"} due soonest · schedule unchanged"
    }
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        shape = RoundedCornerShape(18.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.5.dp, if (onDark) Color.White.copy(alpha = 0.6f) else MaterialTheme.colorScheme.primary,
        ),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (onDark) Color.White else MaterialTheme.colorScheme.primary),
    ) {
        Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.TrendingUp, null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(sub, style = MaterialTheme.typography.labelMedium, color = LocalContentColorCompat())
        }
    }
}

@Composable
private fun LocalContentColorCompat() = androidx.compose.material3.LocalContentColor.current.copy(alpha = 0.75f)

@Composable
private fun Summary(ui: StudyUi, onReviewMissed: () -> Unit, onKeepGoing: () -> Unit, onDone: () -> Unit) {
    val graded = ui.knewFirstTime + ui.missed.size
    val fraction = if (graded == 0) 0f else ui.knewFirstTime / graded.toFloat()
    val pct = (fraction * 100).toInt()
    val (title, subtitle) = when {
        ui.total == 0 && ui.mode == Mode.DUE -> "Today's done" to "Nothing due right now. Keep going if you're in the mood."
        ui.total == 0 -> "Nothing to study" to "No cards match right now."
        !ui.schedules -> (if (pct >= 70) "Nice practice" else "Good practice") to
            "Practice doesn't change your schedule, so it's free to repeat."
        pct == 100 -> "Perfect run!" to "You knew every card the first time."
        pct >= 70 -> "Nice work" to "Most of it stuck. Missed cards come back tomorrow."
        else -> "Good practice" to "Tough set. Missed cards come back soon, that's how it sticks."
    }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (ui.total > 0) {
            ProgressRing(fraction, Knew, size = 168.dp, stroke = 14.dp, track = Missed.copy(alpha = 0.25f)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$pct%", style = MaterialTheme.typography.displayMedium)
                    Text("first try", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (ui.total > 0) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("${ui.knewFirstTime} knew", Knew, icon = Icons.Filled.Check)
            Pill("${ui.missed.size} missed", Missed, icon = Icons.Filled.Close)
            if (ui.streak > 0) Pill(if (ui.streak == 1) "1 day streak" else "${ui.streak} day streak", Flame, icon = Icons.Filled.LocalFireDepartment)
        }
        Spacer(Modifier.size(4.dp))
        if (ui.missed.isNotEmpty()) {
            FilledTonalButton(onClick = onReviewMissed, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)) {
                Text("Review ${ui.missed.size} missed again", style = MaterialTheme.typography.titleSmall)
            }
        }
        val (nextKind, nextCount) = ui.next
        if (nextKind != MoreKind.NONE) KeepGoing(nextKind, nextCount, onKeepGoing)
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)) {
            Text("Done", style = MaterialTheme.typography.titleSmall)
        }
    }
}
