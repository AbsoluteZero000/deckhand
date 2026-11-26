package io.github.absolutezero000.deckhand.domain

import io.github.absolutezero000.deckhand.data.Progress
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Two-answer spaced repetition (see docs/app-design.md, "Scheduling").
 * All days are local epoch days.
 */
object Scheduler {
    const val START_EASE = 2.5
    const val MIN_EASE = 1.3
    const val EASE_PENALTY = 0.2
    const val MAX_INTERVAL = 365
    /** A missed card comes back this many cards later in the same session. */
    const val REQUEUE_GAP = 4

    fun knew(prev: Progress?, cardId: String, today: Long, nowMillis: Long): Progress {
        val p = prev ?: fresh(cardId, today)
        val interval = when (p.reps) {
            0 -> 1
            1 -> 3
            else -> min(MAX_INTERVAL, max(p.intervalDays + 1, (p.intervalDays * p.ease).roundToInt()))
        }
        return p.copy(
            reps = p.reps + 1,
            intervalDays = interval,
            dueDay = today + interval,
            lastSeenMillis = nowMillis,
        )
    }

    fun missed(prev: Progress?, cardId: String, today: Long, nowMillis: Long): Progress {
        val p = prev ?: fresh(cardId, today)
        return p.copy(
            reps = 0,
            lapses = p.lapses + 1,
            intervalDays = 0,
            ease = max(MIN_EASE, p.ease - EASE_PENALTY),
            dueDay = today,
            lastSeenMillis = nowMillis,
        )
    }

    private fun fresh(cardId: String, today: Long) = Progress(
        cardId = cardId, dueDay = today, intervalDays = 0, ease = START_EASE,
        reps = 0, lapses = 0, firstSeenDay = today, lastSeenMillis = 0,
    )
}
