package io.github.absolutezero000.deckhand.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.time.Instant

/** Progress backup file (see "Export / Import" in docs/app-design.md). */
@Serializable
data class ProgressFile(
    val app: String = "deckhand",
    val schema: Int = 1,
    val repo: String? = null,
    val exported_at: String,
    val progress: List<ProgressJson>,
)

@Serializable
data class ProgressJson(
    val card_id: String,
    val due_epoch_day: Long,
    val interval_days: Int,
    val ease: Double,
    val reps: Int,
    val lapses: Int,
    val first_seen_epoch_day: Long,
    val last_seen_millis: Long,
)

object ProgressIo {
    fun export(rows: List<Progress>, repo: String?, now: Instant = Instant.now()): String =
        json.encodeToString(
            ProgressFile.serializer(),
            ProgressFile(
                repo = repo,
                exported_at = now.toString(),
                progress = rows.sortedBy { it.cardId }.map {
                    ProgressJson(it.cardId, it.dueDay, it.intervalDays, it.ease, it.reps, it.lapses, it.firstSeenDay, it.lastSeenMillis)
                },
            ),
        )

    /** Parses a backup; returns the rows for cards in [knownIds] and how many were skipped. */
    fun parse(text: String, knownIds: Set<String>): Pair<List<Progress>, Int> {
        val file = try {
            json.decodeFromString(ProgressFile.serializer(), text)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("This isn't a deckhand progress file.")
        }
        require(file.app == "deckhand" && file.schema == 1) { "This isn't a deckhand progress file." }
        val rows = file.progress.filter { it.card_id in knownIds }.map {
            Progress(it.card_id, it.due_epoch_day, it.interval_days, it.ease, it.reps, it.lapses, it.first_seen_epoch_day, it.last_seen_millis)
        }
        return rows to (file.progress.size - rows.size)
    }
}
