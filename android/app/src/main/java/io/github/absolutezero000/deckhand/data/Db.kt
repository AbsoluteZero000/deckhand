package io.github.absolutezero000.deckhand.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

@Entity(tableName = "decks")
data class DeckEntity(
    @PrimaryKey val id: String, val name: String, val description: String, val ord: Int,
    /** Background for Ask AI (optional in the cards repo). */
    @ColumnInfo(defaultValue = "") val context: String = "",
)

@Entity(tableName = "sections", primaryKeys = ["deckId", "id"])
data class SectionEntity(
    val deckId: String, val id: String, val name: String, val ord: Int,
    @ColumnInfo(defaultValue = "") val context: String = "",
)

@Entity(tableName = "cards", indices = [Index("deckId")])
data class CardEntity(
    @PrimaryKey val id: String,
    val deckId: String,
    val sectionId: String,
    val front: String,
    val back: String,
    val why: String,
    val tags: List<String>,
    val ord: Int,
)

/** Study progress of one card. Never written by sync, only deleted when its card is gone. */
@Entity(tableName = "progress")
data class Progress(
    @PrimaryKey val cardId: String,
    val dueDay: Long,
    val intervalDays: Int,
    val ease: Double,
    val reps: Int,
    val lapses: Int,
    val firstSeenDay: Long,
    val lastSeenMillis: Long,
)

/** How many grades were given on a local epoch day; drives the streak and "reviewed today". */
@Entity(tableName = "review_days")
data class ReviewDay(@PrimaryKey val day: Long, val count: Int)

class Converters {
    private val list = ListSerializer(String.serializer())

    @TypeConverter
    fun tagsToString(tags: List<String>): String = Json.encodeToString(list, tags)

    @TypeConverter
    fun stringToTags(s: String): List<String> = Json.decodeFromString(list, s)
}

@Dao
interface DeckDao {
    @Query("SELECT * FROM decks ORDER BY ord, id")
    fun decks(): Flow<List<DeckEntity>>

    @Query("SELECT * FROM decks WHERE id = :id")
    suspend fun deck(id: String): DeckEntity?

    @Query("SELECT * FROM sections WHERE deckId = :deckId ORDER BY ord, id")
    suspend fun sections(deckId: String): List<SectionEntity>

    @Query("SELECT * FROM cards ORDER BY deckId, ord")
    fun allCards(): Flow<List<CardEntity>>

    @Query("SELECT * FROM cards WHERE deckId = :deckId ORDER BY ord")
    suspend fun cards(deckId: String): List<CardEntity>

    /** Every card, decks in their order, cards in deck order. */
    @Query("SELECT cards.* FROM cards JOIN decks ON decks.id = cards.deckId ORDER BY decks.ord, decks.id, cards.ord")
    suspend fun allCardsInOrder(): List<CardEntity>

    @Query("SELECT id FROM cards")
    suspend fun cardIds(): List<String>

    @Query("DELETE FROM decks") suspend fun clearDecks()
    @Query("DELETE FROM sections") suspend fun clearSections()
    @Query("DELETE FROM cards") suspend fun clearCards()
    @Insert suspend fun insertDecks(d: List<DeckEntity>)
    @Insert suspend fun insertSections(s: List<SectionEntity>)
    @Insert suspend fun insertCards(c: List<CardEntity>)

    @Query("DELETE FROM progress WHERE cardId NOT IN (SELECT id FROM cards)")
    suspend fun deleteOrphanProgress(): Int

    /** Replaces all content in one transaction; progress of removed cards is deleted. */
    @Transaction
    suspend fun replaceContent(decks: List<DeckEntity>, sections: List<SectionEntity>, cards: List<CardEntity>): Int {
        clearCards(); clearSections(); clearDecks()
        insertDecks(decks); insertSections(sections); insertCards(cards)
        return deleteOrphanProgress()
    }

    @Query("DELETE FROM review_days") suspend fun clearReviewDays()

    @Transaction
    suspend fun clearAll() {
        clearCards(); clearSections(); clearDecks(); clearProgress(); clearReviewDays()
    }

    @Query("SELECT * FROM progress")
    fun progressFlow(): Flow<List<Progress>>

    @Query("SELECT * FROM progress")
    suspend fun allProgress(): List<Progress>

    @Query("SELECT * FROM progress WHERE cardId = :cardId")
    suspend fun progress(cardId: String): Progress?

    @Upsert suspend fun upsertProgress(p: Progress)
    @Upsert suspend fun upsertProgressList(p: List<Progress>)

    @Query("DELETE FROM progress WHERE cardId = :cardId")
    suspend fun deleteProgress(cardId: String)

    @Query("DELETE FROM progress")
    suspend fun clearProgress()

    @Query("SELECT COUNT(*) FROM progress WHERE firstSeenDay = :day")
    suspend fun firstSeenOn(day: Long): Int

    // -- review log (streak)
    @Query("SELECT * FROM review_days WHERE day >= :fromDay ORDER BY day DESC")
    fun reviewDays(fromDay: Long): Flow<List<ReviewDay>>

    @Query("INSERT OR IGNORE INTO review_days(day, count) VALUES(:day, 0)")
    suspend fun ensureReviewDay(day: Long)

    @Query("UPDATE review_days SET count = MAX(0, count + :delta) WHERE day = :day")
    suspend fun addReviews(day: Long, delta: Int)

    @Transaction
    suspend fun logReview(day: Long, delta: Int) {
        ensureReviewDay(day); addReviews(day, delta)
    }

    // -- editing
    @Query("SELECT * FROM cards WHERE id = :id")
    suspend fun card(id: String): CardEntity?

    @Query("SELECT * FROM cards WHERE id = :id")
    fun cardFlow(id: String): Flow<CardEntity?>

    @Query("SELECT * FROM cards WHERE deckId = :deckId ORDER BY ord")
    fun cardsFlow(deckId: String): Flow<List<CardEntity>>

    @Query("SELECT * FROM sections ORDER BY deckId, ord, id")
    fun allSections(): Flow<List<SectionEntity>>

    @Upsert suspend fun upsertCard(c: CardEntity)

    @Query("DELETE FROM cards WHERE id = :id")
    suspend fun deleteCardRow(id: String)

    @Transaction
    suspend fun deleteCard(id: String) {
        deleteCardRow(id); deleteProgress(id)
    }

    /** Puts [card] at the end of its section and renumbers the deck in section order. */
    @Transaction
    suspend fun addCardAtEnd(card: CardEntity) {
        val sectionOrder = sections(card.deckId).map { it.id }
        val existing = cards(card.deckId).filter { it.id != card.id }
        val ordered = existing.sortedWith(compareBy({ sectionOrder.indexOf(it.sectionId) }, { it.ord })).toMutableList()
        val lastInSection = ordered.indexOfLast { it.sectionId == card.sectionId }
        val insertAt = if (lastInSection >= 0) lastInSection + 1
        else ordered.indexOfFirst { sectionOrder.indexOf(it.sectionId) > sectionOrder.indexOf(card.sectionId) }
            .let { if (it < 0) ordered.size else it }
        ordered.add(insertAt, card)
        ordered.forEachIndexed { i, c -> upsertCard(c.copy(ord = i)) }
    }
}

@Database(
    entities = [DeckEntity::class, SectionEntity::class, CardEntity::class, Progress::class, ReviewDay::class],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class DeckhandDb : RoomDatabase() {
    abstract fun dao(): DeckDao

    companion object {
        fun open(context: Context): DeckhandDb =
            Room.databaseBuilder(context, DeckhandDb::class.java, "deckhand.db").addMigrations(MIGRATION_1_2).build()

        /** v2 adds deck and section context. Settings' content keys changed too, so the next sync refetches. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE decks ADD COLUMN context TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE sections ADD COLUMN context TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}
