package io.github.absolutezero000.deckhand

import io.github.absolutezero000.deckhand.data.CardEntity
import io.github.absolutezero000.deckhand.data.Progress
import io.github.absolutezero000.deckhand.data.ProgressIo
import io.github.absolutezero000.deckhand.domain.Filter
import io.github.absolutezero000.deckhand.domain.Mode
import io.github.absolutezero000.deckhand.domain.Queue
import io.github.absolutezero000.deckhand.domain.Scheduler
import io.github.absolutezero000.deckhand.domain.StudySession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulerTest {
    private val today = 20_000L

    @Test fun knownIntervalsGrow() {
        var p = Scheduler.knew(null, "a", today, 1)
        assertEquals(1, p.intervalDays); assertEquals(today + 1, p.dueDay); assertEquals(today, p.firstSeenDay)
        p = Scheduler.knew(p, "a", today + 1, 2)
        assertEquals(3, p.intervalDays)
        p = Scheduler.knew(p, "a", today + 4, 3)
        assertEquals(8, p.intervalDays) // round(3 * 2.5)
        assertEquals(today + 12, p.dueDay)
    }

    @Test fun missResetsAndLowersEase() {
        var p = Scheduler.knew(Scheduler.knew(null, "a", today, 0), "a", today, 0)
        p = Scheduler.missed(p, "a", today + 3, 0)
        assertEquals(0, p.reps); assertEquals(1, p.lapses); assertEquals(today + 3, p.dueDay)
        assertEquals(2.3, p.ease, 1e-9)
        assertEquals(1, Scheduler.knew(p, "a", today + 3, 0).intervalDays)
    }

    @Test fun easeHasAFloorAndIntervalACap() {
        var p: Progress? = null
        repeat(20) { p = Scheduler.missed(p, "a", today, 0) }
        assertEquals(Scheduler.MIN_EASE, p!!.ease, 1e-9)
        val long = p!!.copy(reps = 5, intervalDays = 300, ease = 2.5)
        assertEquals(Scheduler.MAX_INTERVAL, Scheduler.knew(long, "a", today, 0).intervalDays)
    }

    @Test fun firstSeenIsKept() {
        val p = Scheduler.knew(null, "a", today, 0)
        assertEquals(today, Scheduler.knew(p, "a", today + 9, 0).firstSeenDay)
    }
}

class QueueTest {
    private fun card(id: String, sec: String = "s1", tags: List<String> = emptyList(), ord: Int = 0) =
        CardEntity(id, "d", sec, "f", "b", "", tags, ord)

    private fun due(id: String, day: Long) = Progress(id, day, 1, 2.5, 1, 0, day - 1, 0)

    private val cards = listOf(
        card("a", tags = listOf("x")), card("b", tags = listOf("x", "y")), card("c", "s2"),
        card("d", "s2", listOf("y")), card("e"),
    )

    @Test fun dueFirstOldestFirstThenNewUpToAllowance() {
        val progress = mapOf("c" to due("c", 10), "a" to due("a", 5), "b" to due("b", 99))
        val q = Queue.build(cards, progress, Filter(), Mode.DUE, today = 10, newAllowance = 1)
        assertEquals(listOf("a", "c", "d"), q)
    }

    @Test fun filtersBySectionAndAllTags() {
        val q = Queue.build(cards, emptyMap(), Filter(tags = setOf("x", "y")), Mode.PRACTICE, 0, 0)
        assertEquals(listOf("b"), q)
        val s2 = Queue.build(cards, emptyMap(), Filter(sections = setOf("s2")), Mode.PRACTICE, 0, 0)
        assertEquals(listOf("c", "d"), s2)
    }

    @Test fun practiceIsSoonestDueFirstThenUnseen() {
        val progress = mapOf("d" to due("d", 30), "b" to due("b", 12), "e" to due("e", 12))
        val q = Queue.build(cards, progress, Filter(), Mode.PRACTICE, today = 10, newAllowance = 0)
        assertEquals(listOf("b", "e", "d", "a", "c"), q)
        assertFalse(Queue.schedules(Mode.PRACTICE, io.github.absolutezero000.deckhand.domain.MoreKind.NONE))
    }

    @Test fun moreGivesNewCardsPastTheLimitThenPracticeAhead() {
        // a, b seen and not due; c, d, e new: keep going = next new cards, scheduled.
        val seen = mapOf("a" to due("a", 15), "b" to due("b", 12))
        val (k1, ids1) = Queue.more(cards, seen, Filter(), today = 10, batch = 2)
        assertEquals(io.github.absolutezero000.deckhand.domain.MoreKind.NEW, k1)
        assertEquals(listOf("c", "d"), ids1)
        assertTrue(Queue.schedules(Mode.MORE, k1))
        // everything seen, nothing due: practice the soonest-due cards, schedule untouched.
        val all = cards.associate { it.id to due(it.id, 20L + it.id[0].code) } + ("b" to due("b", 11))
        val (k2, ids2) = Queue.more(cards, all, Filter(), today = 10, batch = 2)
        assertEquals(io.github.absolutezero000.deckhand.domain.MoreKind.AHEAD, k2)
        assertEquals("b", ids2.first())
        assertEquals(2, ids2.size)
        assertFalse(Queue.schedules(Mode.MORE, k2))
        // respects the filter
        val (_, s2) = Queue.more(cards, seen, Filter(sections = setOf("s2")), today = 10)
        assertEquals(listOf("c", "d"), s2)
    }

    @Test fun counts() {
        val progress = mapOf("a" to due("a", 5), "b" to due("b", 99))
        assertEquals(1 to 3, Queue.counts(cards, progress, today = 10))
    }
}

class StudySessionTest {
    @Test fun missedCardComesBackFourCardsLater() {
        val s = StudySession(listOf("a", "b", "c", "d", "e", "f"))
        s.grade(false) // a missed
        val order = mutableListOf<String>()
        while (!s.isDone) { order += s.current!!; s.grade(true) }
        assertEquals(listOf("b", "c", "d", "a", "e", "f"), order)
        assertEquals(listOf("a"), s.missedOnce)
        assertEquals(5, s.knewFirstTime.size)
        assertEquals(6, s.finished)
    }

    @Test fun missedNearTheEndGoesLast() {
        val s = StudySession(listOf("a", "b"))
        s.grade(true); s.grade(false)
        assertEquals("b", s.current)
        assertEquals(1, s.finished)
    }

    @Test fun undoRestoresQueueExactly() {
        val s = StudySession(listOf("a", "b", "c", "d", "e"))
        s.grade(true)
        s.grade(false) // b requeued
        assertEquals("b", s.undo())
        assertEquals("b", s.current)
        assertEquals("a", s.undo())
        assertEquals("a", s.current)
        assertFalse(s.canUndo)
        assertNull(s.undo())
        val order = mutableListOf<String>()
        while (!s.isDone) { order += s.current!!; s.grade(true) }
        assertEquals(listOf("a", "b", "c", "d", "e"), order)
    }

    @Test fun emptySessionIsDone() {
        assertTrue(StudySession(emptyList()).isDone)
    }

    @Test fun droppingADeletedCardKeepsTheSessionConsistent() {
        val s = StudySession(listOf("a", "b", "c"))
        s.grade(false) // a requeued behind b, c
        s.drop("a")
        assertEquals(2, s.total)
        assertFalse(s.canUndo)
        assertEquals(emptyList<String>(), s.missedOnce)
        val order = mutableListOf<String>()
        while (!s.isDone) { order += s.current!!; s.grade(true) }
        assertEquals(listOf("b", "c"), order)
        assertEquals(2, s.finished)
    }

    @Test fun undoAlsoUpdatesTheSummary() {
        val s = StudySession(listOf("a", "b"))
        s.grade(false)
        assertEquals(listOf("a"), s.missedOnce)
        s.undo()
        assertEquals(emptyList<String>(), s.missedOnce)
        assertEquals("b", StudySession(listOf("a", "b")).next)
    }
}

class StreakTest {
    @Test fun countsConsecutiveDays() {
        assertEquals(3, io.github.absolutezero000.deckhand.domain.Streak.days(setOf(8, 9, 10, 5), today = 10))
    }

    @Test fun yesterdayKeepsTheStreakAlive() {
        assertEquals(2, io.github.absolutezero000.deckhand.domain.Streak.days(setOf(8, 9), today = 10))
        assertEquals(0, io.github.absolutezero000.deckhand.domain.Streak.days(setOf(7, 8), today = 10))
    }
}

class ProgressIoTest {
    @Test fun roundTripSkipsUnknownCards() {
        val rows = listOf(Progress("a", 1, 2, 2.5, 3, 0, 0, 9), Progress("gone", 1, 2, 2.5, 3, 0, 0, 9))
        val text = ProgressIo.export(rows, "me/cards")
        val (back, skipped) = ProgressIo.parse(text, setOf("a", "b"))
        assertEquals(listOf(rows[0]), back)
        assertEquals(1, skipped)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOtherFiles() {
        ProgressIo.parse("""{"hello": 1}""", emptySet())
    }
}
