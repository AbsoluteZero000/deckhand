package io.github.absolutezero000.deckhand.domain

import io.github.absolutezero000.deckhand.data.CardEntity
import io.github.absolutezero000.deckhand.data.Progress

/**
 * DUE: today's work (due cards plus new cards up to the daily limit).
 * MORE: "keep going" after today's work, see [Queue.more].
 * PRACTICE: every matching card, soonest due first, schedule untouched.
 */
enum class Mode { DUE, MORE, PRACTICE }

/** What a MORE session holds: extra new cards (scheduled normally), or practice ahead (schedule untouched). */
enum class MoreKind { NEW, AHEAD, NONE }

const val MORE_BATCH = 10

/** Which cards of a deck to study. Empty sets mean "no restriction". Cards must have every tag. */
data class Filter(val sections: Set<String> = emptySet(), val tags: Set<String> = emptySet()) {
    fun matches(c: CardEntity) =
        (sections.isEmpty() || c.sectionId in sections) && c.tags.containsAll(tags)
}

object Queue {
    /**
     * Cards for a session, in study order.
     * DUE: due cards (oldest due first, then deck order), then new cards in deck order up to [newAllowance].
     * PRACTICE: every matching card: seen cards soonest due first, then unseen cards in deck order.
     * [cards] must already be in deck order.
     */
    fun build(
        cards: List<CardEntity>, progress: Map<String, Progress>, filter: Filter,
        mode: Mode, today: Long, newAllowance: Int,
    ): List<String> {
        val matching = cards.filter(filter::matches)
        if (mode == Mode.PRACTICE) {
            val (seen, unseen) = matching.withIndex().partition { (_, c) -> c.id in progress }
            return seen.sortedWith(compareBy({ progress.getValue(it.value.id).dueDay }, { it.index })).map { it.value.id } +
                unseen.map { it.value.id }
        }
        if (mode == Mode.MORE) return more(cards, progress, filter, today).second
        val due = matching.withIndex()
            .mapNotNull { (i, c) -> progress[c.id]?.takeIf { it.dueDay <= today }?.let { Triple(c.id, it.dueDay, i) } }
            .sortedWith(compareBy({ it.second }, { it.third }))
            .map { it.first }
        val fresh = matching.filter { it.id !in progress }.take(newAllowance.coerceAtLeast(0)).map { it.id }
        return due + fresh
    }

    /**
     * "Keep going" after today's work. Due cards come first if any are left, then the next
     * [batch] new cards past the daily limit (these are scheduled like any first review).
     * With nothing due or new, it's practice ahead: the [batch] cards due soonest, graded
     * without touching their schedule, because reviewing early would stretch their gaps unfairly.
     */
    fun more(
        cards: List<CardEntity>, progress: Map<String, Progress>, filter: Filter, today: Long,
        batch: Int = MORE_BATCH,
    ): Pair<MoreKind, List<String>> {
        val matching = cards.filter(filter::matches)
        val due = build(cards, progress, filter, Mode.DUE, today, newAllowance = 0)
        val fresh = matching.filter { it.id !in progress }.take(batch).map { it.id }
        if (due.isNotEmpty() || fresh.isNotEmpty()) return MoreKind.NEW to (due + fresh)
        val ahead = matching.withIndex()
            .mapNotNull { (i, c) -> progress[c.id]?.let { Triple(c.id, it.dueDay, i) } }
            .sortedWith(compareBy({ it.second }, { it.third }))
            .take(batch).map { it.first }
        return (if (ahead.isEmpty()) MoreKind.NONE else MoreKind.AHEAD) to ahead
    }

    /** Whether grades in this session update the schedule. */
    fun schedules(mode: Mode, kind: MoreKind): Boolean = when (mode) {
        Mode.DUE -> true
        Mode.MORE -> kind == MoreKind.NEW
        Mode.PRACTICE -> false
    }

    fun counts(cards: List<CardEntity>, progress: Map<String, Progress>, today: Long): Pair<Int, Int> {
        var due = 0
        var new = 0
        for (c in cards) {
            val p = progress[c.id]
            if (p == null) new++ else if (p.dueDay <= today) due++
        }
        return due to new
    }
}

object Streak {
    /**
     * Days in a row with at least one review, counting back from today. If nothing has been
     * reviewed yet today, a streak that ran through yesterday still counts (it isn't broken yet).
     */
    fun days(reviewedDays: Set<Long>, today: Long): Int {
        var day = if (today in reviewedDays) today else today - 1
        var n = 0
        while (day in reviewedDays) { n++; day-- }
        return n
    }
}

/**
 * The in-memory study queue. Missed cards come back [Scheduler.REQUEUE_GAP] cards later
 * until they are known. Supports undo of the last grade.
 */
class StudySession(cardIds: List<String>) {
    private val queue = ArrayDeque(cardIds.distinct())
    private val history = ArrayList<Step>()

    private data class Step(val cardId: String, val knew: Boolean, val requeuedAt: Int?)

    var total: Int = queue.size
        private set
    val current: String? get() = queue.firstOrNull()
    /** The card after the current one, for drawing the stack. */
    val next: String? get() = queue.getOrNull(1)

    /**
     * Removes a card that no longer exists (deleted while studying). Undo history is cleared,
     * because earlier steps refer to queue positions that just shifted.
     */
    fun drop(cardId: String) {
        if (cardId !in queue) return
        queue.removeAll { it == cardId }
        history.clear()
        graded.removeAll { it.first == cardId }
        total--
    }

    /** Every grade this session, for the summary (undo removes the last one). */
    private val graded = ArrayList<Pair<String, Boolean>>()

    val isDone: Boolean get() = queue.isEmpty()
    val canUndo: Boolean get() = history.isNotEmpty()

    /** Cards graded "knew it" and not waiting again. */
    val finished: Int get() = total - queue.distinct().size

    /** Cards whose first grade this session was "knew it". */
    val knewFirstTime: List<String> get() = firstGrades().filterValues { it }.keys.toList()

    /** Cards missed at least once this session. */
    val missedOnce: List<String> get() = graded.filter { !it.second }.map { it.first }.distinct()

    private fun firstGrades(): Map<String, Boolean> {
        val m = LinkedHashMap<String, Boolean>()
        for ((id, knew) in graded) m.putIfAbsent(id, knew)
        return m
    }

    fun grade(knew: Boolean) {
        val id = queue.removeFirstOrNull() ?: return
        var at: Int? = null
        if (!knew) {
            at = minOf(Scheduler.REQUEUE_GAP - 1, queue.size)
            queue.add(at, id)
        }
        history.add(Step(id, knew, at))
        graded.add(id to knew)
    }

    /** Reverts the last grade and returns the card id that is current again. */
    fun undo(): String? {
        val s = history.removeLastOrNull() ?: return null
        if (s.requeuedAt != null) queue.removeAt(s.requeuedAt)
        queue.addFirst(s.cardId)
        graded.removeLastOrNull()
        return s.cardId
    }
}
